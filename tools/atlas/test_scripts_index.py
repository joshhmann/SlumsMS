"""Tests for the script index: python3 -m unittest discover -s tools/atlas -p 'test_*.py'"""
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scripts_index as si  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

NPC = """/*
    This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
    This program is free software: you can redistribute it and/or modify
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
/* Author: Someone
 * Edited by Somebody

    NPC Name: \t\tRoger
    Map(s): \t\tMaple Road : Lower level of the Training Camp (2)
    Description: \t\tQuest - Roger's Apple
*/
var status = -1;

function start() {
    // cm.gainItem(1111111, 1);
    /* cm.warp(222222222, 0); */
    cm.sendNext("say gainItem(3333333, 1)");
    if (!cm.haveItem(4000000)) {
        cm.gainItem(2010007,
                    1);
    }
    cm.warp(100000000 + (1 * 2), 0);
    cm.forceCompleteQuest(1021);
    cm.startQuest(qid);
    cm.openShop(9001000);
}

function gainItem(id, count) { }

var cfg = {gainItem: 7};
"""

ALL_CALLS = """function action() {
    cm.gainItem(2010007, 1);
    cm.haveItem(4000000);
    cm.removeAll(2000000);
    cm.canHold(1302000, 1);
    rm.dropItems(true, 2, 8, 15);
    cm.forceStartQuest(1001);
    cm.forceCompleteQuest(1021);
    cm.startQuest(5000);
    cm.completeQuest(5001);
    cm.isQuestStarted(1002);
    cm.isQuestCompleted(1003);
    cm.getQuestStatus(1004);
    cm.warp(100000000, 0);
    cm.warpParty(910000000);
    pm.spawnMonster(9400000, 1, 0);
    pm.teachSkill(80000000, 0, 0);
    cm.openShop(9001000);
    cm.gainExp(120);
    cm.gainMeso(5000);
    cm.gainItem(itemId, 1);
    cm.warp(100000000 + offset, 0);
}
"""


