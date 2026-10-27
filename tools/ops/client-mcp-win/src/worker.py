"""Long-running worker loop: file queue, owner-idle wait queue, client self-healing and a heartbeat file.

No Windows calls here (the driver does those), so the loop is unit-tested with a fake driver. The worker never
relaxes a driver guard: requests and recoveries wait until the owner has been idle long enough, and every client
action still goes through Driver.call (idle check, endpoint, owned-process and focus checks, audit log).
"""
from collections import deque
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time

VERSION = '2026-10-27-longrun'
DEFAULTS = {
    'lifetime_hours': 12,             # stop after this long without a request; 0 = run until stopped
    'idle_minutes': 10,               # owner must be away this long before the client is driven
    'queue_wait_minutes': 120,        # a queued request is dropped after waiting this long for the owner
    'auto_recover': True,             # restart + re-login the client after a crash, hang or disconnect
    'max_recoveries_per_hour': 3,     # then stop recovering until the next successful login
    'heartbeat_seconds': 15,
    'health_seconds': 20,
}
LIMITS = {'lifetime_hours': (0, 720), 'idle_minutes': (1, 240), 'queue_wait_minutes': (1, 1440),
          'max_recoveries_per_hour': (0, 20), 'heartbeat_seconds': (5, 300), 'health_seconds': (5, 600)}


class ConfigError(Exception):
    pass


def load_config(path):
    """worker-config.json: optional; unknown keys or out-of-range values refuse to start (never silently guessed)."""
    config = dict(DEFAULTS)
    path = Path(path)
    if path.exists():
        try:
            value = json.loads(path.read_text(encoding='utf-8-sig'))
        except ValueError:
            raise ConfigError('worker-config.json is not valid JSON') from None
        if not isinstance(value, dict) or set(value) - set(DEFAULTS):
            raise ConfigError('worker-config.json supports only: ' + ', '.join(sorted(DEFAULTS)))
        for key, item in value.items():
            if key == 'auto_recover':
                if not isinstance(item, bool):
                    raise ConfigError('auto_recover must be true or false')
            elif isinstance(item, bool) or not isinstance(item, (int, float)) \
                    or not LIMITS[key][0] <= item <= LIMITS[key][1]:
                raise ConfigError(key + ' must be a number from %s to %s' % LIMITS[key])
            config[key] = item
    return config


def utc(epoch):
    return None if epoch is None else datetime.fromtimestamp(epoch, timezone.utc).isoformat(timespec='seconds')


def write_json(path, value):
    temporary = Path(str(path) + '.tmp')
    temporary.write_text(json.dumps(value), encoding='utf-8')
    os.replace(temporary, path)


