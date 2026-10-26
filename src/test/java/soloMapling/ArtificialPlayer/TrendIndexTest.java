package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0 S9: the population trend index (places, items and prices only; one player at most ~20%). */
class TrendIndexTest {
    static final long DAY = 86_400_000L;
    static final long T0 = 20_000 * DAY + 13 * 3_600_000L; // 13:00 UTC, the afternoon block
    static final int HHG1 = 104040000, ANT = 105070001, PIGS = 100010000;

    @BeforeEach
    void setUp() {
        TrendIndex.resetForTest(ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        TrendIndex.resetForTest(null);
    }

    /** {@code days} days of the same votes, stepped each day. */
    static void days(int days, Runnable votes) {
        days(0, days, votes);
    }

    /** As above, from day {@code from} (votes are stamped T0, the step decides the day they count for). */
    static void days(int from, int days, Runnable votes) {
        for (int d = from; d < from + days; d++) {
            TrendIndex.stepForTest(T0 + d * DAY); // the previous day's votes become one EMA step
            votes.run();
        }
        TrendIndex.stepForTest(T0 + (from + days) * DAY);
    }

    @Test
    void onePlayerAloneIsAtMostAFifthOfAnyTrend() {
        // one player hunts HHG1 all day, every day, for a month
        days(30, () -> {
            for (int i = 0; i < 100; i++) TrendIndex.observeHunt(1, 30, HHG1, 1, T0);
        });
        double score = TrendIndex.mapScore(30, HHG1, T0 + 30 * DAY);
        assertTrue(score > .15, "a trend forms: " + score);
        assertTrue(score <= TrendIndex.PLAYER_SHARE_CAP + 1e-9, "one player is at most 20%: " + score);
        assertTrue(TrendIndex.mapWeight(30, HHG1, T0 + 30 * DAY) <= 1 + TrendIndex.MAP_BOOST * .2 + 1e-9);
    }

    @Test
    void manyPlayersMakeAFullTrendAndNoOneOfThemDominates() {
        // nine players hunt HHG1; one plays all day on Ant Tunnel and votes a thousand times
        days(30, () -> {
            for (int p = 1; p <= 9; p++) TrendIndex.observeHunt(p, 30, HHG1, 1, T0 + p);
            for (int i = 0; i < 1000; i++) TrendIndex.observeHunt(10, 30, ANT, 1, T0 + i);
        });
        long now = T0 + 30 * DAY;
        assertTrue(TrendIndex.mapScore(30, HHG1, now) > .8, "nine of ten players: " + TrendIndex.mapScore(30, HHG1, now));
        assertTrue(TrendIndex.mapScore(30, ANT, now) <= .1 + 1e-9, "the grinder is one vote of ten");
        assertEquals(HHG1, TrendIndex.topMaps(30, now, 1).getFirst().getKey());
        assertEquals(0, TrendIndex.mapScore(80, HHG1, now), "another band never sees it");
        assertEquals(0, TrendIndex.mapScore(30, HHG1, now + 12 * 3_600_000L), "another time of day neither");
    }

    @Test
    void trendsFadeWhenPlayersMoveOn() {
        days(20, () -> { for (int p = 1; p <= 6; p++) TrendIndex.observeHunt(p, 30, HHG1, 1, T0); });
        double peak = TrendIndex.mapScore(30, HHG1, T0 + 20 * DAY);
        days(20, 20, () -> { for (int p = 1; p <= 6; p++) TrendIndex.observeHunt(p, 30, PIGS, 1, T0 + 20 * DAY); });
        assertTrue(TrendIndex.mapScore(30, HHG1, T0 + 40 * DAY) < peak / 5);
        assertTrue(TrendIndex.mapScore(30, PIGS, T0 + 40 * DAY) > .5);
    }

    @Test
    void neverCopiesWordsOnlyAClosedListOfPqNames() {
        assertFalse(TrendIndex.observePq(1, 50, "lol omg noob kekw", T0), "slang is refused");
        assertFalse(TrendIndex.observePq(1, 50, "zhelm or bust", T0));
        assertFalse(TrendIndex.observePq(1, 50, null, T0));
        assertTrue(TrendIndex.observePq(1, 50, "LPQ", T0));
        // the only text the index takes is observePq's name, checked against PQ_NAMES; every other input is a number
        for (Method m : TrendIndex.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !m.getName().startsWith("observe")) continue;
            boolean text = Arrays.asList(m.getParameterTypes()).contains(String.class);
            assertEquals(m.getName().equals("observePq"), text, m.getName());
        }
        days(10, () -> { for (int p = 1; p <= 6; p++) TrendIndex.observePq(p, 55, "LPQ", T0); });
        assertEquals("LPQ", TrendIndex.topPq(55, T0 + 10 * DAY).getKey());
        assertTrue(TrendIndex.bias(Activity.PARTY_QUEST, 55, HHG1, T0 + 10 * DAY) > 0);
        assertTrue(TrendIndex.bias(Activity.PARTY_QUEST, 55, HHG1, T0 + 10 * DAY) <= .08 + 1e-9);
    }

    @Test
    void itemsAndPricesAreSmoothedAndCapped() {
        days(15, () -> {
            for (int a = 1; a <= 6; a++) TrendIndex.observeItem(a, 2040804, 1_000_000, T0);
            for (int i = 0; i < 500; i++) TrendIndex.observeItem(99, 4000000, 5, T0 + i);
        });
        List<Map.Entry<Integer, Double>> hot = TrendIndex.hotItems(2);
        assertEquals(2040804, hot.getFirst().getKey());
        assertTrue(hot.stream().filter(e -> e.getKey() == 4000000).allMatch(e -> e.getValue() <= 1.0 / 7 + 1e-9));
        assertEquals(1_000_000, TrendIndex.priceOf(2040804), 1000);
    }

    @Test
    void theMoveMapPullLeavesAnUntrendyMapAndIsBounded() {
        days(20, () -> { for (int p = 1; p <= 8; p++) TrendIndex.observeHunt(p, 30, HHG1, 1, T0); });
        long now = T0 + 20 * DAY;
        double away = TrendIndex.bias(Activity.MOVE_MAP, 30, PIGS, now);
        assertTrue(away > 0 && away <= .10 + 1e-9, "leave pigs for HHG1: " + away);
        assertEquals(0, TrendIndex.bias(Activity.MOVE_MAP, 30, HHG1, now), "already on the trending map");
        assertEquals(0, TrendIndex.bias(Activity.GRIND, 30, PIGS, now));
    }

    @Test
    void roundTripsThroughItsFile() {
        days(5, () -> { for (int p = 1; p <= 6; p++) TrendIndex.observeHunt(p, 30, HHG1, 1, T0); });
        double score = TrendIndex.mapScore(30, HHG1, T0 + 5 * DAY);
        Map<String, Object> saved = TrendIndex.write();
        Object parsed = soloMapling.server.Json.parse(soloMapling.server.Json.write(saved));
        TrendIndex.resetForTest(ZoneOffset.UTC);
        TrendIndex.read((Map<?, ?>) parsed);
        assertEquals(score, TrendIndex.mapScore(30, HHG1, T0 + 5 * DAY), 1e-9);
    }
}
