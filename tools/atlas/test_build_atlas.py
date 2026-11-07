import os
import tempfile
import unittest

import build_atlas as B

NOTE = B.UPGRADE_NOTES[0]


def item(iid, reach="unobtainable", note=NOTE):
    return {"id": iid, "reach": reach, "reach_note": note, "scripts": []}


class CashIds(unittest.TestCase):
    def test_reads_first_column_and_skips_comments(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "catalog.tsv")
            with open(p, "w") as f:
                f.write("# header\n1002711\t3100\tKitty\n\n  x\tbad\n5000000\t1\n")
            self.assertEqual(B.cash_ids(p), {1002711, 5000000})
        self.assertEqual(B.cash_ids("/nonexistent"), set())


class ItemReach(unittest.TestCase):
    scripts = {"quest/1021": {"mentions": [{"k": "item", "id": 2010007, "line": 3}, {"k": "mob", "id": 5, "line": 4}]}}
    quests = {
        "1": {"reach": "player", "rewards": {"items": [{"ref": {"k": "item", "id": 10}, "count": 1}]}},
        "2": {"reach": "unobtainable", "rewards": {"items": [{"ref": {"k": "item", "id": 11}, "count": 1}]}},
        "3": {"reach": "player", "rewards": {"items": [{"ref": {"k": "item", "id": 12}, "count": 0}]}},
    }

    def test_sources(self):
        s = B.item_sources(self.scripts, self.quests, {99})
        self.assertEqual(s, {2010007: ["script quest/1021"], 10: ["quest 1 reward"], 99: ["cash shop catalogue"]})

    def test_script_item_flips_and_links_the_script(self):
        items = {"2010007": item(2010007), "7": item(7)}
        up = B.fix_item_reach(items, B.item_sources(self.scripts, self.quests, set()))
        self.assertEqual(up, [2010007])
        self.assertEqual(items["2010007"]["reach"], "player")
        self.assertIn("quest/1021", items["2010007"]["reach_note"])
        self.assertEqual(items["2010007"]["scripts"], [{"k": "script", "id": "quest/1021"}])
        self.assertEqual(items["7"]["reach"], "unobtainable")

    def test_rejected_names_stay_unobtainable(self):
        items = {"5": item(5, note="name is a brand")}
        self.assertEqual(B.fix_item_reach(items, {5: ["cash shop catalogue"]}), [])
        self.assertEqual(items["5"]["reach"], "unobtainable")

    def test_gm_item_is_not_overwritten(self):
        items = {"5": item(5, reach="gm", note=None)}
        self.assertEqual(B.fix_item_reach(items, {5: ["cash shop catalogue"]}), [])
        self.assertEqual(items["5"]["reach"], "gm")


class Dangling(unittest.TestCase):
    def test_unresolved_refs_by_kind(self):
        built = {k: {} for k in ("quests", "npcs", "items", "mobs", "maps", "scripts")}
        built["mobs"] = {"1": {"id": 1}}
        built["maps"] = {"9": {"mobs": [{"ref": {"k": "mob", "id": 1}}, {"ref": {"k": "mob", "id": 2}}],
                              "portals": [{"target_map": {"k": "map", "id": 9}}, {"target_map": {"k": "map", "id": 8}}]}}
        self.assertEqual(B.dangling(built), {"map": [8], "mob": [2]})


class ScriptNames(unittest.TestCase):
    def test_name_and_reach_follow_the_entity(self):
        scripts = {"npc/2100": {}, "quest/5": {}, "event/Zakum": {}}
        built = {"npcs": {"2100": {"reach": "gm"}}, "quests": {}}
        B.name_scripts(scripts, built)
        self.assertEqual((scripts["npc/2100"]["name"], scripts["npc/2100"]["reach"]), ("npc/2100", "gm"))
        self.assertEqual(scripts["quest/5"]["reach"], "player")
        self.assertEqual(scripts["event/Zakum"]["reach"], "player")


if __name__ == "__main__":
    unittest.main()
