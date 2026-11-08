"""qa-walk-to (1.6.7): walk_to and climb hold the arrows a player holds and stop on the QA client's `position`.
Fake sender and a small simulated field (cards/qa-walk-to.md expected outcomes 1-16), like test_jump."""
from pathlib import Path
import sys
import time
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))

UP, DOWN, LEFT, RIGHT = 0x26, 0x28, 0x25, 0x27


class World:
    """A floor at y 130 from x -400 to 400 (walls beyond), walked at `speed` px/s, sliding `slide` px after a release.
    A ladder at x -117 from y 130 up to -180, where a platform starts. Optional: an open edge at x `edge` (walk past it
    and fall), a portal at x `portal` (the map changes)."""

    def __init__(self, x=0, y=130, speed=300, slide=3, edge=None, portal=None, ladder_x=-117, top=-180,
                 climb=250, grab=True):
        self.x, self.y = float(x), float(y)
        self.speed, self.slide, self.edge, self.portal = speed, slide, edge, portal
        self.ladder_x, self.top, self.climb, self.grab = ladder_x, top, climb, grab
        self.held = set()
        self.on_ladder = False
        self.foothold = True
        self.map_id = 922010100
        self.t = time.monotonic()
        self.slide_left = 0.0
        self.last_dir = 0

    def key(self, vk, down=None, extended=False):
        self.advance()
        if down:
            self.held.add(vk)
        else:
            if vk in (LEFT, RIGHT) and vk in self.held:
                self.slide_left = self.slide
                self.last_dir = 1 if vk == RIGHT else -1
            self.held.discard(vk)
        self.events.append((vk, down))

    def advance(self):
        now = time.monotonic()
        dt, self.t = now - self.t, now
        if not self.foothold and not self.on_ladder:
            self.y += 600 * dt
            return
        if self.on_ladder:
            if UP in self.held:
                self.y -= self.climb * dt
            elif DOWN in self.held:
                self.y += self.climb * dt
            if self.y <= self.top:
                self.y, self.on_ladder, self.foothold = self.top, False, True
            elif self.y >= 130:
                self.y, self.on_ladder, self.foothold = 130, False, True
            return
        if self.grab and abs(self.x - self.ladder_x) <= 10 and (UP in self.held and self.y > self.top
                                                                or DOWN in self.held and self.y <= self.top):
            self.on_ladder, self.foothold, self.x = True, False, self.ladder_x
            return
        step = 0.0
        if RIGHT in self.held:
            step = self.speed * dt
        elif LEFT in self.held:
            step = -self.speed * dt
        elif self.slide_left > 0:
            s = min(self.slide_left, self.speed * dt)
            self.slide_left -= s
            step = s * self.last_dir
        self.x = max(-400.0, min(400.0, self.x + step))
        if self.edge is not None and self.x > self.edge:
            self.foothold = False
        if self.portal is not None and abs(self.x - self.portal) < 5 and UP in self.held:
            self.map_id = 922010200

    def read(self):
        self.advance()
        return {'map_id': self.map_id, 'x': int(round(self.x)), 'y': int(round(self.y)), 'foothold': self.foothold,
                'rope': False, 'ladder': self.on_ladder}


