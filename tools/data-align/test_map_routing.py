#!/usr/bin/env python3
"""map_routing.py's client-crash lists: a '!' line clears an id, the seed is always read, and the committed table
agrees with the seed. The generator's WZ half needs the game data; this file only reads the two checked-in files, so it
runs everywhere (the drift check that the table was regenerated is `map_routing.py --check` with a WZ).

    python3 -m unittest discover -s tools/data-align -p 'test_map_routing.py'
"""
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SEED = os.path.join(HERE, 'client-crash-maps.txt')
TABLE = os.path.join(ROOT, 'src', 'main', 'resources', 'soloMapling', 'map-routing.tsv')

spec = importlib.util.spec_from_file_location('map_routing', os.path.join(HERE, 'map_routing.py'))
map_routing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(map_routing)


def blocked_rows():
    """The table's blocked_maps block: map, kind, reason, fallback of every data line."""
    want = '# blocked_maps:'
    out, inside = [], False
    with open(TABLE, encoding='utf-8') as f:
        for line in f:
            if line.startswith(want):
                inside = True
                continue
            if not inside:
                continue
            line = line.split('#', 1)[0].strip()
            if not line:
                continue
            out.append(line.split('\t'))
    return out


class CrashList(unittest.TestCase):
    def write(self, text):
        f = tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False, encoding='utf-8')
        f.write(text)
        f.close()
        self.addCleanup(os.unlink, f.name)
        return map_routing.Path(f.name)

    def test_a_plain_file_lists_its_ids(self):
        listed, cleared = map_routing.read_list(self.write('863010240\n\n# comment\n211080100  trailing word\n'))
        self.assertEqual([863010240, 211080100], listed)
        self.assertEqual([], cleared)

    def test_an_id_with_a_bang_clears_it(self):
        listed, cleared = map_routing.read_list(self.write('!410000350   # rc6 fixed it\n211080100\n'))
        self.assertEqual([211080100], listed)
        self.assertEqual([410000350], cleared)

    def test_the_seed_clears_the_one_rc6_fixed(self):
        listed, cleared = map_routing.read_list(map_routing.Path(SEED))
        self.assertIn(410000350, cleared, 'client build 10721 fixed Rampart Residences')
        self.assertNotIn(410000350, listed)
        for mapid in (211080100, 211080200, 211080300, 211080400, 211080500, 211080600, 211080700):
            self.assertIn(mapid, listed, 'the Rose Garden family past its entrance')
        for mapid in (402000324, 814040000, 814041900, 863010240):
            self.assertIn(mapid, listed, "the Client lane's audit")

class CommittedTable(unittest.TestCase):
    def setUp(self):
        self.rows = {int(r[0]): r for r in blocked_rows()}

    def test_every_seed_id_is_a_client_crash_row(self):
        listed, cleared = map_routing.read_list(map_routing.Path(SEED))
        for mapid in listed:
            self.assertIn(mapid, self.rows, f'{mapid} is on the crash list but the table does not block it')
            self.assertEqual('client-crash', self.rows[mapid][1], f'{mapid} kind')
            self.assertGreater(int(self.rows[mapid][3]), 0, f'{mapid} fallback')

    def test_a_cleared_id_is_not_blocked(self):
        _, cleared = map_routing.read_list(map_routing.Path(SEED))
        for mapid in cleared:
            self.assertNotIn(mapid, self.rows, f'{mapid} is cleared but the table still blocks it')

    def test_the_rose_garden_family_routes_to_its_entrance(self):
        for mapid in (211080100, 211080200, 211080300, 211080400, 211080500, 211080600, 211080700):
            self.assertEqual(211080000, int(self.rows[mapid][3]), f'Rose Garden {mapid} fallback')
        self.assertNotIn(211061001, self.rows, "Roof of the Fifth Tower loads fine and stays ordinary")

    def test_no_fallback_is_itself_blocked(self):
        for mapid, row in self.rows.items():
            self.assertNotIn(int(row[3]), self.rows, f'the fallback of {mapid} is blocked too')


if __name__ == '__main__':
    unittest.main()
