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
        (vk1, down1, t1), (vk2, down2, t2) = self.d.sender.events
        self.assertEqual((vk1, down1, vk2, down2), (0x11, True, 0x11, False))
        self.assertGreaterEqual(t2 - t1, 0.28)

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
