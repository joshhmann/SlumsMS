"""b170: the tools the 187 orchestrator plays a script with (login_credentials, click_anchor, select_*, qa_events).
Fake screens, sender and pipe: no Windows calls."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))


class FakeSender:
    def __init__(self):
        self.events = []

    def text(self, value):
        self.events.append(('text', value))

    def key(self, vk, down=None, extended=False):
        self.events.append(('key', vk, down))


class FakeQa:
    def __init__(self, events):
        self.log = events
        self.closed = False

    def events_from(self, index):
        return list(self.log[index:]), len(self.log)


class NewToolsTest(unittest.TestCase):
    def setUp(self):
        try:
            import driver
        except ImportError as error:
            self.skipTest('driver import failed: %s' % error)
        self.driver = driver
        self.tmp = Path(tempfile.mkdtemp())
        self.real_inside = driver.inside
        driver.inside = lambda path, base=None: Path(path)
        d = object.__new__(driver.Driver)
        d.root = self.tmp
        d.ui = {'points': {'account': (300, 200), 'bera': (410, 300), 'channel_3': (250, 330),
                           'channel_confirm': (400, 360), 'first_character': (200, 250),
                           'character_confirm': (330, 300), 'gender_confirm': (300, 260)},
                'screens': {}}
        d.sender = FakeSender()
        d.secrets = driver.Secrets(self.tmp / 'secrets' / 'account.txt')
        d.state = 'login'
        d.sensitive_screen = False
        d.vault_pic = None
        d.guard = lambda: None
        d.endpoint = lambda: None
        d.field_input = lambda: None
        d.matches = lambda name, image=None: name in ('login', 'save_login_unchecked')
        d.wait = lambda name, timeout=35: {'name': name}
        d.wait_choice = lambda names, timeout=35: 'character'
        d.click = lambda name: d.sender.events.append(('click', name))
        (self.tmp / 'ui.json').write_text('{"points": {}, "screens": {}}')
        self.d = d

    def tearDown(self):
        self.driver.inside = self.real_inside

    # -- the vault --

    def test_login_types_the_vault_account_and_keeps_the_pic(self):
        (self.tmp / 'secrets').mkdir()
        (self.tmp / 'secrets' / 'aq-gm-1.txt').write_text('qa-acct\nqa-pass\n123456\n')
        self.assertEqual({'vault': 'aq-gm-1', 'entered': True}, self.d.login_credentials('aq-gm-1'))
        typed = [event for event in self.d.sender.events if event[0] == 'text'
                 or (event[0] == 'key' and event[1] in (9, 13))][:4]
        self.assertEqual([('text', 'qa-acct'), ('key', 9, None), ('text', 'qa-pass'), ('key', 13, None)],
                         [('key', event[1], event[2]) if event[0] == 'key' and len(event) > 2 else event
                          for event in typed])
        self.assertEqual('123456', self.d.vault_pic)

    def test_a_vault_without_its_file_is_refused_with_the_fix(self):
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.login_credentials('aq-absent')
        self.assertIn('Create secrets/aq-absent.txt', str(caught.exception))

    def test_a_vault_name_that_is_not_a_file_name_is_refused(self):
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.vault_secrets('../account')
        self.assertIn('vault alias', str(caught.exception))

    def test_no_password_is_typed_when_the_login_screen_is_not_showing(self):
        self.d.matches = lambda name, image=None: False
        with self.assertRaises(self.driver.Refusal):
            self.d.login_credentials('aq-gm-1')
        self.assertEqual([], [event for event in self.d.sender.events if event[0] == 'text'])

    # -- the screens --

    def test_click_anchor_clicks_the_calibrated_point(self):
        self.assertEqual({'clicked': 'bera', 'point': [410, 300]}, self.d.click_anchor('bera'))
        self.assertIn(('click', 'bera'), self.d.sender.events)

    def test_an_anchor_that_is_not_calibrated_is_refused(self):
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.click_anchor('party_invite_accept')
        self.assertIn('not a calibrated point in ui.json', str(caught.exception))

    def test_select_world_channel_and_character_click_their_points(self):
        self.assertEqual({'world': 'Bera'}, self.d.select_world('Bera'))
        self.assertEqual({'channel': 3}, self.d.select_channel(3))
        self.assertEqual({'slot': 0, 'character': 'first_character'}, self.d.select_character(0))
        clicks = [event[1] for event in self.d.sender.events if event[0] == 'click']
        self.assertEqual(['bera', 'channel_3', 'channel_confirm', 'first_character', 'character_confirm'], clicks)

    def test_only_the_calibrated_character_slot_is_offered(self):
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.select_character(4)
        self.assertIn('only slot 0', str(caught.exception))

    def test_a_character_slot_with_no_characters_behind_it_is_refused(self):
        self.d.wait_choice = lambda names, timeout=35: 'empty_character'
        self.d.save_shot = lambda label, include_image=False: {'path': 'shots/x.png'}
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.select_character(0)
        self.assertIn('no characters on Bera', str(caught.exception))

    # -- the pipe's log --

    def test_qa_events_returns_the_events_from_the_position_with_their_time(self):
        self.d.qa = FakeQa([{'ev': 'hello', 'pid': 7, '_at': 1761900000.0},
                            {'ev': 'worldselect.ready', '_at': 1761900001.5}])
        first = self.d.qa_events(0)
        self.assertEqual((0, 2), (first['from'], first['next']))
        self.assertEqual(['hello', 'worldselect.ready'], [event['ev'] for event in first['events']])
        self.assertTrue(first['events'][1]['utc'].endswith('Z'))
        self.assertNotIn('_at', first['events'][1])
        self.assertEqual([], self.d.qa_events(2)['events'])

    def test_qa_events_truncates_a_long_log_and_says_so(self):
        self.d.qa = FakeQa([{'ev': 'ping', '_at': 1761900000.0}] * (self.driver.QA_EVENTS_MAX + 5))
        page = self.d.qa_events(0)
        self.assertEqual(self.driver.QA_EVENTS_MAX, len(page['events']))
        self.assertEqual(self.driver.QA_EVENTS_MAX, page['next'])
        self.assertTrue(page['more'])

    def test_qa_events_needs_the_qa_pipe(self):
        self.d.qa = None
        self.d.qa_build = lambda: False
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.qa_events(0)
        self.assertIn('No QA pipe', str(caught.exception))

    # -- validation --

    def test_the_new_tools_validate_their_arguments(self):
        self.d.validate('click_anchor', {'name': 'bera'})
        self.d.validate('qa_events', {})
        self.d.validate('select_channel', {'channel': 5})
        for name, args in (('click_anchor', {}), ('select_channel', {'channel': 6}),
                           ('select_character', {'slot': 15}), ('select_world', {'world': 'Scania'}),
                           ('qa_events', {'since': -1})):
            with self.assertRaises(self.driver.Refusal):
                self.d.validate(name, args)


if __name__ == '__main__':
    unittest.main()
