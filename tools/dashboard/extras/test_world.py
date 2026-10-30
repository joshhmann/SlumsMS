"""Tests for the world graph and dead-end check in slums-dashboard-extras (ticket 157).

    python3 -m unittest discover -s tools/dashboard/extras -p 'test_*.py'
"""
import importlib.machinery
import json
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
X = importlib.machinery.SourceFileLoader("extras", os.path.join(HERE, "slums-dashboard-extras")).load_module()

NO = X.NO_MAP


def region(rid, x1, y, x2, kind=0):
    return [rid, kind, x1, y, x2, y]


def edge(a, b, kind="WALK"):
    return [a, b, kind, 0, 0, 0, 0, 0, 0, 100, 0, 1.0, 0.0, 0.0]


class RegionAtTest(unittest.TestCase):
    def test_point_stands_on_the_platform_below_it(self):
        regions = [region(1, 0, 100, 200), region(2, 0, 300, 200), region(3, 0, -200, 200)]
        self.assertEqual(1, X.region_at(regions, 50, 90))
        self.assertEqual(2, X.region_at(regions, 50, 250))

    def test_ropes_are_not_platforms_and_far_points_take_the_nearest(self):
        regions = [region(1, 0, 100, 200), [9, 2, 60, 0, 60, 100]]
        self.assertEqual(1, X.region_at(regions, 60, 95))
        self.assertEqual(1, X.region_at(regions, 900, 900))


class CheckMapTest(unittest.TestCase):
    # Map 1: arrive at "in" on r1, walk to r2 where "out" leads on; r3 is a floor you can drop to but not leave.
    PORTALS = [["sp", 0, 10, 90, NO], ["in", 2, 20, 90, 2], ["out", 2, 180, 290, 3]]
    GRAPH = {"regions": [region(1, 0, 100, 100), region(2, 100, 300, 200), region(3, 0, 600, 200)],
             "edges": [edge(1, 2, "DROP"), edge(2, 1, "JUMP"), edge(2, 3, "DROP")]}

    def kinds(self, rows):
        return sorted(r[1] for r in rows)

    def test_a_good_map_has_no_findings_but_a_pit(self):
        rows, exits = X.check_map(1, self.PORTALS, self.GRAPH, [(2, "in")])
        self.assertEqual(["in", "out"], [p[0] for p in exits])
        self.assertEqual(["pit"], self.kinds(rows))
        self.assertEqual("r3", rows[0][2])

    def test_a_pit_under_the_visible_map_says_so(self):
        rows, _ = X.check_map(1, self.PORTALS, self.GRAPH, [(2, "in")], bounds=[0, -100, 300, 500])
        self.assertEqual("r3 (below view)", rows[0][2])

    def test_pits_the_planner_refuses_are_handled(self):
        rows, _ = X.check_map(1, self.PORTALS, self.GRAPH, [(2, "in")], handled={3})
        self.assertEqual([[1, "pit_handled", "r3"]], rows)

    def test_no_arrivals_means_nothing_to_check(self):
        self.assertEqual([], X.check_map(1, self.PORTALS, self.GRAPH, [])[0])

    def test_no_exit_and_script_only_exit(self):
        closed = [["sp", 0, 10, 90, NO], ["in", 2, 20, 90, 1]]   # its only portal leads to itself
        self.assertEqual(["no_exit"], self.kinds(X.check_map(1, closed, self.GRAPH, [(2, "in")])[0]))
        scripted = closed + [["boat", 7, 50, 90, NO]]
        self.assertEqual(["script_exit"], self.kinds(X.check_map(1, scripted, self.GRAPH, [(2, "in")])[0]))

    def test_arriving_where_no_exit_can_be_reached(self):
        graph = {"regions": self.GRAPH["regions"], "edges": [edge(1, 2, "DROP")]}   # no way back up from r2
        # "hole" is one-way: you arrive there from map 4, but it leads nowhere
        portals = [["in", 2, 20, 90, 2], ["out", 2, 20, 95, 3], ["hole", 2, 150, 290, NO]]
        rows, _ = X.check_map(1, portals, graph, [(4, "hole")])
        self.assertEqual(["arrive_stuck"], self.kinds(rows))
        self.assertIn("from 4 at hole", rows[0][2])

    def test_arriving_at_a_portal_that_leads_back_is_never_stuck(self):
        graph = {"regions": self.GRAPH["regions"], "edges": [edge(1, 2, "DROP")]}
        portals = [["in", 2, 20, 90, 2], ["hole", 2, 150, 290, 4]]
        self.assertEqual([], X.check_map(1, portals, graph, [(4, "hole")])[0])

    def test_skill_moves_do_not_count_as_a_way_out(self):
        graph = {"regions": self.GRAPH["regions"], "edges": [edge(1, 2, "DROP"), edge(2, 1, "FLASH_JUMP")]}
        portals = [["in", 2, 20, 90, 2], ["hole", 2, 150, 290, NO]]
        rows, _ = X.check_map(1, portals, graph, [(4, "hole")])
        self.assertEqual(["arrive_stuck"], self.kinds(rows))

    def test_unknown_target_portal_guesses_the_portal_back(self):
        # older exports have no toPortal: coming from map 3, you most likely arrive at the portal leading to 3
        rows, _ = X.check_map(1, self.PORTALS, {"regions": self.GRAPH["regions"], "edges": [edge(2, 1, "JUMP")]}, [(3, None)])
        self.assertEqual([], rows)

    def test_no_graph_runs_only_the_map_level_check(self):
        self.assertEqual(["no_graph"], self.kinds(X.check_map(1, self.PORTALS, None, [(2, "in")])[0]))


