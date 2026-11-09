import collections, os, sys, types, unittest
import xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import early_quests as eq


def el(xml):
    return ET.fromstring(xml)


def fixture(check_xml):
    g = types.SimpleNamespace(
        check={1: el(check_xml)}, npc_maps=collections.defaultdict(set), mob_maps=collections.defaultdict(set),
        quest_names={1: "q"}, maker_outputs=set())
    src = types.SimpleNamespace(plife=collections.defaultdict(set), mob_drops=collections.defaultdict(set),
                                reactor_drops={}, global_drops=set(), shop_items={}, script_literals={})
    chk = types.SimpleNamespace(reward_items={})
    return g, src, chk


class EarlyQuests(unittest.TestCase):
    def test_offered(self):
        self.assertTrue(eq.offered([], eq.LEGEND_JOBS))
        self.assertTrue(eq.offered([100, 200], eq.EXPLORER_JOBS))
        self.assertFalse(eq.offered([100], eq.NOBLESSE_JOBS))

    def test_reachable_follows_edges_from_every_start(self):
        edges = {1: {2}, 2: {3}, 10: {11}}
        self.assertEqual(eq.reachable(edges, (1, 10)), {1, 2, 3, 10, 11})
        self.assertEqual(eq.reachable(edges, 1), {1, 2, 3})

    def test_blocked_when_the_start_npc_is_only_on_an_unreachable_map(self):
        g, src, chk = fixture('<imgdir name="1"><imgdir name="0"><int name="npc" value="7"/></imgdir>'
                              '<imgdir name="1"><int name="npc" value="8"/></imgdir></imgdir>')
        g.npc_maps[7] = {500}
        g.npc_maps[8] = {1}
        v, why = eq.classify(1, g, src, chk, {1, 2}, {}, set())
        self.assertEqual(v, "BLOCKED")
        self.assertIn("start NPC 7", why[0])

    def test_script_makes_it_client_required_and_a_clean_quest_is_runnable(self):
        g, src, chk = fixture('<imgdir name="1"><imgdir name="0"><int name="npc" value="7"/>'
                              '<string name="startscript" value="s"/></imgdir>'
                              '<imgdir name="1"><int name="npc" value="7"/></imgdir></imgdir>')
        g.npc_maps[7] = {1}
        self.assertEqual(eq.classify(1, g, src, chk, {1}, {}, set())[0], "CLIENT-REQUIRED")
        g2, src2, chk2 = fixture('<imgdir name="1"><imgdir name="0"><int name="npc" value="7"/></imgdir>'
                                 '<imgdir name="1"><int name="npc" value="7"/></imgdir></imgdir>')
        g2.npc_maps[7] = {1}
        self.assertEqual(eq.classify(1, g2, src2, chk2, {1}, {}, set())[0], "RUNNABLE")
        self.assertEqual(eq.classify(1, g2, src2, chk2, {1}, {}, {1})[0], "EXPIRED")

    def test_missing_script_is_client_required_not_blocked(self):
        g, src, chk = fixture('<imgdir name="1"><imgdir name="0"><int name="npc" value="7"/></imgdir>'
                              '<imgdir name="1"><int name="npc" value="7"/></imgdir></imgdir>')
        g.npc_maps[7] = {1}
        f = types.SimpleNamespace(code="missing-script", detail="no scripts/quest/1.js")
        self.assertEqual(eq.classify(1, g, src, chk, {1}, {1: [f]}, set())[0], "CLIENT-REQUIRED")
        f2 = types.SimpleNamespace(code="unobtainable-item", detail="nothing gives it")
        self.assertEqual(eq.classify(1, g, src, chk, {1}, {1: [f2]}, set())[0], "BLOCKED")


if __name__ == "__main__":
    unittest.main()
