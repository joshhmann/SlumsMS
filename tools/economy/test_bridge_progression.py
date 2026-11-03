import tempfile
import unittest
from pathlib import Path

from audit_bridge_progression import classify_rows, read_export, source_facts, validate_coverage


class BridgeProgressionTest(unittest.TestCase):
    def test_quest_only_supply_cannot_count_as_normal_gear(self):
        self.assertEqual(classify_rows([{'questid': 123, 'chance': 999999}], 2100), 'quest_only')

    def test_existing_rare_pair_requires_floor_instead_of_insert_only(self):
        self.assertEqual(classify_rows([{'questid': 0, 'chance': 60}], 2100), 'raise_floor')
        self.assertEqual(classify_rows([{'questid': 0, 'chance': 0}], 2100), 'raise_floor')

    def test_stronger_existing_supply_must_not_be_lowered(self):
        self.assertEqual(classify_rows([{'questid': 0, 'chance': 7000}], 2100), 'preserve')
        self.assertEqual(classify_rows([{'questid': 1, 'chance': 999999},
                                        {'questid': 0, 'chance': 60}], 2100), 'raise_floor')

    def test_missing_is_distinct_from_quest_only(self):
        self.assertEqual(classify_rows([], 2100), 'insert')

    def test_export_rejects_extra_columns_and_impossible_quantities(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'drops.tsv'
            path.write_text('dropperid\titemid\towner\n1\t2\tprivate\n')
            with self.assertRaisesRegex(ValueError, 'six permitted'):
                read_export(path)
            path.write_text('dropperid\titemid\tminimum_quantity\tmaximum_quantity\tquestid\tchance\n'
                            '1\t2\t3\t1\t0\t60\n')
            with self.assertRaisesRegex(ValueError, 'Invalid exported'):
                read_export(path)

    def test_missing_weapon_type_is_not_complete_class_coverage(self):
        with self.assertRaisesRegex(ValueError, 'weapon'):
            validate_coverage([], [], False)

    def test_complete_armour_does_not_mean_one_family_only(self):
        weapons = [{'itemid': p * 10000} for p in
                   [130,131,132,133,137,138,140,141,142,143,144,145,146,147,148,149]]
        armour = [{'slot': s, 'reqJob': 1} for s in ['Cap','Longcoat','Glove','Shoes']]
        with self.assertRaisesRegex(ValueError, 'armour'):
            validate_coverage(weapons, armour, False)

    def test_source_requires_actual_placement_and_appropriate_level(self):
        with tempfile.TemporaryDirectory() as tmp:
            wz = Path(tmp)
            (wz / 'Mob.wz').mkdir()
            (wz / 'Map.wz/Map/Map4').mkdir(parents=True)
            mob = wz / 'Mob.wz/2400000.img.xml'
            mapfile = wz / 'Map.wz/Map/Map4/400010100.img.xml'
            mob.write_text('<imgdir><imgdir name="info"><int name="level" value="165"/>'
                           '<int name="exp" value="100"/></imgdir></imgdir>')
            mapfile.write_text('<imgdir><imgdir name="life"><imgdir><string name="id" '
                               'value="2400000"/><string name="type" value="m"/></imgdir></imgdir></imgdir>')
            with self.assertRaisesRegex(ValueError, 'level'):
                source_facts(wz, 2400000, 400010100, 124)
            mob.write_text('<imgdir><imgdir name="info"><int name="level" value="113"/>'
                           '<int name="exp" value="100"/></imgdir></imgdir>')
            self.assertEqual(source_facts(wz, 2400000, 400010100, 124)['level'], 113)
            with self.assertRaisesRegex(ValueError, 'placement'):
                source_facts(wz, 2400001, 400010100, 124)


if __name__ == '__main__':
    unittest.main()
