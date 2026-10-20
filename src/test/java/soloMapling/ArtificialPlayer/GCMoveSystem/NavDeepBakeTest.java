package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import soloMapling.WzTestData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 04b. The bake test needs the real wz/ and is skipped without it. */
class NavDeepBakeTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @BeforeEach
    void scratch() {
        NavDeepOverlay.useRootForTest(dir);
        NavObservedEdges.useForTest(dir.resolve("observed"), null);
    }

    @AfterEach
    void real() {
        NavDeepOverlay.useRootForTest(null);
        NavObservedEdges.useForTest(null, null);
    }

    private static BotNavigationGraph.Edge jump(int from, int to, int minX, int maxX, int stepX, int cost) {
        return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.JUMP, new Point(minX, 50),
                new Point(to * 100, 0), minX, maxX, stepX, 0, 0, 0, 0, cost);
    }

    /** Three ropes side by side (region shapes do not matter here), with the given edges out of region 0. */
    private static BotNavigationGraph graph(List<BotNavigationGraph.Edge> fromZero) {
        List<BotNavigationGraph.Region> regions = List.of(new BotNavigationGraph.Region(0, 0, 0, 100, false),
                new BotNavigationGraph.Region(1, 100, 0, 100, false), new BotNavigationGraph.Region(2, 200, 0, 100, false));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        Map<Integer, List<BotNavigationGraph.Edge>> out = new HashMap<>();
        out.put(0, new ArrayList<>(fromZero));
        return new BotNavigationGraph(990000000, BotNavigationGraphProvider.GRAPH_VERSION, BotMovementProfile.base(),
                regions, byId, Map.of(5, 0), out, Set.of());
    }

    @Test
    void onlyMovesTheLiveGraphLacksAreKept() {
        BotNavigationGraph.Edge liveJump = jump(0, 1, 100, 140, 8, 500);
        BotNavigationGraph live = graph(List.of(liveJump));

        BotNavigationGraph.Edge sameSpot = jump(0, 1, 150, 160, 8, 400);       // within 16px of the live window
        BotNavigationGraph.Edge otherSpot = jump(0, 1, 400, 420, 8, 600);      // same move, far away: a new spot
        BotNavigationGraph.Edge otherWay = jump(0, 1, 110, 120, -8, 700);      // same spot, jumping the other way
        BotNavigationGraph.Edge newLink = jump(0, 2, 300, 300, 0, 900);        // regions the live graph never joined
        BotNavigationGraph.Edge walk = new BotNavigationGraph.Edge(0, 1, BotNavigationGraph.EdgeType.WALK,
                new Point(0, 50), new Point(100, 50), 0, 0, 0, 0, 0, 0, 0, 10);
        BotNavigationGraph deep = graph(List.of(liveJump, sameSpot, otherSpot, otherWay, newLink, walk));

        List<BotNavigationGraph.Edge> added = NavDeepBake.missing(live, deep);

        assertEquals(List.of(otherSpot, otherWay, newLink), added);
    }

    @Test
    void aMoveKeepsAtMostThreeLaunchSpotsCheapestFirst() {
        BotNavigationGraph live = graph(List.of(jump(0, 1, 0, 10, 8, 500)));
        List<BotNavigationGraph.Edge> spots = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            spots.add(jump(0, 1, i * 100, i * 100 + 10, 8, 1000 - i * 100)); // farther spots are cheaper
        }
        List<BotNavigationGraph.Edge> added = NavDeepBake.missing(live, graph(spots));

        assertEquals(NavDeepBake.MAX_PER_MOVE - 1, added.size(), "the live spot counts toward the cap");
        assertEquals(List.of(spots.get(4), spots.get(3)), added);
    }

    @Test
    void thePlannerSeesTheCurrentGenerationAndNothingForAnotherLayout() throws Exception {
        BotNavigationGraph graph = graph(List.of(jump(0, 1, 100, 140, 8, 500)));
        BotNavigationGraph.Edge baked = jump(0, 2, 300, 320, 8, 700);
        assertEquals(1, NavObservedEdges.outgoing(graph, 0).size(), "no bake yet: the graph's own edges");

        writeGeneration("20261019-030000", graph, List.of(baked));
        NavDeepBake.swapCurrent(dir, "20261019-030000");
        NavDeepOverlay.useRootForTest(dir); // skip the once-a-minute recheck

        List<BotNavigationGraph.Edge> edges = NavObservedEdges.outgoing(graph, 0);
        assertEquals(2, edges.size());
        BotNavigationGraph.Edge loaded = edges.get(1);
        assertEquals(List.of(0, 2, "JUMP", 300, 50, 200, 0, 300, 320, 50, 50, 8, 0, 0, 0, 0, 700, 700, 700),
                NavDeepOverlay.toJson(loaded), "every field survives the file");
        assertSame(NavObservedEdges.outgoing(graph, 1), NavObservedEdges.outgoing(graph, 1), "nothing baked there");

        // The same map with another region layout (a rebuilt graph that no longer matches) ignores the file.
        BotNavigationGraph other = new BotNavigationGraph(graph.mapId, graph.version, graph.movementProfile,
                graph.regions, graph.regionsById, Map.of(5, 1), graph.outgoingByRegionId, Set.of());
        assertEquals(1, NavObservedEdges.outgoing(other, 0).size());

        // A new generation replaces the old set; rolling back restores it.
        writeGeneration("20261020-030000", graph, List.of());
        NavDeepBake.swapCurrent(dir, "20261020-030000");
        NavDeepOverlay.useRootForTest(dir);
        assertEquals(1, NavObservedEdges.outgoing(graph, 0).size());
        assertEquals("20261019-030000", NavDeepBake.rollback(dir));
        NavDeepOverlay.useRootForTest(dir);
        assertEquals(2, NavObservedEdges.outgoing(graph, 0).size());
    }

    @Test
    void pruningKeepsTheNewestGenerationsAndTheCurrentOne() throws Exception {
        BotNavigationGraph graph = graph(List.of());
        for (String gen : List.of("20261016-030000", "20261017-030000", "20261018-030000", "20261019-030000")) {
            writeGeneration(gen, graph, List.of());
        }
        NavDeepBake.swapCurrent(dir, "20261016-030000"); // rolled far back
        NavDeepBake.prune(dir, 2);

        assertEquals(List.of("20261016-030000", "20261018-030000", "20261019-030000"), NavDeepBake.generations(dir));
        assertEquals("20261016-030000", NavDeepBake.currentDir(dir).getFileName().toString());
        assertNull(NavDeepBake.rollback(dir), "nothing older than the oldest");
    }

    private void writeGeneration(String gen, BotNavigationGraph graph, List<BotNavigationGraph.Edge> edges) throws Exception {
        Files.createDirectories(dir.resolve(gen));
        Files.writeString(dir.resolve(gen).resolve(NavDeepOverlay.keyOf(graph) + ".json"),
                NavDeepOverlay.fileContent(graph, edges));
    }

    /** Henesys at deep density: the same regions as the live graph, and only real additions. */
    @Test
    void aDeepBakeOfARealMapFitsItsLiveGraph() throws Exception {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph live = BotNavigationGraphProvider.buildGraphWithDensity(map, BotMovementProfile.base(),
                BotNavigationGraphProvider.SampleDensity.LIVE);
        BotNavigationGraph deep = BotNavigationGraphProvider.buildGraphWithDensity(map, BotMovementProfile.base(),
                BotNavigationGraphProvider.SampleDensity.DEEP);
        assertEquals(NavDeepOverlay.signature(live), NavDeepOverlay.signature(deep));
        int liveEdges = live.outgoingByRegionId.values().stream().mapToInt(List::size).sum();
        int deepEdges = deep.outgoingByRegionId.values().stream().mapToInt(List::size).sum();
        assertTrue(deepEdges >= liveEdges, "denser sampling finds at least as much: " + deepEdges + " vs " + liveEdges);

        Path gen = dir.resolve("20261019-030000.partial");
        Files.createDirectories(gen);
        List<NavDeepBake.KeyResult> results = NavDeepBake.bakeMap(map.getId(), List.of(BotMovementProfile.base()),
                gen, Long.MAX_VALUE);
        assertEquals(1, results.size());
        assertNull(results.get(0).error());
        Path file = gen.resolve(NavDeepOverlay.keyOf(live) + ".json");
        assertTrue(Files.isRegularFile(file));
        for (BotNavigationGraph.Edge edge : NavDeepBake.missing(live, deep)) {
            assertFalse(edge.type == BotNavigationGraph.EdgeType.WALK || edge.type == BotNavigationGraph.EdgeType.PORTAL);
            assertNotNull(live.getRegion(edge.fromRegionId));
            assertNotNull(live.getRegion(edge.toRegionId));
            assertFalse(edge.fromRegionId == edge.toRegionId);
        }
    }
}