class BuildWorldTest(unittest.TestCase):
    def setUp(self):
        self.nav = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.nav, "maps"))
        os.makedirs(os.path.join(self.nav, "graphs"))

        def put(rel, obj):
            with open(os.path.join(self.nav, rel), "w", encoding="utf-8") as f:
                json.dump(obj, f)
        put("index.json", {"generated": 5, "maps": [[1, "Road", "Town", 1, 0, 0, 0, 0, 0], [2, "Road", "Field", 1, 0, 0, 0, 0, 0]]})
        put("maps/1.json", {"bounds": [0, 0, 300, 300], "portals": [["out", 2, 50, 90, 2, "in"]]})
        put("maps/2.json", {"bounds": [0, 0, 300, 300], "portals": [["in", 2, 50, 90, 1, "out"], ["fall", 2, 150, 290, 1, "out"]]})
        put("graphs/1-s100-j100.json", {"regions": [region(1, 0, 100, 100)], "edges": []})
        put("graphs/2-s100-j100.json", {"regions": [region(1, 0, 100, 100), region(2, 100, 300, 200)], "edges": [edge(1, 2, "DROP")]})

    def tearDown(self):
        shutil.rmtree(self.nav)

    def test_links_and_findings(self):
        doc = X.build_world(self.nav)
        self.assertTrue(doc["exactArrivals"])
        self.assertEqual([[1, "Road", "Town"], [2, "Road", "Field"]], doc["maps"])
        self.assertIn([1, 2, "out", "in"], doc["links"])
        self.assertEqual([], doc["deadEnds"])     # map 2's r2 has its own exit ("fall"), so no pit
        with open(os.path.join(self.nav, "world.json"), encoding="utf-8") as f:
            self.assertEqual(doc["links"], json.load(f)["links"])

    def test_pits_json_marks_pits_handled(self):
        with open(os.path.join(self.nav, "maps", "2.json"), "w", encoding="utf-8") as f:
            json.dump({"bounds": [0, 0, 300, 300], "portals": [["in", 2, 50, 90, 1, "out"]]}, f)
        self.assertEqual([[2, "pit", "r2"]], X.build_world(self.nav)["deadEnds"])
        with open(os.path.join(self.nav, "pits.json"), "w", encoding="utf-8") as f:
            json.dump({"version": 1, "maps": {"2": [2]}}, f)
        self.assertEqual([[2, "pit_handled", "r2"]], X.build_world(self.nav)["deadEnds"])


if __name__ == "__main__":
    unittest.main()
