"""b170: the tools the 187 orchestrator plays a script with (login_credentials, click_anchor, select_*, qa_events).
Fake screens, sender and pipe: no Windows calls."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))


class FakeSender:
    def __init__(self):
        self.events = []

    def text(self, value):
        self.events.append(('text', value))

    def click(self, x, y, right=False, double=False):
        self.events.append(('mouse', x, y))

    def key(self, vk, down=None, extended=False):
        self.events.append(('key', vk, down))


class FakeQa:
    def __init__(self, events):
        self.log = events
        self.closed = False

    def events_from(self, index):
        return list(self.log[index:]), len(self.log)

    def send(self, line):
        self.sent = getattr(self, 'sent', []) + [line]

    def wait(self, name, timeout):
        while self.log:
            event = self.log.pop(0)
            if event.get('ev') == name:
                return event
        return None


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

    def test_a_lost_click_on_bera_is_repeated_on_a_release_build(self):
        # b249: a release build has no QA pipe; the first click on Bera is often lost and the channel list never shows.
        self.d.qa = None
        answers = iter([False, True])
        self.d.channel_shows = lambda timeout: next(answers)
        self.d.click_bera()
        clicks = [event[1] for event in self.d.sender.events if event[0] == 'click']
        self.assertEqual(['bera', 'bera'], clicks)

    def test_bera_is_clicked_three_times_at_most_on_a_release_build(self):
        self.d.qa = None
        self.d.channel_shows = lambda timeout: False
        self.d.click_bera()
        clicks = [event[1] for event in self.d.sender.events if event[0] == 'click']
        self.assertEqual(['bera'] * 3, clicks)

    def test_the_chat_primer_never_toggles_gm_hide(self):
        # !unhide casts GM Hide, which toggles: the primer used to send it before login's own !unhide, so the GM
        # ended up hidden again and bots ignored it (cv-13, 2026-11-01).
        self.assertNotIn('hide', self.driver.CHAT_PRIMER)
        sent = []
        d = self.d
        d.chat_primed = False
        real = self.driver.Driver.command
        def fake(line, _real=real):
            sent.append(line)
            if line == self.driver.CHAT_PRIMER or d.chat_primed is False:
                return _real(d, line) if line != self.driver.CHAT_PRIMER else None
        d.command = fake
        d.endpoint = lambda: (_ for _ in ()).throw(RuntimeError('stop'))
        try:
            real(d, '!unhide')
        except RuntimeError:
            pass
        self.assertEqual([self.driver.CHAT_PRIMER], sent)
        self.assertTrue(d.chat_primed)

    def test_a_scripted_login_still_unhides_exactly_once(self):
        # Scripted runs log in through select_*; nothing else sends !unhide there, so the primer must (once), or the
        # GM stays hidden and bots ignore its chat (b066/b100/b102 on TEST .111, 2026-11-01 18:15-18:25 UTC).
        d = self.d
        d.chat_primed = False
        d.unhidden = False
        sent = []
        real = self.driver.Driver.command
        def fake(line):
            sent.append(line)
            if line in ('!unhide', self.driver.CHAT_PRIMER):
                if line == '!unhide':
                    d.unhidden = True
                return None
        d.command = fake
        d.endpoint = lambda: (_ for _ in ()).throw(RuntimeError('stop'))
        try:
            real(d, '!qa run cv-8')
        except RuntimeError:
            pass
        self.assertEqual(['!unhide'], sent)

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


class ClientEndpointTest(unittest.TestCase):
    """o03: the QA client may point at TEST only: 164 until the 1.6.3 TEST cutover, then mapletest (192.168.0.111)."""

    def setUp(self):
        try:
            import driver
        except ImportError as error:
            self.skipTest('driver import failed: %s' % error)
        self.driver = driver
        self.testhost = driver.testhost
        self.tmp = Path(tempfile.mkdtemp())

    def config(self, text):
        path = self.tmp / 'config.ini'
        path.write_text(text, encoding='ascii')
        return path

    def setting(self, value):
        """SLUMS_TEST_HOST=value for the block (None: unset)."""
        environ = {k: v for k, v in os.environ.items() if k != 'SLUMS_TEST_HOST'}
        if value is not None:
            environ['SLUMS_TEST_HOST'] = value
        return mock.patch.dict(os.environ, environ, clear=True)

    def test_the_client_config_points_at_the_test_box(self):
        # o03: TEST is mapletest (192.168.0.111) since the 2026-11-01 cutover
        with self.setting(None):
            self.driver.verify_config(self.config('host=192.168.0.111\nport=8489\n'))
        with self.setting('192.168.0.111'):
            self.driver.verify_config(self.config('host=192.168.0.111\nport=8489\n'))
        # the two boxes are the whole allowlist: TEST's own box is accepted beside the old one
        with self.setting('192.168.0.164'):
            self.driver.verify_config(self.config('host=192.168.0.164\nport=8489\n'))

    def test_another_box_and_live_are_refused(self):
        with self.setting(None):
            for text in ('host=192.168.0.164\nport=8489\n',                                  # the old box, not the default
                         'host=slumsms.asslorde.com\nport=8489\n',                          # the live site
                         'host=10.0.0.9\nport=8489\n',                                      # any other address
                         'host=\nport=8489\n',                                              # no host at all
                         'host=192.168.0.111\nport=8485\n',                                 # live's login port
                         'host=192.168.0.111\nport=8489\nhost=10.0.0.9\nport=8489\n'):     # two endpoints
                with self.assertRaises(self.driver.Refusal, msg=text):
                    self.driver.verify_config(self.config(text))

    def test_slums_test_host_moves_the_guard(self):
        with self.setting('192.168.0.111'):
            self.driver.verify_config(self.config('host=192.168.0.111\nport=8489\n'))
            with self.assertRaises(self.driver.Refusal):
                self.driver.verify_config(self.config('host=192.168.0.164\nport=8489\n'))

    def test_a_refused_setting_names_its_value_and_is_never_a_default(self):
        for refused in ('slumsms.asslorde.com', '10.0.0.9', 'mapletest', ''):
            with self.setting(refused):
                with self.assertRaises(self.driver.Refusal, msg=refused) as caught:
                    self.driver.test_host()
                self.assertIn(repr(refused), str(caught.exception), refused)
                with self.assertRaises(self.driver.Refusal, msg=refused):
                    self.driver.verify_config(self.config('host=192.168.0.164\nport=8489\n'))

    def test_the_guard_is_one_allowlist_and_one_port(self):
        self.assertEqual(('192.168.0.164', '192.168.0.111'), self.testhost.TEST_HOSTS)
        self.assertEqual('8489', self.testhost.PORT)
        with self.setting(None):
            self.assertEqual('192.168.0.111', self.driver.test_host())
            for name in self.testhost.TEST_HOSTS:
                self.assertEqual(name, self.testhost.host(name))
            with self.assertRaises(self.testhost.Refused) as caught:
                self.testhost.port('8485')
            self.assertIn('8485', str(caught.exception))
            self.assertIn("8485 is live's login port", str(caught.exception))


if __name__ == '__main__':
    unittest.main()


class PositionTest(NewToolsTest):
    # qa-position-signal (1.6.6): gm-0 scripts cannot use !pos; the QA client says where the character is.
    def test_position_is_the_clients_answer(self):
        self.d.qa = FakeQa([{'ev': 'player.pos', 'ok': True, 'map_id': 103000800, 'x': -110, 'y': -846,
                             'foothold': True, 'rope': False, 'ladder': False}])
        self.assertEqual({'map_id': 103000800, 'x': -110, 'y': -846, 'foothold': True, 'rope': False, 'ladder': False},
                         self.d.position())
        self.assertEqual(['pos'], self.d.qa.sent)
        self.assertEqual([], self.d.sender.events, 'position presses nothing')

    def test_position_passes_the_client_error_on(self):
        self.d.qa = FakeQa([{'ev': 'player.pos', 'ok': False, 'error': 'site_mismatch'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.position()
        self.assertEqual('site_mismatch', str(caught.exception))

    def test_position_without_an_answer_is_field_not_ready(self):
        self.d.qa = FakeQa([])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.position()
        self.assertEqual('field_not_ready', str(caught.exception))

    def test_position_is_a_script_tool_without_arguments(self):
        self.assertIn('position', self.driver.SCRIPT_TOOLS)
        self.assertEqual({}, self.driver.TOOLS['position'])


class ClickNpcTest(NewToolsTest):
    def test_click_npc_clicks_where_the_client_says(self):
        self.d.qa = FakeQa([{'ev': 'npc.pos', 'npc': 9999, 'ok': True, 'x': 1, 'y': 1},
                            {'ev': 'npc.pos', 'npc': 2030008, 'ok': True, 'x': 431, 'y': 352}])
        self.assertEqual({'npc': 2030008, 'clicked': [431, 352]}, self.d.click_npc(2030008))
        self.assertEqual(['npc_pos 2030008'], self.d.qa.sent)
        self.assertIn(('mouse', 431, 352), self.d.sender.events)

    def test_click_npc_passes_the_client_error_on(self):
        self.d.qa = FakeQa([{'ev': 'npc.pos', 'npc': 2030008, 'ok': False, 'error': 'out_of_view', 'x': 900, 'y': 10}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.click_npc(2030008)
        self.assertEqual('out_of_view', str(caught.exception))
        self.assertNotIn('mouse', [event[0] for event in self.d.sender.events])

    def test_click_npc_without_an_answer_is_field_not_ready(self):
        self.d.qa = FakeQa([])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.click_npc(2030008)
        self.assertEqual('field_not_ready', str(caught.exception))


class ClickOptionTest(NewToolsTest):
    def test_click_option_reports_the_row_value(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'click_option', 'option': 1, 'ok': True, 'value': 0}])
        self.assertEqual({'option': 1, 'value': 0}, self.d.click_option(1))
        self.assertEqual(['click_option 1'], self.d.qa.sent)

    def test_the_option_anchor_goes_to_click_option(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'click_option', 'option': 3, 'ok': True, 'value': 12}])
        self.assertEqual({'option': 3, 'value': 12}, self.d.click_anchor('npc_dialog.option_3'))

    def test_not_a_menu_is_passed_on(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'click_option', 'option': 1, 'ok': False, 'error': 'not_a_menu'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.click_option(1)
        self.assertEqual('not_a_menu', str(caught.exception))

    def test_option_9_is_still_a_ui_point_name(self):
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.click_anchor('npc_dialog.option_9')
        self.assertIn('not a calibrated point', str(caught.exception))


class DropAndPartyTest(NewToolsTest):
    """P1-Q2 (Zakum run): drop_item, party_create, party_invite ride the QA client's own senders."""

    def test_drop_item_sends_the_item_and_count(self):
        self.d.qa = FakeQa([{'ev': 'item.drop', 'item': 4001017, 'count': 1, 'type': 4, 'slot': 3, 'left': 4,
                             'ok': True}])
        self.assertEqual({'item': 4001017, 'count': 1, 'slot': 3, 'left': 4}, self.d.drop_item(4001017))
        self.assertEqual(['drop_item 4001017 1'], self.d.qa.sent)

    def test_a_refused_drop_is_not_dropped(self):
        # qa-drop-honest: build 18b slot 2026-11-08 14:15, the server refused a gm 3 character's drops ("GM
        # characters cannot drop items.") and drop_item said ok. The client now answers only once the stack shrank.
        self.d.qa = FakeQa([{'ev': 'item.drop', 'item': 4001022, 'count': 1, 'slot': 33, 'left': 3, 'ok': False,
                             'error': 'not_dropped'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.drop_item(4001022)
        self.assertEqual('not_dropped', str(caught.exception))

    def test_drop_item_waits_past_the_clients_own_wait(self):
        # The client answers not_dropped after 3 s; a 3 s driver wait would call that no_character instead.
        waits = []
        qa = FakeQa([{'ev': 'item.drop', 'item': 4001017, 'count': 1, 'slot': 3, 'left': 0, 'ok': True}])
        real_wait = qa.wait
        qa.wait = lambda name, timeout: (waits.append(timeout), real_wait(name, timeout))[1]
        self.d.qa = qa
        self.d.drop_item(4001017)
        self.assertGreater(waits[0], 4.0)

    def test_drop_item_passes_the_client_error_on(self):
        self.d.qa = FakeQa([{'ev': 'item.drop', 'item': 4001017, 'count': 1, 'ok': False, 'error': 'not_in_inventory'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.drop_item(4001017)
        self.assertEqual('not_in_inventory', str(caught.exception))

    def test_drop_item_skips_an_older_answer(self):
        self.d.qa = FakeQa([{'ev': 'item.drop', 'item': 2000000, 'count': 1, 'ok': True, 'slot': 1},
                            {'ev': 'item.drop', 'item': 4001017, 'count': 2, 'ok': True, 'slot': 5}])
        self.assertEqual(5, self.d.drop_item(4001017, 2)['slot'])

    def test_no_answer_is_no_character(self):
        self.d.qa = FakeQa([])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.drop_item(4001017)
        self.assertEqual('no_character', str(caught.exception))

    def test_party_create(self):
        self.d.qa = FakeQa([{'ev': 'party.sent', 'op': 'create', 'ok': True}])
        self.assertEqual({'op': 'create'}, self.d.party_create())
        self.assertEqual(['party_create'], self.d.qa.sent)

    def test_party_invite(self):
        self.d.qa = FakeQa([{'ev': 'party.sent', 'op': 'invite', 'name': 'Bishbot', 'ok': True}])
        self.assertEqual({'op': 'invite', 'name': 'Bishbot'}, self.d.party_invite('Bishbot'))
        self.assertEqual(['party_invite Bishbot'], self.d.qa.sent)

    def test_party_invite_refuses_a_bad_name_before_sending(self):
        self.d.qa = FakeQa([])
        for name in ('abc', 'Bish bot', 'Bish;bot', 'abcdefghijklm'):
            with self.assertRaises(self.driver.Refusal):
                self.d.party_invite(name)
        self.assertFalse(hasattr(self.d.qa, 'sent'))

    def test_party_invite_field_not_ready(self):
        self.d.qa = FakeQa([{'ev': 'party.sent', 'op': 'invite', 'name': 'Bishbot', 'ok': False,
                             'error': 'field_not_ready'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.party_invite('Bishbot')
        self.assertEqual('field_not_ready', str(caught.exception))

    def test_the_new_tools_are_script_steps_with_arguments(self):
        for tool in ('drop_item', 'party_create', 'party_invite'):
            self.assertIn(tool, self.driver.SCRIPT_TOOLS)
            self.assertIn(tool, self.driver.TOOLS)
        self.assertEqual(('item',), self.driver.REQUIRED['drop_item'])
        self.assertEqual(('name',), self.driver.REQUIRED['party_invite'])


class CloseDialogTest(NewToolsTest):
    """D1: the welcome dialog on a new character blocks chat; close_dialog ends any npc dialog."""

    def test_close_dialog(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'close_dialog', 'ok': True}])
        self.assertEqual({'op': 'close_dialog'}, self.d.close_dialog())
        self.assertEqual(['close_dialog'], self.d.qa.sent)

    def test_the_close_anchor_goes_to_close_dialog(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'close_dialog', 'ok': True}])
        self.assertEqual({'op': 'close_dialog'}, self.d.click_anchor('npc_dialog.close'))

    def test_no_dialog_is_passed_on(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'close_dialog', 'ok': False, 'error': 'no_dialog'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.close_dialog()
        self.assertEqual('no_dialog', str(caught.exception))

    def test_an_option_answer_is_not_a_close_answer(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'click_option', 'option': 1, 'ok': True, 'value': 0}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.close_dialog()
        self.assertEqual('no_dialog', str(caught.exception))


class DialogNextTest(NewToolsTest):
    """H1: sendOk('Good luck!') starts the Horntail instance only on OK; dialog_next presses it."""

    def test_dialog_next(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'dialog_next', 'ok': True, 'type': 0}])
        self.assertEqual({'op': 'dialog_next', 'type': 0}, self.d.dialog_next())
        self.assertEqual(['dialog_next'], self.d.qa.sent)

    def test_the_next_anchor_goes_to_dialog_next(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'dialog_next', 'ok': True, 'type': 1}])
        self.assertEqual({'op': 'dialog_next', 'type': 1}, self.d.click_anchor('npc_dialog.next'))

    def test_a_menu_is_refused(self):
        self.d.qa = FakeQa([{'ev': 'click.done', 'op': 'dialog_next', 'ok': False, 'error': 'use_click_option',
                             'type': 4}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.dialog_next()
        self.assertEqual('use_click_option', str(caught.exception))


class UseCashItemTest(NewToolsTest):
    """P0-6: the Incubator on an egg, or on an explicit (empty) slot."""

    def test_incubator_on_an_egg(self):
        self.d.qa = FakeQa([{'ev': 'item.use', 'item': 5060002, 'slot': 2, 'target_type': 4, 'target_slot': 5,
                             'ok': True}])
        self.assertEqual({'item': 5060002, 'slot': 2, 'target_type': 4, 'target_slot': 5},
                         self.d.use_cash_item(5060002, '4170000'))
        self.assertEqual(['use_cash_item 5060002 4170000'], self.d.qa.sent)

    def test_an_explicit_slot(self):
        self.d.qa = FakeQa([{'ev': 'item.use', 'item': 5060002, 'slot': 2, 'target_type': 4, 'target_slot': 99,
                             'ok': True}])
        self.assertEqual(99, self.d.use_cash_item(5060002, '4:99')['target_slot'])

    def test_a_bad_target_is_refused_before_sending(self):
        self.d.qa = FakeQa([])
        for target in ('egg', '6:1', '4:', '4170000;x'):
            with self.assertRaises(self.driver.Refusal):
                self.d.use_cash_item(5060002, target)
        self.assertFalse(hasattr(self.d.qa, 'sent'))

    def test_the_client_error_is_passed_on(self):
        self.d.qa = FakeQa([{'ev': 'item.use', 'item': 5060002, 'ok': False, 'error': 'target_not_in_inventory'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.use_cash_item(5060002, '4170000')
        self.assertEqual('target_not_in_inventory', str(caught.exception))


class MerchantOpenTest(NewToolsTest):
    """P0-2 GP8: a hired merchant set up through the QA client."""

    def test_merchant_open(self):
        self.d.qa = FakeQa([{'ev': 'merchant.open', 'item': 2000000, 'quantity': 5, 'price': 1000, 'ok': True,
                             'slot': 3, 'merchant_item': 5030000}])
        self.assertEqual({'item': 2000000, 'quantity': 5, 'price': 1000, 'slot': 3, 'merchant_item': 5030000},
                         self.d.merchant_open(2000000, 5, 1000, 'GP8 shop'))
        self.assertEqual(['merchant_open 2000000 5 1000 GP8 shop'], self.d.qa.sent)

    def test_fredrick_first_is_passed_on(self):
        self.d.qa = FakeQa([{'ev': 'merchant.open', 'item': 2000000, 'ok': False,
                             'error': 'retrieve_from_fredrick_first', 'check': 9}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.merchant_open(2000000, 1, 10)
        self.assertEqual('retrieve_from_fredrick_first', str(caught.exception))

    def test_merchant_close(self):
        self.d.qa = FakeQa([{'ev': 'merchant.close', 'ok': True}])
        self.assertEqual({'closed': True}, self.d.merchant_close())
        self.assertEqual(['merchant_close'], self.d.qa.sent)

    def test_no_merchant_is_passed_on(self):
        self.d.qa = FakeQa([{'ev': 'merchant.close', 'ok': False, 'error': 'no_merchant'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.merchant_close()
        self.assertEqual('no_merchant', str(caught.exception))

    def test_a_quote_in_the_title_is_refused_before_sending(self):
        self.d.qa = FakeQa([])
        with self.assertRaises(self.driver.Refusal):
            self.d.merchant_open(2000000, 1, 10, 'a "b"')
        self.assertFalse(hasattr(self.d.qa, 'sent'))


class FredrickTest(NewToolsTest):
    def test_retrieve(self):
        self.d.qa = FakeQa([{'ev': 'item.fredrick', 'ok': True, 'code': 30}])
        self.assertEqual({'retrieved': True, 'code': 30}, self.d.fredrick_retrieve())
        self.assertEqual(['fredrick_retrieve'], self.d.qa.sent)

    def test_a_refusal_keeps_the_code(self):
        self.d.qa = FakeQa([{'ev': 'item.fredrick', 'ok': False, 'code': 33, 'error': 'refused'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.fredrick_retrieve()
        self.assertEqual('refused (code 33)', str(caught.exception))


class ShopTest(NewToolsTest):
    """1.6.5 GP4: the open NPC shop's buy, sell and close through the QA client (QA build 16)."""

    def test_buy_reports_the_server_code(self):
        self.d.qa = FakeQa([{'ev': 'shop.result', 'op': 'buy', 'item': 2000000, 'quantity': 50, 'code': 0, 'ok': True}])
        self.assertEqual({'op': 'buy', 'item': 2000000, 'quantity': 50, 'code': 0}, self.d.shop_buy(2000000, 50))
        self.assertEqual(['shop_buy 2000000 50'], self.d.qa.sent)

    def test_a_buy_without_the_mesos_is_a_refusal(self):
        self.d.qa = FakeQa([{'ev': 'shop.result', 'op': 'buy', 'item': 2000000, 'quantity': 50, 'code': 2,
                             'ok': False, 'error': 'not_enough_mesos'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.shop_buy(2000000, 50)
        self.assertEqual('not_enough_mesos', str(caught.exception))

    def test_sell_without_a_quantity_sells_every_stack(self):
        self.d.qa = FakeQa([{'ev': 'shop.result', 'op': 'sell', 'item': 4000000, 'quantity': 0, 'sold': 130,
                             'stacks': 2, 'ok': True}])
        self.assertEqual({'op': 'sell', 'item': 4000000, 'sold': 130, 'stacks': 2}, self.d.shop_sell(4000000))
        self.assertEqual(['shop_sell 4000000'], self.d.qa.sent)

    def test_sell_with_a_quantity(self):
        self.d.qa = FakeQa([{'ev': 'shop.result', 'op': 'sell', 'item': 4000000, 'quantity': 10, 'sold': 10,
                             'stacks': 1, 'ok': True}])
        self.assertEqual(10, self.d.shop_sell(4000000, 10)['sold'])
        self.assertEqual(['shop_sell 4000000 10'], self.d.qa.sent)

    def test_a_short_sale_says_how_many_went(self):
        self.d.qa = FakeQa([{'ev': 'shop.result', 'op': 'sell', 'item': 4000000, 'quantity': 10, 'sold': 4,
                             'stacks': 1, 'ok': False, 'error': 'not_enough_items'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.shop_sell(4000000, 10)
        self.assertEqual('not_enough_items (sold 4)', str(caught.exception))

    def test_close(self):
        self.d.qa = FakeQa([{'ev': 'shop.result', 'op': 'close', 'ok': True}])
        self.assertEqual({'op': 'close'}, self.d.shop_close())
        self.assertEqual(['shop_close'], self.d.qa.sent)

    def test_no_shop_open(self):
        self.d.qa = FakeQa([{'ev': 'shop.result', 'op': 'close', 'ok': False, 'error': 'no_shop'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.shop_close()
        self.assertEqual('no_shop', str(caught.exception))

    def test_the_tools_are_script_steps_with_checked_arguments(self):
        self.assertIn('shop_buy', self.driver.SCRIPT_TOOLS)
        self.assertIn('shop_sell', self.driver.SCRIPT_TOOLS)
        self.assertIn('shop_close', self.driver.SCRIPT_TOOLS)
        self.assertEqual(('item', 'quantity'), self.driver.REQUIRED['shop_buy'])
        self.assertEqual(('item',), self.driver.REQUIRED['shop_sell'])
        self.assertEqual(32767, self.driver.TOOLS['shop_buy']['quantity']['maximum'])
        self.assertEqual(0, self.driver.TOOLS['shop_sell']['quantity']['minimum'])


class UseItemTest(NewToolsTest):
    """1.6.5 GP4 leg B: drinks a USE item through the QA client (QA build 17)."""

    def test_use_reports_what_is_left(self):
        self.d.qa = FakeQa([{'ev': 'item.used', 'item': 2000000, 'slot': 3, 'left': 5, 'ok': True}])
        self.assertEqual({'used': True, 'item': 2000000, 'slot': 3, 'left': 5}, self.d.use_item(2000000))
        self.assertEqual(['use_item 2000000'], self.d.qa.sent)

    def test_none_left_is_a_refusal(self):
        self.d.qa = FakeQa([{'ev': 'item.used', 'item': 2000000, 'ok': False, 'error': 'not_in_inventory'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.use_item(2000000)
        self.assertEqual('not_in_inventory', str(caught.exception))


class TradeTest(NewToolsTest):
    """1.6.5 GP6: a trade with a player on the field through the QA client (QA build 17)."""

    def test_invite_names_the_partner(self):
        self.d.qa = FakeQa([{'ev': 'trade.result', 'op': 'invite', 'name': 'Ownerchar', 'cid': 77, 'ok': True}])
        self.assertEqual({'op': 'invite', 'name': 'Ownerchar', 'cid': 77}, self.d.trade_invite('Ownerchar'))
        self.assertEqual(['trade_invite Ownerchar'], self.d.qa.sent)

    def test_a_partner_who_is_not_here(self):
        self.d.qa = FakeQa([{'ev': 'trade.result', 'op': 'invite', 'ok': False, 'error': 'not_on_map'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.trade_invite('Ownerchar')
        self.assertEqual('not_on_map', str(caught.exception))

    def test_add_item_and_mesos(self):
        self.d.qa = FakeQa([{'ev': 'trade.result', 'op': 'add_item', 'item': 4000000, 'slot': 1, 'quantity': 5,
                             'ok': True},
                            {'ev': 'trade.result', 'op': 'add_meso', 'meso': 1000, 'set': 1000, 'ok': True}])
        self.assertEqual({'op': 'add_item', 'item': 4000000, 'slot': 1, 'quantity': 5},
                         self.d.trade_add_item(4000000, 5))
        self.assertEqual({'op': 'add_meso', 'meso': 1000}, self.d.trade_add_meso(1000))
        self.assertEqual(['trade_add_item 4000000 5', 'trade_add_meso 1000'], self.d.qa.sent)

    def test_add_item_without_a_quantity_puts_the_stack(self):
        self.d.qa = FakeQa([{'ev': 'trade.result', 'op': 'add_item', 'item': 4000000, 'slot': 1, 'quantity': 9,
                             'ok': True}])
        self.assertEqual(9, self.d.trade_add_item(4000000)['quantity'])
        self.assertEqual(['trade_add_item 4000000'], self.d.qa.sent)

    def test_confirm_waits_for_the_trade_to_end(self):
        self.d.qa = FakeQa([{'ev': 'trade.result', 'op': 'confirm', 'ok': True},
                            {'ev': 'trade.done', 'result': 7, 'ok': True}])
        self.assertEqual({'op': 'confirm', 'done': True, 'result': 7}, self.d.trade_confirm(timeout_s=30))
        self.assertEqual(['trade_confirm'], self.d.qa.sent)

    def test_a_cancelled_trade_is_a_refusal(self):
        self.d.qa = FakeQa([{'ev': 'trade.result', 'op': 'confirm', 'ok': True},
                            {'ev': 'trade.done', 'result': 2, 'ok': False, 'error': 'partner_cancelled'}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.trade_confirm(timeout_s=30)
        self.assertEqual('partner_cancelled', str(caught.exception))

    def test_accept_and_cancel(self):
        self.d.qa = FakeQa([{'ev': 'trade.result', 'op': 'accept', 'ok': True},
                            {'ev': 'trade.result', 'op': 'cancel', 'ok': True}])
        self.assertEqual({'op': 'accept'}, self.d.trade_accept())
        self.assertEqual({'op': 'cancel'}, self.d.trade_cancel())
        self.assertEqual(['trade_accept', 'trade_cancel'], self.d.qa.sent)

    def test_the_tools_are_script_steps(self):
        for name in ('use_item', 'trade_invite', 'trade_accept', 'trade_add_item', 'trade_add_meso',
                     'trade_confirm', 'trade_cancel'):
            self.assertIn(name, self.driver.SCRIPT_TOOLS)
        self.assertEqual(('name',), self.driver.REQUIRED['trade_invite'])
        self.assertEqual(('meso',), self.driver.REQUIRED['trade_add_meso'])
        self.assertEqual(300, self.driver.TOOLS['trade_confirm']['timeout_s']['maximum'])


class PickupTest(NewToolsTest):
    def test_pickup_names_the_slot_it_landed_in(self):
        self.d.qa = FakeQa([{'ev': 'item.picked', 'item': 4001018, 'oid': 7, 'ok': True, 'type': 4, 'slot': 3}])
        self.assertEqual({'picked': True, 'item': 4001018, 'oid': 7, 'type': 4, 'slot': 3},
                         self.d.pickup_item(4001018))
        self.assertEqual(['pickup_item 4001018'], self.d.qa.sent)

    def test_a_drop_taken_by_someone_else_is_a_refusal(self):
        self.d.qa = FakeQa([{'ev': 'item.picked', 'item': 4001018, 'oid': 7, 'ok': False,
                             'error': 'taken_by_other', 'picker': 99}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.pickup_item(4001018)
        self.assertEqual('taken_by_other (picker 99)', str(caught.exception))

    def test_pickup_says_which_drop_it_took(self):
        # qa-pickup-nearest (1.6.6): the client takes the nearest matching drop and says where it was.
        self.d.qa = FakeQa([{'ev': 'item.picked', 'item': 4001022, 'oid': 501, 'ok': True, 'type': 4, 'slot': 2,
                             'x': 10, 'y': 130, 'distance': 10}])
        self.assertEqual({'picked': True, 'item': 4001022, 'oid': 501, 'type': 4, 'slot': 2, 'x': 10, 'y': 130,
                          'distance': 10}, self.d.pickup_item(4001022))

    def test_a_drop_out_of_the_servers_range_is_named(self):
        # LPQ run lpq-stage1-handin-20261108T053234Z: the only drop was a monster's 1600 px up; the server ignores it.
        self.d.qa = FakeQa([{'ev': 'item.picked', 'item': 4001022, 'ok': False, 'error': 'out_of_range',
                             'x': 212, 'y': -1499, 'distance': 1642}])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.pickup_item(4001022)
        self.assertEqual('out_of_range (nearest drop at 212,-1499, 1642 px away)', str(caught.exception))

    def test_no_answer_is_a_timeout(self):
        self.d.qa = FakeQa([])
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.pickup_item(4001018)
        self.assertEqual('timeout', str(caught.exception))
