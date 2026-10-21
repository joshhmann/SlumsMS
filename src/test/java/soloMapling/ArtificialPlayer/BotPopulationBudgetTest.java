package soloMapling.ArtificialPlayer;

import config.ServerConfig;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 57: about 500 bots at the evening peak - the environment's town dressing at BOT_ENVIRONMENT_SCALE plus its
 * set pieces, and the director's roster bots on the daily curve - spread ch1-heavy over eight channels.
 *
 * <p>The cohort sizes below are the boot waves' headcounts in EnvironmentManager / ArtificialFreeMarket (expected
 * values where a count is random); keep them in step when a wave changes.
 */
class BotPopulationBudgetTest {

    /** Town dressing, wave by wave (spread over the channels and scaled). */
    private static final int[] DRESSING_BY_WAVE = {
            10 + 15,                          // 1: Henesys batch, FM entrance
            15 + 5 + 4 + 4,                   // 2: FM entrance, merchants m1/m2/m5
            15 + 23 + 44,                     // 3: Pet Park JQ, Henesys batch, Henesys fillers
            15 + 18 + 31,                     // 4: FM entrance, merchants, market fillers
            33 + 29 + 8 + 13,                 // 5: Henesys batch, park/potion/game-zone fillers
            14,                               // 6: Pet Park social
            14                                // 7: merchants
    };
    /** FM rooms: shop spots per region x rooms, times the chance a spot is a bot's shop (not a hired merchant). */
    private static final double FM_SHOP_BOTS =
            24 * (0.1 + 0.2 + 0.4 + 0.6 * 3)  // Henesys rooms 1-6
                    + 28 * (0.3 + 0.6 * 5)    // Ludibrium rooms 7-12
                    + 26 * 0.6 * 5            // Perion
                    + 27 * 0.6 * 5;           // El Nath
    /** Set pieces, spawned whole on channel 1: tutorial, gacha, game-zone hosts, blackjack, drop game, OPQ lobby. */
    private static final double SET_PIECES = 1 + 3 + 2 + 5 * (1 + 3.5) + 1 + 4.5 + 12.5;

    private static double dressing() {
        double sum = 0;
        for (int n : DRESSING_BY_WAVE) {
            sum += n;
        }
        return sum + FM_SHOP_BOTS * 0.98; // 2% of FM spots stay empty
    }

    @Test
    void theOldEnvironmentMatchesWhatLiveSpawned() {
        // Live, 21 Oct 2026: 659 and 670 environment bots after a restart.
        double before = dressing() + SET_PIECES;
        assertTrue(before > 620 && before < 700, "unscaled environment " + before);
    }

    @Test
    void theShippedDefaultsAddUpToAboutTwelveHundredAtPeak() {
        ServerConfig server = new ServerConfig();
        assertEquals(1200, server.BOT_POPULATION_PEAK);
        assertEquals(1200, server.BOT_ROSTER_PEAK_ONLINE);
        assertEquals(0.5, server.BOT_ENVIRONMENT_SCALE, 1e-9);

        double environment = dressing() * server.BOT_ENVIRONMENT_SCALE + SET_PIECES;
        assertTrue(environment > 330 && environment < 390, "environment " + environment);

        int directorPeak = BotChannels.directorPeak(server.BOT_ROSTER_PEAK_ONLINE, server.BOT_POPULATION_PEAK,
                (int) Math.round(environment));
        assertTrue(directorPeak >= 810 && directorPeak <= 870, "director peak " + directorPeak);
        // Live keeps BOT_ROSTER_PEAK_ONLINE: 1200 in its config.yaml; the cap still holds the total.
        assertEquals(directorPeak, BotChannels.directorPeak(1200, server.BOT_POPULATION_PEAK,
                (int) Math.round(environment)));
        assertTrue(environment + directorPeak <= server.BOT_POPULATION_PEAK + 1);
    }

