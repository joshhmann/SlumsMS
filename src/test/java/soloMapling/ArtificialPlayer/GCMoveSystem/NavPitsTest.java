package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.dashboard.DashboardMapExport;
import soloMapling.server.Json;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    // ── b109: traps, platforms with no way back to the map's exits ──────────────────────────────

    /* A ledge with the map's exit on it, whose right end drops into a 200 px deep well no jump gets out of. */
    private static final int[][] WELL = {
            {1, -600, 300, -100, 300},  // the ledge, with the exit portal at x -500
            {2, -100, 300, -100, 500},  // the well's left wall, down from the ledge's lip
            {3, -100, 500, 100, 500},   // the well's floor
            {4, 100, 500, 100, 300},    // the well's right wall
    };

    private static MapleMap wellMap(int id) {
        MapleMap map = map(id, 900, WELL);
        server.maps.GenericPortal exit = new server.maps.GenericPortal(Portal.MAP_PORTAL);
        exit.setId(0);
        exit.setName("out00");
        exit.setPosition(new Point(-500, 300));
        exit.setTargetMapId(100000000);
        exit.setTarget("sp");
        map.addPortal(exit);
        return map;
    }

    @Test
    void aWellWithNoWayBackToTheExitIsATrapThePlannerNeverRoutesInto() {
        MapleMap map = wellMap(910000960);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int ledge = graph.regionIdByFootholdId.get(1);
        int well = graph.regionIdByFootholdId.get(3);
        assertTrue(graph.getOutgoing(ledge).stream().anyMatch(e -> e.toRegionId == well),
                "fixture: the builder links the ledge to the well (the dead-end check's \"Pit\")");
        assertTrue(graph.getOutgoing(well).stream().noneMatch(e -> e.toRegionId == ledge && !e.isMovementSkill()),
                "fixture: nothing but a movement skill leaves the well");

        assertTrue(NavPits.isTrap(graph, map, well));
        assertTrue(NavPits.isPit(graph, map, well));
        assertFalse(NavPits.isPit(graph, map, ledge));
        assertFalse(NavProbe.probe(map, graph, new Point(-300, 300), new Point(0, 500), 0).reachable(),
                "the planner never routes into the well");
        assertFalse(GCMovement.reachableRegions(map, -300, 300).contains(well), "nor does walk reachability");
    }

    @Test
    void aMapWithNoPortalToAnotherMapHasNoTraps() {
        MapleMap map = map(910000961, 900, WELL); // no portals at all: nothing to measure a way out against
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        assertFalse(NavPits.isTrap(graph, map, graph.regionIdByFootholdId.get(3)));
    }

    @Test
    void aSwimMapHasNoTraps() {
        MapleMap map = wellMap(910000962);
        map.setSwim(true);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        assertFalse(NavPits.isTrap(graph, map, graph.regionIdByFootholdId.get(3)), "swimmers swim out");
        assertEquals(Boolean.TRUE, NavDashboardData.geometry(map).get("swim"));
    }

    @Test
    void aBotAlreadyInATrapIsPutBackOnThePlatformWithTheExit() {
        MapleMap map = wellMap(910000963);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(7311);
        chr.setMap(map);
        chr.setPosition(new Point(0, 500));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.navGraph = graph;
        entry.physX = 0;
        entry.physY = 500;

        assertFalse(GCMovementDriver.tickPitRescue(entry), "first seen in the trap: the clock starts");
        assertFalse(GCMovementDriver.tickPitRescue(entry), "not yet");
        entry.pitSinceMs -= GCMovementDriver.PIT_RESCUE_MS;
        assertTrue(GCMovementDriver.tickPitRescue(entry), "put back after PIT_RESCUE_MS");
        Point at = chr.getPosition();
        int region = graph.findRegionId(map, at);
        assertEquals(graph.regionIdByFootholdId.get(1), region, "on the ledge with the exit: " + at);
        assertEquals(new Point(-100, 300), at, "the nearest point of it");
        assertTrue(NavProbe.probe(map, graph, at, new Point(-500, 300), 0).reachable());
        assertFalse(GCMovementDriver.tickPitRescue(entry), "out: nothing more to do");
    }

    @Test
    void theExportedPitsAddTheTrapsWithTheSameRule() {
        MapleMap map = wellMap(910000964);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Map<String, Object> g = NavDashboardData.graph(graph, dirs(), System.currentTimeMillis());
        List<Integer> expected = NavPits.pits(graph, map).stream().boxed().toList();
        assertEquals(List.of(graph.regionIdByFootholdId.get(3)), expected);
        assertEquals(expected, NavDashboardData.pitRegions(910000964, g.get("regions"), g.get("edges"),
                NavDashboardData.geometry(map)));
        Map<String, Object> swim = new java.util.HashMap<>(NavDashboardData.geometry(map));
        swim.put("swim", true);
        assertEquals(List.of(), NavDashboardData.pitRegions(910000964, g.get("regions"), g.get("edges"), swim));
    }

    /* The dead-end check's region_at, ported: under or just above the point within the span +-20 px, else nearest. */
    @Test
    void regionAtPlacesAPointLikeTheDeadEndCheck() {
        List<NavPits.Row> rows = List.of(new NavPits.Row(1, false, 0, 100, 200, 100),
                new NavPits.Row(2, false, 0, 300, 200, 300), new NavPits.Row(3, true, 50, 0, 50, 300));
        assertEquals(1, NavPits.regionAt(rows, 100, 90), "the platform under it");
        assertEquals(1, NavPits.regionAt(rows, 100, 130), "a platform up to 40 px above it still counts");
        assertEquals(2, NavPits.regionAt(rows, 100, 150), "further above: the one under it");
        assertEquals(1, NavPits.regionAt(rows, 215, 90), "the span widened by 20 px");
        assertEquals(2, NavPits.regionAt(rows, 400, 290), "outside every span: the nearest");
    }

    // ── nav/pits.json (the Website lane's dead-end check) ──────────────────────────────────────

    @TempDir
    Path dir;

    private NavDashboardData.Dirs dirs() {
        return new NavDashboardData.Dirs(dir.resolve("v68"), dir.resolve("deep"), dir.resolve("observed"),
                dir.resolve("learned"), dir.resolve("stuck"));
    }

    @Test
    void theExportedPitsAreExactlyTheRegionsThePlannerRefuses() {
        MapleMap map = map(910000952, 450, FOOTHOLDS);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Map<String, Object> g = NavDashboardData.graph(graph, dirs(), System.currentTimeMillis());
        Map<String, Object> geo = NavDashboardData.geometry(map);
        List<Integer> expected = NavPits.pits(graph, map).stream().boxed().toList();
        assertEquals(List.of(graph.regionIdByFootholdId.get(3)), expected);
        assertEquals(expected, NavDashboardData.pitRegions(910000952, g.get("regions"), g.get("edges"), geo));
        assertEquals(List.of(), NavDashboardData.pitRegions(910000952, g.get("regions"), g.get("edges"), Map.of()),
                "no view bounds, no pits");
        assertEquals(List.of(), NavDashboardData.pitRegions(910000953, g.get("regions"), g.get("edges"),
                NavDashboardData.geometry(map(910000953, 700, FOOTHOLDS))));
    }

    @Test
    void theMapExportWritesPitsJsonOnlyWhenItChanges() throws Exception {
        int id = 910000954;
        MapleMap map = map(id, 450, FOOTHOLDS);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        NavDashboardData.Dirs dirs = dirs();
        Files.createDirectories(dirs.graphs());
        String key = NavEdgeLearning.bookKey(graph);
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(dirs.graphs().resolve(key + ".bin")))) {
            o.writeObject(graph);
        }
        Path out = dir.resolve("dashboard");
        DashboardMapExport.Options opts = DashboardMapExport.Options.parse(new String[]{"--out=" + out,
                "--nav-reports=" + dir.resolve("reports")});
        DashboardMapExport.Geometry geometry = mapId -> NavDashboardData.geometry(map);
        long now = System.currentTimeMillis();

        assertEquals(1, DashboardMapExport.run(opts, dirs, geometry, Map.of(), now).mapsWritten());
        Path pits = out.resolve("nav/pits.json");
        Map<?, ?> doc = (Map<?, ?>) Json.parse(Files.readString(pits));
        assertEquals(1, ((Number) doc.get("version")).intValue());
        Map<?, ?> maps = (Map<?, ?>) doc.get("maps");
        assertEquals(List.of(String.valueOf(id)), new ArrayList<>(maps.keySet()));
        List<Integer> regions = ((List<?>) maps.get(String.valueOf(id))).stream().map(v -> ((Number) v).intValue()).toList();
        assertEquals(List.of(graph.regionIdByFootholdId.get(3)), regions);

        Files.setLastModifiedTime(pits, java.nio.file.attribute.FileTime.fromMillis(1_000));
        assertEquals(0, DashboardMapExport.run(opts, dirs, geometry, Map.of(), now).mapsWritten(), "nothing changed");
        assertEquals(1_000, Files.getLastModifiedTime(pits).toMillis(), "pits.json kept from the map state, not rewritten");
        assertEquals(Json.write(doc), Files.readString(pits));
    }
}
