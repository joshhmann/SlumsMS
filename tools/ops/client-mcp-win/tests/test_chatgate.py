"""qa-chatgate-evidence (1.6.7): a chat-gate refusal ("Chat entry is open and did not close") keeps its evidence: a
screenshot and the client's slums-ui-state.json at that moment, so the pixels and the chat focus can be told apart
(horntail-stand-short-20261109T180625Z, 18:16:26 UTC, refused with nothing kept). Fake sender, fake screen."""
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))


class FakeSender:
    def __init__(self):
        self.keys = []
        self.texts = []

    def key(self, vk, down=None, extended=False):
        self.keys.append(vk)

    def text(self, value):
        self.texts.append(value)


class FakeSecrets:
    values = ()

    def load(self):
        pass


class ChatGateEvidenceTest(unittest.TestCase):
    def setUp(self):
        try:
            import driver
        except ImportError as error:
            self.skipTest('driver import failed: %s' % error)
        self.driver = driver
        original = driver.inside
        driver.inside = lambda path, base=None: Path(path)   # the real one reads Windows reparse attributes
        self.addCleanup(setattr, driver, 'inside', original)
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / 'client').mkdir()
        (self.root / 'shots').mkdir()
        d = object.__new__(driver.Driver)
        d.root = self.root
        d.sender = FakeSender()
        d.secrets = FakeSecrets()
        d.state = 'field'
        d.qa = None
        d.chat_primed = True
        d.unhidden = True
        d.guard = lambda: None
        d.endpoint = lambda: None
        d.field_input = lambda: None
        # The entry never closes: the field shows, and chat_open stays true.
        d.matches = lambda name, image=None: name in ('field', 'chat_open')
        d.chat_pixels_match = lambda: False

        def save_shot(label, include_image=True):
            path = self.root / 'shots' / ('20261109T181626.000000Z-' + label + '.png')
            path.write_bytes(b'png')
            return {'path': str(path), 'width': 800, 'height': 600}
        d.save_shot = save_shot
        self.d = d

    def tearDown(self):
        self.tmp.cleanup()

    def write_state(self, chat_open):
        state = {'pid': 1, 'stage': 'field', 'chat_open': chat_open}
        (self.root / 'client' / 'slums-ui-state.json').write_text(json.dumps(state), encoding='ascii')

    def refusal(self, call):
        with self.assertRaises(self.driver.Refusal) as caught:
            call()
        return str(caught.exception)

    def evidence(self, message):
        self.assertIn('Evidence: ', message)
        path = Path(message.split('Evidence: ', 1)[1].strip())
        self.assertTrue(path.is_file(), path)
        self.assertEqual(self.root / 'shots', path.parent, 'kept next to the screenshot')
        return json.loads(path.read_text(encoding='utf-8'))

    def test_a_chat_line_refusal_keeps_the_screenshot_and_the_ui_state(self):
        self.write_state(True)
        message = self.refusal(lambda: self.d.command('!botstatus'))
        self.assertTrue(message.startswith('Chat entry is open and did not close; line not sent.'), message)
        kept = self.evidence(message)
        self.assertTrue(Path(kept['shot']).is_file())
        self.assertIs(kept['pixel_match'], False)
        self.assertEqual({'pid': 1, 'stage': 'field', 'chat_open': True}, kept['ui_state'])
        self.assertLess(kept['ui_state_age_s'], 10)
        self.assertEqual([], self.d.sender.texts, 'nothing typed')

    def test_a_key_refusal_keeps_the_same_evidence(self):
        self.write_state(False)
        message = self.refusal(lambda: self.d.press_key('X'))
        self.assertTrue(message.startswith('Chat entry is open and did not close; key not sent.'), message)
        kept = self.evidence(message)
        self.assertIs(kept['ui_state']['chat_open'], False)

    def test_a_missing_ui_state_is_named_not_fatal(self):
        message = self.refusal(lambda: self.d.press_key('X'))
        kept = self.evidence(message)
        self.assertNotIn('ui_state', kept)
        self.assertEqual('FileNotFoundError', kept['ui_state_error'])


if __name__ == '__main__':
    unittest.main()
