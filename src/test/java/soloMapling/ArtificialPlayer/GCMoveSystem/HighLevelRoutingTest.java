package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 03 follow-up: the planner rules the high-level hops add - a script's rebirth check on an edge, and the hyper
// rock's virtual jump (into its areas from anywhere, out of them only to a home town, one jump per route, never in a
// returned route). Small made-up worlds; real ids where BotMapGates keys on them.
class HighLevelRoutingTest {

    private static BotTransit.Edge npc(int from, int to, int minRebirths) {
        return new BotTransit.Edge("teleporter", from, to, BotTransit.Via.NPC, 9010002, -1, null, 0, -1, null, true,
                30, -1, null, null, null, 0, minRebirths);
    }

    private static BotTransit.HyperRock rock(List<Integer> into, int outMin, int outMax, List<Integer> home) {
        return new BotTransit.HyperRock(300, into, List.<int[]>of(new int[]{outMin, outMax}), home);
    }

    @AfterEach
    void restore() {
        BotTransit.reload();
        GCWorldGraph.clearRouteCache();
    }

    @Test
    void anEdgeWhoseScriptWantsThreeRebirthsIsClosedToABotWithTwo() {
        // Henesys's teleporter to Twilight Perion town: the map itself needs 2 rebirths, the NPC's menu 3.
        int perion = 273000000;
        Map<Integer, int[]> world = Map.of(1, new int[]{});
        BotTransit.install(List.of(npc(1, perion, 3)));

        assertNull(GCWorldGraph.dijkstra(world, 1, perion, 60, 2, true, -1));
        assertEquals(List.of(perion), GCWorldGraph.dijkstra(world, 1, perion, 60, 3, true, -1));
    }

    @Test
    void theRockJumpsIntoItsAreaAndTheRouteNamesOnlyRealMaps() {
        int haven = 310070000;
        int hill = 310070120;
        Map<Integer, int[]> world = Map.of(1, new int[]{2}, haven, new int[]{hill});
        BotTransit.install(List.of());
        BotTransit.installHyperRock(rock(List.of(haven), 310000000, 311000000, List.of(1)));

        List<Integer> route = GCWorldGraph.dijkstra(world, 1, hill, 60, 0, true, -1);

        assertEquals(List.of(haven, hill), route);
        assertTrue(route.stream().allMatch(m -> m > 0));
        assertEquals(0, GCMovement.routeFare(1, route, 100), "the rock is free");
    }

    @Test
    void theRockLeavesItsAreaOnlyForAHomeTown() {
        int hill = 310070120;
        Map<Integer, int[]> world = Map.of(hill, new int[]{}, 1, new int[]{2}, 2, new int[]{});
        BotTransit.install(List.of());
        BotTransit.installHyperRock(rock(List.of(310070000), 310000000, 311000000, List.of(1)));

        assertEquals(List.of(1), GCWorldGraph.dijkstra(world, hill, 1, 60, 0, true, -1));
        assertEquals(List.of(1, 2), GCWorldGraph.dijkstra(world, hill, 2, 60, 0, true, -1));
        assertNull(GCWorldGraph.dijkstra(world, 2, 1, 60, 0, true, -1), "no jump home from outside the area");
    }

    @Test
    void oneJumpPerRouteSoTheRockIsNeverAShortcutBetweenTowns() {
        // Town 1 to town 5 is a long walk; in-and-straight-out through the rock's area would be two quick jumps.
        int haven = 310070000;
        Map<Integer, int[]> world = Map.of(1, new int[]{6}, 6, new int[]{7}, 7, new int[]{8}, 8, new int[]{5},
                haven, new int[]{});
        BotTransit.install(List.of());
        BotTransit.installHyperRock(rock(List.of(haven), 310000000, 311000000, List.of(5)));

        assertEquals(List.of(6, 7, 8, 5), GCWorldGraph.dijkstra(world, 1, 5, 60, 0, true, -1));
    }

    @Test
    void theRockRespectsTheRebirthGatesOnTheWay() {
        // A jump target inside the Twilight Perion range is closed to a fresh bot as a stepping stone.
        int gated = 273010000;
        Map<Integer, int[]> world = Map.of(1, new int[]{}, gated, new int[]{9});
        BotTransit.install(List.of());
        BotTransit.installHyperRock(rock(List.of(gated), 273000000, 274000000, List.of(1)));

        assertNull(GCWorldGraph.dijkstra(world, 1, 9, 60, 0, true, -1));
        assertEquals(List.of(gated, 9), GCWorldGraph.dijkstra(world, 1, 9, 60, 2, true, -1));
    }

    @Test
    void routesMayRunPastTheOldSixtyHopCap() {
        assertTrue(GCWorldGraph.MAX_ROUTE_HOPS >= 120, "Henesys to Omega Sector alone is 61 hops");
    }
}
