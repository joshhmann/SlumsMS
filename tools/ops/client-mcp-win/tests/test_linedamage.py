"""qa-line-damaged (1.6.7): a "!" command whose "!" was lost on the way in goes out as plain chat, and the client's own
chat echo shows it (cygnus-fight-player-20261109T193047Z: "!mobdamage 14 8850011" arrived as "mobdamage 14 8850011" and
"4 8850011" right after a "Welcome to Bera!" banner, and every step said ok). command() now watches the echo and refuses
with line_damaged. Fake sender, fake QA pipe."""
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))


class FakeQa:
    def __init__(self):
        self.log = []
        self.on_enter = None

    def drain(self):
        pass

    def events_from(self, index):
        return list(self.log[index:]), len(self.log)


class FakeSender:
    def __init__(self, qa):
        self.qa = qa
        self.typed = ''

    def key(self, vk, down=None, extended=False):
        if vk == 13 and self.typed and self.qa.on_enter:
            self.qa.log.extend(self.qa.on_enter(self.typed))
            self.typed = ''

    def text(self, value):
        self.typed += value


class FakeSecrets:
    values = ()

    def load(self):
        pass


def echo(text, kind=1):
    return {'ev': 'chat.received', 'kind': kind, 'type': 'all', 'text': text}


class LineDamagedTest(unittest.TestCase):
    def setUp(self):
        try:
            import driver
        except ImportError as error:
            self.skipTest('driver import failed: %s' % error)
        self.driver = driver
        self.qa = FakeQa()
        d = object.__new__(driver.Driver)
        d.sender = FakeSender(self.qa)
        d.secrets = FakeSecrets()
        d.state = 'field'
        d.qa = self.qa
        d.chat_primed = True
        d.unhidden = True
        d.guard = lambda: None
        d.endpoint = lambda: None
        self.open = False

        def matches(name, image=None):
            if name == 'field':
                return True
            if name == 'chat_open':
                self.open = not self.open   # closed at the gate, open after the driver's Enter
                return self.open
            return False
        d.matches = matches
        d.wait = lambda name, timeout=35: True
        self.d = d

    def test_a_lost_bang_is_refused_with_the_echo(self):
        self.qa.on_enter = lambda typed: [echo(typed[1:])]
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.command('!mobdamage 14 8850011')
        self.assertIn('line_damaged', str(caught.exception))
        self.assertIn('mobdamage 14 8850011', str(caught.exception))

    def test_a_lost_prefix_is_refused(self):
        self.qa.on_enter = lambda typed: [echo('4 8850011')]
        with self.assertRaises(self.driver.Refusal) as caught:
            self.d.command('!mobdamage 14 8850011')
        self.assertIn('line_damaged', str(caught.exception))

    def test_an_intact_command_passes(self):
        self.qa.on_enter = lambda typed: [{'ev': 'chat.received', 'kind': 255, 'type': 'system', 'text': 'Hit 1: 8850011'}]
        self.assertEqual({'sent': True}, self.d.command('!mobdamage 14 8850011'))

    def test_other_players_chat_is_not_damage(self):
        self.qa.on_enter = lambda typed: [echo('my mage literally one shots everything'), echo('8850011 lol'), echo('11')]
        self.assertEqual({'sent': True}, self.d.command('!mobdamage 14 8850011'))

    def test_plain_chat_is_not_checked(self):
        self.qa.on_enter = lambda typed: [echo(typed)]
        self.assertEqual({'sent': True}, self.d.command('hello there'))

    def test_without_the_qa_pipe_nothing_is_checked(self):
        self.d.qa = None
        self.qa.on_enter = lambda typed: [echo(typed[1:])]
        self.assertEqual({'sent': True}, self.d.command('!mobdamage 14 8850011'))


if __name__ == '__main__':
    unittest.main()
