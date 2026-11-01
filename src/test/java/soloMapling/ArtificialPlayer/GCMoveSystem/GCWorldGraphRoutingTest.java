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
    private static final int GOLLUX_ABDOMEN = 863010240;       // client-crash; the Gollux legs point at it
    private static final int HEART_TREE = 863010000;           // the map the player reroute lands on
    private static final int ROSE_GARDEN = 211080000;          // loads fine
    private static final int ROSE_GARDEN_DEPTH = 211080100;    // client-crash, behind the entrance's east00

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @Test
    void aBlockedTargetIsNotAnEdge() {
        Map<Integer, int[]> graph = new HashMap<>();
        graph.put(SIDE_MAP, new int[]{GOLLUX_ABDOMEN, FOUNTAIN_SQUARE});
        graph.put(FOUNTAIN_SQUARE, new int[]{SIDE_MAP, GOLLUX_ABDOMEN, STORAGE_CAVE});
        graph.put(GOLLUX_ABDOMEN, new int[]{FOUNTAIN_SQUARE});
        graph.put(STORAGE_CAVE, new int[]{FOUNTAIN_SQUARE});

        GCWorldGraph.dropMissingTargets(graph);

        assertArrayEquals(new int[]{FOUNTAIN_SQUARE}, graph.get(SIDE_MAP), "the way into Gollux's abdomen is gone");
        assertArrayEquals(new int[]{SIDE_MAP, STORAGE_CAVE}, graph.get(FOUNTAIN_SQUARE));
        assertArrayEquals(new int[]{FOUNTAIN_SQUARE}, graph.get(GOLLUX_ABDOMEN), "a map keeps its own way out");
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

        // the legs whose own portal leads into the crash map have no step into it...
        for (int leg : new int[]{863010210, 863010230, 863010300, 863010400}) {
            for (int next : GCWorldGraph.neighbors(leg)) {
                assertFalse(next == GOLLUX_ABDOMEN, "the planner has no step from " + leg + " into the crash map");
            }
        }
        for (int next : GCWorldGraph.neighbors(ROSE_GARDEN)) {
            assertFalse(next == ROSE_GARDEN_DEPTH, "the planner has no step past the Rose Garden entrance");
        }
        // ...and the maps the player is rerouted to are still in the graph.
        assertTrue(graph.containsKey(HEART_TREE), "the Gollux reroute destination is a map bots can still use");
        assertTrue(graph.containsKey(ROSE_GARDEN), "the Rose Garden entrance is a map bots can still use");
        assertTrue(graph.containsKey(FOUNTAIN_SQUARE), "Fountain Square is a map bots can still use");
    }
}