def line_of(text, needle):
    """1-based line where `needle` starts in `text`."""
    return text.count("\n", 0, text.index(needle)) + 1


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="slums-atlas-test-")
        self.addCleanup(shutil.rmtree, self.tmp)

    def write(self, rel, text):
        path = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def row(self, rel, text):
        self.write(rel, text)
        index = si.index_scripts(self.tmp)
        return index, index[rel[len("scripts/"):-len(".js")]]

    def test_row_fields(self):
        index, row = self.row("scripts/npc/1002000.js", NPC)
        self.assertEqual(list(index), ["npc/1002000"])
        self.assertEqual(row["id"], "npc/1002000")
        self.assertEqual(row["path"], "scripts/npc/1002000.js")
        self.assertEqual(row["kind"], "npc")
        self.assertEqual(row["lines"], len(NPC.splitlines()))
        self.assertEqual(row["src"], [{"kind": "script", "path": "scripts/npc/1002000.js", "line": 1}])

    def test_header_drops_licence_and_credits(self):
        _, row = self.row("scripts/npc/1002000.js", NPC)
        self.assertEqual(row["header"], [
            "NPC Name: Roger",
            "Map(s): Maple Road : Lower level of the Training Camp (2)",
            "Description: Quest - Roger's Apple",
        ])

    def test_header_empty_when_only_licence(self):
        src = """/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana
    This program is free software: you can redistribute it and/or modify
*/
function start() {
    cm.dispose();
}
"""
        _, row = self.row("scripts/npc/9000000.js", src)
        self.assertEqual(row["header"], [])

    def test_calls_skip_comments_strings_and_declarations(self):
        _, row = self.row("scripts/npc/1002000.js", NPC)
        self.assertEqual([c["fn"] for c in row["calls"]],
                         ["haveItem", "gainItem", "warp", "forceCompleteQuest", "startQuest", "openShop"])
        args = " ".join(c["args"] for c in row["calls"])
        for gone in ("1111111", "222222222", "3333333"):
            self.assertNotIn(gone, args)

    def test_call_args_line_and_multiline(self):
        _, row = self.row("scripts/npc/1002000.js", NPC)
        calls = {c["fn"]: c for c in row["calls"]}
        self.assertEqual(calls["gainItem"]["args"], "2010007, 1")  # two source lines collapsed
        self.assertEqual(calls["gainItem"]["line"], line_of(NPC, "cm.gainItem(2010007"))
        self.assertEqual(calls["haveItem"]["line"], line_of(NPC, "cm.haveItem(4000000)"))
        self.assertEqual(calls["warp"]["args"], "100000000 + (1 * 2), 0")  # nested parens kept
        self.assertEqual(calls["forceCompleteQuest"]["args"], "1021")

    def test_args_are_capped(self):
        src = 'function start() { cm.gainItem(%s, 1); }\n' % ("9" * 300)
        _, row = self.row("scripts/quest/1.js", src)
        self.assertEqual(len(row["calls"][0]["args"]), si.MAX_ARGS)

    def test_mentions_kinds_and_skipped_arguments(self):
        _, row = self.row("scripts/quest/1.js", ALL_CALLS)
        self.assertEqual(row["mentions"], [
            {"k": "item", "id": 2010007, "line": line_of(ALL_CALLS, "cm.gainItem(2010007")},
            {"k": "item", "id": 4000000, "line": line_of(ALL_CALLS, "cm.haveItem")},
            {"k": "item", "id": 2000000, "line": line_of(ALL_CALLS, "cm.removeAll")},
            {"k": "item", "id": 1302000, "line": line_of(ALL_CALLS, "cm.canHold")},
            {"k": "quest", "id": 1001, "line": line_of(ALL_CALLS, "cm.forceStartQuest")},
            {"k": "quest", "id": 1021, "line": line_of(ALL_CALLS, "cm.forceCompleteQuest")},
            {"k": "quest", "id": 5000, "line": line_of(ALL_CALLS, "cm.startQuest")},
            {"k": "quest", "id": 5001, "line": line_of(ALL_CALLS, "cm.completeQuest")},
            {"k": "quest", "id": 1002, "line": line_of(ALL_CALLS, "cm.isQuestStarted")},
            {"k": "quest", "id": 1003, "line": line_of(ALL_CALLS, "cm.isQuestCompleted")},
            {"k": "quest", "id": 1004, "line": line_of(ALL_CALLS, "cm.getQuestStatus")},
            {"k": "map", "id": 100000000, "line": line_of(ALL_CALLS, "cm.warp(100000000")},
            {"k": "map", "id": 910000000, "line": line_of(ALL_CALLS, "cm.warpParty")},
            {"k": "mob", "id": 9400000, "line": line_of(ALL_CALLS, "pm.spawnMonster")},
            {"k": "skill", "id": 80000000, "line": line_of(ALL_CALLS, "pm.teachSkill")},
            {"k": "shop", "id": 9001000, "line": line_of(ALL_CALLS, "cm.openShop")},
        ])
        # dropItems takes meso/coordinate flags, never an item id; variables and expressions are not guessed.
        self.assertNotIn("dropItems", json.dumps(row["mentions"]))

    def test_event_script_calls(self):
        src = (
            "function setup(level, lobbyid) {\n"
            "    var eim = em.newInstance('Test');\n"
            "    eim.getInstanceMap(990000100);\n"
            "    var m = eim.getMapInstance(910010000);\n"
            "    m.spawnMonsterOnGroundBelow(MapleLifeFactory.getMonster(9300012), new Point(1, 2));\n"
            "    eim.spawnNpc(9250000, new Point(0, 0), m);\n"
            "    eim.warpEventTeam(930000100);\n"
            "    m.getMonsterById(9300001);\n"
            "    eim.getMapInstance(i);\n"
            "}\n")
        _, row = self.row("scripts/event/Test.js", src)
        got = [(m["k"], m["id"]) for m in row["mentions"]]
        self.assertEqual(got, [("map", 990000100), ("map", 910010000), ("mob", 9300012), ("npc", 9250000),
                               ("map", 930000100), ("mob", 9300001)])

    def test_mentions_ignore_non_literals(self):
        src = ("function start() {\n    cm.gainItem(itemId, 1);\n"
               "    cm.warp(100000000 + offset, 0);\n    cm.forceStartQuest(getQuest());\n}\n")
        _, row = self.row("scripts/quest/2.js", src)
        self.assertEqual(row["mentions"], [])

    def test_skips_base_js_and_other_folders(self):
        self.write("scripts/NPC Base.js", "function start() { cm.gainItem(1, 1); }\n")
        self.write("scripts/npc/Base.js", "function start() { cm.gainItem(1, 1); }\n")
        self.write("scripts/json/data.js", "var x = 1;\n")
        self.write("scripts/lib.js", "var x = 1;\n")
        self.write("scripts/map/onUserEnter/100000000.js", "function start(ms) { ms.warp(100000000, 0); }\n")
        index = si.index_scripts(self.tmp)
        self.assertEqual(list(index), ["map/onUserEnter/100000000"])
        self.assertEqual(index["map/onUserEnter/100000000"]["kind"], "map")

    def test_write_index_is_sorted_and_indented(self):
        self.write("scripts/npc/2.js", "function start() { cm.gainItem(1, 1); }\n")
        self.write("scripts/quest/1.js", "var x = 1;\n")
        out = os.path.join(self.tmp, "out", "scripts.json")
        si.write_index(self.tmp, out)
        with open(out, encoding="utf-8") as f:
            text = f.read()
        self.assertTrue(text.startswith('{\n "npc/2": {\n  "calls": ['))
        data = json.loads(text)
        self.assertEqual(list(data), ["npc/2", "quest/1"])


class RealRepoTest(unittest.TestCase):
    """The index of this checkout: count, known calls and a spot check against the file on disk."""

    @classmethod
    def setUpClass(cls):
        cls.index = si.index_scripts(REPO)

    def test_every_script_is_indexed(self):
        self.assertGreaterEqual(len(self.index), 1900)
        kinds = {row["kind"] for row in self.index.values()}
        self.assertEqual(kinds, set(si.KINDS))

    def test_quest_1021(self):
        row = self.index["quest/1021"]
        self.assertEqual(row["path"], "scripts/quest/1021.js")
        self.assertIn("NPC Name: Roger", row["header"])
        self.assertIn({"fn": "forceCompleteQuest", "args": "", "line": 105}, row["calls"])
        self.assertIn({"fn": "gainItem", "args": "2010007, 1", "line": 58}, row["calls"])
        self.assertIn({"k": "quest", "id": 1021, "line": 99}, row["mentions"])  # isQuestCompleted(1021)
        self.assertIn({"k": "item", "id": 2010007, "line": 58}, row["mentions"])
        with open(os.path.join(REPO, "scripts", "quest", "1021.js"), encoding="utf-8") as f:
            self.assertEqual(row["lines"], len(f.read().splitlines()))


if __name__ == "__main__":
    unittest.main()
