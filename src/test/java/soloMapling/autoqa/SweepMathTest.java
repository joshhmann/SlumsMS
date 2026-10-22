package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotChannels;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepMathTest {

    @Test
    void aMovingBotInOneSpotPastTheLimitIsStuck() {
        SweepMath.StuckTracker t = new SweepMath.StuckTracker();
        assertEquals(0, t.observe(7, 100, 10, 20, true, 0));
        assertEquals(60_000, t.observe(7, 100, 12, 21, true, 60_000)); // jitter inside the box
        assertEquals(240_000, t.observe(7, 100, 10, 20, true, 240_000));
        assertEquals(0, t.observe(7, 100, 60, 20, true, 250_000));     // it moved: the clock restarts
        assertEquals(10_000, t.observe(7, 100, 60, 20, true, 260_000));
    }

    @Test
    void restingBotsAndMapChangesAreNotStuck() {
        SweepMath.StuckTracker t = new SweepMath.StuckTracker();
        t.observe(1, 100, 0, 0, true, 0);
        assertEquals(0, t.observe(1, 100, 0, 0, false, 600_000));      // resting: forgotten
        assertEquals(0, t.observe(1, 100, 0, 0, true, 700_000));
        assertEquals(0, t.observe(1, 200, 0, 0, true, 900_000));       // same x/y on another map: moved
        t.retain(Set.of());
        assertEquals(0, t.size());
    }

    @Test
    void clumpsFindTheDensestGroupFirstAndCountEachBotOnce() {
        List<SweepMath.Spot> spots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            spots.add(new SweepMath.Spot(i, 100 + i * 3, 50));          // 6 bots within 15 px
        }
        for (int i = 0; i < 4; i++) {
            spots.add(new SweepMath.Spot(10 + i, 900 + i * 5, 50));     // 4 bots: under the minimum
        }
        spots.add(new SweepMath.Spot(99, 2000, 50));
        List<SweepMath.Clump> c = SweepMath.clumps(spots, 30, 5);
        assertEquals(1, c.size());
        assertEquals(6, c.get(0).members().size());
        assertTrue(SweepMath.clumps(spots, 30, 7).isEmpty());
        assertEquals(2, SweepMath.clumps(spots, 30, 4).size());
    }

    @Test
    void spreadOutBotsAreNoClump() {
        List<SweepMath.Spot> spots = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            spots.add(new SweepMath.Spot(i, i * 50, 0));
        }
        assertTrue(SweepMath.clumps(spots, 30, 3).isEmpty());
    }

    @Test
    void theChannelSplitFollowsTheDirectorsWeights() {
        double[] exp = BotChannels.shares("100,85,50,30,20", 5);
        int[] good = {350, 300, 175, 105, 70};
        assertTrue(SweepMath.channelSplit(good, exp, 0.15, 40).stream().noneMatch(SweepMath.ChannelShare::off));
        int[] lopsided = {900, 50, 25, 15, 10};
        List<SweepMath.ChannelShare> s = SweepMath.channelSplit(lopsided, exp, 0.15, 40);
        assertTrue(s.get(0).off());
        int[] emptyCh3 = {350, 300, 0, 105, 70};
        assertTrue(SweepMath.channelSplit(emptyCh3, exp, 0.15, 40).get(2).off());
    }

    @Test
    void aChannelThatShouldBeQuietMustHaveNoBots() {
        double[] exp = BotChannels.shares("100,85,50,30,0", 5);
        assertEquals(0.0, exp[4]);
        int[] counts = {380, 320, 190, 110, 3};
        assertTrue(SweepMath.channelSplit(counts, exp, 0.15, 40).get(4).off());
    }

    @Test
    void tooFewBotsAreNotJudged() {
        double[] exp = BotChannels.shares("100,85,50,30,20", 5);
        assertFalse(SweepMath.channelSplit(new int[]{10, 0, 0, 0, 0}, exp, 0.15, 40).stream()
                .anyMatch(SweepMath.ChannelShare::off));
    }

    @Test
    void anErrorSpikeIsManyTimesTheRecentMedian() {
        SweepMath.ErrorRate r = new SweepMath.ErrorRate(12, 20, 3.0);
        for (int i = 0; i < 6; i++) {
            assertFalse(r.add(8));
        }
        assertFalse(r.add(20));   // 20 < 3 x 8
        assertTrue(r.add(100));   // 100 > 24 and >= 20
        assertFalse(r.add(15));   // under the minimum
    }

    @Test
    void errorLinesAreLog4jErrorsAndUncaughtExceptions() {
        assertTrue(SweepMath.isErrorLine("12:00:01.123 [pool-3] ERROR n.s.Server - boom"));
        assertTrue(SweepMath.isErrorLine("Exception in thread \"main\" java.lang.NullPointerException"));
        assertFalse(SweepMath.isErrorLine("12:00:01.123 [pool-3] WARN  n.s.Server - ERRORS are fine here"));
        assertFalse(SweepMath.isErrorLine(""));
    }

    @Test
    void sampleSpreadsEvenly() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.add(i);
        }
        List<Integer> s = SweepMath.sample(all, 10);
        assertEquals(10, s.size());
        assertEquals(0, s.get(0));
        assertEquals(90, s.get(9));
        assertEquals(all, SweepMath.sample(all, 500));
    }
}
