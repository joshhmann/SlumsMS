package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerWealthIndexTest {

    private static final long DAY = 86_400_000L, HOUR = 3_600_000L;
    private static final int RATE = 10;

    @BeforeEach
    void setUp() {
        PlayerWealthIndex.resetForTest();
    }

    @AfterEach
    void tearDown() {
        PlayerWealthIndex.resetForTest();
        for (String k : new String[]{"wealthIndex", "wealthIndexMax", "wealthIndexMinSample", "wealthIndexTauDays"}) {
            System.clearProperty("solomapling." + k);
        }
    }

    /** n players at {@code times} x the Lv70 ceiling. */
    private static List<PlayerWealthIndex.Sample> players(int n, double times) {
        List<PlayerWealthIndex.Sample> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new PlayerWealthIndex.Sample(70, (long) (times * BotWealth.baseCeiling(70, RATE))));
        }
        return l;
    }

    @Test
    void medianIgnoresOneRichPlayer() {
        List<PlayerWealthIndex.Sample> l = players(6, 2.0);
        l.add(new PlayerWealthIndex.Sample(72, 183_000_000));
        assertEquals(2.0, PlayerWealthIndex.rawIndex(l, RATE, 10, 5, 0.5), 0.01);
    }

    @Test
    void quantileInterpolates() {
        double[] a = {1, 2, 3, 4};
        assertEquals(2.5, PlayerWealthIndex.quantile(a, 0.5), 1e-9);
        assertEquals(1, PlayerWealthIndex.quantile(a, 0), 1e-9);
        assertEquals(4, PlayerWealthIndex.quantile(a, 1), 1e-9);
    }

    @Test
    void lowLevelsAndTooFewPlayersGiveNoIndex() {
        List<PlayerWealthIndex.Sample> l = players(4, 5.0);
        assertTrue(Double.isNaN(PlayerWealthIndex.rawIndex(l, RATE, 10, 5, 0.5)), "4 < min sample 5");
        l.add(new PlayerWealthIndex.Sample(9, 99_000_000));   // under level 10: ignored
        assertTrue(Double.isNaN(PlayerWealthIndex.rawIndex(l, RATE, 10, 5, 0.5)));
        l.add(new PlayerWealthIndex.Sample(70, (long) (5.0 * BotWealth.baseCeiling(70, RATE))));
        assertEquals(5.0, PlayerWealthIndex.rawIndex(l, RATE, 10, 5, 0.5), 0.01);
    }

    @Test
    void emaMovesTowardTheSampleByTheTimeConstant() {
        assertEquals(0.0, PlayerWealthIndex.emaStep(0.0, 1.0, DAY, 3), 1e-9);
        double after = PlayerWealthIndex.emaStep(0.0, Math.E, 3 * DAY, 3);   // one time constant: ~63% of the way
        assertEquals(1 - Math.exp(-1), after, 1e-9);
        assertEquals(Math.log(8), PlayerWealthIndex.emaStep(0.0, 8.0, DAY, 0), 1e-9, "tau 0 = no smoothing");
    }

    @Test
    void multiplierIsClampedAndRateLimitedPerDay() {
        assertEquals(1.5, PlayerWealthIndex.nextMultiplier(1.0, 50, DAY, 1.5, 1, 10), 1e-9, "x1.5 a day");
        assertEquals(2.25, PlayerWealthIndex.nextMultiplier(1.5, 50, DAY, 1.5, 1, 10), 1e-9);
        assertEquals(10.0, PlayerWealthIndex.nextMultiplier(9.0, 50, DAY, 1.5, 1, 10), 1e-9, "max clamp");
        assertEquals(1.0, PlayerWealthIndex.nextMultiplier(1.2, 0.1, DAY, 1.5, 1, 10), 1e-9, "never under 1");
        assertEquals(4.0 / 1.5, PlayerWealthIndex.nextMultiplier(4.0, 1.0, DAY, 1.5, 1, 10), 1e-9, "/1.5 a day");
        assertEquals(1.0, PlayerWealthIndex.nextMultiplier(1.0, 5, 10 * DAY, 1.5, 0.2, 10) > 1 ? 1.0 : 0.0,
                "a min below 1 is raised to 1");
        double hour = PlayerWealthIndex.nextMultiplier(1.0, 50, HOUR, 1.5, 1, 10);
        assertEquals(Math.pow(1.5, 1.0 / 24), hour, 1e-9, "hourly steps share the daily limit");
    }

    @Test
    void applyClimbsSlowlyAndOneFlashPlayerCannotSwingIt() {
        long t = DAY;
        PlayerWealthIndex.apply(players(6, 8.0), RATE, t);   // first call: no elapsed time, no movement
        assertEquals(1.0, PlayerWealthIndex.multiplier(), 1e-9);
        for (int h = 1; h <= 24; h++) {
            PlayerWealthIndex.apply(players(6, 8.0), RATE, t + h * HOUR);
        }
        double day1 = PlayerWealthIndex.multiplier();
        assertTrue(day1 > 1.0 && day1 <= 1.5 + 1e-9, "after a day at most x1.5, got " + day1);
        for (int d = 0; d < 30; d++) {
            for (int h = 1; h <= 24; h++) {
                PlayerWealthIndex.apply(players(6, 8.0), RATE, t + (25 + d * 24 + h) * HOUR);
            }
        }
        assertTrue(PlayerWealthIndex.multiplier() > 6 && PlayerWealthIndex.multiplier() <= 8.0 + 1e-6,
                "settles near the index " + PlayerWealthIndex.multiplier());
    }

    @Test
    void maxIsConfigurable() {
        System.setProperty("solomapling.wealthIndexMax", "3");
        System.setProperty("solomapling.wealthIndexTauDays", "0");
        long t = DAY;
        for (int h = 0; h < 24 * 20; h++) {
            PlayerWealthIndex.apply(players(6, 50.0), RATE, t + h * HOUR);
        }
        assertEquals(3.0, PlayerWealthIndex.multiplier(), 1e-9);
    }

    @Test
    void noHumanDataMeansOne() {
        PlayerWealthIndex.apply(List.of(), RATE, DAY);
        PlayerWealthIndex.apply(List.of(), RATE, 2 * DAY);
        assertEquals(1.0, PlayerWealthIndex.multiplier(), 1e-9);
        assertEquals(1.0, PlayerWealthIndex.smoothedIndex(), 1e-9);
        PlayerWealthIndex.apply(players(3, 9.0), RATE, 3 * DAY);   // too few players
        assertEquals(1.0, PlayerWealthIndex.multiplier(), 1e-9);
    }

    @Test
    void poorPlayersNeverLowerTheCeilingBelowToday() {
        for (int h = 0; h < 24 * 10; h++) {
            PlayerWealthIndex.apply(players(8, 0.05), RATE, DAY + h * HOUR);
        }
        assertEquals(1.0, PlayerWealthIndex.multiplier(), 1e-9);
        assertEquals(5_880_000, BotWealth.ceiling(70, RATE));
    }

    @Test
    void ceilingUsesTheMultiplierButYardstickDoesNot() {
        System.setProperty("solomapling.wealthIndexTauDays", "0");
        for (int h = 0; h < 24 * 10; h++) {
            PlayerWealthIndex.apply(players(6, 4.0), RATE, DAY + h * HOUR);
        }
        double m = PlayerWealthIndex.multiplier();
        assertEquals(4.0, m, 1e-9);
        assertEquals(5_880_000, BotWealth.baseCeiling(70, RATE));
        assertEquals((long) (5_880_000 * m), BotWealth.ceiling(70, RATE));
        System.setProperty("solomapling.wealthIndex", "false");
        assertEquals(5_880_000, BotWealth.ceiling(70, RATE), "index off = today's ceiling");
    }

    @Test
    void gmsBotsAndBotRangeIdsAreExcluded() throws Exception {
        Character human = mock(Character.class);
        when(human.getId()).thenReturn(1234);
        when(human.getLevel()).thenReturn(72);
        when(human.getMeso()).thenReturn(5_000_000);
        Character gm = mock(Character.class);
        when(gm.getId()).thenReturn(1);
        when(gm.gmLevel()).thenReturn(4);
        when(gm.getMeso()).thenReturn(2_000_000_000);
        Character botRange = mock(Character.class);
        when(botRange.getId()).thenReturn(1_000_000_005);
        Character bot = Character.getDefault(new BotClient(0, 0));
        Character observer = mock(Character.class); // 1.6.0 social trace: an observer is no player of the economy
        when(observer.getId()).thenReturn(1240);
        when(observer.getName()).thenReturn("AutoQaThree");
        when(observer.getLevel()).thenReturn(30);
        var m = PlayerWealthIndex.humans(List.of(human, gm, botRange, bot, observer));
        assertEquals(1, m.size());
        assertEquals(new PlayerWealthIndex.Sample(72, 5_000_000), m.get(1234));
        assertFalse(m.containsKey(1));
        assertFalse(m.containsKey(1240));
    }
}
