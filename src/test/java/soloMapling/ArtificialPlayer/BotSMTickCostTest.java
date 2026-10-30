package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157 inspect: a huge warm-up tick must not seed the fading tick-cost average (Website lane, 10-30). */
class BotSMTickCostTest {

    @Test
    void warmUpTicksDoNotSeedTheAverage() {
        long avg = 0;
        long[] ticks = {662_000_000L, 40_000_000L, 5_000_000L, 165_000L, 170_000L, 160_000L};
        for (int seen = 0; seen < ticks.length; seen++) {
            avg = BotSM.fadeTickCost(avg, ticks[seen], seen);
        }
        assertTrue(avg < 200_000L, "average after warm-up stays near the real cost, was " + avg);
    }

    @Test
    void afterWarmUpTheAverageFadesByAnEighth() {
        assertEquals(165_000L, BotSM.fadeTickCost(0, 165_000L, BotSM.WARMUP_TICKS));
        assertEquals(165_000L + (245_000L - 165_000L) / 8, BotSM.fadeTickCost(165_000L, 245_000L, BotSM.WARMUP_TICKS + 1));
    }
}
