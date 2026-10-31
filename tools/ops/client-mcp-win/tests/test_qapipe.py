"""QA client runner channel (qapipe.py) and script loading (driver.load_script). Fake pipes, no Windows calls."""
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from qapipe import QaPipe


class FakeEvents:
    """A blocking byte stream fed by the test, like a pipe the client writes."""
    def __init__(self):
        self.buffer = b''
        self.done = False
        self.cond = threading.Condition()

    def feed(self, data=b'', end=False):
        with self.cond:
            self.buffer += data
            self.done = self.done or end
            self.cond.notify_all()

    def read(self, n):
        with self.cond:
            while not self.buffer and not self.done:
                self.cond.wait()
            chunk, self.buffer = self.buffer[:n], self.buffer[n:]
            return chunk

    def close(self):
        self.feed(end=True)


def line(event):
    return (json.dumps(event) + '\n').encode()


class QaPipeTest(unittest.TestCase):
    def make(self):
        events, commands = FakeEvents(), io.BytesIO()
        commands.close = lambda: None
        return QaPipe(events, commands, 4242), events, commands

    def test_wait_returns_the_named_event_and_keeps_the_log(self):
        pipe, events, _ = self.make()
        events.feed(line({'ev': 'hello', 't': 1}) + line({'ev': 'worldselect.ready', 't': 9}))
        self.assertEqual(pipe.wait('worldselect.ready', 2)['t'], 9)
        self.assertEqual([e['ev'] for e in pipe.log], ['hello', 'worldselect.ready'])

    def test_wait_times_out_with_none(self):
        pipe, _, _ = self.make()
        started = time.monotonic()
        self.assertIsNone(pipe.wait('channels.ready', 0.3))
        self.assertLess(time.monotonic() - started, 2)

    def test_partial_lines_and_bad_json_are_handled(self):
        pipe, events, _ = self.make()
        events.feed(b'{"ev":"fie')
        events.feed(b'ld.ready","t":5}\nnot json\n' + line({'ev': 'pong', 't': 6}))
        self.assertEqual(pipe.wait('field.ready', 2)['t'], 5)
        self.assertEqual(pipe.wait('pong', 2)['t'], 6)

    def test_drain_forgets_old_signals(self):
        pipe, events, _ = self.make()
        events.feed(line({'ev': 'channels.ready', 't': 1}))
        time.sleep(0.2)
        pipe.drain()
        self.assertIsNone(pipe.wait('channels.ready', 0.3))   # a stale signal must not satisfy a new click

    def test_events_from_reads_the_log_by_position(self):
        pipe, events, _ = self.make()
        events.feed(line({'ev': 'hello', 't': 1}) + line({'ev': 'worldselect.ready', 't': 2}))
        self.assertEqual(2, pipe.wait('worldselect.ready', 2)['t'])
        first, position = pipe.events_from(0)
        self.assertEqual(['hello', 'worldselect.ready'], [event['ev'] for event in first])
        self.assertEqual(2, position)
        self.assertEqual(([], 2), pipe.events_from(position))   # nothing new: the same position comes back

    def test_closed_pipe_ends_waits(self):
        pipe, events, _ = self.make()
        events.feed(end=True)
        self.assertIsNone(pipe.wait('field.ready', 2))
        time.sleep(0.1)
        self.assertTrue(pipe.closed)

    def test_burst_sends_the_client_command_and_checks_ranges(self):
        pipe, _, commands = self.make()
        pipe.burst(10, 2000, 'b140-heal')
        self.assertEqual(commands.getvalue(), b'burst 10 2000 b140-heal\n')
        for bad in ((0, 2000, 'x'), (31, 2000, 'x'), (10, 50, 'x'), (10, 6000, 'x'), (10, 2000, '../x')):
            with self.assertRaises(ValueError):
                pipe.burst(*bad)

    def test_connect_returns_none_for_a_player_build(self):
        def opener(path, mode, buffering=0):
            raise FileNotFoundError(path)
        self.assertIsNone(QaPipe.connect([1, 2], timeout=0.3, opener=opener))


class LoadScriptTest(unittest.TestCase):
    def setUp(self):
        try:
            import driver
        except ImportError as error:   # driver imports PIL; skip where it is missing
            self.skipTest('driver import failed: %s' % error)
        self.driver = driver
        original = driver.inside
        driver.inside = lambda path, base=None: Path(path)   # the real one reads Windows reparse attributes
        self.addCleanup(setattr, driver, 'inside', original)
        self.root = Path(tempfile.mkdtemp())
        (self.root / 'qa-scripts').mkdir()

    def write(self, name, value):
        (self.root / 'qa-scripts' / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')

    def test_a_good_script_loads(self):
        self.write('b140-heal', {'expect': 'green column', 'steps': [
            {'tool': 'start_client'}, {'tool': 'login', 'args': {'channel': 3}},
            {'tool': 'qa_burst', 'args': {'fps': 10, 'ms': 2000, 'label': 'heal'}}, {'tool': 'wait_ms', 'args': {'ms': 500}}]})
        raw, steps = self.driver.load_script(self.root, 'b140-heal')
        self.assertEqual(len(steps), 4)
        self.assertIn(b'green column', raw)

    def test_refusals(self):
        Refusal = self.driver.Refusal
        with self.assertRaises(Refusal):
            self.driver.load_script(self.root, '../etc')
        with self.assertRaises(Refusal):
            self.driver.load_script(self.root, 'missing')
        self.write('nested', {'steps': [{'tool': 'run_script', 'args': {'name': 'nested'}}]})
        with self.assertRaises(Refusal):
            self.driver.load_script(self.root, 'nested')
        self.write('long-wait', {'steps': [{'tool': 'wait_ms', 'args': {'ms': 60000}}]})
        with self.assertRaises(Refusal):
            self.driver.load_script(self.root, 'long-wait')
        self.write('empty', {'steps': []})
        with self.assertRaises(Refusal):
            self.driver.load_script(self.root, 'empty')


if __name__ == '__main__':
    unittest.main()
