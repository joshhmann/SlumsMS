import unittest
from audit_gear_stats import transfer, set_bonus


class GearStatsTest(unittest.TestCase):
    def test_forge_carries_full_attack_with_ceiling_percentage_not_only_scroll_delta(self):
        r=transfer({'incPAD':218}, {'incPAD':321}, .2, 0, 0)
        self.assertEqual(r['incPAD'],604)  # 218 + 321 + ceil(64.2)

    def test_hp_mp_carry_but_defence_and_accuracy_do_not(self):
        r=transfer({'incMHP':50,'incMMP':30,'incPDD':10,'incACC':2},
                   {'incMHP':1630,'incMMP':1500,'incPDD':200,'incACC':30}, .2, 0, 0)
        self.assertEqual((r['incMHP'],r['incMMP'],r['incPDD'],r['incACC']),(1680,1530,10,2))

    def test_counter_bonus_and_short_cap_follow_java_transfer(self):
        r=transfer({'incPAD':10,'incMHP':30000},{'incPAD':100,'incMHP':5000,'incSTR':12},.2,.1,5)
        self.assertEqual(r['incPAD'],135)
        self.assertEqual(r['incSTR'],18) # 12 + ceil(1.2) + 4
        self.assertEqual(r['incMHP'],32767)

    def test_set_tiers_stack_and_all_stat_expands_once(self):
        sets=[{'items':{1,2,3},'tiers':{2:{'incAllStat':4},3:{'incSTR':2,'incPAD':10}}}]
        self.assertEqual(set_bonus([1,2,3],sets)['incSTR'],6)
        self.assertEqual(set_bonus([1,2,3],sets)['incINT'],4)
        self.assertEqual(set_bonus([1],sets)['incPAD'],0)


if __name__=='__main__':unittest.main()