    @Test
    void theScaledWavesLandNearTheirExpectation() {
        // Run every dressing cohort through the live rounding many times: the total stays near count * scale.
        Random rng = new Random(57);
        int[] cohorts = {10, 5, 5, 5, 2, 2, 1, 2, 2, 1, 15, 5, 2, 3, 3, 6, 6, 4, 8, 4, 3, 4, 3, 3, 3, 3, 4, 5, 3, 3,
                4, 4, 5, 2, 4, 1, 2, 1, 2, 4, 3, 2, 3, 4, 6, 3, 1, 2, 3, 1, 1, 1, 1, 1, 3};
        double expected = 0;
        for (int n : cohorts) {
            expected += n * 0.25;
        }
        double mean = 0;
        int runs = 2000;
        for (int r = 0; r < runs; r++) {
            int total = 0;
            for (int n : cohorts) {
                total += BotChannels.scaledCount(n, 0.25, rng.nextDouble());
            }
            mean += total / (double) runs;
        }
        assertEquals(expected, mean, expected * 0.03);
    }

    @Test
    void theDailyCurveRunsTheDirectorFromAboutFortyToItsPeak() {
        ServerConfig server = new ServerConfig();
        BotPopulationCurve curve = BotPopulationCurve.parse(server.BOT_ROSTER_CURVE, server.BOT_ROSTER_WEEKEND_LIFT);
        ZonedDateTime wednesday = ZonedDateTime.of(2026, 10, 21, 0, 0, 0, 0, ZoneId.of("America/Los_Angeles"));
        int peak = 300;
        assertEquals(300, curve.target(peak, wednesday.withHour(20)), "evening peak");
        assertEquals(45, curve.target(peak, wednesday.withHour(6)), "early-morning low");
        assertEquals(135, curve.target(peak, wednesday.withHour(0)));
        assertEquals(300, curve.target(peak, wednesday.plusDays(3).withHour(20)), "the weekend lift never passes the peak");
        assertEquals(149, curve.target(peak, wednesday.plusDays(3).withHour(0)), "Saturday midnight: 135 x 1.1");
    }

    @Test
    void thePeakSplitsOverTheChannelsChannelOneFirst() {
        double[] shares = BotChannels.shares(new ServerConfig().BOT_CHANNEL_WEIGHTS, 5);
        int[] counts = new int[5];
        for (int i = 0; i < 1200; i++) {
            counts[BotChannels.pickByDeficit(counts, shares)]++;
        }
        assertEquals(480, counts[0]);
        assertEquals(336, counts[1]);
        assertEquals(240, counts[2]);
        assertEquals(144, counts[3]);
        assertEquals(0, counts[4], "channel 5 is the quiet channel");
    }

    @Test
    void channelFiveStaysBotFreeAndTheRestHoldTheirShares() {
        double[] shares = BotChannels.shares(null, 5);
        // Boot: 47 set pieces on channel 1, then about 308 dressing bots split among themselves.
        int[] all = new int[5];
        int[] dressing = new int[5];
        all[0] = 47;
        for (int i = 0; i < 308; i++) {
            int c = BotChannels.pickByDeficit(dressing, shares);
            dressing[c]++;
            all[c]++;
        }
        assertEquals(0, dressing[4], "no town dressing on the quiet channel");
        assertTrue(dressing[3] >= 30, "channel 4's towns still have life: " + dressing[3]);
        // Evening: the director logs in about 845 against every bot online.
        for (int i = 0; i < 845; i++) {
            all[BotChannels.pickByDeficit(all, shares)]++;
        }
        int total = java.util.Arrays.stream(all).sum();
        assertEquals(1200, total);
        assertEquals(0, all[4], "channel 5 stays bot-free at the peak");
        for (int c = 0; c < 4; c++) {
            assertEquals(shares[c] * total, all[c], 1.5, "ch" + (c + 1));
        }
        // Early morning: about 125 director bots. Channel 1 stays the busiest, channel 5 empty.
        int[] night = new int[5];
        night[0] = 47;
        for (int c = 0; c < 5; c++) {
            night[c] += dressing[c];
        }
        for (int i = 0; i < 125; i++) {
            night[BotChannels.pickByDeficit(night, shares)]++;
        }
        assertEquals(0, night[4]);
        for (int c = 1; c < 4; c++) {
            assertTrue(night[c - 1] >= night[c], "night ch" + c + " >= ch" + (c + 1) + ": " + java.util.Arrays.toString(night));
        }
    }
}
