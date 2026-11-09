"""b360 (1.6.6): the jump tool, for measuring the client's rope-grab reach. Fake sender, like test_presskey."""
from pathlib import Path
import sys
import time
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))

ALT, UP, LEFT, RIGHT = 0x12, 0x26, 0x25, 0x27


class FakeSender:
    def __init__(self):
        self.events = []

    def key(self, vk, down=None, extended=False):
        self.events.append((vk, down, time.monotonic()))


class JumpTest(unittest.TestCase):
    def setUp(self):
        try:
            import driver
        except ImportError as error:
            self.skipTest('driver import failed: %s' % error)
        self.driver = driver
        d = object.__new__(driver.Driver)
        d.sender = FakeSender()
        d.state = 'field'
        d.guard = lambda: None
        d.endpoint = lambda: None
        d.field_input = lambda: None
        d.matches = lambda name, image=None: False
        self.d = d

    def keys(self):
        return [(vk, down) for vk, down, _ in self.d.sender.events]

    def test_a_standing_jump_taps_the_jump_key(self):
        self.assertEqual({'jumped': True, 'dir': 'none', 'catch': False}, self.d.jump(hold_ms=200))
        self.assertEqual([(ALT, True), (ALT, False)], self.keys())

    def test_a_catch_holds_up_through_the_jump(self):
        self.d.jump(catch=True, hold_ms=200)
        self.assertEqual([(UP, True), (ALT, True), (ALT, False), (UP, False)], self.keys())

    def test_a_running_catch_holds_the_arrow_first_then_up(self):
        started = time.monotonic()
        self.d.jump(dir='right', catch=True, run_ms=300, hold_ms=200)
        self.assertEqual([(RIGHT, True), (UP, True), (ALT, True), (ALT, False), (UP, False), (RIGHT, False)],
                         self.keys())
        ev = self.d.sender.events
        self.assertGreaterEqual(ev[1][2] - ev[0][2], 0.28, "the run comes before the jump")
        self.assertLess(time.monotonic() - started, 1.5)

    def test_everything_is_released_even_when_the_guard_stops_it(self):
        def stop():
            raise self.driver.Refusal('owner input')
        self.d.guard = stop
        with self.assertRaises(self.driver.Refusal):
            self.d.jump(dir='left', catch=True, run_ms=100, hold_ms=200)
        downs = {vk for vk, down in self.keys() if down is True}
        ups = {vk for vk, down in self.keys() if down is False}
        self.assertTrue(downs <= ups, "every key pressed is released")

    def test_the_tool_is_a_script_step_with_checked_arguments(self):
        self.assertIn('jump', self.driver.SCRIPT_TOOLS)
        self.assertEqual(['none', 'left', 'right'], self.driver.TOOLS['jump']['dir']['enum'])
        self.assertEqual(2000, self.driver.TOOLS['jump']['run_ms']['maximum'])
        with self.assertRaises(self.driver.Refusal):
            self.d.jump(dir='up')


if __name__ == '__main__':
    unittest.main()
