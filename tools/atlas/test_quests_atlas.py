import os
import tempfile
import unittest

import quests_atlas as Q

CHECK = """<imgdir name="Check.img">
<imgdir name="1021"><imgdir name="0"><int name="npc" value="2000"/><int name="lvmin" value="5"/>
<string name="startscript" value="q1021s"/><imgdir name="job"><int name="0" value="0"/></imgdir>
<imgdir name="quest"><imgdir name="0"><int name="id" value="1020"/><int name="state" value="2"/></imgdir></imgdir>
</imgdir>
<imgdir name="1"><int name="npc" value="2001"/><imgdir name="mob"><imgdir name="0"><int name="id" value="100100"/>
<int name="count" value="5"/></imgdir></imgdir></imgdir></imgdir>
</imgdir>"""
ACT = """<imgdir name="Act.img"><imgdir name="1021"><imgdir name="0"></imgdir><imgdir name="1">
<int name="exp" value="300"/><int name="nextQuest" value="1022"/><imgdir name="item">
<imgdir name="0"><int name="id" value="1092000"/><int name="count" value="1"/></imgdir>
<imgdir name="1"><int name="id" value="4000003"/><int name="count" value="-30"/></imgdir></imgdir></imgdir></imgdir></imgdir>"""
SAY = """<imgdir name="Say.img"><imgdir name="1021"><imgdir name="0">
<string name="0" value="#bHello#k #p2000#, take #t1092000#."/></imgdir></imgdir></imgdir>"""
INFO = """<imgdir name="QuestInfo.img"><imgdir name="1021"><string name="name" value="Roger&apos;s Apple"/>
<string name="0" value="Go."/><int name="area" value="20"/></imgdir>
<imgdir name="1022"><string name="name" value="Next"/></imgdir></imgdir>"""


class QuestsAtlasTest(unittest.TestCase):
    def build(self, **kw):
        d = tempfile.mkdtemp()
        os.makedirs(os.path.join(d, "Quest.wz"))
        for n, x in (("Check", CHECK), ("Act", ACT), ("Say", SAY), ("QuestInfo", INFO)):
            with open(os.path.join(d, "Quest.wz", f"{n}.img.xml"), "w", encoding="utf-8") as f:
                f.write(x)
        return Q.build(d, **kw)

    def test_plain_text_drops_codes_and_names_lookups(self):
        self.assertEqual(Q.plain("#bHi#k #p2100#, #t01092000# #h0#!\\r\\n#L0#Yes#l"), "Hi <NPC 2100>, <item 1092000> <player>! Yes")

    def test_requirements_rewards_and_sources(self):
        r = self.build(script_ids={"quest/1021"})["1021"]
        self.assertEqual(r["name"], "Roger's Apple")
        self.assertEqual(r["start_npc"], {"k": "npc", "id": 2000})
        self.assertEqual(r["end_npc"], {"k": "npc", "id": 2001})
        self.assertEqual(r["requirements"]["min_level"], 5)
        self.assertEqual(r["requirements"]["jobs"], [0])
        self.assertEqual(r["requirements"]["quests"], [{"ref": {"k": "quest", "id": 1020}, "state": 2}])
        self.assertEqual(r["requirements"]["mobs"], [{"ref": {"k": "mob", "id": 100100}, "count": 5}])
        self.assertEqual(r["rewards"]["items"], [{"ref": {"k": "item", "id": 1092000}, "count": 1}])
        self.assertEqual(r["rewards"]["take"], [{"ref": {"k": "item", "id": 4000003}, "count": 30}])
        self.assertEqual(r["rewards"]["next_quest"], {"k": "quest", "id": 1022})
        self.assertEqual(r["script"], {"k": "script", "id": "quest/1021"})
        self.assertEqual(r["text"]["start"], ["Hello <NPC 2000>, take <item 1092000>."])
        self.assertEqual([(s["path"], s["line"]) for s in r["src"]][0], ("Quest.wz/Check.img.xml", 2))

    def test_chain_links_both_ways(self):
        rows = self.build()
        self.assertEqual(rows["1021"]["chain"]["next"], [{"k": "quest", "id": 1022}])
        self.assertEqual(rows["1021"]["chain"]["prev"], [{"k": "quest", "id": 1020}])


@unittest.skipUnless(os.environ.get("SLUMS_ATLAS_WZ"), "set SLUMS_ATLAS_WZ to a folder holding Quest.wz/")
class RealQuestsTest(unittest.TestCase):
    def test_counts(self):
        rows = Q.build(os.environ["SLUMS_ATLAS_WZ"])
        self.assertGreater(len(rows), 2800)
        self.assertEqual(rows["1021"]["name"], "Roger's Apple")


if __name__ == "__main__":
    unittest.main()
