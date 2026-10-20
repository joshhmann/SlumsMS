package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* Graph v60/v61/v64/v65: teleport and flash-jump edges with X launch windows, and their per-job gating (ported from
 * nutnnut/Cosmic, AGPL-3.0. Credit: NutNNut; the job gate is ours). */
class BotMovementSkillEdgesTest {

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

    /* A floor and a ledge 120px above it with no rope: walkers cannot get up, a mage can blink up. */
    private static MapleMap ledgeAboveFloor() {
        return map(910000901, new int[][]{
                {1, -600, 400, 600, 400},
                {2, -100, 280, 100, 280},
        });
    }

    /* Two platforms at the same height with a 330px gap: too wide for a jump, within a flash jump's reach. */
    private static MapleMap wideGap() {
        return map(910000902, new int[][]{
                {1, -800, 300, -165, 300},
                {2, 165, 300, 800, 300},
                {3, -1200, 900, 1200, 900}, // floor far below so a missed arc still lands somewhere
        });
    }

    @Test
    void jobsMapToTheirStockSkills() {
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.maskForJob(211)); // F/P mage
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.maskForJob(222)); // I/L arch mage
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.maskForJob(230)); // cleric
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.maskForJob(1210)); // blaze wizard 2nd
        assertEquals(BotNavigationGraph.SKILL_FLASH_JUMP, NavMovementSkills.maskForJob(411)); // hermit
        assertEquals(BotNavigationGraph.SKILL_FLASH_JUMP, NavMovementSkills.maskForJob(412)); // night lord
        assertEquals(BotNavigationGraph.SKILL_FLASH_JUMP, NavMovementSkills.maskForJob(1410)); // night walker 2nd
        for (int job : new int[]{0, 100, 200, 300, 400, 410, 420, 500, 1200, 1400, 2112, 2210}) {
            assertEquals(0, NavMovementSkills.maskForJob(job), "job " + job + " plans walk-only");
        }
    }

    @Test
    void skillEdgesAreUsableOnlyUnderTheirMask() {
        BotNavigationGraph.Edge tele = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.TELEPORT,
                new Point(0, 0), new Point(150, 0), 0, 0, 0, 0, 0, 150);
        BotNavigationGraph.Edge fj = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.FLASH_JUMP,
                new Point(0, 0), new Point(300, 0), 5, 0, 0, 0, 0, 600);
        BotNavigationGraph.Edge jump = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(0, 0), new Point(60, 0), 5, 0, 0, 0, 0, 600);
        assertFalse(NavMovementSkills.allows(tele, 0));
        assertFalse(NavMovementSkills.allows(fj, 0));
        assertTrue(NavMovementSkills.allows(jump, 0));
        assertTrue(NavMovementSkills.allows(tele, BotNavigationGraph.SKILL_TELEPORT));
        assertFalse(NavMovementSkills.allows(fj, BotNavigationGraph.SKILL_TELEPORT));
        assertTrue(NavMovementSkills.allows(fj, BotNavigationGraph.SKILL_FLASH_JUMP));
        assertTrue(tele.isMovementSkill() && fj.isMovementSkill() && !jump.isMovementSkill());
    }

    @Test
    void skillRouteNeedsARealSaving() {
        assertFalse(BotNavigationManager.preferSkillRoute(1000, 100), "short trips never bother");
        assertTrue(BotNavigationManager.preferSkillRoute(Integer.MAX_VALUE, 900), "only way there");
        assertFalse(BotNavigationManager.preferSkillRoute(3000, Integer.MAX_VALUE));
        assertTrue(BotNavigationManager.preferSkillRoute(8000, 7000), "long trip: a 1/8 saving is enough");
        assertFalse(BotNavigationManager.preferSkillRoute(2000, 1800), "medium trip: 10% is not");
    }

    @Test
    void teleportEdgesAreWindowedAndEveryWindowXBlinksIntoTheTarget() {
        MapleMap map = ledgeAboveFloor();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int floor = graph.regionIdByFootholdId.get(1);
        int ledge = graph.regionIdByFootholdId.get(2);
        List<BotNavigationGraph.Edge> up = graph.getOutgoing(floor).stream()
                .filter(e -> e.type == BotNavigationGraph.EdgeType.TELEPORT && e.toRegionId == ledge).toList();
        assertFalse(up.isEmpty(), "floor -> ledge teleport exists");
        assertTrue(up.stream().anyMatch(e -> e.launchMaxX > e.launchMinX), "teleport edges carry an X window");
        for (BotNavigationGraph.Edge e : up) {
            for (int x = e.launchMinX; x <= e.launchMaxX; x += Math.max(1, (e.launchMaxX - e.launchMinX) / 8)) {
                Point origin = graph.getRegion(floor).pointAt(x);
                Point dest = BotNavigationManager.teleportDestination(map, origin, e);
                assertNotNull(dest, "blink from x=" + x + " resolves");
                assertEquals(ledge, (int) graph.regionIdByFootholdId.get(BotPhysicsEngine.findGroundFoothold(map,
                        new Point(dest.x, dest.y - 1)).getId()), "blink from x=" + x + " lands on the ledge");
            }
        }
        // Walkers don't see it: no non-skill way up, so a walk-only search finds nothing.
        Point start = new Point(-400, 400);
        assertTrue(BotNavigationManager.runSearch(graph, map, start, floor, ledge, new Point(0, 280), null,
                true, false, 0L, 0).path().isEmpty());
        List<BotNavigationGraph.Edge> magePath = BotNavigationManager.runSearch(graph, map, start, floor, ledge,
                new Point(0, 280), null, true, false, 0L, BotNavigationGraph.SKILL_TELEPORT).path();
        assertFalse(magePath.isEmpty());
        assertEquals(BotNavigationGraph.EdgeType.TELEPORT, magePath.getLast().type);
        // ...and walk reachability (grind spot finding) ignores it too.
        assertFalse(graph.hasInterRegionEdge(floor, ledge));
    }

    @Test
    void teleportLandingPrefersTheSameLevelAndDownGoesFurthest() {
        MapleMap map = map(910000903, new int[][]{
                {1, -300, 400, 300, 400},  // origin floor
                {2, 100, 350, 300, 350},   // a higher diagonal platform at the blink x
                {3, -300, 460, 300, 460},  // lower platforms below origin
                {4, -300, 530, 300, 530},
        });
        Point origin = new Point(0, 400);
        assertEquals(new Point(150, 400), BotPhysicsEngine.teleportLanding(map, origin, 1, 0, 150, 75),
                "horizontal blink stays on the same level over the higher diagonal one");
        assertEquals(530, BotPhysicsEngine.teleportLanding(map, origin, 0, 1, 150, 75).y,
                "down-teleport snaps to the furthest platform within range");
        assertNull(BotPhysicsEngine.teleportLanding(map, origin, 0, -1, 150, 75), "nothing above");
    }

    @Test
    void flashJumpEdgesOnlyWhereAPlainJumpFallsShort() {
        MapleMap map = wideGap();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int left = graph.regionIdByFootholdId.get(1);
        int right = graph.regionIdByFootholdId.get(2);
        assertFalse(graph.getOutgoing(left).stream()
                        .anyMatch(e -> e.type == BotNavigationGraph.EdgeType.JUMP && e.toRegionId == right),
                "a plain jump cannot clear the gap");
        List<BotNavigationGraph.Edge> fj = graph.getOutgoing(left).stream()
                .filter(e -> e.type == BotNavigationGraph.EdgeType.FLASH_JUMP && e.toRegionId == right).toList();
        assertFalse(fj.isEmpty(), "a flash jump clears it");
        for (BotNavigationGraph.Edge e : fj) {
            assertTrue(e.launchStepX > 0);
            for (int x : new int[]{e.launchMinX, (e.launchMinX + e.launchMaxX) / 2, e.launchMaxX}) {
                Point from = graph.getRegion(left).pointAt(x);
                BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateFlashJumpLanding(map, from,
                        e.launchStepX, graph.movementProfile);
                assertNotNull(landing);
                assertEquals(right, (int) graph.regionIdByFootholdId.get(landing.foothold().getId()),
                        "flash jump from x=" + x + " lands across the gap");
            }
        }
        // A thief plans over the gap; a mage-only or walk-only bot does not take the dash.
        Point start = new Point(-400, 300);
        List<BotNavigationGraph.Edge> thief = BotNavigationManager.runSearch(graph, map, start, left, right,
                new Point(400, 300), null, true, false, 0L, BotNavigationGraph.SKILL_FLASH_JUMP).path();
        assertTrue(thief.stream().anyMatch(e -> e.type == BotNavigationGraph.EdgeType.FLASH_JUMP));
        List<BotNavigationGraph.Edge> walker = BotNavigationManager.runSearch(graph, map, start, left, right,
                new Point(400, 300), null, true, false, 0L, BotNavigationGraph.SKILL_TELEPORT).path();
        assertTrue(walker.stream().noneMatch(e -> e.type == BotNavigationGraph.EdgeType.FLASH_JUMP));
    }

    @Test
    void learningKeysKeepSkillEdgesApartFromJumps() {
        BotNavigationGraph.Edge fj = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.FLASH_JUMP,
                new Point(10, 20), new Point(400, 20), 0, 30, 5, 0, 0, 0, 0, 600);
        BotNavigationGraph.Edge jump = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(10, 20), new Point(400, 20), 0, 30, 5, 0, 0, 0, 0, 600);
        assertFalse(NavEdgeLearning.edgeKey(fj).equals(NavEdgeLearning.edgeKey(jump)));
        assertEquals("FLASH_JUMP:1>2:x0..30:y20..20:s5:10,20>400,20", NavEdgeLearning.edgeKey(fj));
    }
}
