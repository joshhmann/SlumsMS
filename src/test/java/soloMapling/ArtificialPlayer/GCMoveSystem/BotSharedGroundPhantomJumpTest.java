package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/*
 * Graph v67 (ported from nutnnut/Cosmic BotSharedGroundPhantomJumpTest, AGPL-3.0. Credit: NutNNut).
 * Map 600020100: region 73 is a ramp whose flat FOOT overlaps flat region 97 at the same coordinate. The builder
 * used to author JUMP r97->r73 landing on ground r97 already covers; the client never switches chains there, so
 * the jump can never change region and bots oscillated against it.
 */
class BotSharedGroundPhantomJumpTest {

    private static final int MAP = 600020100;

    /* {id, x1,y1, x2,y2, prev, next}. A (flat) is one chain at y=156; B (ramp) is a separate chain whose foot
     * (fh10) coincides with A at y=156. B is inserted first so the foothold tree resolves the shared ground to B. */
    private static final int[][] OVERLAP_FH = {
            {10, -1340, 156, -1255, 156, 0, 11},
            {11, -1255, 156, -1080, 96, 10, 12},
            {12, -1080, 96, -900, -24, 11, 13},
            {13, -900, -24, -720, -24, 12, 0},
            {50, -1475, 156, -1260, 156, 0, 51},
            {51, -1260, 156, 865, 156, 50, 0},
    };

    /* Two flat platforms at the SAME height with a real 60px gap: a jump the builder must still author. */
    private static final int[][] GAP_FH = {
            {300, -600, 156, -360, 156, 0, 0},
            {301, -300, 156, 0, 156, 0, 0},
    };

    private static MapleMap buildMap(int[][] footholds) {
        MapleMap map = new MapleMap(MAP, 0, 0, MAP, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : footholds) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    @Test
    void surfaceCoversPointIsExact() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(OVERLAP_FH));
        BotNavigationGraph.Region a = graph.getRegion(graph.regionIdByFootholdId.get(50));
        assertTrue(a.surfaceCoversPoint(-1300, 156, 0), "A's flat surface passes through (-1300,156)");
        assertFalse(a.surfaceCoversPoint(-1300, 155, 0), "1px off is a distinct platform on top, NOT shared");
        assertFalse(a.surfaceCoversPoint(2000, 156, 0), "outside A's x-extent is not covered");
    }

    @Test
    void overlapFormsTwoSeparateRegions() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(OVERLAP_FH));
        Integer a = graph.regionIdByFootholdId.get(50);
        Integer b = graph.regionIdByFootholdId.get(10);
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b);
    }

    @Test
    void noBallisticEdgeLandsOnSharedGround() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(OVERLAP_FH));
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge e : graph.getOutgoing(region.id)) {
                if (e.type == BotNavigationGraph.EdgeType.JUMP) {
                    assertFalse(region.surfaceCoversPoint(e.endPoint.x, e.endPoint.y, 0),
                            "phantom " + e.type + " r" + region.id + "->r" + e.toRegionId
                                    + " lands at " + e.endPoint + " on its own source surface");
                }
            }
        }
    }

    @Test
    void genuineSameHeightGapJumpIsStillAuthored() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(GAP_FH));
        int left = graph.regionIdByFootholdId.get(300);
        int right = graph.regionIdByFootholdId.get(301);
        assertTrue(graph.getOutgoing(left).stream()
                        .anyMatch(e -> e.type == BotNavigationGraph.EdgeType.JUMP && e.toRegionId == right),
                "a same-height jump across a real gap must NOT be pruned by the shared-ground guard");
    }

    @Test
    void chainContinuityKeepsRegionAcrossSharedGround() {
        MapleMap map = buildMap(OVERLAP_FH);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementState bot = new BotMovementState(mock(Character.class), null);
        bot.navGraph = graph;
        int aId = graph.regionIdByFootholdId.get(50);
        assertEquals(aId, BotNavigationManager.resolveCurrentRegionId(graph, bot, map, new Point(-1400, 156)),
                "starts on region A");
        assertEquals(aId, BotNavigationManager.resolveCurrentRegionId(graph, bot, map, new Point(-1300, 156)),
                "keeps chain A across shared ground instead of flipping to the overlapping ramp B");
    }
}
