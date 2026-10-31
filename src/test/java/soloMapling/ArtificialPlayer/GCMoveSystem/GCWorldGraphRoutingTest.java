package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapRouting;
import soloMapling.WzTestData;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * s-maproute: the bots' world graph and the player's routing table are one policy. A bot may not plan a step into a
 * map a player cannot enter (Map.wz has no image, or the client crashes on it): the graph drops the edge, so the
 * planner never sends a bot somewhere the player path reroutes away from.
 */
class GCWorldGraphRoutingTest {

    private static final int FOUNTAIN_SQUARE = 410000300;
    private static final int STORAGE_CAVE = 410000310;
    private static final int SIDE_MAP = 410000325;
    private static final int RAMPART_RESIDENCES = 410000350;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @Test
    void aBlockedTargetIsNotAnEdge() {
        Map<Integer, int[]> graph = new HashMap<>();
        graph.put(SIDE_MAP, new int[]{RAMPART_RESIDENCES, FOUNTAIN_SQUARE});
        graph.put(FOUNTAIN_SQUARE, new int[]{SIDE_MAP, RAMPART_RESIDENCES, STORAGE_CAVE});
        graph.put(RAMPART_RESIDENCES, new int[]{FOUNTAIN_SQUARE});
        graph.put(STORAGE_CAVE, new int[]{FOUNTAIN_SQUARE});

        GCWorldGraph.dropMissingTargets(graph);

        assertArrayEquals(new int[]{FOUNTAIN_SQUARE}, graph.get(SIDE_MAP), "the way into Rampart Residences is gone");
        assertArrayEquals(new int[]{SIDE_MAP, STORAGE_CAVE}, graph.get(FOUNTAIN_SQUARE));
        assertArrayEquals(new int[]{FOUNTAIN_SQUARE}, graph.get(RAMPART_RESIDENCES), "a map keeps its own way out");
    }

    @Test
    void theRealGraphHasNoEdgeIntoABlockedMap() {
        WzTestData.assumePresent("Map.wz");
        Map<Integer, int[]> graph = GCWorldGraph.get();
        assertTrue(graph.size() > 1000, "graph built: " + graph.size());

        int edges = 0;
        for (Map.Entry<Integer, int[]> e : graph.entrySet()) {
            for (int target : e.getValue()) {
                edges++;
                assertFalse(MapRouting.blocked(target), "map " + e.getKey() + " still has an edge into blocked " + target);
            }
        }
        assertTrue(edges > 1000, "edges: " + edges);

        for (int next : GCWorldGraph.neighbors(SIDE_MAP)) {
            assertFalse(next == RAMPART_RESIDENCES, "the planner has no step into the crash map");
        }
        // the side map's only portal led into the crash map, so it is now a dead end for bots - and the map the
        // player is rerouted to is still in the graph.
        assertTrue(graph.containsKey(FOUNTAIN_SQUARE), "the reroute destination is a map bots can still use");
    }
}
