import math
import unittest
from gen_normal_drops import check_item, wait_stats


class NormalDropPolicyTest(unittest.TestCase):
    def info(self, **changes):
        return dict(name='Sweetwater Hat',kind='equip',slot='Cap',cash=0,quest=0,timeLimited=0,
                    only=0,accountSharable=0,tradeBlock=0,equipTradeBlock=1,success=0) | changes
    def entry(self):
        return dict(name='Sweetwater Hat',kind='equip',bindings=['equipTradeBlock'])
    def test_bound_item_requires_exact_allowlist(self):
        check_item(1003976,self.info(),self.entry(),{},set())
        with self.assertRaises(ValueError):check_item(1003976,self.info(),None,{},set())
        with self.assertRaises(ValueError):check_item(1003976,self.info(tradeBlock=1),self.entry(),{},set())
    def test_allowlist_does_not_override_other_exclusions(self):
        for flag in ['cash','quest','timeLimited','only','accountSharable']:
            with self.subTest(flag=flag),self.assertRaises(ValueError):
                check_item(1003976,self.info(**{flag:1}),self.entry(),{},set())
    def test_forge_and_rebirth_items_stay_blocked(self):
        for restrictions,forged in [({1003976:1},set()),({},{1003976})]:
            with self.assertRaises(ValueError):check_item(1003976,self.info(),self.entry(),restrictions,forged)
    def test_name_and_kind_drift_fail_closed(self):
        for changes in [{'name':'different'},{'kind':'cash'}]:
            with self.assertRaises(ValueError):check_item(1003976,self.info(**changes),self.entry(),{},set())
        with self.assertRaises(ValueError):check_item(1003976,None,self.entry(),{},set())
    def test_wait_model_saturates_and_rejects_invalid_rates(self):
        self.assertEqual(wait_stats(800000,4,300)['mean_eligible_kills'],1)
        self.assertEqual(wait_stats(800000,4,300)['p90_eligible_kills'],1)
        for args in [(0,4,300),(100,-1,300),(100,4,0),(100,float('nan'),300)]:
            with self.assertRaises(ValueError):wait_stats(*args)
    def test_wait_model_does_not_double_two_sources(self):
        s=wait_stats(700,4,300)
        self.assertAlmostEqual(s['mean_eligible_kills'],999999/2800)
        self.assertGreater(s['p90_eligible_kills'],s['mean_eligible_kills'])

if __name__=='__main__':unittest.main()
