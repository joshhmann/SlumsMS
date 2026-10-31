package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b109: the 12 maps the Website lane's dead-end check listed with unhandled pits on TEST (2026-10-31, graph v69),
 * on the real WZ geometry ({@code -Dwz-path}; skipped without it). Each pit is either a missing edge the builder now
 * makes (graph v70) or a real trap the planner now refuses and the pit rescue gets a bot out of.
 *
 * <ul>
 * <li>Missing edges: 105070200 / 105070400 r11 and 105090000 r21 (a 5-10 px notch under a 12 px step, walled on both
 * ends: its only anchors were wall-blocked, so it had no jump out); 610020006 r1 / r2 (a 2 px near-vertical foothold a
 * jump lands on: too narrow for a walk-off runway, so no drop off its low end); 410000350 r5 (the bottom floor's way
 * out is the touch portal up00, 44 px under the floor, so the portal edge found no ground under it).</li>
 * <li>Traps: 104000000 r48 / r50 (inside the crates at x 737-887), 220000100 r3 (the hollow inside the block),
 * 222020100 r18 (the sealed room under the floor at y 1981), 220060201 / 220060301 (below the level at y -316 /
 * 232 nothing climbs back up: rope tops end in the air and the rope jumps fall short).</li>
 * <li>Swim maps 230030001 and 230040400: swimmers do not use the ground graph; the map export marks them swim.</li>
 * </ul>
 */
class NavDeadEndMapsTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    private record Loaded(MapleMap map, BotNavigationGraph graph) {
    }

    private static Loaded load(int mapId) {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        return new Loaded(map, BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base()));
    }

    private static int regionOfFoothold(Loaded l, int footholdId) {
        Integer r = l.graph.regionIdByFootholdId.get(footholdId);
        assertNotNull(r, "foothold " + footholdId + " has a region on " + l.map.getId());
        return r;
    }

    private static Point on(Loaded l, int regionId, int x) {
        return l.graph.getRegion(regionId).pointAt(x);
    }

    private static Point portal(Loaded l, String name) {
        return l.map.getPortal(name).getPosition();
    }

    /* A walker (no movement skills) standing on the region gets to the exit portal. */
    private static void walksOut(Loaded l, int regionId, Point from, String exit) {
        NavProbe.Result r = NavProbe.probe(l.map, l.graph, regionId, from, portal(l, exit), 0);
        assertTrue(r.reachable(), l.map.getId() + " r" + regionId + " -> " + exit + ": " + r.lines());
        assertFalse(NavPits.isPit(l.graph, l.map, regionId), l.map.getId() + " r" + regionId + " is no pit any more");
    }

    // ── missing edges, now built ─────────────────────────────────────────────────────────────

    @Test
    void theWalledNotchesUnderASmallStepHaveTheirJumpOut() {
        for (int mapId : new int[]{105070200, 105070400}) {
            Loaded l = load(mapId);
            int notch = l.graph.findRegionId(l.map, new Point(-315, 415));
            assertEquals(5, l.graph.getRegion(notch).maxX - l.graph.getRegion(notch).minX, "fixture: the 5 px notch");
            walksOut(l, notch, new Point(-315, 415), "top00");
        }
        Loaded l = load(105090000);
        int notch = l.graph.findRegionId(l.map, new Point(537, 163));
        walksOut(l, notch, new Point(537, 163), "west00");
    }

    @Test
    void aNearVerticalFootholdIsLeftByWalkingOffItsLowEnd() {
        Loaded l = load(610020006);
        for (int fh : new int[]{147, 148}) {
            int r = regionOfFoothold(l, fh);
            BotNavigationGraph.Region region = l.graph.getRegion(r);
            assertTrue(region.maxX - region.minX <= 2, "fixture: a 2 px wide region");
            assertTrue(l.graph.getOutgoing(r).stream().anyMatch(e -> e.type == BotNavigationGraph.EdgeType.DROP),
                    "r" + r + " walks off its low end");
            walksOut(l, r, on(l, r, region.minX), "in00");
        }
    }

    @Test
    void theBottomFloorKeepsItsTouchPortalUp() {
        Loaded l = load(410000350);
        int floor = l.graph.findRegionId(l.map, new Point(-1000, 335));
        assertTrue(l.graph.getOutgoing(floor).stream().anyMatch(e -> e.type == BotNavigationGraph.EdgeType.PORTAL),
                "up00 (44 px under the floor) is the floor's way out");
        walksOut(l, floor, new Point(-1000, 335), "west00");
    }

    // ── real traps: refused, and a bot in one is put back ──────────────────────────────────────

    private static void trap(Loaded l, Point inside, String exit) {
        int r = l.graph.findRegionId(l.map, inside);
        assertTrue(r >= 0, "fixture: " + inside + " is on the graph of " + l.map.getId());
        assertTrue(NavPits.isTrap(l.graph, l.map, r), l.map.getId() + " r" + r + " is a trap");
        assertFalse(NavProbe.probe(l.map, l.graph, r, inside, portal(l, exit), 0).reachable(),
                "fixture: nothing walks out of " + l.map.getId() + " r" + r);
        assertFalse(NavProbe.probe(l.map, l.graph, portal(l, exit), inside, 0).reachable(),
                "the planner never routes into " + l.map.getId() + " r" + r);
        Point back = NavPits.rescuePoint(l.graph, l.map, inside);
        assertNotNull(back);
        int to = l.graph.findRegionId(l.map, back);
        walksOut(l, to, back, exit);
    }

    @Test
    void theHollowsInsideBlocksAndCratesAreTraps() {
        Loaded lith = load(104000000);
        trap(lith, on(lith, regionOfFoothold(lith, 98), 800), "east00");
        trap(lith, on(lith, regionOfFoothold(lith, 107), 750), "east00");
        Loaded ticketing = load(220000100);
        trap(ticketing, new Point(600, 77), "out00");
    }

    @Test
    void theSealedRoomUnderTheFloorIsATrap() {
        Loaded l = load(222020100);
        trap(l, new Point(0, 2282), "in01");
    }

    @Test
    void theLowerHalvesOfUnbalancedAndTwistedTimeAreTraps() {
        Loaded unbalanced = load(220060201);
        trap(unbalanced, new Point(-500, -316), "out00");
        trap(unbalanced, new Point(0, 1960), "out00");
        Loaded twisted = load(220060301);
        trap(twisted, new Point(-400, 818), "out00");
        trap(twisted, new Point(0, 1960), "out00");
        assertFalse(NavPits.isPit(unbalanced.graph, unbalanced.map,
                unbalanced.graph.findRegionId(unbalanced.map, new Point(-500, -507))), "the level above stays open");
    }

    @Test
    void swimMapsAreLeftToTheSwimmers() {
        for (int mapId : new int[]{230030001, 230040400}) {
            Loaded l = load(mapId);
            assertTrue(l.map.isSwim());
            assertEquals(Boolean.TRUE, NavDashboardData.geometry(l.map).get("swim"));
            for (BotNavigationGraph.Region r : l.graph.regions) {
                assertFalse(NavPits.isTrap(l.graph, l.map, r.id), mapId + " r" + r.id);
            }
        }
    }

    // ── nav/pits.json says exactly what the planner refuses ──────────────────────────────────────

    @Test
    void theExportedPitsMatchThePlannerOnEveryListedMap() {
        NavDashboardData.Dirs dirs = new NavDashboardData.Dirs(dir.resolve("v"), dir.resolve("deep"),
                dir.resolve("observed"), dir.resolve("learned"), dir.resolve("stuck"));
        for (int mapId : new int[]{104000000, 105070200, 105070400, 105090000, 220000100, 220060201, 220060301,
                222020100, 230030001, 230040400, 410000350, 610020006}) {
            Loaded l = load(mapId);
            Map<String, Object> g = NavDashboardData.graph(l.graph, dirs, System.currentTimeMillis());
            List<Integer> planner = NavPits.pits(l.graph, l.map).stream().boxed().toList();
            assertEquals(planner, NavDashboardData.pitRegions(mapId, g.get("regions"), g.get("edges"),
                    NavDashboardData.geometry(l.map)), "map " + mapId);
        }
    }
}
