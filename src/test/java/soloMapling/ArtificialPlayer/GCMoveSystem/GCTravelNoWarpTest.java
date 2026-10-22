package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 59 (review 2026-10-22): a walk a player asked for in chat never warps. The gate only promises a walk over
 * walkable portals, and while the hold lasts the bot's trips give up instead of falling back to a warp.
 */
class GCTravelNoWarpTest {

    @Test
    void onlyAllWalkablePortalHopsCount() {
        Map<Integer, int[]> portals = Map.of(1, new int[]{2}, 2, new int[]{1, 3}, 3, new int[]{2});
        assertTrue(GCMovement.walkableHops(1, List.of(2, 3), portals));
        assertFalse(GCMovement.walkableHops(1, List.of(3), portals), "1 -> 3 is a cab or a warp, not a walk");
        assertFalse(GCMovement.walkableHops(4, List.of(1), portals), "a disconnected map");
        assertTrue(GCMovement.walkableHops(1, List.of(), portals), "already there");
    }

    @Test
    void forbiddenWarpsLastTheHoldThenLapse() throws Exception {
        int bot = 1_000_888_001;
        assertFalse(GCTravel.warpsForbidden(bot));
        GCTravel.forbidWarps(bot, 60_000);
        assertTrue(GCTravel.warpsForbidden(bot));
        GCTravel.forbidWarps(bot, 0);
        Thread.sleep(5);
        assertFalse(GCTravel.warpsForbidden(bot));
    }
}
