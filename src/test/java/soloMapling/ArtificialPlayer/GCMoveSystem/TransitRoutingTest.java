package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Ticket 03: the planner over walkable portals plus transit, on a small made-up world:
//
//   town A (1) -- dock (2) ==boat==> deck (3) ==ride==> far dock (4) -- far town (5)
//   town A (1) -- 6 -- 7 -- 8 -- far town (5)          a long walk (4 hops)
//   town A (1) ==tour==> island (9) ==home==> saved town
//
// Real map ids are used where the rebirth gates key on them.
class TransitRoutingTest {

    private static final Map<Integer, int[]> WORLD = Map.of(
            1, new int[]{2, 6},
            2, new int[]{1},
            4, new int[]{5},
            6, new int[]{7},
            7, new int[]{8},
            8, new int[]{5},
            5, new int[]{4});

    private static BotTransit.Edge board(int from, int to, int fare, int sec) {
        return new BotTransit.Edge("boat", from, to, BotTransit.Via.NPC, 1, -1, null, fare, -1, "Boats", true, sec,
                -1, null, null, null);
    }

    private static BotTransit.Edge ride(int from, int to, int sec) {
        return new BotTransit.Edge("boat", from, to, BotTransit.Via.RIDE, -1, -1, null, 0, -1, null, true, sec,
                -1, null, null, null);
    }

    @AfterEach
    void restore() {
        BotTransit.reload();
    }

    @Test
    void aShortBoatBeatsALongWalk() {
        BotTransit.install(List.of(board(2, 3, 100, 10), ride(3, 4, 10)));

        assertEquals(List.of(2, 3, 4, 5), GCWorldGraph.dijkstra(WORLD, 1, 5, 60, 0, true, -1));
    }

    @Test
    void aSlowBoatLosesToTheWalk() {
        BotTransit.install(List.of(board(2, 3, 100, 10), ride(3, 4, 600)));

        assertEquals(List.of(6, 7, 8, 5), GCWorldGraph.dijkstra(WORLD, 1, 5, 60, 0, true, -1));
    }

    @Test
    void aBotThatCantPayKeepsToFreeHops() {
        BotTransit.install(List.of(board(2, 3, 100, 10), ride(3, 4, 10)));

        assertEquals(List.of(6, 7, 8, 5), GCWorldGraph.dijkstra(WORLD, 1, 5, 60, 0, false, -1));
    }

    @Test
    void theHopCapStillApplies() {
        BotTransit.install(List.of());

        assertNull(GCWorldGraph.dijkstra(WORLD, 1, 5, 3, 0, true, -1));
        assertEquals(List.of(6, 7, 8, 5), GCWorldGraph.dijkstra(WORLD, 1, 5, 4, 0, true, -1));
    }

    @Test
    void routesNeverPassThroughARebirthGate() {
        // 211060100 is Lion King's Castle (rebirth 1): the short way through it is closed to a fresh bot.
        int lkc = 211060100;
        Map<Integer, int[]> world = Map.of(1, new int[]{lkc, 6}, lkc, new int[]{5}, 6, new int[]{7}, 7, new int[]{5});
        BotTransit.install(List.of());

        assertEquals(List.of(6, 7, 5), GCWorldGraph.dijkstra(world, 1, 5, 60, 0, true, -1));
        assertEquals(List.of(lkc, 5), GCWorldGraph.dijkstra(world, 1, 5, 60, 1, true, -1));
        assertEquals(List.of(lkc), GCWorldGraph.dijkstra(world, 1, lkc, 60, 0, true, -1),
                "the destination itself is the caller's call");
    }

    @Test
    void aBotInsideAGateMayWalkOut() {
        int lkcA = 211060100;
        int lkcB = 211060200;
        Map<Integer, int[]> world = Map.of(lkcA, new int[]{lkcB}, lkcB, new int[]{5});
        BotTransit.install(List.of());

        assertEquals(List.of(lkcB, 5), GCWorldGraph.dijkstra(world, lkcA, 5, 60, 0, true, -1));
    }

    @Test
    void theWorldTourOnlyGoesHomeToTheSavedTown() {
        BotTransit.Edge out = new BotTransit.Edge("world tour", 1, 9, BotTransit.Via.NPC, 2, -1, null, 3000, -1, null,
                true, 30, 0, null, "WORLDTOUR", null);
        // The way home is quick here (10 s), so out-and-back would be a tempting shortcut past the 80 s walk.
        BotTransit.Edge homeTo1 = new BotTransit.Edge("home", 9, 1, BotTransit.Via.NPC, 2, -1, null, 0, -1, null,
                true, 10, -1, null, null, "WORLDTOUR");
        BotTransit.Edge homeTo5 = new BotTransit.Edge("home", 9, 5, BotTransit.Via.NPC, 2, -1, null, 0, -1, null,
                true, 10, -1, null, null, "WORLDTOUR");
        BotTransit.install(List.of(out, homeTo1, homeTo5));
        Map<Integer, int[]> world = Map.of(1, new int[]{6}, 6, new int[]{7}, 7, new int[]{8}, 8, new int[]{5});

        // From town 1 the tour can't be used as a shortcut to town 5: going out saves town 1 as home.
        assertEquals(List.of(6, 7, 8, 5), GCWorldGraph.dijkstra(world, 1, 5, 60, 0, true, 5));
        // On the island, home is wherever the bot really came from.
        assertEquals(List.of(5), GCWorldGraph.dijkstra(world, 9, 5, 60, 0, true, 5));
        assertEquals(List.of(1, 6, 7, 8, 5), GCWorldGraph.dijkstra(world, 9, 5, 60, 0, true, 1));
    }

    @Test
    void aRideMapsWzPortalsAreNeverWalked() {
        // The Ellinia <-> Ereve ferry loop: the Victoria-bound ferry (13) has WZ portals into Orbis (20), but the server
        // only ever sets the bot down at the Ellinia station (10). Ellinia -> Orbis must take the Orbis ferry (14)
        // from Ereve, not "ride to Ereve, ride back, walk off the deck into Orbis".
        Map<Integer, int[]> world = Map.of(13, new int[]{20, 12}, 11, new int[]{10});
        BotTransit.install(List.of(board(10, 11, 1000, 10), ride(11, 12, 120),
                board(12, 13, 1000, 10), ride(13, 10, 120),
                board(12, 14, 1000, 10), ride(14, 20, 480)));

        assertEquals(List.of(11, 12, 14, 20), GCWorldGraph.dijkstra(world, 10, 20, 60, 0, true, -1));
        assertEquals(List.of(12, 13, 10), GCWorldGraph.dijkstra(world, 11, 10, 60, 0, true, -1),
                "aboard, the ride is the only way on - not the deck's own portal back to the station");
    }

    @Test
    void routeFareSumsThePaidHops() {
        BotTransit.install(List.of(board(2, 3, 5000, 10), ride(3, 4, 10)));

        assertEquals(5000, GCMovement.routeFare(1, List.of(2, 3, 4, 5), 100));
        assertEquals(0, GCMovement.routeFare(1, List.of(6, 7, 8, 5), 100));
    }
}
