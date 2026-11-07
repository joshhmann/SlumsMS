"""b024: press_key holds any single game key for hold_ms, and refuses a hold it cannot do. Fake sender."""
from pathlib import Path
import sys
import time
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))


class FakeSender:
    def __init__(self):
        self.events = []

    def key(self, vk, down=None, extended=False):
        self.events.append((vk, down, time.monotonic()))


class PressKeyTest(unittest.TestCase):
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

    def test_ctrl_hold_is_held(self):
        result = self.d.press_key('CTRL', hold_ms=300)
        self.assertEqual(result, {'pressed': 'CTRL', 'held_ms': 300})
        events = self.d.sender.events
        self.assertEqual((0x11, True), events[0][:2])
        self.assertEqual((0x11, False), events[-1][:2])
        self.assertGreaterEqual(events[-1][2] - events[0][2], 0.28)

    def test_a_hold_repeats_the_key_down_like_a_keyboard(self):
        # qa-attack-step (1.6.6): the game attacks once per key-down, so a single down held for 3 s was one swing
        # (Server Dev's packet read, 2026-11-07: one CLOSE_RANGE_ATTACK per hold). A held key repeats its down
        # (auto-repeat) after the repeat delay, as a keyboard does; the release comes once, at the end.
        self.d.press_key('CTRL', hold_ms=1000)
        events = self.d.sender.events
        downs = [e for e in events if e[1] is True]
        self.assertEqual(1, len([e for e in events if e[1] is False]))
        self.assertGreaterEqual(len(downs), 15, "about 30 repeats a second after the delay")
        self.assertGreaterEqual(downs[1][2] - downs[0][2], self.driver.KEY_REPEAT_DELAY - 0.01)
        self.assertTrue(all(e[0] == 0x11 for e in events))

    def test_letter_and_arrow_holds(self):
        self.assertEqual(self.d.press_key('Z', hold_ms=100)['held_ms'], 100)
        self.assertEqual(self.d.press_key('LEFT', hold_ms=100)['held_ms'], 100)

    def test_a_tap_without_hold_is_one_key(self):
        self.assertEqual(self.d.press_key('X'), {'pressed': 'X'})
        self.assertEqual(len(self.d.sender.events), 1)

    def test_holds_that_cannot_apply_are_refused(self):
        for name in ('ESCAPE', 'ENTER', 'TAB', 'BACKSPACE', 'CTRL+F10'):
            with self.assertRaises(self.driver.Refusal, msg=name):
                self.d.press_key(name, hold_ms=500)
        self.assertEqual(self.d.sender.events, [])

    def test_hold_is_capped_at_three_seconds(self):
        started = time.monotonic()
        self.assertEqual(self.d.press_key('SPACE', hold_ms=3000)['held_ms'], 3000)
        self.assertLess(time.monotonic() - started, 3.5)


if __name__ == '__main__':
    unittest.main()
