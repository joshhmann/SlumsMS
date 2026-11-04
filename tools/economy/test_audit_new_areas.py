import tempfile
from pathlib import Path
import unittest

from audit_new_areas import audit, chance, discover_inventory, expected_quantity, load_export, mob_fields


class AuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.wz = Path(self.temp.name)
        (self.wz / 'Mob.wz').mkdir()
        (self.wz / 'String.wz').mkdir()
        (self.wz / 'String.wz/Mob.img.xml').write_text('<imgdir><imgdir name="100">'
            '<string name="name" value="Classic"/></imgdir><imgdir name="200">'
            '<string name="name" value="Import"/></imgdir></imgdir>')
        for mid in (100, 200):
            (self.wz / f'Mob.wz/{mid:07d}.img.xml').write_text('''<imgdir>
                <imgdir name="info"><int name="level" value="120"/><int name="maxHP" value="100000"/>
                <int name="exp" value="1000"/><int name="PADamage" value="925"/></imgdir>
                <imgdir name="attack1"><imgdir name="info"><int name="level" value="4"/>
                <int name="PADamage" value="1500"/><int name="deadlyAttack" value="0"/>
                </imgdir></imgdir></imgdir>''')
        self.inventory = [{'mobid': '200', 'name': 'Import', 'region': 'grandis', 'status': 'none'}]
        self.plan = {'dropperid': '200', 'itemid': '0', 'minimum_quantity': '800',
                     'maximum_quantity': '1200', 'questid': '0', 'chance': '400000', 'category': 'meso'}

    def run_audit(self, plans=None, exported=None):
        return audit(self.wz, self.inventory, {200: (80000, 2000)}, plans or [],
                     3, 10, 4, 3, 141000000, 1500, exported)[0]

    def test_info_and_attack_fields_are_separate(self):
        fields, attacks = mob_fields(self.wz / 'Mob.wz/0000200.img.xml')
        self.assertEqual(fields['level'], '120')
        self.assertEqual(fields['PADamage'], '925')
        self.assertEqual(attacks[0]['level'], '4')
        self.assertEqual(attacks[0]['PADamage'], '1500')
        self.assertEqual(attacks[0]['deadlyAttack'], '0')

    def test_server_overrides_do_not_pretend_to_scale_attacks(self):
        row = self.run_audit()
        self.assertEqual((row['server_hp'], row['server_exp'], row['source_pa']), (80000, 2000, 925))
        self.assertEqual(row['model_kills_per_hour'], 1500)
        self.assertEqual(row['model_exp_per_hour'], 9000000)

    def test_planned_rows_do_not_establish_active_loot(self):
        row = self.run_audit([self.plan])
        self.assertEqual(row['planned_rows'], 1)
        self.assertIsNone(row['active_export_rows'])
        self.assertEqual(row['planned_mesos_per_kill'], 9995)
        row = self.run_audit([self.plan], exported={})
        self.assertEqual(row['active_export_rows'], 0)
        self.assertEqual(row['active_export_meso_rows'], 0)
        row = self.run_audit([self.plan], exported={200: [{'itemid': '2380001', 'questid': '0'}]})
        self.assertEqual(row['active_export_rows'], 1)
        self.assertEqual(row['active_export_noncard_item_rows'], 0)

    def test_chances_include_world_rate_and_saturate(self):
        self.assertEqual(chance(400000, 4), 1)
        self.assertAlmostEqual(chance(60, 4), 240 / 999999)
        self.assertEqual(chance(-5, 4), 0)
        self.assertEqual(expected_quantity(self.plan), 999.5)

    def test_duplicate_planned_pairs_are_rejected(self):
        with self.assertRaises(ValueError):
            self.run_audit([self.plan, self.plan])

    def test_discovery_marks_classic_mobs_reused_on_imported_maps(self):
        directory = self.wz / 'Map.wz/Map/Map0'
        directory.mkdir(parents=True)
        for mid, mobs in ((10, [100]), (20, [100, 200])):
            life = ''.join(f'<imgdir name="{i}"><string name="type" value="m"/>'
                           f'<string name="id" value="{mob}"/></imgdir>' for i, mob in enumerate(mobs))
            (directory / f'{mid:09d}.img.xml').write_text(f'<imgdir><imgdir name="life">{life}</imgdir></imgdir>')
        rows, summary = discover_inventory(self.wz, {10}, self.inventory, 100)
        self.assertEqual(summary['errors'], [])
        self.assertEqual([r['mobid'] for r in rows], ['100', '200'])
        self.assertTrue(rows[0]['also_on_stock_maps'])
        self.assertFalse(rows[1]['also_on_stock_maps'])
        self.assertEqual(rows[1]['map_ids'], [20])
        rows, summary = discover_inventory(self.wz, {10}, self.inventory, 130)
        self.assertEqual(rows, [])

    def test_export_requires_drop_data_columns(self):
        export = self.wz / 'drops.tsv'
        export.write_text('dropperid\titemid\tminimum_quantity\tmaximum_quantity\tquestid\tchance\n'
                          '200\t0\t800\t1200\t0\t400000\n')
        self.assertEqual(len(load_export(export)[200]), 1)
        export.write_text('item\tcount\n123\t1\n')
        with self.assertRaises(ValueError):
            load_export(export)

    def test_missing_mob_does_not_hide_other_mobs_on_same_map(self):
        directory = self.wz / 'Map.wz/Map/Map0'
        directory.mkdir(parents=True)
        (directory / '000000020.img.xml').write_text('''<imgdir><imgdir name="life">
            <imgdir><string name="type" value="m"/><string name="id" value="999"/></imgdir>
            <imgdir><string name="type" value="m"/><string name="id" value="200"/></imgdir>
            </imgdir></imgdir>''')
        rows, summary = discover_inventory(self.wz, {10}, self.inventory, 100)
        self.assertEqual([r['mobid'] for r in rows], ['200'])
        self.assertEqual(len(summary['errors']), 1)
        self.assertIn('0000999.img.xml', summary['errors'][0])


if __name__ == '__main__':
    unittest.main()