class Worker:
    def __init__(self, driver, action, config, emit, inbox, heartbeat_path, safe_error,
                 clock=time.monotonic, wall=time.time, sleep=time.sleep):
        self.driver, self.action, self.config, self.emit = driver, action, config, emit
        self.inbox, self.heartbeat_path, self.safe_error = Path(inbox), Path(heartbeat_path), safe_error
        self.clock, self.wall, self.sleep = clock, wall, sleep
        driver.idle_required = int(config['idle_minutes'] * 60)
        self.started = self.last_activity = clock()
        self.started_wall = self.last_request_wall = wall()
        # Start at the end of the inbox: requests written before this worker started are never replayed.
        self.offset = self.inbox.stat().st_size if self.inbox.exists() else 0
        self.pending = deque()
        self.desired = None            # {'channel': n, 'map_id': m}: the session to restore after a crash
        self.recoveries = deque()      # clock() of each recovery attempt in the last hour
        self.recovery_paused = None
        self.last_recovery = None
        self.problem_ticks = {}
        self.next_health = self.next_heartbeat = self.started
        self.waiting_since = None
        self.stopped = None

    # ---- owner guard ----
    def owner_ready(self):
        """True when the driver's guard would let a tool act now. The guard itself still runs on every action."""
        try:
            if self.driver.local_config().get('force', False):
                return True
            return self.driver.activity.idle_seconds() >= self.driver.idle_required
        except Exception:
            return False

    def owner_idle_seconds(self):
        try:
            return round(self.driver.activity.idle_seconds(), 1)
        except Exception:
            return None

    # ---- lifetime ----
    def expired(self):
        hours = self.config['lifetime_hours']
        return bool(hours) and self.clock() - self.last_activity > hours * 3600

    def expires_wall(self):
        hours = self.config['lifetime_hours']
        return None if not hours else self.last_request_wall + hours * 3600

    # ---- queue ----
    def read_requests(self):
        if not self.inbox.exists():
            return []
        if self.inbox.stat().st_size < self.offset:
            self.offset = 0   # the inbox was emptied by hand; read it from the start again
        lines = []
        with self.inbox.open('r', encoding='utf-8') as source:
            source.seek(self.offset)
            while True:
                line = source.readline()
                if not line.endswith('\n'):
                    break
                self.offset = source.tell()
                lines.append(line)
        return lines

    def handle(self, line):
        """One inbox line. Returns False when the worker must exit."""
        request_id = None
        try:
            request = json.loads(line)
            request_id = request.pop('id')
            kind = request.get('action')
            if kind == 'cancel':
                # The caller gave up waiting: drop it if still queued (it may already have run).
                self.pending = deque(p for p in self.pending if p['id'] != request.get('target'))
                return True
            if kind == 'exit':
                if self.driver.client.pids():
                    raise self.safe_error.refusal('Stop the owned client before exiting this worker.')
                self.emit({'id': request_id, 'exited': True})
                self.stopped = 'exit request'
                return False
            if kind == 'call' and request.get('name') == 'status':
                result = self.action(request)
                if isinstance(result, dict) and isinstance(result.get('content'), list):
                    result['content'].append({'type': 'text', 'text': 'worker: ' + json.dumps(self.summary())})
                self.emit({'id': request_id, 'result': result})
                return True
            # Every other request (tools and the local calibration actions) renews the lifetime and is queued.
            self.last_activity, self.last_request_wall = self.clock(), self.wall()
            wait_until = request.pop('wait_until', None)
            if not isinstance(wait_until, (int, float)):
                wait_until = self.wall() + self.config['queue_wait_minutes'] * 60
            self.pending.append({'id': request_id, 'request': request, 'wait_until': wait_until})
            if not self.owner_ready():
                self.emit({'id': request_id, 'event': 'queued', 'reason': 'owner is using the PC',
                           'owner_idle_seconds': self.owner_idle_seconds(),
                           'idle_required_seconds': self.driver.idle_required,
                           'position': len(self.pending), 'wait_until_utc': utc(wait_until)})
        except Exception as error:
            self.driver.audit('calibration', False)
            self.emit({'id': request_id, 'error': self.safe_error.text(error)})
        return True

    def dispatch(self):
        now = self.wall()
        for item in [p for p in self.pending if p['wait_until'] < now]:
            self.pending.remove(item)
            self.emit({'id': item['id'], 'error': 'owner is using the PC: gave up after waiting for '
                       + str(self.config['idle_minutes']) + ' idle minutes'})
        if not self.pending:
            self.waiting_since = None
            return False
        if not self.owner_ready():
            if self.waiting_since is None:
                self.waiting_since = now
            return False
        self.waiting_since = None
        item = self.pending.popleft()
        self.emit({'id': item['id'], 'event': 'started'})
        try:
            result = self.action(item['request'])
            ok = not (isinstance(result, dict) and result.get('isError'))
            self.driver.audit('calibration', ok)
            if ok:
                self.track(item['request'])
            self.emit({'id': item['id'], 'result': result})
        except Exception as error:
            self.driver.audit('calibration', False)
            self.emit({'id': item['id'], 'error': self.safe_error.text(error)})
        self.last_activity, self.last_request_wall = self.clock(), self.wall()
        return True

    def track(self, request):
        """Remember the session the agent built, so a crash can be put back the same way."""
        if request.get('action') != 'call':
            return
        name, args = request.get('name'), request.get('arguments') or {}
        if name == 'login':
            self.desired = {'channel': args.get('channel', 1), 'map_id': None}
            self.recoveries.clear()
            self.recovery_paused = None
        elif name == 'go_to' and self.desired is not None:
            if 'map_id' in args:
                self.desired['map_id'] = args['map_id']
            if 'channel' in args:
                self.desired['channel'] = args['channel']
        elif name in ('stop_client', 'start_client'):
            self.desired = None
        self.problem_ticks.clear()

    # ---- self-healing ----
    def diagnose(self):
        """A reason the logged-in client needs a restart, or None. Read-only checks, never input."""
        client = self.driver.client
        pids = client.pids()
        if not pids:
            return 'client exited'
        state = self.driver.ui_state()
        if state is not None and state.get('stage') == 'login':
            return 'disconnected to the login screen'
        try:
            hwnd = client.window()[0]
        except Exception:
            return 'no game window'
        hung = getattr(self.driver.backend, 'window_hung', None)
        if hung is not None and hung(hwnd):
            return 'client not responding'
        return None

    def health(self):
        if self.desired is None or not self.config['auto_recover'] or self.pending:
            self.problem_ticks.clear()
            return
        try:
            problem = self.diagnose()
        except Exception as error:
            problem = None
            self.last_recovery = {'utc': utc(self.wall()), 'ok': False, 'step': 'health check',
                                  'message': self.safe_error.text(error)}
        if problem is None:
            self.problem_ticks.clear()
            return
        # An exit is certain at once; the others must persist for three checks (a loading screen, a busy frame).
        ticks = self.problem_ticks[problem] = self.problem_ticks.get(problem, 0) + 1
        self.problem_ticks = {problem: ticks}
        if problem != 'client exited' and ticks < 3:
            return
        if self.recovery_paused or not self.owner_ready():
            return
        self.recover(problem)

    def recover(self, problem):
        hour_ago = self.clock() - 3600
        while self.recoveries and self.recoveries[0] < hour_ago:
            self.recoveries.popleft()
        if len(self.recoveries) >= self.config['max_recoveries_per_hour']:
            self.recovery_paused = (problem + ': ' + str(len(self.recoveries))
                                    + ' recoveries in the last hour; paused until the next login')
            self.emit({'event': 'recovery_paused', 'problem': problem})
            return
        self.recoveries.append(self.clock())
        self.problem_ticks.clear()
        desired = dict(self.desired)
        self.emit({'event': 'recovering', 'problem': problem, 'utc': utc(self.wall())})
        step = 'stop'
        try:
            if self.driver.client.pids():
                self.driver.call('stop_client', {})
            steps = [('start_client', {}), ('login', {'channel': desired['channel']})]
            if desired.get('map_id') is not None:
                steps.append(('go_to', {'map_id': desired['map_id']}))
            for step, args in steps:
                result = self.driver.call(step, args)
                if result.get('isError'):
                    raise self.safe_error.refusal(result['content'][0]['text'])
            self.desired = desired
            self.last_recovery = {'utc': utc(self.wall()), 'problem': problem, 'ok': True}
            self.emit({'event': 'recovered', 'problem': problem})
        except Exception as error:
            self.last_recovery = {'utc': utc(self.wall()), 'problem': problem, 'ok': False, 'step': step,
                                  'message': self.safe_error.text(error)}
            self.emit({'event': 'recovery_failed', 'problem': problem, 'step': step})

    # ---- heartbeat ----
    def summary(self):
        try:
            pids = self.driver.client.pids()
        except Exception:
            pids = None
        return {'version': VERSION, 'pid': os.getpid(), 'started_utc': utc(self.started_wall),
                'uptime_seconds': round(self.clock() - self.started), 'heartbeat_utc': utc(self.wall()),
                'heartbeat_seconds': self.config['heartbeat_seconds'],
                'lifetime_hours': self.config['lifetime_hours'], 'expires_utc': utc(self.expires_wall()),
                'last_request_utc': utc(self.last_request_wall), 'owner_idle_seconds': self.owner_idle_seconds(),
                'idle_required_seconds': self.driver.idle_required, 'owner_ready': self.owner_ready(),
                'queued': len(self.pending), 'waiting_for_owner_since_utc': utc(self.waiting_since),
                'client_pids': pids, 'session': self.desired, 'auto_recover': self.config['auto_recover'],
                'recoveries_last_hour': len(self.recoveries), 'recovery_paused': self.recovery_paused,
                'last_recovery': self.last_recovery, 'stopped': self.stopped}

    def heartbeat(self):
        try:
            write_json(self.heartbeat_path, self.summary())
        except OSError:
            pass

    # ---- loop ----
    def tick(self):
        """One pass. Returns False when the worker must stop."""
        now = self.clock()
        if self.expired():
            self.stopped = 'expired: no request for ' + str(self.config['lifetime_hours']) + ' h'
            return False
        busy = False
        for line in self.read_requests():
            busy = True
            if not self.handle(line):
                return False
        busy = self.dispatch() or busy
        if now >= self.next_health:
            self.next_health = now + self.config['health_seconds']
            self.health()
        if self.clock() >= self.next_heartbeat:
            self.next_heartbeat = self.clock() + self.config['heartbeat_seconds']
            self.heartbeat()
        return busy

    def run(self):
        self.heartbeat()
        while True:
            busy = self.tick()
            if busy is False and self.stopped:
                break
            if not busy:
                self.sleep(0.1)
        if self.stopped.startswith('expired'):
            try:
                if self.driver.client.pids():
                    self.driver.client.stop()   # owned job only; it would also die with this process
            except Exception:
                pass
            self.emit({'event': 'expired', 'reason': self.stopped})
        self.heartbeat()
