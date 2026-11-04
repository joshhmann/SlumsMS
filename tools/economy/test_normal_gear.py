"""Policy checks for an offline candidate audit, not gameplay simulation."""
import tempfile
import unittest
from pathlib import Path
from audit_normal_gear import exclusion_reasons, read_rebirths, supports, coverage, forge_outputs


class NormalGearTest(unittest.TestCase):
    def item(self, **changes):
        item = dict(itemid=1402220, name='Sweetwater Two-Handed Sword', slot='Weapon',
                    reqLevel=160, reqJob=1, rebirths=0, forge_output=False, cash=0, quest=0, timeLimited=0)
        return item | changes

    def test_forge_exception_is_not_normal_gear(self):
        self.assertIn('forge_output', exclusion_reasons(self.item(itemid=1402095, forge_output=True)))
        self.assertIn('rebirth_restricted', exclusion_reasons(self.item(rebirths=3)))

    def test_missing_name_and_unsupported_weapon(self):
        self.assertIn('missing_name', exclusion_reasons(self.item(name='')))
        self.assertIn('unsupported_weapon_type', exclusion_reasons(self.item(itemid=1522000)))

    def test_cash_quest_and_timed_are_excluded(self):
        for flag in ['cash', 'quest', 'timeLimited']:
            self.assertIn(flag, exclusion_reasons(self.item(**{flag: 1})))

    def test_job_mask_is_not_inferred_from_name(self):
        self.assertTrue(supports(self.item(itemid=1482201, name='Jaihin Claw', reqJob=16), 16))
        self.assertFalse(supports(self.item(reqJob=2), 1))
        self.assertTrue(supports(self.item(reqJob=0), 2))
        self.assertTrue(supports(self.item(reqJob=3), 2))

    def test_coverage_separates_available_and_new_options(self):
        rows=coverage([self.item()], [160,180,200])
        sword=[r for r in rows if r['family']=='Warrior' and r['slot']=='weapon_140']
        self.assertEqual([r['available_count'] for r in sword], [1,1,1])
        self.assertEqual([r['new_option_count'] for r in sword], [1,0,0])
        self.assertEqual([r['best_item_level'] for r in sword], [160,160,160])

    def test_restricted_or_forged_cannot_satisfy_coverage(self):
        rows=coverage([self.item(rebirths=3),self.item(forge_output=True)], [160])
        self.assertFalse(any(r['available_count'] for r in rows))

    def test_forge_outputs_include_genesis_and_nested_accessories(self):
        import json
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            (root/'scripts/json').mkdir(parents=True)
            payloads={
                'gearSets.json': {'Warrior':[{'name':'Set','recipes':[{'itemId':1000001,'materials':[]}]}]},
                'weapons.json': {'Warrior':[{'name':'Genesis','recipes':[{'itemId':1302355,'materials':[]}]}]},
                'otherAccessories.json': {'Rings':[{'itemId':1110001,'materials':[]}]}}
            for name,payload in payloads.items():
                (root/'scripts/json'/name).write_text(json.dumps(payload))
            self.assertEqual(forge_outputs(root), {1000001,1302355,1110001})

    def test_rebirth_parser_requires_every_stage(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'ItemConstants.java'
            p.write_text('')
            with self.assertRaises(ValueError):
                read_rebirths(p)

    def test_rebirth_parser_ignores_comments_and_rejects_overlap(self):
        def source(one,two,three):
            return '\n'.join(f'public final static Set<Integer> rebirth{i} = new HashSet<>(Arrays.asList({v}));' for i,v in enumerate([one,two,three],1))
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'ItemConstants.java'
            p.write_text(source('1300001, // 1300002\n1300003','1300004','1300005'))
            self.assertEqual(read_rebirths(p),{1300001:1,1300003:1,1300004:2,1300005:3})
            p.write_text(source('1300001','1300001','1300005'))
            with self.assertRaises(ValueError):read_rebirths(p)


if __name__=='__main__':
    unittest.main()
