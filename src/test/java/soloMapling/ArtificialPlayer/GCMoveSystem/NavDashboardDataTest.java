package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.dashboard.DashboardMapExport;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 35: the nav data the dashboard draws, and the map export that writes it. */
class NavDashboardDataTest {

    @TempDir
    Path dir;

    private NavDashboardData.Dirs dirs;
    private BotNavigationGraph graph;
    private BotNavigationGraph.Edge liveJump;
    private final long now = System.currentTimeMillis();
    private static final int MAP = 990000000;
    private static final String KEY = MAP + "-s100-j100";

    private static BotNavigationGraph.Edge jump(int from, int to, int minX, int maxX, int cost) {
        return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.JUMP, new Point(minX, 50),
                new Point(to * 100, 0), minX, maxX, 8, 0, 0, 0, 0, cost);
    }

    @BeforeEach
    void files() throws Exception {
        dirs = new NavDashboardData.Dirs(dir.resolve("v68"), dir.resolve("deep"), dir.resolve("observed"),
                dir.resolve("learned"), dir.resolve("stuck"));
        List<BotNavigationGraph.Region> regions = List.of(new BotNavigationGraph.Region(0, 0, 0, 100, false),
                new BotNavigationGraph.Region(1, 100, 0, 100, false), new BotNavigationGraph.Region(2, 200, 0, 100, true));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        liveJump = jump(0, 1, 100, 140, 500);
        Map<Integer, List<BotNavigationGraph.Edge>> out = new HashMap<>();
        out.put(0, new ArrayList<>(List.of(liveJump)));
        graph = new BotNavigationGraph(MAP, BotNavigationGraphProvider.GRAPH_VERSION, BotMovementProfile.base(),
                regions, byId, Map.of(5, 0), out, Set.of());

        Files.createDirectories(dirs.graphs());
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(dirs.graphs().resolve(KEY + ".bin")))) {
            o.writeObject(graph);
        }
        Path gen = dirs.deepRoot().resolve("20261019-030000");
        Files.createDirectories(gen);
        Files.writeString(gen.resolve(KEY + ".json"), NavDeepOverlay.fileContent(graph, List.of(jump(0, 2, 300, 320, 700))));
        Files.createSymbolicLink(dirs.deepRoot().resolve("current"), Path.of("20261019-030000"));

        Files.createDirectories(dirs.observed());
        Files.writeString(dirs.observed().resolve(MAP + ".json"), Json.write(Map.of("version", graph.version,
                "edges", List.of(List.of(1, 2, 8, 150, 50, 200, 0, 600)))));

        Files.createDirectories(dirs.learned());
        Files.writeString(dirs.learned().resolve(KEY + ".json"), Json.write(Map.of("version", graph.version,
                "edges", Map.of(NavEdgeLearning.edgeKey(liveJump), List.of(6, 0, now)))));

        Files.createDirectories(dirs.stuck());
        Map<String, Object> spot = new LinkedHashMap<>();
        spot.put("map", MAP);
        spot.put("kind", "GAVE_UP");
        spot.put("x", 125);
        spot.put("y", 75);
        spot.put("edge", NavStuckLog.describe(liveJump));
        spot.put("graph", KEY);
        spot.put("count", 4);
        spot.put("first", now);
        spot.put("last", now);
        Files.writeString(NavStuckLog.fileFor(dirs.stuck(), NavDashboardData.todayUtc()),
                Json.write(Map.of("day", NavDashboardData.todayUtc().toString(), "spots", List.of(spot))));
    }

    @Test
    void aGraphCarriesItsBakedPlayerLearnedAndPenalisedEdges() {
        Map<String, Object> g = NavDashboardData.graph(KEY, dirs, now);
        List<?> edges = (List<?>) g.get("edges");
        assertEquals(3, edges.size());
        List<?> own = (List<?>) edges.get(0);
        assertEquals(List.of(0, 1, "JUMP"), own.subList(0, 3));
        assertEquals(NavDashboardData.SOURCE_GRAPH, own.get(10));
        assertEquals(NavEdgeLearning.BAD_FACTOR, ((Number) own.get(11)).doubleValue(), "6 failures, no success");
        assertEquals(NavDashboardData.SOURCE_DEEP, ((List<?>) edges.get(1)).get(10));
        assertEquals(NavDashboardData.SOURCE_PLAYERS, ((List<?>) edges.get(2)).get(10));
        assertEquals(1, g.get("deepEdges"));
        assertEquals(1, g.get("playerEdges"));
        assertEquals(1, g.get("penalised"));
        assertEquals(List.of(2, 2, 200, 0, 200, 100), ((List<?>) g.get("regions")).get(2), "a ladder region");
    }

    @Test
    void aBakeForAnotherRegionLayoutIsLeftOut() throws Exception {
        BotNavigationGraph other = new BotNavigationGraph(MAP, graph.version, graph.movementProfile, graph.regions,
                graph.regionsById, Map.of(5, 1), graph.outgoingByRegionId, Set.of());
        Files.writeString(dirs.deepRoot().resolve("current").resolve(KEY + ".json"),
                NavDeepOverlay.fileContent(other, List.of(jump(0, 2, 300, 320, 700))));
        assertEquals(0, NavDashboardData.graph(KEY, dirs, now).get("deepEdges"));
        assertNull(NavDashboardData.graph(MAP + "-s105-j100", dirs, now), "no cache file, no graph");
    }

    @Test
    void theMapExportWritesEachMapOnceUntilItsInputsChange() throws Exception {
        Path out = dir.resolve("dashboard");
        DashboardMapExport.Options o = DashboardMapExport.Options.parse(new String[]{"--out=" + out,
                "--nav-reports=" + dir.resolve("reports")});
        DashboardMapExport.Geometry geometry = id -> Map.of("footholds", List.of(List.of(1, 0, 50, 300, 50, 0)),
                "ropes", List.of(), "portals", List.of());
        Map<Integer, String[]> names = Map.of(MAP, new String[]{"Test Road", "Test Field"});

        DashboardMapExport.Summary first = DashboardMapExport.run(o, dirs, geometry, names, now);
        assertEquals(1, first.mapsWritten());
        assertEquals(1, first.graphsWritten());

        Map<?, ?> map = (Map<?, ?>) Json.parse(Files.readString(out.resolve("nav/maps/" + MAP + ".json")));
        assertEquals("Test Field", map.get("name"));
        List<?> spot = (List<?>) ((List<?>) map.get("stuck")).get(0);
        assertEquals(List.of(125, 75, "GAVE_UP", 4), spot.subList(0, 4).stream().map(v -> v instanceof Number n ? n.intValue() : v).toList());
        assertTrue(Files.exists(out.resolve("nav/graphs/" + KEY + ".json")));

        Map<?, ?> index = (Map<?, ?>) Json.parse(Files.readString(out.resolve("nav/index.json")));
        List<?> row = (List<?>) ((List<?>) index.get("maps")).get(0);
        assertEquals(MAP, ((Number) row.get(0)).intValue());
        assertEquals(4, ((Number) row.get(8)).intValue(), "stuck events");

        Map<?, ?> learning = (Map<?, ?>) Json.parse(Files.readString(out.resolve("nav/learning.json")));
        Map<?, ?> today = (Map<?, ?>) learning.get("today");
        assertEquals(1, ((Number) today.get("deepEdges")).intValue());
        assertEquals(1, ((Number) today.get("playerEdges")).intValue());
        assertEquals(1, ((Number) today.get("penalised")).intValue());
        assertEquals(4, ((Number) today.get("stuckToday")).intValue());
        assertEquals(Map.of("GAVE_UP", 4L), Map.of("GAVE_UP", ((Number) ((Map<?, ?>) learning.get("stuckTodayByKind")).get("GAVE_UP")).longValue()));

        DashboardMapExport.Summary again = DashboardMapExport.run(o, dirs, geometry, names, now);
        assertEquals(0, again.mapsWritten(), "nothing changed");
        assertEquals(0, again.graphsWritten());

        Files.writeString(dirs.observed().resolve(MAP + ".json"), Json.write(Map.of("version", graph.version,
                "edges", List.of(List.of(1, 2, 8, 150, 50, 200, 0, 600), List.of(0, 2, 8, 90, 50, 200, 0, 650)))));
        Files.setLastModifiedTime(dirs.observed().resolve(MAP + ".json"),
                java.nio.file.attribute.FileTime.fromMillis(now + 5_000));
        DashboardMapExport.Summary changed = DashboardMapExport.run(o, dirs, geometry, names, now);
        assertEquals(1, changed.graphsWritten(), "a new player-learned jump");
        assertEquals(1, changed.mapsWritten());
    }

    @Test
    void portalsCarryTheirTargetPortalNameAsTheSixthField() {
        server.maps.MapleMap map = new server.maps.MapleMap(910000920, 0, 0, 910000920, 1.0f);
        server.maps.GenericPortal door = new server.maps.GenericPortal(2);
        door.setName("east00");
        door.setPosition(new Point(500, 300));
        door.setTargetMapId(100000000);
        door.setTarget("west00");
        door.setId(1);
        server.maps.GenericPortal spawn = new server.maps.GenericPortal(0);
        spawn.setName("sp");
        spawn.setPosition(new Point(0, 300));
        spawn.setTargetMapId(999999999);
        spawn.setId(0);
        map.addPortal(door);
        map.addPortal(spawn);

        List<?> portals = (List<?>) NavDashboardData.geometry(map).get("portals");
        assertEquals(2, portals.size());
        Map<String, List<?>> byName = new HashMap<>();
        for (Object p : portals) {
            byName.put((String) ((List<?>) p).get(0), (List<?>) p);
        }
        assertEquals(List.of("east00", 2, 500, 300, 100000000, "west00"), byName.get("east00"));
        assertEquals(6, byName.get("sp").size());
        assertEquals("", byName.get("sp").get(5), "no target portal: an empty name");
        assertEquals(List.of("east00", 2, 500, 300, 100000000), byName.get("east00").subList(0, 5),
                "the first five fields are as before");
    }
}
