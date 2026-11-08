package tools;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0nk/Cosmic#261 (upstream test 215dc4229): rand is meant to be uniform over [lbound, ubound]. The old expression
 * added lbound before the (int) cast, and Java truncates a negative double toward zero, so with a negative lbound the
 * value 0 got roughly double the mass and the lower bound was never reached. Only the chaos scroll calls it with a
 * negative bound: chscrollRandomizedStat uses rand(-range, range).
 */
class RandomizerTest {

    private static final int FULL = 11;          // -5..5
    private static final int ROLLS = 200_000;

    @Test
    void randShouldIncludeEntireRange() {
        Map<Integer, Integer> seen = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            seen.merge(Randomizer.rand(-5, 5), 1, Integer::sum);
        }

        for (int v = -5; v <= 5; v++) {
            assertTrue(seen.containsKey(v), "rand(-5, 5) never produced " + v);
        }
        assertFalse(seen.containsKey(-6));
        assertFalse(seen.containsKey(6));
    }

    @Test
    void everyValueOfANegativeBoundRangeIsAboutEquallyLikely() {
        int[] counts = new int[FULL];
        for (int i = 0; i < ROLLS; i++) {
            counts[Randomizer.rand(-5, 5) + 5]++;
        }

        double expected = (double) ROLLS / FULL;
        for (int v = -5; v <= 5; v++) {
            double got = counts[v + 5];
            assertTrue(Math.abs(got - expected) <= expected * 0.10,
                    "rand(-5, 5) gave " + v + " " + (int) got + " times in " + ROLLS
                            + " rolls; a uniform roll gives " + (int) expected + " plus or minus 10%");
        }
    }

    @Test
    void nonNegativeBoundsKeepTheirRangeAndDistribution() {
        // rand(lo, hi) with lo >= 0 was already fair: the cast only ever saw non-negative doubles, so the port must
        // not change its results.
        int rolls = 120_000;
        int[] counts = new int[6];
        for (int i = 0; i < rolls; i++) {
            counts[Randomizer.rand(0, 5)]++;
        }

        double expected = (double) rolls / counts.length;
        for (int v = 0; v <= 5; v++) {
            assertTrue(Math.abs(counts[v] - expected) <= expected * 0.10,
                    "rand(0, 5) gave " + v + " " + counts[v] + " times in " + rolls
                            + " rolls; a uniform roll gives " + (int) expected + " plus or minus 10%");
        }

        // A shifted positive range must not leak outside its bounds either.
        for (int i = 0; i < 50_000; i++) {
            int v = Randomizer.rand(3, 7);
            assertTrue(v >= 3 && v <= 7, "rand(3, 7) gave " + v);
        }
    }
}
