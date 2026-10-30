package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * TEST health sweep (2026-10-25): ~90% of same-pixel bot pairs sat within 2 px of a mob spawn point (map 541010040
 * at 554,-1825) because every grinder walked to the idle mob's exact x. Each bot now keeps a personal offset.
 */
class StandOffsetTest {

    static final int SPAWN = 554, REACH = 35;

    @Test
    void offsetIsStableBoundedAndHasBothSides() {
        boolean left = false, right = false;
        for (int id = 1; id < 400; id++) {
            int o = StandOffset.offsetFor(id, REACH);
            assertEquals(o, StandOffset.offsetFor(id, REACH), "stable per bot");
            assertTrue(Math.abs(o) >= StandOffset.MIN_PX && Math.abs(o) <= REACH);
            left |= o < 0;
            right |= o > 0;
        }
        assertTrue(left && right);
        assertEquals(0, StandOffset.offsetFor(7, 0));
    }

    @Test
    void botsOnTheSameSpawnStandOnDistinctPixelsWithinReach() {
        // Eight bots on one spawn with a 35 px reach: there is room for only ~5 spots 16 px apart, so later bots may
        // share a spot (by design they keep their own once every candidate is held). What matters: everyone stays in
        // reach, nobody is put on the spawn pixel itself, and the group spreads over several distinct spots.
        Set<Integer> held = new HashSet<>();
        for (int id = 100; id < 108; id++) {
            int x = StandOffset.standX(id, SPAWN, 0, 2000, REACH, c -> true, c -> held.stream().anyMatch(h -> Math.abs(h - c) < 16));
            assertTrue(Math.abs(x - SPAWN) <= REACH, "still within reach: " + x);
            assertTrue(x != SPAWN, "not on the spawn pixel: " + x);
            held.add(x);
        }
        assertTrue(held.size() >= 4, "spread over several spots: " + held);
        assertTrue(Math.abs(StandOffset.standX(100, SPAWN, 0, 2000, REACH, c -> true, c -> false) - SPAWN) >= StandOffset.MIN_PX);
    }

    @Test
    void aSingleBotStillReachesItsTarget() {
        for (int id = 1; id < 200; id++) {
            int x = StandOffset.standX(id, SPAWN, 0, 2000, REACH, c -> true, c -> false);
            assertTrue(Math.abs(x - SPAWN) <= REACH);
        }
    }

    @Test
    void neverLeavesThePlatformOrTheLeash() {
        // platform spans x 540..570 only; spawn near its left edge
        for (int id = 1; id < 200; id++) {
            int x = StandOffset.standX(id, 545, 530, 575, REACH, c -> c >= 540 && c <= 570, c -> c % 3 == 0);
            assertTrue(x >= 540 && x <= 570, "on platform: " + x);
        }
        // nothing walkable around the mob but its own pixel: stand there
        assertEquals(545, StandOffset.standX(5, 545, 530, 575, REACH, c -> c == 545, c -> false));
    }

    // 1.6.2 b085 (t-cb-2: 14 of 40 grinders near or over their mob): bots stood 10-40 px off a mob's centre, inside
    // most mobs' bodies (a Red Snail is ~40 px wide, a Pig ~60).

    @Test
    void aMeleeBotStandsPastTheBodyEdgeAndStillInReach() {
        int half = 30, reach = 72;   // a Pig; a sword's basic reach (90 px x 0.8)
        int[] band = StandOffset.band(half, reach, false, 120);
        for (int id = 1; id < 300; id++) {
            int x = StandOffset.standX(id, SPAWN, 0, 2000, band[0], band[1], c -> true, c -> false);
            int dx = Math.abs(x - SPAWN);
            assertTrue(dx >= half + StandOffset.MELEE_CLEAR_PX - 1 && dx <= reach, "dx " + dx);
        }
        int old = Math.abs(StandOffset.standX(1, SPAWN, 0, 2000, Math.min(StandOffset.MAX_PX, reach / 2), c -> true, c -> false) - SPAWN);
        assertTrue(old <= half, "the old pick was inside the body: " + old);
    }

    @Test
    void archersAndMagesHoldTheirDistance() {
        int[] band = StandOffset.band(20, 360, true, 150);   // a bow: 450 px reach x 0.8
        for (int id = 1; id < 300; id++) {
            int dx = Math.abs(StandOffset.standX(id, SPAWN, 0, 2000, band[0], band[1], c -> true, c -> false) - SPAWN);
            assertTrue(dx >= 150 && dx <= 360, "dx " + dx);
        }
        // a reach shorter than the comfort distance: the reach wins
        assertArrayEquals(new int[]{100, 100}, StandOffset.band(20, 100, true, 150));
        // a mob wider than the reach: the reach wins
        assertArrayEquals(new int[]{72, 72}, StandOffset.band(120, 72, false, 120));
    }

    @Test
    void aNarrowLedgeStillGetsASpot() {
        int[] band = StandOffset.band(20, 72, false, 120);
        // the ledge is 545..575 around a mob at 560: no spot 42+ px away fits, so the furthest that does
        int x = StandOffset.standX(9, 560, 0, 2000, band[0], band[1], c -> c >= 545 && c <= 575, c -> false);
        assertTrue(x >= 545 && x <= 575 && Math.abs(x - 560) >= 12, "x " + x);
    }
}
