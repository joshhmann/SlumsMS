import unittest
from audit_forge_sources import map_cases,has_reward

class ForgeSourcesTest(unittest.TestCase):
    def test_map_specific_receipts_do_not_leak_between_branches(self):
        cases=map_cases('case 450004750:\n playerInteraction.gainItem(4001879, 2);\ncase 450004751:\n playerInteraction.gainItem(4001879, 1);')
        self.assertTrue(has_reward(cases[450004750],4001879,2))
        self.assertFalse(has_reward(cases[450004751],4001879,2))
        self.assertTrue(has_reward(cases[450004751],4001879,1))
    def test_comment_does_not_become_a_map_case(self):
        self.assertEqual(map_cases('// case 123456789: no source\n'),{})

    def test_commented_or_quoted_calls_are_not_receipts(self):
        self.assertFalse(has_reward('// playerInteraction.gainItem(4001879,1);',4001879,1))
        self.assertFalse(has_reward('var text="playerInteraction.gainItem(4001879,1)";',4001879,1))

    def test_exit_map_helper_cannot_overwrite_the_reward_switch(self):
        cases=map_cases('case 450004751:\n playerInteraction.gainItem(4001879,1);\ncase 450004751:\n warp=123;')
        self.assertTrue(has_reward(cases[450004751],4001879,1))
