"""The QA client's runner channel (ticket 166): the two one-way pipes a SLUMS_QA_MODULE slums.dll opens.

  \\\\.\\pipe\\slums-qa-<pid>-events    client -> runner, one JSON object per line (hello, pong, error, the ready
                                       signals worldselect.ready / channels.ready / charselect.ready / field.ready,
                                       burst.start, burst.done)
  \\\\.\\pipe\\slums-qa-<pid>-commands  runner -> client: "ping", "burst <fps> <ms> <label>"

The wire format is the client's (client repo src/qamodule_policy.h). A player build has no pipes; QaPipe.connect
returns None then, and the driver keeps its screen-template waits.
"""
import json
import queue
import re
import threading
import time

SIGNALS = ('worldselect.ready', 'channels.ready', 'charselect.ready', 'field.ready')
LABEL = re.compile(r'^[A-Za-z0-9_-]{1,40}$')


class QaPipe:
    def __init__(self, events, commands, pid, opener=open):
        self.pid = pid
        self._events = events
        self._commands = commands
        self._queue = queue.Queue()
        self.log = []                 # every event line read, in order (the run folder's events.jsonl)
        self._lock = threading.Lock()
        self.hello = None
        self.closed = False
        threading.Thread(target=self._read, daemon=True).start()

    @classmethod
    def connect(cls, pids, timeout=10.0, opener=open):
        """Connects to the first pid with QA pipes; None when no pid has them (not a QA build) by the timeout."""
        deadline = time.monotonic() + timeout
        while True:
            for pid in pids:
                base = '\\\\.\\pipe\\slums-qa-%d-' % pid
                try:
                    events = opener(base + 'events', 'rb', buffering=0)
                except OSError:
                    continue
                try:
                    commands = opener(base + 'commands', 'wb', buffering=0)
                except OSError:
                    events.close()
                    continue
                pipe = cls(events, commands, pid, opener)
                hello = pipe.wait('hello', 5)
                pipe.hello = hello
                return pipe
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.25)

    def _read(self):
        pending = b''
        try:
            while True:
                chunk = self._events.read(4096)
                if not chunk:
                    break
                pending += chunk
                while b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    try:
                        event = json.loads(line.decode('utf-8'))
                    except ValueError:
                        continue
                    event['_at'] = time.time()
                    with self._lock:
                        self.log.append(event)
                    self._queue.put(event)
        except OSError:
            pass
        try:
            self._events.close()
        except OSError:
            pass
        self.closed = True
        self._queue.put({'ev': '_closed', '_at': time.time()})

    def send(self, line):
        self._commands.write((line + '\n').encode('ascii'))
        self._commands.flush()

    def drain(self):
        """Forgets queued events (keeps them in log), so the next wait sees only what happens after now."""
        while True:
            try:
                self._queue.get_nowait()
            except queue.Empty:
                return

    def wait(self, name, timeout):
        """The next event named `name` within timeout seconds, or None. Events read meanwhile stay in log."""
        deadline = time.monotonic() + timeout
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            try:
                event = self._queue.get(timeout=left)
            except queue.Empty:
                return None
            if event.get('ev') == name:
                return event
            if event.get('ev') == '_closed':
                return None

    def burst(self, fps, ms, label):
        if not LABEL.match(label) or not 1 <= fps <= 30 or not 100 <= ms <= 5000:
            raise ValueError('burst needs fps 1-30, ms 100-5000 and a label of A-Z a-z 0-9 - _ (1-40)')
        self.send('burst %d %d %s' % (fps, ms, label))

    def events_since(self, since):
        with self._lock:
            return [e for e in self.log if e['_at'] >= since]

    def close(self):
        """Closes the command pipe. The events pipe is closed by the reader thread once its read ends (the client
        exited or disconnected): closing it here while that thread is blocked in a read would hang on Windows."""
        try:
            self._commands.close()
        except OSError:
            pass
