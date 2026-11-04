import json
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
SW={130:1302297,131:1312173,132:1322223,133:1332247,137:1372195,138:1382231,
    140:1402220,141:1412152,142:1422158,143:1432187,144:1442242,145:1452226,
    146:1462213,147:1472235,148:1482189,149:1492199,
    100:1003976,105:1052669,107:1072870,108:1082556,110:1102623}

def royal():
    result=[]
    for filename in ['gearSets','weapons']:
        data=json.loads((ROOT/'scripts/json'/f'{filename}.json').read_text())
        for family,groups in data.items():
            for g in groups:
                if g['name'].startswith('Royal Von Leon'):
                    result.extend((family,r) for r in g['recipes'])
    return result

class RoyalProgressionTest(unittest.TestCase):
    def test_matching_normal_piece_is_the_consumed_upgrade_input(self):
        rows=royal();self.assertEqual(len(rows),41)
        for family,r in rows:
            with self.subTest(item=r['itemId']):
                inputs=[m for m in r['materials'] if m.get('processStarforce')]
                self.assertEqual([(m['itemId'],m['amount']) for m in inputs],[(SW[r['itemId']//10000],1)])
    def test_fee_is_consistent_and_full_set_plus_weapon_is_30m(self):
        for family,r in royal():self.assertEqual(r['cost'],5000000,r['itemId'])
    def test_regional_materials_and_one_weapon_trophy_replace_early_core_grind(self):
        for family,r in royal():
            weapon=r['itemId']//10000>=130
            normal=SW[r['itemId']//10000]
            expected={normal,4000625,4000626,4000627,4000628,4000629}|({4001693} if weapon else set())
            self.assertTrue({m['itemId'] for m in r['materials']}<=expected,r['itemId'])
            self.assertNotIn(4011007,{m['itemId'] for m in r['materials']})
            self.assertNotIn(4021009,{m['itemId'] for m in r['materials']})
            self.assertEqual(sum(m['amount'] for m in r['materials'] if m['itemId']==4001693),1 if weapon else 0)
            self.assertTrue(all(m['amount']==(200 if weapon else 100) for m in r['materials'] if m['itemId'] in [4000625,4000626,4000627,4000628,4000629]))

if __name__=='__main__':unittest.main()
