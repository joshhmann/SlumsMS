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

/**
 * Ticket 157 follow-up: a hidden catch floor under the view bottom (as on 100040001, r33 at y 632 under a view bottom
 * of 450) is never routed into, but a bot already in it may leave.
 */
class NavPitsTest {

    private static MapleMap map(int id, int viewBottom, int[][] footholds) {
        MapleMap map = new MapleMap(id, 0, 0, id, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : footholds) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(0);
            fh.setNext(0);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        map.setMapLineBoundings(-200, viewBottom, -700, 700);
        return map;
    }

    /* A visible ledge whose right end drops onto a wide floor; under it all, a hidden catch floor at y 632. */
    private static final int[][] FOOTHOLDS = {
            {1, -600, 300, 0, 300},   // the visible ledge
            {2, -100, 420, 600, 420}, // the visible floor the ledge drops onto (its left end drops into the pit)
            {3, -650, 632, 650, 632}, // the hidden pit floor
    };

    @Test
    void thePlannerNeverRoutesIntoAPitUnderTheViewBottom() {
        MapleMap map = map(910000950, 450, FOOTHOLDS);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int ledge = graph.regionIdByFootholdId.get(1);
        int pit = graph.regionIdByFootholdId.get(3);
        int floor = graph.regionIdByFootholdId.get(2);
        assertTrue(graph.getOutgoing(floor).stream().anyMatch(e -> e.toRegionId == pit),
                "the builder still links the visible floor to the pit (the edge the dead-end check found)");
        assertTrue(NavPits.isPit(graph, map, pit));
        assertFalse(NavPits.isPit(graph, map, ledge));

        List<BotNavigationGraph.Edge> path = BotNavigationManager.runSearch(graph, map, new Point(300, 420), floor,
                pit, new Point(0, 632), null, true, false, 0L, 0).path();
        assertTrue(path.isEmpty() || path.getLast().toRegionId != pit, "no route into the pit: " + path);
        assertFalse(NavProbe.probe(map, graph, new Point(-300, 300), new Point(0, 632), 0).reachable());

        assertTrue(NavProbe.probe(map, graph, new Point(-300, 300), new Point(400, 420), 0).reachable(),
                "the visible floor below the ledge is still reachable");
        for (BotNavigationGraph.Edge out : graph.getOutgoing(pit)) {
            assertFalse(NavPits.into(graph, map, out), "edges out of a pit stay usable");
        }
        assertTrue(floor != pit);
    }

    @Test
    void theSameFloorInsideTheViewIsNoPit() {
        MapleMap map = map(910000951, 700, FOOTHOLDS);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int pit = graph.regionIdByFootholdId.get(3);
        assertFalse(NavPits.isPit(graph, map, pit));
        assertEquals(0, NavPits.pits(graph, map).cardinality());
    }
}
