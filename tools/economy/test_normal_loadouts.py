import random
import unittest
from simulate_normal_loadouts import one_loadout

class NormalLoadoutsTest(unittest.TestCase):
    def test_guaranteed_simultaneous_drops_take_one_kill(self):
        self.assertEqual(one_loadout({1:{i:1 for i in range(6)}},set(range(6)),random.Random(1)),1)
    def test_disjoint_guaranteed_drops_need_six_kills(self):
        self.assertEqual(one_loadout({i:{i:1} for i in range(6)},set(range(6)),random.Random(1)),6)
    def test_missing_sources_and_bad_probabilities_fail(self):
        for sources in [{1:{1:.5}},{1:{2:0}},{1:{2:1.1}}]:
            with self.assertRaises(ValueError):one_loadout(sources,{2},random.Random(1))
    def test_seeded_model_is_repeatable(self):
        a=one_loadout({1:{1:.01,2:.02},2:{2:.03}},{1,2},random.Random(19))
        self.assertEqual(a,one_loadout({1:{1:.01,2:.02},2:{2:.03}},{1,2},random.Random(19)))