class WalkTest(unittest.TestCase):
    def setUp(self):
        try:
            import driver
        except ImportError as error:
            self.skipTest('driver import failed: %s' % error)
        self.driver = driver

    def make(self, **world):
        w = World(**world)
        w.events = []
        d = object.__new__(self.driver.Driver)
        d.sender = w
        d.state = 'field'
        d.guard = lambda: None
        d.endpoint = lambda: None
        d.field_input = lambda: None
        d.matches = lambda name, image=None: False
        d.position = w.read
        self.w, self.d = w, d
        return d

    def released(self):
        downs = {vk for vk, down in self.w.events if down}
        ups = {vk for vk, down in self.w.events if down is False}
        return downs <= ups

    def refusal(self, call):
        with self.assertRaises(self.driver.Refusal) as caught:
            call()
        self.assertTrue(self.released(), 'every key pressed is released')
        return str(caught.exception)

    # walk_to
    def test_1_walks_right_and_stops_within_tolerance(self):
        d = self.make(x=0)
        r = d.walk_to(150)
        self.assertLessEqual(abs(r['x'] - 150), 6)
        self.assertEqual(0, r['from_x'])
        self.assertEqual({RIGHT}, {vk for vk, down in self.w.events if down})
        self.assertTrue(self.released())
        self.assertTrue({'map_id', 'x', 'y', 'from_x', 'moved', 'ms'} <= set(r))

    def test_2_walks_left(self):
        d = self.make(x=0)
        r = d.walk_to(-117)
        self.assertLessEqual(abs(r['x'] + 117), 6)
        self.assertIn(LEFT, {vk for vk, down in self.w.events if down})
        self.assertTrue(self.released())

    def test_3_already_there_presses_nothing(self):
        d = self.make(x=-40)
        r = d.walk_to(-38)
        self.assertEqual([], self.w.events)
        self.assertEqual(-40, r['x'])

    def test_4_a_long_slide_is_corrected(self):
        d = self.make(x=0, slide=20)
        r = d.walk_to(120)
        self.assertLessEqual(abs(r['x'] - 120), 6)
        self.assertTrue(self.released())

    def test_4b_a_slide_it_cannot_correct_is_overshoot(self):
        d = self.make(x=0, slide=40)
        message = self.refusal(lambda: d.walk_to(120, tolerance=2))
        self.assertTrue(message.startswith('overshoot'), message)

    def test_5_a_wall_is_no_progress(self):
        d = self.make(x=380)
        message = self.refusal(lambda: d.walk_to(399, tolerance=2))
        self.assertTrue(message.startswith('no_progress'), message)

    def test_6_walking_off_an_edge_is_fell(self):
        d = self.make(x=0, edge=60)
        message = self.refusal(lambda: d.walk_to(200))
        self.assertTrue(message.startswith('fell'), message)

    def test_7_a_map_change_is_map_changed(self):
        d = self.make(x=0)
        self.w.map_id = 922010100
        real = self.w.read
        calls = []

        def read():
            calls.append(1)
            p = real()
            if len(calls) > 3:
                p['map_id'] = 922010200
            return p
        d.position = read
        message = self.refusal(lambda: d.walk_to(300))
        self.assertTrue(message.startswith('map_changed'), message)

    def test_8_not_on_the_ground_presses_nothing(self):
        d = self.make(x=-117, y=0)
        self.w.on_ladder, self.w.foothold = True, False
        message = self.refusal(lambda: d.walk_to(0))
        self.assertTrue(message.startswith('not_on_ground'), message)
        self.assertEqual([], self.w.events)

    def test_9_timeout(self):
        d = self.make(x=0, speed=20)
        message = self.refusal(lambda: d.walk_to(300, timeout_ms=400))
        self.assertTrue(message.startswith('timeout'), message)

    def test_10_owner_input_releases_the_keys(self):
        d = self.make(x=0)
        count = []

        def guard():
            count.append(1)
            if len(count) > 2:
                raise self.driver.Refusal('owner input')
        d.guard = guard
        self.assertEqual('owner input', self.refusal(lambda: d.walk_to(300)))

    # climb
    def test_11_climbs_a_ladder_up_to_a_height(self):
        d = self.make(x=-117, y=50)
        self.w.on_ladder, self.w.foothold = True, False
        r = d.climb(-60)
        self.assertLessEqual(abs(r['y'] + 60), 6)
        self.assertTrue(r['ladder'])
        self.assertTrue(self.released())

    def test_12_grabs_the_ladder_from_its_foot(self):
        d = self.make(x=-115)
        r = d.climb(-180)
        self.assertLessEqual(abs(r['y'] + 180), 6)
        self.assertTrue(r['foothold'])
        self.assertTrue(self.released())

    def test_12b_no_ladder_here(self):
        d = self.make(x=0)
        message = self.refusal(lambda: d.climb(-180))
        self.assertTrue(message.startswith('no_ladder'), message)

    def test_13_a_target_above_the_top_stops_at_the_top(self):
        d = self.make(x=-115)
        message = self.refusal(lambda: d.climb(-300))
        self.assertTrue(message.startswith('stopped_at_top'), message)

    def test_14_climbs_down(self):
        d = self.make(x=-117, y=-100)
        self.w.on_ladder, self.w.foothold = True, False
        r = d.climb(40)
        self.assertLessEqual(abs(r['y'] - 40), 6)
        self.assertIn(DOWN, {vk for vk, down in self.w.events if down})

    def test_15_climb_timeout(self):
        d = self.make(x=-117, y=100, climb=20)
        self.w.on_ladder, self.w.foothold = True, False
        message = self.refusal(lambda: d.climb(-180, timeout_ms=400))
        self.assertTrue(message.startswith('timeout'), message)

    # script steps
    def test_16_walk_to_and_climb_are_script_steps(self):
        self.assertIn('walk_to', self.driver.SCRIPT_TOOLS)
        self.assertIn('climb', self.driver.SCRIPT_TOOLS)
        self.assertEqual(-5000, self.driver.TOOLS['walk_to']['x']['minimum'])
        self.assertEqual(30000, self.driver.TOOLS['climb']['timeout_ms']['maximum'])


if __name__ == '__main__':
    unittest.main()
