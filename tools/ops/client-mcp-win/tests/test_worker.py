"""Long-running worker: lifetime, owner-idle queue, self-healing and heartbeat. Fake driver, no Windows calls."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from worker import ConfigError, DEFAULTS, Worker, load_config


class Refusal(Exception):
    pass


class SafeError:
    refusal = Refusal

    @staticmethod
    def text(error):
        return str(error) if isinstance(error, Refusal) else type(error).__name__


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class FakeActivity:
    def __init__(self):
        self.idle = 3600

    def idle_seconds(self):
        return self.idle


class FakeClient:
    def __init__(self):
        self.running = []
        self.window_ok = True

    def pids(self):
        return list(self.running)

    def window(self):
        if not self.window_ok:
            raise Refusal('Expected exactly one owned game window; found 0.')
        return (77, 1, 'SlumsMS')

    def stop(self):
        stopped, self.running = self.running, []
        return stopped


class FakeBackend:
    hung = False

    def window_hung(self, hwnd):
        return self.hung


class FakeDriver:
    def __init__(self):
        self.activity, self.client, self.backend = FakeActivity(), FakeClient(), FakeBackend()
        self.idle_required = 600
        self.force = False
        self.calls = []
        self.stage = None
        self.fail = None

    def local_config(self):
        return {'force': self.force}

    def ui_state(self):
        return None if self.stage is None else {'stage': self.stage}

    def audit(self, name, ok):
        pass

    def call(self, name, args):
        self.calls.append((name, args))
        if self.fail == name:
            return {'content': [{'type': 'text', 'text': 'owner is using the PC'}], 'isError': True}
        if name == 'start_client':
            self.client.running = [11, 12]
        if name == 'stop_client':
            self.client.running = []
        return {'content': [{'type': 'text', 'text': '{}'}], 'isError': False}


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        root = Path(self.folder.name)
        self.inbox, self.heartbeat = root / 'inbox.jsonl', root / 'heartbeat.json'
        self.inbox.write_text(json.dumps({'id': 'old', 'action': 'call', 'name': 'stop_client'}) + '\n')
        self.driver, self.clock, self.wall = FakeDriver(), Clock(), Clock()
        self.out = []
        self.config = dict(DEFAULTS)

    def tearDown(self):
        self.folder.cleanup()

    def worker(self, **config):
        self.config.update(config)
        return Worker(self.driver, lambda request: self.driver.call(request['name'], request.get('arguments', {})),
                      self.config, self.out.append, self.inbox, self.heartbeat, SafeError,
                      clock=self.clock, wall=self.wall, sleep=lambda s: None)

    def send(self, request_id, name, **arguments):
        with self.inbox.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps({'id': request_id, 'action': 'call', 'name': name, 'arguments': arguments}) + '\n')

    def answers(self, request_id):
        return [o for o in self.out if o.get('id') == request_id]

    def test_old_inbox_lines_are_never_replayed(self):
        worker = self.worker()
        worker.tick()
        self.assertEqual(self.driver.calls, [])

    def test_idle_threshold_comes_from_config(self):
        self.worker(idle_minutes=3)
        self.assertEqual(self.driver.idle_required, 180)

    def test_request_waits_for_owner_then_runs(self):
        worker = self.worker()
        self.driver.activity.idle = 30
        self.send('a', 'start_client')
        worker.tick()
        self.assertEqual(self.answers('a')[0]['event'], 'queued')
        self.assertEqual(self.driver.calls, [])
        worker.tick()
        self.assertEqual(self.driver.calls, [])
        self.driver.activity.idle = 601
        worker.tick()
        self.assertEqual(self.driver.calls, [('start_client', {})])
        self.assertEqual([o.get('event') for o in self.answers('a')], ['queued', 'started', None])
        self.assertFalse(self.answers('a')[-1]['result']['isError'])

    def test_local_force_skips_the_wait(self):
        worker = self.worker()
        self.driver.activity.idle, self.driver.force = 5, True
        self.send('a', 'start_client')
        worker.tick()
        self.assertEqual(self.driver.calls, [('start_client', {})])

    def test_status_answers_while_owner_is_active(self):
        worker = self.worker()
        self.driver.activity.idle = 5
        worker.action = lambda request: {'content': [{'type': 'text', 'text': '{}'}], 'isError': False}
        self.send('s', 'status')
        worker.tick()
        content = self.answers('s')[0]['result']['content']
        self.assertTrue(content[-1]['text'].startswith('worker: '))
        self.assertIn('uptime_seconds', json.loads(content[-1]['text'][8:]))

    def test_queued_request_gives_up_after_wait_limit(self):
        worker = self.worker(queue_wait_minutes=30)
        self.driver.activity.idle = 5
        self.send('a', 'start_client')
        worker.tick()
        self.wall.t += 31 * 60
        worker.tick()
        self.assertIn('gave up', self.answers('a')[-1]['error'])
        self.driver.activity.idle = 9999
        worker.tick()
        self.assertEqual(self.driver.calls, [])

    def test_cancel_drops_a_queued_request(self):
        worker = self.worker()
        self.driver.activity.idle = 5
        self.send('a', 'start_client')
        with self.inbox.open('a') as stream:
            stream.write(json.dumps({'id': 'c', 'action': 'cancel', 'target': 'a'}) + '\n')
        worker.tick()
        self.driver.activity.idle = 9999
        worker.tick()
        self.assertEqual(self.driver.calls, [])

    def test_lifetime_is_renewed_by_requests_and_zero_means_until_stopped(self):
        worker = self.worker(lifetime_hours=12)
        self.clock.t += 11 * 3600
        self.send('a', 'read_logs')
        self.assertIsNot(worker.tick(), None)
        self.clock.t += 11 * 3600
        worker.tick()
        self.assertIsNone(worker.stopped)
        self.clock.t += 2 * 3600
        worker.tick()
        self.assertTrue(worker.stopped.startswith('expired'))
        forever = self.worker(lifetime_hours=0)
        self.clock.t += 1000 * 3600
        forever.tick()
        self.assertIsNone(forever.stopped)

    def test_health_checks_do_not_renew_the_lifetime(self):
        worker = self.worker(lifetime_hours=1, health_seconds=5)
        for _ in range(800):
            self.clock.t += 5
            worker.tick()
        self.assertTrue(worker.stopped.startswith('expired'))

    def login(self, worker, channel=2, map_id=104000000):
        self.send('l', 'login', channel=channel)
        self.send('g', 'go_to', map_id=map_id)
        self.driver.client.running = [11, 12]
        worker.tick()
        worker.tick()
        self.driver.calls.clear()

    def test_crash_restarts_and_logs_back_in_to_the_same_place(self):
        worker = self.worker()
        self.login(worker)
        self.driver.client.running = []
        self.clock.t += 30
        worker.tick()
        self.assertEqual(self.driver.calls, [('start_client', {}), ('login', {'channel': 2}),
                                             ('go_to', {'map_id': 104000000})])
        self.assertTrue(worker.last_recovery['ok'])
        self.assertEqual(worker.desired, {'channel': 2, 'map_id': 104000000})

    def test_recovery_waits_for_the_owner(self):
        worker = self.worker()
        self.login(worker)
        self.driver.client.running = []
        self.driver.activity.idle = 10
        self.clock.t += 30
        worker.tick()
        self.assertEqual(self.driver.calls, [])
        self.driver.activity.idle = 700
        self.clock.t += 30
        worker.tick()
        self.assertEqual(self.driver.calls[0], ('start_client', {}))

    def test_disconnect_and_hang_need_three_checks(self):
        worker = self.worker()
        self.login(worker)
        self.driver.stage = 'login'
        for _ in range(2):
            self.clock.t += 30
            worker.tick()
        self.assertEqual(self.driver.calls, [])
        self.clock.t += 30
        worker.tick()
        self.assertEqual(self.driver.calls[:2], [('stop_client', {}), ('start_client', {})])
        self.driver.stage, self.driver.calls = None, []
        self.driver.backend.hung = True
        for _ in range(3):
            self.clock.t += 30
            worker.tick()
        self.assertEqual(self.driver.calls[0], ('stop_client', {}))

    def test_recoveries_are_capped_per_hour(self):
        worker = self.worker(max_recoveries_per_hour=2)
        self.login(worker)
        self.driver.fail = 'login'
        for _ in range(6):
            self.driver.client.running = []
            self.clock.t += 30
            worker.tick()
        self.assertEqual(sum(1 for c in self.driver.calls if c[0] == 'start_client'), 2)
        self.assertIsNotNone(worker.recovery_paused)
        self.assertFalse(worker.last_recovery['ok'])
        self.assertEqual(worker.last_recovery['step'], 'login')

    def test_stop_client_ends_supervision(self):
        worker = self.worker()
        self.login(worker)
        self.send('s', 'stop_client')
        worker.tick()
        self.driver.calls.clear()
        self.clock.t += 60
        worker.tick()
        self.assertEqual(self.driver.calls, [])
        self.assertIsNone(worker.desired)

    def test_heartbeat_file(self):
        worker = self.worker()
        self.clock.t += 120
        worker.tick()
        beat = json.loads(self.heartbeat.read_text())
        self.assertEqual(beat['uptime_seconds'], 120)
        self.assertEqual(beat['idle_required_seconds'], 600)
        self.assertEqual(beat['lifetime_hours'], 12)
        self.assertIsNotNone(beat['expires_utc'])

    def test_exit_refuses_while_client_runs(self):
        worker = self.worker()
        self.driver.client.running = [11]
        with self.inbox.open('a') as stream:
            stream.write(json.dumps({'id': 'x', 'action': 'exit'}) + '\n')
        worker.tick()
        self.assertIn('Stop the owned client', self.answers('x')[0]['error'])
        self.assertIsNone(worker.stopped)

    def test_config_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'worker-config.json'
            self.assertEqual(load_config(path), DEFAULTS)
            path.write_text('{"lifetime_hours": 0, "idle_minutes": 15}')
            self.assertEqual(load_config(path)['idle_minutes'], 15)
            for bad in ('{"idle_minutes": 0}', '{"force": true}', '{"auto_recover": 1}', '{"lifetime_hours": true}'):
                path.write_text(bad)
                with self.assertRaises(ConfigError):
                    load_config(path)


if __name__ == '__main__':
    unittest.main()
