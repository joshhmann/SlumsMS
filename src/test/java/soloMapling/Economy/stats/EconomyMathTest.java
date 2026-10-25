package soloMapling.Economy.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EconomyMathTest {

    @Test
    void giniCalculatesPerfectEqualityAsZero() {
        // When everyone has the exact same amount, Gini is 0.0
        assertEquals(0.0, EconomyMath.gini(new long[]{100, 100, 100, 100}), 0.0001);
        assertEquals(0.0, EconomyMath.gini(new long[]{1_000_000, 1_000_000}), 0.0001);
    }

    @Test
    void giniCalculatesExtremeInequality() {
        // In extreme inequality where 1 out of N has all wealth, Gini approaches (N-1)/N
        // For N=4, (4-1)/4 = 0.75
        assertEquals(0.75, EconomyMath.gini(new long[]{0, 0, 0, 100}), 0.0001);

        // For N=100, 99 have 0, 1 has 1000 -> 99/100 = 0.99
        long[] extreme = new long[100];
        extreme[99] = 1_000;
        assertEquals(0.99, EconomyMath.gini(extreme), 0.0001);
    }

    @Test
    void giniHandlesEmptyOrZeroTotalGracefully() {
        assertEquals(0.0, EconomyMath.gini(new long[]{}), 0.0001);
        assertEquals(0.0, EconomyMath.gini(null), 0.0001);
        assertEquals(0.0, EconomyMath.gini(new long[]{0, 0, 0}), 0.0001);
        assertEquals(0.0, EconomyMath.gini(new long[]{500}), 0.0001); // Single person = equality
    }

    @Test
    void giniCalculatesStandardDistribution() {
        // Hand-calculated: values [1, 2, 3, 4, 5], sum = 15, N = 5
        // 2 * (1*1 + 2*2 + 3*3 + 4*4 + 5*5) = 2 * (1 + 4 + 9 + 16 + 25) = 2 * 55 = 110
        // N * sum = 5 * 15 = 75
        // 110 / 75 = 1.46666...
        // (N+1)/N = 6/5 = 1.2
        // G = 1.46666... - 1.2 = 0.26666... = 4/15
        assertEquals(4.0 / 15.0, EconomyMath.gini(new long[]{1, 2, 3, 4, 5}), 0.0001);
    }

    @Test
    void percentilesCalculateCorrectly() {
        long[] data = new long[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        assertEquals(60, EconomyMath.percentile(data, 0.5)); // median round(0.5*9)=5 -> 60
        assertEquals(30, EconomyMath.percentile(data, 0.25)); // p25 round(0.25*9)=2 -> 30
        assertEquals(80, EconomyMath.percentile(data, 0.75)); // p75 round(0.75*9)=7 -> 80
        assertEquals(10, EconomyMath.percentile(data, 0.0));
        assertEquals(100, EconomyMath.percentile(data, 1.0));
    }

    @Test
    void topShareCalculatesPercentage() {
        // 10 people, total = 100
        // 9 people have 5 each (45 total)
        // 1 person has 55
        // top 10% (1 person) holds 55 / 100 = 55.0%
        long[] data = new long[]{5, 5, 5, 5, 5, 5, 5, 5, 5, 55};
        assertEquals(55.0, EconomyMath.topShare(data, 0.10), 0.001);

        // top 50% (5 people): 4 people with 5 (20) + 1 person with 55 (55) = 75 / 100 = 75.0%
        assertEquals(75.0, EconomyMath.topShare(data, 0.50), 0.001);

        // All equal: top 10% (1 person) holds 10.0%
        long[] equal = new long[]{10, 10, 10, 10, 10, 10, 10, 10, 10, 10};
        assertEquals(10.0, EconomyMath.topShare(equal, 0.10), 0.001);
    }

    @Test
    void levelBandCategorizesCorrectly() {
        assertEquals("1-30", EconomyMath.levelBand(1));
        assertEquals("1-30", EconomyMath.levelBand(30));
        assertEquals("31-70", EconomyMath.levelBand(31));
        assertEquals("31-70", EconomyMath.levelBand(70));
        assertEquals("71-120", EconomyMath.levelBand(71));
        assertEquals("71-120", EconomyMath.levelBand(120));
        assertEquals("121-200", EconomyMath.levelBand(121));
        assertEquals("121-200", EconomyMath.levelBand(200));
        assertEquals("121-200", EconomyMath.levelBand(250)); // over 200 clamped to top band
    }
}
