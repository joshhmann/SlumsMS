package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

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
}
