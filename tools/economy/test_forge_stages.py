import json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

class ForgeStageDataTest(unittest.TestCase):
    def test_all_class_sets_follow_the_budget_and_keep_matching_previous_piece(self):
        policy=json.loads((ROOT/'docs/economy/forge-stages.json').read_text())
        armour=json.loads((ROOT/'scripts/json/gearSets.json').read_text())
        weapons=json.loads((ROOT/'scripts/json/weapons.json').read_text())
        for file,byfamily in [('armour',armour),('weapon',weapons)]:
            for family,groups in byfamily.items():
                previous={}
                for g in groups:
                    entry=next((p for name,p in policy['stages'].items() if g['name']==(p['armour_group'] if file=='armour' else name)),None)
                    if entry is None:continue
                    for r in g['recipes']:
                        prefix=r['itemId']//10000
                        with self.subTest(item=r['itemId']):
                            self.assertEqual(r['cost'],entry['fee_per_piece'])
                            self.assertEqual(r['requiredLevel'],entry['craft_level'])
                            inputs=[m for m in r['materials'] if m.get('processStarforce')]
                            self.assertEqual(len(inputs),1)
                            if previous:self.assertEqual(inputs[0]['itemId'],previous[prefix])
                            allowed=set(entry['regional_items'])|{inputs[0]['itemId']}
                            if file=='weapon':allowed|={entry['weapon_trophy']}|set(range(4036544,4036549))
                            self.assertTrue({m['itemId'] for m in r['materials']}<=allowed)
                            self.assertEqual(sum(m['amount'] for m in r['materials'] if m['itemId']==entry['weapon_trophy']),1 if file=='weapon' else 0)
                    previous={r['itemId']//10000:r['itemId'] for r in g['recipes']}
    def test_genesis_fragment_has_a_four_token_recipe(self):
        recipes=json.loads((ROOT/'scripts/json/refiningMaterials.json').read_text())
        found=[r for r in recipes if r['itemId']==4036539];self.assertEqual(len(found),1)
        self.assertEqual({m['itemId']:m['amount'] for m in found[0]['materials']},{i:1 for i in range(4036535,4036539)})
        self.assertEqual(found[0]['cost'],1000000)
        self.assertEqual(found[0]['requiredLevel'],225)
    def test_endgame_is_a_single_previous_weapon_plus_boss_materials(self):
        policy=json.loads((ROOT/'docs/economy/forge-stages.json').read_text())
        for groups in json.loads((ROOT/'scripts/json/weapons.json').read_text()).values():
            previous={}
            for g in groups:
                if g['name'] in policy['endgame']:
                    entry=policy['endgame'][g['name']]
                    for r in g['recipes']:
                        self.assertEqual(r['cost'],entry['fee'])
                        inputs=[m for m in r['materials'] if m.get('processStarforce')]
                        self.assertEqual([(m['itemId'],m['amount']) for m in inputs],[(previous[r['itemId']//10000],1)])
                        extras={m['itemId']:m['amount'] for m in r['materials'] if not m.get('processStarforce')}
                        for i,n in entry['materials'].items():self.assertEqual(extras.pop(int(i),None),n)
                        self.assertEqual(len(extras),1);core,count=next(iter(extras.items()));self.assertIn(core,range(4036544,4036549));self.assertEqual(count,1)
                previous={r['itemId']//10000:r['itemId'] for r in g['recipes']}
