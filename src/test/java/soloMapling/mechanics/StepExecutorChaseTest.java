package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 (KPQ pace, b153/b204): a fighting bot gives up on a mob it gets no closer to. On TEST bot-only runs, bots
 * hung on KPQ stage 1's x=-437 rope or stood under the y=-340 platform for whole runs, re-sent to the same Ligator.
 */
class StepExecutorChaseTest {

    @Test
    void noCloserInThreeSecondsSkipsTheMobForTenThenItMayBePickedAgain() {
        StepExecutor.Chase c = new StepExecutor.Chase();
        assertFalse(c.walking(7, 5_000, 0));
        assertFalse(c.walking(7, 5_000, 1_000));
        assertFalse(c.walking(7, 4_990, 2_000), "10 ms of walk is not progress");
        assertTrue(c.walking(7, 4_990, 3_000), "no 20 px closer in 3 s: give up");
        assertTrue(c.skipped(7, 3_001));
        assertTrue(c.skipped(7, 12_999));
        assertFalse(c.skipped(7, 13_000), "10 s later it may be picked again");
    }

    @Test
    void aBotThatKeepsGettingCloserNeverGivesUpAndReachResetsTheClock() {
        StepExecutor.Chase c = new StepExecutor.Chase();
        int cost = 20_000;
        for (long t = 0; t <= 60_000; t += 250) {
            assertFalse(c.walking(3, cost, t), "at " + t);
            cost -= 60; // a climb: about 30 px a beat, slow but steady
        }
        c.inReach(3, 61_000);
        assertFalse(c.walking(3, 900, 62_000));
        assertFalse(c.walking(3, 900, 64_000), "the clock started again at the walk, not at the old best");
    }

    @Test
    void theSwingSeeingNoMobSkipsItAtOnce() {
        StepExecutor.Chase c = new StepExecutor.Chase();
        c.inReach(9, 0);
        c.skip(9, 100);
        assertTrue(c.skipped(9, 5_000));
        assertFalse(c.skipped(8, 5_000));
    }
}
