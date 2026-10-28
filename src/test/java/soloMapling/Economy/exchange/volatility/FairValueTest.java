package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 1.6.1 volatility: the fair value formula, its clamps and its smoothing, from real flows. */
class FairValueTest {

    static final long NOW = 1_800_000_000_000L;

    @Test
    void theFormulaFollowsDemandOverSupplyBetweenFloorAndCeiling() {
        // floor x (1 + k x (demand - supply) / max(supply, 1)), k = 0.5
        assertEquals(500_000, FairValue.raw(500_000, 1_500_000, 100, 100, 0.5), "balanced: the floor");
        assertEquals(625_000, FairValue.raw(500_000, 1_500_000, 100, 150, 0.5), "50% more demand: +25%");
        assertEquals(500_000, FairValue.raw(500_000, 1_500_000, 200, 50, 0.5), "a glut: never under the floor");
        assertEquals(1_500_000, FairValue.raw(500_000, 1_500_000, 10, 500, 0.5), "a drought: capped at the ceiling");
        assertEquals(1_500_000, FairValue.raw(500_000, 1_500_000, 0, 5, 0.5), "no supply: max(supply, 1)");
        assertEquals(1, FairValue.raw(0, 0, 0, 0, 0.5), "never 0");
    }

    @Test
    void theEmaSmoothsHourByHour() {
        assertEquals(1_000, FairValue.ema(null, 1_000, 0.3), "the first value as it is");
        assertEquals(1_300, FairValue.ema(1_000L, 2_000, 0.3));
        assertEquals(1_000, FairValue.ema(1_000L, 1_000, 0.3));
    }

    @Test
    void updateReadsTheFlowsHourlyAndClampsTheSmoothedValue() {
        MarketFlows flows = new MarketFlows();
        FairValue.Sources src = new FairValue.Sources() {
            public long floor(String t) {
                return "LEAF".equals(t) ? 500_000 : 3_000;
            }

            public long ceiling(String t, Map<String, Long> fairs) {
                return "LEAF".equals(t) ? 1_200_000 : 0; // 0 -> 1.2x the floor at least
            }
        };
        FairValue f = new FairValue(0.5, 0.3);
        flows.add("LEAF", 100, 200, NOW);     // demand double the supply: raw = 500k x 1.5 = 750k
        assertEquals(true, f.maybeUpdate(NOW, flows, src));
        assertEquals(750_000, f.fair("LEAF"));
        assertEquals(false, f.maybeUpdate(NOW + 60_000, flows, src), "once an hour");
        flows.add("LEAF", 0, 1_000, NOW + 3_600_000L);   // a demand spike: raw hits the 1.2m ceiling
        f.maybeUpdate(NOW + 3_600_000L, flows, src);
        assertEquals(Math.round(750_000 + 0.3 * (1_200_000 - 750_000)), f.fair("LEAF"), "smoothed, not a jump");
        FairValue.Point p = f.point("ELIXIR");
        assertEquals(3_000, p.floor());
        assertEquals(3_600, p.ceiling(), "never under 1.2x the floor");
        assertEquals(3_000, p.fair());
        // the window is 24 h: old flows fall out
        f.update(NOW + 26 * 3_600_000L, flows, src);
        assertEquals(0.0, f.point("LEAF").supply());
    }

    @Test
    void flowsCountOnlyTheTickersItemsAndExchangeTakers() {
        MarketFlows flows = new MarketFlows();
        flows.add("LEAF", 3, 0, NOW);
        assertEquals("LEAF", MarketFlows.tickerOf(4001126));
        assertNull(MarketFlows.tickerOf(4000000));
        double[] w = flows.window("LEAF", NOW);
        assertEquals(3, w[0]);
        assertEquals(0, w[1]);
    }
}
