import csv,tempfile,unittest
from pathlib import Path
from unittest.mock import patch,Mock
from gen_royal_materials import build,PAIRS

class RoyalMaterialSourcesTest(unittest.TestCase):
    def run_fixture(self,kind='m',exp=10):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'docs/economy/bb-drops').mkdir(parents=True)
            (root/'wz/Mob.wz').mkdir(parents=True);(root/'wz/Map.wz/Map/Map2').mkdir(parents=True)
            with (root/'docs/economy/bb-drops/mobs.csv').open('w') as h:
                wr=csv.writer(h);wr.writerow(['mobid','first_map','boss','exp'])
                for n,(mob,item) in enumerate(PAIRS):
                    mid=211060100+n;wr.writerow([mob,mid,0,10])
                    (root/'wz/Mob.wz'/f'{mob:07d}.img.xml').write_text(f'<imgdir><imgdir name="info"><int name="exp" value="{exp}"/></imgdir></imgdir>')
                    (root/'wz/Map.wz/Map/Map2'/f'{mid:09d}.img.xml').write_text(f'<imgdir><imgdir name="life"><imgdir name="0"><string name="type" value="{kind}"/><string name="id" value="{mob}"/></imgdir></imgdir></imgdir>')
            w=Mock();w.droppable.return_value=None
            with patch('gen_royal_materials.WzItems',return_value=w):return build(root/'wz',root)
    def test_five_explicit_material_sources(self):
        rows=self.run_fixture();self.assertEqual(len(rows),5)
        self.assertTrue(all(r[2:]==(1,1,0,400000) for r in rows))
    def test_npc_placement_cannot_satisfy_a_monster_source(self):
        with self.assertRaises(ValueError):self.run_fixture(kind='n')
    def test_raw_zero_exp_source_is_rejected(self):
        with self.assertRaises(ValueError):self.run_fixture(exp=0)
