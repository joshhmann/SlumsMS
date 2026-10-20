package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* Graph v62: rope-exit / rope-transfer CLIMB edges carry a Y launch window (ported from nutnnut/Cosmic, AGPL-3.0.
 * Credit: NutNNut). */
class BotRopeLaunchWindowTest {

    private static BotNavigationGraph.Edge ropeExit() {
        // Fixed rope x=100; launches from any climb height in [150,260]; cost 300 at the top, 100 at the bottom.
        return new BotNavigationGraph.Edge(5, 6, BotNavigationGraph.EdgeType.CLIMB, new Point(100, 200), new Point(140, 260),
                100, 100, 150, 260, 3, 0, 0, 0, 0, 300, 100, 200);
    }

    @Test
    void edgeCarriesYWindowAndSteersWithinIt() {
        BotNavigationGraph.Edge e = ropeExit();
        assertTrue(e.hasLaunchYWindow());
        assertTrue(e.containsLaunchY(150));
        assertTrue(e.containsLaunchY(260));
        assertFalse(e.containsLaunchY(149));
        assertTrue(e.containsLaunchY(145, 10), "tolerance widens the window");
        assertEquals(new Point(100, 150), e.pointAtNearestLaunchY(120));
        assertEquals(new Point(100, 260), e.pointAtNearestLaunchY(300));
        assertEquals(154, BotNavigationManager.steerYWithinLaunchWindow(e, 100));
        assertEquals(256, BotNavigationManager.steerYWithinLaunchWindow(e, 999));
        assertEquals(200, BotNavigationManager.steerYWithinLaunchWindow(e, 200));
    }

    @Test
    void costInterpolatesAcrossTheWindow() {
        BotNavigationGraph.Edge e = ropeExit();
        assertEquals(300, e.launchCostAt(150));
        assertEquals(100, e.launchCostAt(260));
        assertEquals(200, e.launchCostAt(205));
        assertEquals(300, e.launchCostAt(0), "clamped to the window");
        assertEquals(100, e.minLaunchCost());
        // Swapped Y args keep each cost on its own endpoint.
        BotNavigationGraph.Edge swapped = new BotNavigationGraph.Edge(5, 6, BotNavigationGraph.EdgeType.CLIMB,
                new Point(100, 200), new Point(140, 260), 100, 100, 260, 150, 3, 0, 0, 0, 0, 100, 300, 200);
        assertEquals(300, swapped.launchCostAt(150));
    }

    @Test
    void nonRopeEdgesHaveADegenerateYWindow() {
        BotNavigationGraph.Edge ground = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.WALK, new Point(0, 0), new Point(10, 0), 0, -1, 0, 0, 0, 0);
        assertFalse(ground.hasLaunchYWindow());
        assertTrue(ground.containsLaunchY(0));
        assertFalse(ground.containsLaunchY(1));
        assertEquals(0, ground.launchCostAt(50));
    }

    @Test
    void learningKeysTellRopeWindowsApart() {
        BotNavigationGraph.Edge left = new BotNavigationGraph.Edge(5, 6, BotNavigationGraph.EdgeType.CLIMB,
                new Point(100, 200), new Point(60, 260), 100, 100, 150, 250, -3, 0, 0, 0, 0, 300, 100, 200);
        BotNavigationGraph.Edge right = new BotNavigationGraph.Edge(5, 6, BotNavigationGraph.EdgeType.CLIMB,
                new Point(100, 200), new Point(140, 260), 100, 100, 150, 250, 3, 0, 0, 0, 0, 300, 100, 200);
        assertNotEquals(NavEdgeLearning.edgeKey(left), NavEdgeLearning.edgeKey(right));
        // Ticket 04 keys carry both windows, the step and the landing; files from before still read by the old key.
        BotNavigationGraph.Edge jump = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(10, 20), new Point(50, 20), 0, 30, 3, 0, 0, 0, 0, 400);
        assertEquals("JUMP:1>2:x0..30:y20..20:s3:10,20>50,20", NavEdgeLearning.edgeKey(jump));
        assertEquals("1>2:JUMP:0:30:10,20", NavEdgeLearning.legacyKey(jump));
    }

    @Test
    void boundarySearchFindsTheContiguousWindow() {
        // Valid heights are [120, 233]; search from 180 inside rope [50, 300].
        assertEquals(120, BotNavigationGraphProvider.findRopeLaunchBoundary(180, 50, 300, true, y -> y >= 120 && y <= 233));
        assertEquals(233, BotNavigationGraphProvider.findRopeLaunchBoundary(180, 50, 300, false, y -> y >= 120 && y <= 233));
        assertEquals(50, BotNavigationGraphProvider.findRopeLaunchBoundary(180, 50, 300, true, y -> true));
    }

    @Test
    void builtRopeExitsAreWindowedAndEveryWindowHeightLandsInTheTarget() {
        // A long rope between a high ledge and a floor, with a side platform to jump onto.
        MapleMap map = new MapleMap(910000999, 0, 0, 910000999, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        int[][] fh = {
                {1, -400, 400, 800, 400, 0, 0},   // floor
                {2, 60, 100, 240, 100, 0, 0},     // ledge at the rope top
                {3, 300, 260, 520, 260, 0, 0},    // side platform reachable by a jump off the rope
        };
        for (int[] f : fh) {
            Foothold foothold = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            foothold.setPrev(f[5]);
            foothold.setNext(f[6]);
            tree.insert(foothold);
        }
        map.setFootholds(tree);
        map.addRope(new Rope(250, 100, 390, false));
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);

        int ropeRegion = graph.findRopeRegionId(new Point(250, 300));
        assertTrue(ropeRegion >= 0);
        List<BotNavigationGraph.Edge> exits = graph.getOutgoing(ropeRegion).stream()
                .filter(e -> e.type == BotNavigationGraph.EdgeType.CLIMB && e.launchStepX != 0).toList();
        assertFalse(exits.isEmpty(), "rope has jump-off exits");
        assertTrue(exits.stream().anyMatch(BotNavigationGraph.Edge::hasLaunchYWindow), "exits carry Y windows");
        for (BotNavigationGraph.Edge e : exits) {
            for (int y : new int[]{e.launchMinY, (e.launchMinY + e.launchMaxY) / 2, e.launchMaxY}) {
                BotMovementManager.JumpLanding l = BotMovementManager.simulateRopeJumpLanding(map, new Point(250, y),
                        e.launchStepX, graph.movementProfile);
                if (graph.getRegion(e.toRegionId).isRopeRegion) {
                    continue;
                }
                assertTrue(l != null && graph.regionIdByFootholdId.get(l.foothold().getId()) == e.toRegionId,
                        "launch at y=" + y + " in window of " + e.fromRegionId + "->" + e.toRegionId + " lands in target");
            }
        }
        // One windowed edge per (target, direction): no per-anchor duplicates.
        long distinct = exits.stream().map(e -> e.toRegionId + ":" + e.launchStepX + ":" + e.launchMinY).distinct().count();
        assertEquals(distinct, exits.size());
    }
}
