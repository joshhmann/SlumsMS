"""Tests for quest_check.py on a tiny hand-made WZ tree.

    python3 -m unittest discover -s tools/questcheck -p 'test_*.py'
"""
import os
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import quest_check as qc  # noqa: E402


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(textwrap.dedent(text).strip() + "\n")


CHECK = """
<imgdir name="Check.img">
  <imgdir name="100">
    <imgdir name="0"><int name="npc" value="2000"/><int name="lvmin" value="10"/></imgdir>
    <imgdir name="1"><int name="npc" value="2000"/>
      <imgdir name="item"><imgdir name="0"><int name="id" value="4000001"/><int name="count" value="5"/></imgdir></imgdir>
      <imgdir name="mob"><imgdir name="0"><int name="id" value="100100"/><int name="count" value="3"/></imgdir></imgdir>
    </imgdir>
  </imgdir>
  <imgdir name="101">
    <imgdir name="0"><int name="npc" value="2000"/><imgdir name="quest"><imgdir name="0"><int name="id" value="100"/><int name="state" value="2"/></imgdir></imgdir></imgdir>
    <imgdir name="1"><int name="npc" value="9999"/>
      <imgdir name="item"><imgdir name="0"><int name="id" value="4000002"/><int name="count" value="1"/></imgdir></imgdir>
      <imgdir name="mob"><imgdir name="0"><int name="id" value="100200"/><int name="count" value="1"/></imgdir></imgdir>
      <string name="endscript" value="q101e"/>
    </imgdir>
  </imgdir>
  <imgdir name="102">
    <imgdir name="0"><int name="npc" value="2000"/><imgdir name="quest"><imgdir name="0"><int name="id" value="101"/><int name="state" value="2"/></imgdir></imgdir></imgdir>
    <imgdir name="1"><int name="npc" value="2000"/><int name="infoNumber" value="7000"/>
      <imgdir name="infoex"><imgdir name="0"><string name="value" value="1"/></imgdir></imgdir>
      <imgdir name="mob"><imgdir name="0"><int name="id" value="9101000"/><int name="count" value="1"/></imgdir></imgdir>
    </imgdir>
  </imgdir>
  <imgdir name="103">
    <imgdir name="0"><int name="npc" value="2000"/><string name="end" value="2008010100"/></imgdir>
    <imgdir name="1"><int name="npc" value="2000"/></imgdir>
  </imgdir>
  <imgdir name="104">
    <imgdir name="0"><int name="npc" value="2000"/><imgdir name="quest"><imgdir name="0"><int name="id" value="101"/></imgdir></imgdir></imgdir>
    <imgdir name="1"><int name="npc" value="2000"/></imgdir>
  </imgdir>
</imgdir>
"""

ACT = """
<imgdir name="Act.img">
  <imgdir name="100">
    <imgdir name="1"><int name="exp" value="10"/>
      <imgdir name="item"><imgdir name="0"><int name="id" value="2000000"/><int name="count" value="1"/></imgdir>
        <imgdir name="1"><int name="id" value="2999999"/><int name="count" value="1"/></imgdir></imgdir>
      <imgdir name="skill"><imgdir name="0"><int name="id" value="1007"/></imgdir></imgdir>
    </imgdir>
  </imgdir>
</imgdir>
"""


class QuestCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        t = cls.tmp.name
        wz = os.path.join(t, "wz")
        write(wz, "Quest.wz/Check.img.xml", CHECK)
        write(wz, "Quest.wz/Act.img.xml", ACT)
        write(wz, "Quest.wz/QuestInfo.img.xml", '<imgdir name="QuestInfo.img">'
              + "".join('<imgdir name="%d"><string name="name" value="Q%d"/></imgdir>' % (q, q) for q in range(100, 105))
              + "</imgdir>")
        write(wz, "Item.wz/Etc/0400.img.xml",
              '<imgdir name="0400.img"><imgdir name="04000001"/><imgdir name="04000002"/></imgdir>')
        write(wz, "Item.wz/Consume/0200.img.xml", '<imgdir name="0200.img"><imgdir name="02000000"/></imgdir>')
        for npc in ("0002000", "0009999"):
            write(wz, "Npc.wz/%s.img.xml" % npc, '<imgdir name="%s.img"/>' % npc)
        for mob in ("0100100", "0100200", "1110100"):
            write(wz, "Mob.wz/%s.img.xml" % mob, '<imgdir name="%s.img"/>' % mob)
        write(wz, "Map.wz/Map/Map0/000010000.img.xml", """
            <imgdir name="000010000.img"><imgdir name="life">
              <imgdir name="0"><string name="id" value="2000"/><string name="type" value="n"/></imgdir>
              <imgdir name="1"><string name="type" value="m"/><string name="id" value="0100100"/></imgdir>
              <imgdir name="2"><string name="type" value="m"/><string name="id" value="1110100"/></imgdir>
            </imgdir></imgdir>""")
        write(wz, "Skill.wz/000.img.xml", '<imgdir name="000.img"><imgdir name="skill"><imgdir name="0001000"/></imgdir></imgdir>')
        db = os.path.join(t, "db")
        write(db, "drop_data.tsv", "100100\t4000001\t100\t5000\n100100\t4000009\t9999\t100")
        cls.scripts = os.path.join(t, "scripts")
        write(cls.scripts, "npc/x.js", "cm.setQuestProgress(5555, 1);")
        write(cls.scripts, "quest/100.js", "function end(mode, type, selection) {}")
        g = qc.load_wz(wz)
        src = qc.Sources(db, cls.scripts)
        cls.chk = qc.Checker(g, src, {0, 100}, today="2026010100").run()
        cls.codes = {(f.quest, f.code) for f in cls.chk.findings}

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_clean_quest_has_no_blocking_findings(self):
        self.assertNotIn(100, self.chk.uncompletable)
        self.assertNotIn(100, self.chk.unstartable)

    def test_missing_reward_item_and_skill(self):
        self.assertIn((100, "missing-reward-item"), self.codes)
        self.assertIn((100, "missing-reward-skill"), self.codes)

    def test_unplaced_npc_unobtainable_item_unspawned_mob_and_missing_script(self):
        self.assertIn((101, "npc-not-placed"), self.codes)
        self.assertIn((101, "unobtainable-item"), self.codes)
        self.assertIn((101, "mob-never-spawns"), self.codes)
        self.assertIn((101, "missing-script"), self.codes)
        self.assertIn(101, self.chk.uncompletable)

    def test_broken_prerequisite_blocks_the_next_quest(self):
        self.assertIn(102, self.chk.unstartable)
        self.assertIn((102, "blocked-by-prereq"), self.codes)

    def test_quest_mob_alias_counts_the_real_mob_and_infoex_record_must_be_set(self):
        self.assertNotIn((102, "missing-mob"), self.codes)
        self.assertNotIn((102, "mob-never-spawns"), self.codes)
        self.assertIn((102, "infoex-never-set"), self.codes)

    def test_expired_event_and_prereq_state_default(self):
        self.assertIn(103, self.chk.expired)
        # 104 needs 101 NOT started (no state = 0), so a broken 101 does not block it
        self.assertNotIn(104, self.chk.unstartable)

    def test_drop_for_missing_quest(self):
        self.assertIn((9999, "drop-for-missing-quest"), self.codes)

    def test_baseline_ignores_names_in_parentheses(self):
        a = qc.Finding(1, qc.ERROR, "x", "mob 5 (Wild Kargo) never spawns")
        b = qc.Finding(1, qc.ERROR, "x", "mob 5 (Wild Cargo) never spawns")
        self.assertEqual(qc.finding_key(a), qc.finding_key(b))

    def test_report_renders(self):
        text = qc.report(self.chk)
        self.assertIn("Quests that can never be completed", text)


if __name__ == "__main__":
    unittest.main()
