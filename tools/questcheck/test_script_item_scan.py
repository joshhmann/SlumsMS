"""Tests for script_item_scan.py on a tiny hand-made tree.

    python3 -m unittest discover -s tools/questcheck -p 'test_*.py'
"""
import os
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import script_item_scan as sis  # noqa: E402


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(textwrap.dedent(text).strip() + "\n")


class ScriptItemScanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        t = cls.tmp.name
        cls.wz = os.path.join(t, "wz")
        write(cls.wz, "Quest.wz/Check.img.xml", '<imgdir name="Check.img"/>')
        write(cls.wz, "Quest.wz/Act.img.xml", """
            <imgdir name="Act.img"><imgdir name="1"><imgdir name="1"><imgdir name="item">
              <imgdir name="0"><int name="id" value="4000003"/><int name="count" value="1"/></imgdir>
            </imgdir></imgdir></imgdir></imgdir>""")
        write(cls.wz, "Quest.wz/QuestInfo.img.xml", '<imgdir name="QuestInfo.img"/>')
        cls.scripts = os.path.join(t, "scripts")
        # the B-26 shape: a portal only CHECKS and TAKES the key; nothing grants it
        write(cls.scripts, "portal/prison.js", """
            function enter(pi) {
                if (pi.getPlayer().haveItem(4032860)) { pi.gainItem(4032860, -1); return true; }
                return false;
            }""")
        # granted by an NPC script elsewhere
        write(cls.scripts, "npc/giver.js", "function start() { cm.gainItem(4000001, 1); }")
        write(cls.scripts, "portal/needs_granted.js", "function enter(pi) { return pi.haveItem(4000001); }")
        # granted only by a quest reward
        write(cls.scripts, "portal/needs_quest.js", "function enter(pi) { return pi.haveItem(4000003); }")
        # a comment mention must not count as a grant
        write(cls.scripts, "portal/commented.js", """
            // 4000004 is given by nobody
            function enter(pi) { return pi.hasItem(4000004); }""")
        # dropped by a mob (db export)
        write(cls.scripts, "portal/needs_drop.js", "function enter(pi) { return pi.itemQuantity(4000005) > 0; }")
        cls.db = os.path.join(t, "db")
        write(cls.db, "drop_data.tsv", "100100\t4000005\t0\t10000")
        for n in ("reactordrops.tsv", "drop_global.tsv", "shopitems.tsv", "plife.tsv"):
            write(cls.db, n, "")
        write(cls.scripts, "portal/needs_migration.js", "function enter(pi) { return pi.haveItem(4000006); }")
        cls.mig = os.path.join(t, "mig")
        write(cls.mig, "001.sql", """
            -- 4000099 in a comment does not count
            INSERT INTO drop_data (dropperid, itemid, chance) VALUES (100200, 4000006, 1000);""")
        # an item name shown in dialog text (#t4000009#) is not a grant
        write(cls.scripts, "npc/text.js", 'function start() { if (!cm.haveItem(4000009)) cm.sendOk("Bring #t4000009#."); }')
        write(cls.scripts, "portal/needs_known.js", "function enter(pi) { return pi.haveItem(4000007); }")
        cls.known = os.path.join(t, "known.tsv")
        write(t, "known.tsv", "# item\\treason\n4000007\tgiven by Foo.java")
        write(cls.scripts, "portal/needs_tag.js", "function enter(pi) { return pi.haveItem(4000008); }")
        write(cls.wz, "Map.wz/Map/Map9/1.img.xml", '<imgdir name="1"><string name="tags" value="itemId=4000008;qty=1"/></imgdir>')
        cls.findings, cls.n = sis.scan(cls.wz, cls.scripts, cls.db, cls.mig,
                                       {4000007: "given by Foo.java"})
        cls.errors = {f[1] for f in cls.findings if f[0] == "ERROR"}

    def test_require_only_item_is_an_error(self):
        self.assertIn(4032860, self.errors)

    def test_dialog_name_is_not_a_source(self):
        self.assertIn(4000009, self.errors)

    def test_commented_mention_is_not_a_source(self):
        self.assertIn(4000004, self.errors)

    def test_sources_clear_the_item(self):
        for item in (4000001, 4000003, 4000005, 4000006, 4000007, 4000008):
            self.assertNotIn(item, self.errors, item)

    def test_take_counts_as_a_require(self):
        reqs, _ = sis.scan_scripts(self.scripts)
        self.assertEqual({w[0] for w in reqs[4032860]}, {"portal/prison.js"})

    def test_exit_code_and_known_file_format(self):
        self.assertEqual(sis.main(["--wz", self.wz, "--scripts", self.scripts, "--md", os.devnull]), 1)
        with self.assertRaises(SystemExit):
            bad = os.path.join(self.tmp.name, "bad.tsv")
            write(self.tmp.name, "bad.tsv", "4000007")
            sis.load_known(bad)


if __name__ == "__main__":
    unittest.main()
