package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157: "can a bot reach (x,y)" and the per-map portal / NPC sweep, on made-up maps. */
class NavProbeTest {

    private static MapleMap map(int id, int[][] footholds) {
        MapleMap map = new MapleMap(id, 0, 0, id, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : footholds) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(0);
            fh.setNext(0);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    /* A floor, a low step a walker can jump onto, and a high ledge only a teleport reaches. */
    private static final MapleMap MAP = map(910000911, new int[][]{
            {1, -600, 400, 600, 400},
            {2, 300, 360, 500, 360},
            {3, -100, 250, 100, 250},
    });
    private static final BotNavigationGraph GRAPH = BotNavigationGraphProvider.rebuildGraph(MAP);

    @Test
    void aSpotOnTheSamePlatformIsAWalk() {
        NavProbe.Result r = NavProbe.probe(MAP, GRAPH, new Point(-400, 400), new Point(-200, 400), 0);
        assertTrue(r.reachable(), r.lines().toString());
        assertEquals("same platform, walk there", r.reason());
    }

    @Test
    void aStepUpIsReachableWithItsEdges() {
        NavProbe.Result r = NavProbe.probe(MAP, GRAPH, new Point(-400, 400), new Point(400, 360), 0);
        assertTrue(r.reachable(), r.lines().toString());
        assertFalse(r.path().isEmpty());
        assertTrue(r.lines().get(0).startsWith("REACHABLE: "), r.lines().get(0));
        assertTrue(r.lines().get(1).startsWith("1. "), r.lines().toString());
    }

    @Test
    void aLedgeOnlyATeleportReachesSaysWhyNot() {
        NavProbe.Result walker = NavProbe.probe(MAP, GRAPH, new Point(-400, 400), new Point(0, 250), 0);
        assertFalse(walker.reachable(), walker.lines().toString());
        assertTrue(walker.reason().startsWith("no route from r") && walker.reason().endsWith(" walking"),
                walker.reason());
        NavProbe.Result mage = NavProbe.probe(MAP, GRAPH, new Point(-400, 400), new Point(0, 250),
                BotNavigationGraph.SKILL_TELEPORT);
        assertTrue(mage.reachable(), mage.lines().toString());
        assertTrue(mage.path().getLast().startsWith("TELEPORT"), mage.path().toString());
    }

    @Test
    void aTargetOverNothingAndAMissingGraphAreReasonsToo() {
        NavProbe.Result air = NavProbe.probe(MAP, GRAPH, new Point(-400, 400), new Point(5000, 0), 0);
        assertFalse(air.reachable());
        assertTrue(air.reason().contains("no ground or rope under it"), air.reason());
        assertFalse(NavProbe.probe(MAP, null, new Point(-400, 400), new Point(0, 400), 0).reachable());
    }

    @Test
    void theMapSweepListsOnlyTheSpotsNothingReaches() {
        List<String> failures = NavProbe.probeMap(MAP, GRAPH, new Point(-400, 400), List.of(
                new NavProbe.Spot("portal east", new Point(550, 400)),
                new NavProbe.Spot("npc 9000000", new Point(450, 360)),
                new NavProbe.Spot("npc 9000001", new Point(0, 250))));
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).startsWith("map 910000911 npc 9000001 at (0,250): no route"), failures.get(0));
        assertEquals(List.of("map 910000911: no spawn point to start from"),
                NavProbe.probeMap(MAP, GRAPH, null, List.of()));
    }

    @Test
    void posSaysWhichRegionIsUnderYourFeet() {
        String line = NavProbe.regionLine(MAP, GRAPH, new Point(400, 360));
        assertTrue(line.startsWith("Nav region: r") && line.contains(" ground x300..500 y360..360, "), line);
        assertTrue(line.contains(" edges out"), line);
        assertTrue(NavProbe.regionLine(MAP, GRAPH, new Point(5000, 0)).startsWith("Nav region: off the graph at (5000,0)"));
    }
}
