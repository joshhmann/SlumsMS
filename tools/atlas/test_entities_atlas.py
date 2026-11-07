"""Tests for entities_atlas.py.

Small fixtures (a tiny extractor dict, a few WZ XML files, TSV table exports and script stubs) exercise every
builder; the last class runs the real extract and skips unless SLUMS_ATLAS_DATA points at it.
"""
import json
import os
import tempfile
import unittest

import entities_atlas as E

HERE = os.path.dirname(os.path.abspath(__file__))


def put(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


HS = {
    "maps": {
        "1": {"id": 1, "name": "Test Field", "street": "Test Street",
              "mobs": [{"mob_id": 100, "count": 3}],
              "npcs": [{"npc_id": 2000, "count": 1}, {"npc_id": 2002, "count": 1}],
              "portals": [{"name": "sp", "type": 0, "target_map_id": 999999999, "target_portal": ""},
                          {"name": "front00", "type": 2, "target_map_id": 2, "target_portal": "next00"}]},
        "2": {"id": 2, "name": "Empty Field", "street": "Test Street", "mobs": [], "npcs": []},
        "180000000": {"id": 180000000, "name": "GM Room", "street": "Test Street",
                      "mobs": [], "npcs": [{"npc_id": 2001, "count": 1}]},
        "109000001": {"id": 109000001, "name": "Event Field", "street": "Test Street",
                      "mobs": [{"mob_id": 101, "count": 1}], "npcs": []},
        "910000000": {"id": 910000000, "name": "Free Market Entrance", "street": "Hidden Street",
                      "mobs": [], "npcs": [{"npc_id": 2000, "count": 1}]},
    },
    "mobs": {
        "100": {"id": 100, "name": "Snail", "level": 1, "hp": 8, "mp": 0, "exp": 3, "boss": False,
                "maps": [{"map_id": 1, "count": 3}],
                "drops": [{"item_id": 2000000, "chance": 100, "min_qty": 1, "max_qty": 1, "quest_id": 0}]},
        "101": {"id": 101, "name": "Event Mob", "level": 5, "hp": 50, "mp": 0, "exp": 10, "boss": False,
                "maps": [{"map_id": 109000001, "count": 1}], "drops": []},
        "102": {"id": 102, "name": "Big Boss", "level": 30, "hp": 900, "mp": 0, "exp": 500, "boss": True,
                "maps": [], "drops": []},
        "103": {"id": 103, "name": "Ghost", "level": 2, "hp": 1, "mp": 0, "exp": 1, "boss": False,
                "maps": [], "drops": []},
    },
    "npcs": {
        "2000": {"id": 2000, "name": "Roger", "maps": [{"map_id": 1}],
                 "shop_items": [{"shop_id": 1, "item_id": 2000000, "price": 25}],
                 "quests_start": [{"quest_id": 1021}], "quests_end": [{"quest_id": 1022}]},
        "2001": {"id": 2001, "name": "GM Helper", "maps": [{"map_id": 180000000}],
                 "shop_items": [], "quests_start": [], "quests_end": []},
        "2002": {"id": 2002, "name": "초보", "maps": [{"map_id": 1}],
                 "shop_items": [], "quests_start": [], "quests_end": []},
    },
    "items": {
        "2000000": {"id": 2000000, "name": "Orange Potion", "desc": "Heals ##clet50# HP.\nSold cheap.",
                    "type": "Consume", "req_level": 0, "req_job": 0, "stats": {"price": 25, "hp": 50},
                    "drops": [{"mob_id": 100, "chance": 100, "min_qty": 1, "max_qty": 1, "quest_id": 0}],
                    "shops": [{"shop_id": 1, "npc_id": 2000, "price": 25, "position": 0}],
                    "quests_reward": [{"quest_id": 1021, "count": 1, "prop": 1}],
                    "quests_require": [{"quest_id": 1022}], "name_qualifier": "the potion seller's"},
        "1002000": {"id": 1002000, "name": "Blue Cap", "desc": "", "type": "Cap", "req_level": 10, "req_job": 0,
                    "stats": {"incSTR": 1}, "drops": [], "shops": [], "quests_reward": [], "quests_require": []},
        "2002012": {"id": 2002012, "name": "Elixir", "desc": "", "type": "Unknown", "req_level": 0, "req_job": 0,
                    "stats": {}, "drops": [], "shops": [], "quests_reward": [], "quests_require": []},
        "4000001": {"id": 4000001, "name": "Old Paper", "desc": "", "type": "Etc", "req_level": 0, "req_job": 0,
                    "stats": {}, "drops": [], "shops": [], "quests_reward": [],
                    "quests_require": [{"quest_id": 1021}]},
    },
}


def make_fixture(root):
    """The WZ XML, TSV and script stubs the HS fixture points at; returns (wz, db, repo)."""
    wz, db, repo = (os.path.join(root, d) for d in ("wz", "db", "repo"))

    put(wz, "Mob.wz/0000100.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="0100100.img">\n'
        '  <imgdir name="info">\n    <int name="level" value="1"/>\n  </imgdir>\n</imgdir>\n')
    put(wz, "Npc.wz/0002000.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="02000.img">\n'
        '  <imgdir name="info">\n    <int name="speed" value="0"/>\n  </imgdir>\n</imgdir>\n')
    put(wz, "Map.wz/Map/Map0/000000001.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="000000001.img">\n'
        '  <imgdir name="info">\n    <string name="onUserEnter" value="testEnter"/>\n  </imgdir>\n</imgdir>\n')
    # Consume item: the imgdir's own line (3) is the citation, not line 1 of the file.
    put(wz, "Item.wz/Consume/0200.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="0200.img">\n'
        '  <imgdir name="02000000">\n'
        '    <imgdir name="info">\n'
        '      <string name="script" value="testitem"/>\n'
        '      <int name="price" value="25"/>\n'
        '    </imgdir>\n'
        '  </imgdir>\n</imgdir>\n')
    put(wz, "Character.wz/Cap/01002000.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="01002000.img">\n  <imgdir name="info">\n    <int name="reqLevel" value="10"/>\n'
        '  </imgdir>\n</imgdir>\n')
    put(wz, "String.wz/Npc.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="Npc.img">\n'
        '  <imgdir name="2000">\n    <string name="name" value="Roger"/>\n  </imgdir>\n</imgdir>\n')
    put(wz, "String.wz/Mob.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="Mob.img">\n'
        '  <imgdir name="0100">\n    <string name="name" value="Snail"/>\n  </imgdir>\n</imgdir>\n')
    # String.wz/Map.img.xml nests ids under category dirs; the line cited is the id's own imgdir.
    put(wz, "String.wz/Map.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="Map.img">\n'
        '  <imgdir name="maple">\n'
        '    <imgdir name="1">\n'
        '      <string name="mapName" value="Test Field"/>\n'
        '      <string name="streetName" value="Test Street"/>\n'
        '    </imgdir>\n'
        '  </imgdir>\n</imgdir>\n')
    put(wz, "String.wz/Consume.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="Consume.img">\n'
        '  <imgdir name="2000000">\n    <string name="name" value="Orange Potion"/>\n  </imgdir>\n'
        '  <imgdir name="02002012">\n    <string name="name" value="Elixir"/>\n  </imgdir>\n</imgdir>\n')
    put(wz, "String.wz/Eqp.img.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<imgdir name="Eqp.img">\n'
        '  <imgdir name="Eqp">\n'
        '    <imgdir name="Cap">\n'
        '      <imgdir name="1002000">\n        <string name="name" value="Blue Cap"/>\n      </imgdir>\n'
        '    </imgdir>\n  </imgdir>\n</imgdir>\n')

    put(db, "drop_data.tsv",
        "id\tdropperid\titemid\tminimum_quantity\tmaximum_quantity\tquestid\tchance\n"
        "1\t100\t2000000\t1\t1\t0\t100\n")
    put(db, "reactordrops.tsv",
        "reactordropid\treactorid\titemid\tchance\tquestid\n"
        "1\t2001\t2000000\t75\t0\n")
    put(db, "shopitems.tsv",
        "shopitemid\tshopid\titemid\tprice\tpitch\tposition\n"
        "1\t1\t2000000\t25\t0\t0\n")
    put(db, "plife.tsv",
        "id\tworld\tmap\tlife\ttype\tcy\tf\tfh\trx0\trx1\tx\ty\thide\tmobtime\tteam\n"
        "1\t1\t1\t2000\tn\t0\t0\t0\t0\t0\t10\t20\t0\t0\t0\n"
        "2\t1\t1\t100\tm\t0\t0\t0\t0\t0\t30\t40\t0\t5\t0\n"
        "3\t2\t1\t2000\tn\t0\t0\t0\t0\t0\t1\t2\t0\t0\t0\n")

    for rel in ("scripts/npc/2000.js", "scripts/item/testitem.js",
                "scripts/map/onUserEnter/testEnter.js", "scripts/portal/front00.js"):
        put(repo, rel, "// test stub\n")
    return wz, db, repo


class FixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.wz, cls.db, cls.repo = make_fixture(cls.tmp.name)
        cls.npcs = E.build_npcs(HS, cls.wz, cls.db, cls.repo)
        cls.items = E.build_items(HS, cls.wz, cls.db, cls.repo)
        cls.mobs = E.build_mobs(HS, cls.wz, cls.db, cls.repo)
        cls.maps = E.build_maps(HS, cls.wz, cls.db, cls.repo)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_keys_are_strings_and_ids_numbers(self):
        for rows in (self.npcs, self.items, self.mobs, self.maps):
            for k, row in rows.items():
                self.assertIsInstance(k, str)
                self.assertEqual(int(k), row["id"])

    def test_every_row_has_src(self):
        for rows in (self.npcs, self.items, self.mobs, self.maps):
            for row in rows.values():
                self.assertTrue(row["src"], row["id"])
                for s in row["src"]:
                    self.assertIn(s["kind"], ("wz", "db-export", "script", "doc", "java", "yaml"))

    def test_npcs(self):
        r = self.npcs["2000"]
        self.assertEqual(r["name"], "Roger")
        self.assertEqual(r["reach"], "player")
        self.assertEqual(r["maps"], [{"ref": {"k": "map", "id": 1}, "x": 10, "y": 20}])
        self.assertEqual(r["shop"], [{"ref": {"k": "item", "id": 2000000}, "price": 25}])
        self.assertEqual(r["quests_start"], [{"k": "quest", "id": 1021}])
        self.assertEqual(r["quests_end"], [{"k": "quest", "id": 1022}])
        self.assertEqual(r["script"], {"k": "script", "id": "npc/2000"})
        paths = [s.get("path") for s in r["src"]]
        self.assertEqual(paths[0], "Npc.wz/0002000.img.xml")
        self.assertEqual(r["src"][0]["line"], 1)
        self.assertIn("String.wz/Npc.img.xml", paths)
        tables = [s["table"] for s in r["src"] if s["kind"] == "db-export"]
        self.assertEqual(sorted(tables), ["plife", "shopitems"])
        for s in r["src"]:
            if s["kind"] == "db-export":
                self.assertEqual((s["snapshot"], s["date"]), ("test-1.6.5", "2026-11-07"))

    def test_npc_reach(self):
        self.assertEqual(self.npcs["2001"]["reach"], "gm")
        self.assertEqual(self.npcs["2002"]["reach"], "unobtainable")
        self.assertEqual(self.npcs["2002"]["lang"], "ko")
        self.assertIn("Korean", self.npcs["2002"]["reach_note"])

    def test_items(self):
        r = self.items["2000000"]
        self.assertEqual(r["name"], "Orange Potion")
        self.assertEqual(r["desc"], "Heals let50 HP. Sold cheap.")
        self.assertEqual(r["price"], 25)
        self.assertNotIn("price", r["stats"])
        self.assertEqual(r["stats"], {"hp": 50})
        self.assertEqual(r["drops"], [{"ref": {"k": "mob", "id": 100}, "chance": 100, "min": 1, "max": 1}])
        self.assertEqual(r["shops"], [{"ref": {"k": "npc", "id": 2000}, "price": 25}])
        self.assertEqual(r["quest_reward"], [{"k": "quest", "id": 1021}])
        self.assertEqual(r["quest_require"], [{"k": "quest", "id": 1022}])
        self.assertEqual(r["reactor_drops"], [{"reactor": 2001, "chance": 75, "quest": None}])
        self.assertEqual(r["scripts"], [{"k": "script", "id": "item/testitem"}])
        self.assertEqual(r["reach"], "player")
        self.assertEqual(r["name_note"], "the potion seller's")
        self.assertEqual(r["src"][0], {"kind": "wz", "path": "Item.wz/Consume/0200.img.xml", "line": 3})
        self.assertIn({"kind": "wz", "path": "String.wz/Consume.img.xml", "line": 3}, r["src"])
        tables = sorted(s["table"] for s in r["src"] if s["kind"] == "db-export")
        self.assertEqual(tables, ["drop_data", "reactordrops", "shopitems"])

    def test_equip_item_src_is_character_wz(self):
        r = self.items["1002000"]
        self.assertEqual(r["src"][0], {"kind": "wz", "path": "Character.wz/Cap/01002000.img.xml", "line": 1})
        self.assertIn("String.wz/Eqp.img.xml", [s.get("path") for s in r["src"]])
        # no drop, shop, reward or reactor: the site cannot hand it out
        self.assertEqual(r["reach"], "unobtainable")
        self.assertIn("reach_note", r)

    def test_undefined_item_falls_back_to_its_string_file(self):
        r = self.items["2002012"]
        self.assertEqual(r["src"], [{"kind": "wz", "path": "String.wz/Consume.img.xml", "line": 6}])

    def test_mobs(self):
        r = self.mobs["100"]
        self.assertEqual(r["name"], "Snail")
        self.assertEqual((r["level"], r["hp"], r["exp"]), (1, 8, 3))
        self.assertFalse(r["boss"])
        self.assertEqual(r["maps"], [{"ref": {"k": "map", "id": 1}, "count": 3}])
        self.assertEqual(r["drops"], [{"ref": {"k": "item", "id": 2000000}, "chance": 100, "min": 1, "max": 1}])
        self.assertEqual(r["reach"], "player")
        self.assertEqual(r["src"][0], {"kind": "wz", "path": "Mob.wz/0000100.img.xml", "line": 1})
        self.assertIn({"kind": "db-export", "snapshot": "test-1.6.5", "date": "2026-11-07",
                       "table": "drop_data", "key": 100}, r["src"])

    def test_the_best_placement_wins(self):
        reach = {1: ("player", None), 2: ("gm", "x"), 3: ("event", "y"), 4: ("unobtainable", "z")}
        self.assertEqual(E._placement_reach(reach, [4, 3, 2, 1])[0], "player")
        self.assertEqual(E._placement_reach(reach, [3, 2])[0], "gm")
        self.assertEqual(E._placement_reach(reach, [4, 3])[0], "event")
        self.assertEqual(E._placement_reach(reach, [4]), (None, None))

    def test_an_item_with_one_reachable_source_is_reachable(self):
        mob_reach = {1: ("player", None), 2: ("unobtainable", "x")}
        npc_reach = {5: ("event", "y")}
        items = {"10": {"name": "A", "drops": [{"mob_id": 2}, {"mob_id": 1}], "shops": []},
                 "11": {"name": "B", "drops": [{"mob_id": 2}], "shops": [{"npc_id": 5}]},
                 "12": {"name": "C", "drops": [{"mob_id": 2}], "shops": []}}
        got = E._item_reaches(items, mob_reach, npc_reach, {})
        self.assertEqual((got[10][0], got[11][0], got[12][0]), ("player", "event", "unobtainable"))

    def test_mob_reach(self):
        self.assertEqual(self.mobs["101"]["reach"], "event")
        self.assertEqual(self.mobs["102"]["reach"], "player")
        self.assertIn("boss", self.mobs["102"]["reach_note"])
        self.assertEqual(self.mobs["103"]["reach"], "unobtainable")

    def test_maps(self):
        r = self.maps["1"]
        self.assertEqual(r["name"], "Test Field")
        self.assertEqual(r["street"], "Test Street")
        self.assertEqual(r["mobs"], [{"ref": {"k": "mob", "id": 100}, "count": 3}])
        self.assertEqual([n["ref"]["id"] for n in r["npcs"]], [2000, 2002])
        self.assertEqual(r["on_user_enter"], {"k": "script", "id": "map/onUserEnter/testEnter"})
        portals = {p["name"]: p for p in r["portals"]}
        self.assertIsNone(portals["sp"]["target_map"])
        self.assertEqual(portals["front00"]["target_map"], {"k": "map", "id": 2})
        self.assertEqual(portals["front00"]["target_portal"], "next00")
        self.assertEqual(portals["front00"]["script"], {"k": "script", "id": "portal/front00"})
        self.assertEqual(r["plife"], [{"ref": {"k": "mob", "id": 100}, "x": 30, "y": 40, "mobtime": 5}])
        self.assertEqual(r["src"][0], {"kind": "wz", "path": "Map.wz/Map/Map0/000000001.img.xml", "line": 1})

    def test_map_reach_and_street_override(self):
        self.assertEqual(self.maps["2"]["reach"], "unobtainable")
        self.assertIn("no mobs or NPCs", self.maps["2"]["reach_note"])
        self.assertEqual(self.maps["180000000"]["reach"], "gm")
        self.assertEqual(self.maps["109000001"]["reach"], "event")
        self.assertEqual(self.maps["910000000"]["street"], "Free Market")

    def test_main_writes_four_sorted_files(self):
        out = os.path.join(self.tmp.name, "out")
        hs_dir = os.path.join(self.tmp.name, "hs")
        for kind, rows in HS.items():
            put(hs_dir, kind + ".json", json.dumps(rows))
        E.main(["--hs", hs_dir, "--wz", self.wz, "--db", self.db, "--out", out])
        for kind in ("npcs", "items", "mobs", "maps"):
            with open(os.path.join(out, kind + ".json"), encoding="utf-8") as f:
                text = f.read()
            rows = json.loads(text)
            self.assertTrue(rows)
            self.assertEqual(list(rows), sorted(rows), kind)
            self.assertIn('\n "', text)


def iter_refs(node):
    if isinstance(node, dict):
        if len(node) <= 3 and node.get("k") in ("item", "mob", "map", "npc") and "id" in node:
            yield node
            return
        for v in node.values():
            yield from iter_refs(v)
    elif isinstance(node, list):
        for v in node:
            yield from iter_refs(v)


@unittest.skipUnless(os.environ.get("SLUMS_ATLAS_DATA"), "set SLUMS_ATLAS_DATA=/root/work/atlas-data to run")
class RealDataTest(unittest.TestCase):
    """Plausible counts and no dangling entity refs on the real test-1.6.5 extract."""

    @classmethod
    def setUpClass(cls):
        data = os.environ["SLUMS_ATLAS_DATA"]
        hs = E.load_hs(os.path.join(data, "hs-out"))
        cls.rows = E.build_all(hs, os.path.join(data, "wz"), os.path.join(data, "db"))

    def test_counts(self):
        n = {k: len(v) for k, v in self.rows.items()}
        print("counts:", n)
        self.assertGreater(n["items"], 50000)
        self.assertGreater(n["mobs"], 6000)
        self.assertGreater(n["maps"], 11000)
        self.assertGreater(n["npcs"], 8000)

    def test_no_dangling_refs(self):
        ids = {kind: {row["id"] for row in rows.values()} for kind, rows in self.rows.items()}
        dangling = {}
        for kind, rows in self.rows.items():
            for row in rows.values():
                for r in iter_refs(row):
                    target = r["k"]
                    if target in ids and r["id"] not in ids[target] and r["id"] not in E.DANGLING_ALLOW[target]:
                        dangling.setdefault((kind, target), set()).add(r["id"])
        print("dangling:", {f"{a}->{b}": len(v) for (a, b), v in dangling.items()})
        self.assertEqual(dangling, {})

    def test_rows_are_citable(self):
        for kind, rows in self.rows.items():
            for row in rows.values():
                self.assertTrue(row["src"], (kind, row["id"]))
                self.assertIn(row["reach"], E.RANK)


if __name__ == "__main__":
    unittest.main()
