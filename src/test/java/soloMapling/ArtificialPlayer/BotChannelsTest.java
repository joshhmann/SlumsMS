package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 57: how bots split over the channels. */
class BotChannelsTest {

    private static final double[] SHIPPED = {100 / 225.0, 55 / 225.0, 35 / 225.0, 20 / 225.0, 15 / 225.0};

    @Test
    void theShippedWeightsAreTheOwnersSplit() {
        assertArrayEquals(SHIPPED, BotChannels.shares("100,55,35,20,15", 5), 1e-9);
        assertArrayEquals(SHIPPED, BotChannels.shares(new config.ServerConfig().BOT_CHANNEL_WEIGHTS, 5), 1e-9,
                "the shipped config default is the split");
        assertArrayEquals(SHIPPED, BotChannels.shares("", 5), 1e-9, "blank falls back to the shipped split");
        assertArrayEquals(SHIPPED, BotChannels.shares("26,x,16", 5), 1e-9, "a broken list falls back too");
        assertArrayEquals(SHIPPED, BotChannels.shares("26,-1", 5), 1e-9, "negative weights are broken");
    }

    @Test
    void anyChannelCountWorks() {
        // Fewer channels: the first weights, renormalised.
        assertArrayEquals(new double[]{100 / 190.0, 55 / 190.0, 35 / 190.0}, BotChannels.shares(null, 3), 1e-9);
        // More channels: the last weight repeats.
        double[] ten = BotChannels.shares("26,20,16,12,9,7,5,5", 10);
        assertEquals(10, ten.length);
        assertEquals(ten[7], ten[8], 1e-12);
        assertEquals(ten[7], ten[9], 1e-12);
        assertEquals(1.0, java.util.Arrays.stream(ten).sum(), 1e-9);
        assertArrayEquals(new double[]{1.0}, BotChannels.shares(null, 1), 1e-9);
        assertArrayEquals(new double[]{0.25, 0.25, 0.25, 0.25}, BotChannels.shares("0,0", 4), 1e-9,
                "all zeros = every channel alike");
        assertEquals(0, BotChannels.shares(null, 0).length);
    }

    @Test
    void fiveHundredLoginsLandOnTheirShares() {
        int[] counts = new int[SHIPPED.length];
        for (int i = 0; i < 500; i++) {
            counts[BotChannels.pickByDeficit(counts, SHIPPED)]++;
        }
        for (int c = 0; c < SHIPPED.length; c++) {
            assertEquals(SHIPPED[c] * 500, counts[c], 1.0, "channel " + (c + 1));
        }
        assertEquals(500, java.util.Arrays.stream(counts).sum());
        for (int c = 1; c < SHIPPED.length; c++) {
            assertTrue(counts[c - 1] >= counts[c], "ch" + c + " is at least as busy as ch" + (c + 1));
        }
    }

    @Test
    void theSplitHoldsAsBotsComeAndGo() {
        // A day of churn: random logouts anywhere, logins by deficit, the count swinging between 150 and 500.
        Random rng = new Random(57);
        int[] counts = new int[SHIPPED.length];
        for (int i = 0; i < 300; i++) {
            counts[BotChannels.pickByDeficit(counts, SHIPPED)]++;
        }
        for (int step = 0; step < 20_000; step++) {
            int total = java.util.Arrays.stream(counts).sum();
            int target = 325 + (int) Math.round(175 * Math.sin(step / 1500.0));
            if (total > target || (total == target && rng.nextBoolean())) {
                // A random bot leaves: its channel is drawn in proportion to the bots on it.
                int pick = rng.nextInt(total);
                for (int c = 0; c < SHIPPED.length; c++) {
                    if (pick < counts[c]) {
                        counts[c]--;
                        break;
                    }
                    pick -= counts[c];
                }
            } else {
                counts[BotChannels.pickByDeficit(counts, SHIPPED)]++;
            }
            if (step % 500 == 0) {
                int now = java.util.Arrays.stream(counts).sum();
                for (int c = 0; c < SHIPPED.length; c++) {
                    // Logouts are random, so a channel may run over its share for a while; never by much.
                    assertEquals(SHIPPED[c] * now, counts[c], Math.max(3, now * 0.03), "step " + step + " ch" + (c + 1));
                }
            }
        }
    }

    @Test
    void parallelLoginsSeeEachOtherThroughReservations() {
        // reserve() adds the in-flight logins to the counts; picking eight at once from an empty world must not
        // stack them all on channel 1.
        int[] counts = new int[SHIPPED.length];
        int[] inFlight = new int[SHIPPED.length];
        for (int i = 0; i < SHIPPED.length; i++) {
            int[] seen = new int[SHIPPED.length];
            for (int c = 0; c < SHIPPED.length; c++) {
                seen[c] = counts[c] + inFlight[c];
            }
            inFlight[BotChannels.pickByDeficit(seen, SHIPPED)]++;
        }
        assertTrue(inFlight[0] < SHIPPED.length);
        assertTrue(inFlight[0] >= inFlight[SHIPPED.length - 1]);
    }

    @Test
    void environmentCountsScaleWithoutLosingTheirExpectation() {
        assertEquals(10, BotChannels.scaledCount(10, 1.0, 0.99));
        assertEquals(0, BotChannels.scaledCount(0, 0.25, 0.99));
        assertEquals(2, BotChannels.scaledCount(10, 0.25, 0.0));
        assertEquals(3, BotChannels.scaledCount(10, 0.25, 0.5));
        assertEquals(0, BotChannels.scaledCount(10, 0.0, 0.99));
        assertEquals(1, BotChannels.scaledCount(1, 0.25, 0.8), "a one-bot cohort shows up a quarter of the time");
        Random rng = new Random(1);
        long total = 0;
        for (int i = 0; i < 40_000; i++) {
            total += BotChannels.scaledCount(3, 0.25, rng.nextDouble());
        }
        assertEquals(0.75, total / 40_000.0, 0.02);
    }

    @Test
    void theDirectorPeakIsWhatTheCapLeaves() {
        assertEquals(300, BotChannels.directorPeak(300, 500, 200));
        assertEquals(300, BotChannels.directorPeak(1200, 500, 200), "live's BOT_ROSTER_PEAK_ONLINE 1200 is capped");
        assertEquals(250, BotChannels.directorPeak(1200, 500, 250));
        assertEquals(1200, BotChannels.directorPeak(1200, 0, 650), "cap 0 = no cap");
        assertEquals(0, BotChannels.directorPeak(300, 500, 700), "never negative");
    }

    @Test
    void spreadTasksAreRecognisedBySlot() {
        BotChannels.spreadTask(3, 2);
        int base = BotStore.planSlot(3, 2, 0);
        assertTrue(BotChannels.isSpreadSlot(base));
        assertTrue(BotChannels.isSpreadSlot(base + 5 * BotStore.SLOTS_PER_GROUP + 7), "any cohort of the task");
        assertEquals(3, BotStore.waveOf(base + 100));
        assertEquals(2, BotStore.taskOf(base + 100));
        assertTrue(!BotChannels.isSpreadSlot(BotStore.planSlot(3, 1, 0)), "a set-piece task stays put");
        assertTrue(!BotChannels.isSpreadSlot(BotStore.FIRST_PLANNED_SLOT - 1), "a GM spawn stays where it was asked");
        assertEquals(-1, BotStore.taskOf(BotStore.FIRST_ROSTER_SLOT + 3));
        assertEquals(17, BotChannels.envCount(BotStore.planSlot(3, 1, 0), 17), "set pieces spawn whole");
    }

    /* 1.5.2 hotfix: the FM entrance's FM bots and merchants spawn at BOT_FM_ENVIRONMENT_SCALE (shipped 1.0, all of
     * them), the rest of the town dressing at BOT_ENVIRONMENT_SCALE; both stay split over the channels. */
    @Test
    void theFmEntranceDressingHasItsOwnScale() {
        config.ServerConfig server = config.YamlConfig.config.server;
        double savedEnv = server.BOT_ENVIRONMENT_SCALE;
        double savedFm = server.BOT_FM_ENVIRONMENT_SCALE;
        try {
            assertEquals(1.0, new config.ServerConfig().BOT_FM_ENVIRONMENT_SCALE, "shipped: the whole FM entrance");
            server.BOT_ENVIRONMENT_SCALE = 0.5;
            server.BOT_FM_ENVIRONMENT_SCALE = 1.0;
            BotChannels.spreadTask(2, 1, true);    // wave 2's FM entrance bots
            BotChannels.spreadTask(2, 0);          // wave 2's FM room dressing
            int fm = BotStore.planSlot(2, 1, 0);
            int rooms = BotStore.planSlot(2, 0, 0);
            assertTrue(BotChannels.isSpreadSlot(fm), "still spread over the channels");
            assertEquals(15, BotChannels.envCount(fm, 15), "every FM entrance bot spawns");
            assertEquals(15, BotChannels.envCount(fm + 2 * BotStore.SLOTS_PER_GROUP, 15), "any cohort of the task");
            assertEquals(1.0, BotChannels.environmentScale(fm), 1e-12);
            assertEquals(0.5, BotChannels.environmentScale(rooms), 1e-12, "other dressing keeps its scale");
            for (int i = 0; i < 50; i++) {
                int n = BotChannels.envCount(rooms, 15);
                assertTrue(n == 7 || n == 8, "other dressing is halved: " + n);
            }
            server.BOT_FM_ENVIRONMENT_SCALE = 0.5;
            for (int i = 0; i < 50; i++) {
                int n = BotChannels.envCount(fm, 15);
                assertTrue(n == 7 || n == 8, "the FM scale is a knob too: " + n);
            }
            BotChannels.spreadTask(2, 1);
            assertEquals(0.5, BotChannels.environmentScale(fm), 1e-12, "re-marking a task plain drops the FM scale");
        } finally {
            server.BOT_ENVIRONMENT_SCALE = savedEnv;
            server.BOT_FM_ENVIRONMENT_SCALE = savedFm;
        }
    }

    /* 1.6.0 towns: BOT_TOWN_DRESSING_SCALE thins the Henesys town dressing only; -1 (shipped) follows
     * BOT_ENVIRONMENT_SCALE; FM entrance and FM-room dressing keep their own scales. */
    @Test
    void theHenesysTownDressingHasItsOwnScale() {
        config.ServerConfig server = config.YamlConfig.config.server;
        double savedEnv = server.BOT_ENVIRONMENT_SCALE;
        double savedFm = server.BOT_FM_ENVIRONMENT_SCALE;
        double savedTown = server.BOT_TOWN_DRESSING_SCALE;
        try {
            assertEquals(-1.0, new config.ServerConfig().BOT_TOWN_DRESSING_SCALE, "shipped: follow the environment scale");
            server.BOT_ENVIRONMENT_SCALE = 0.5;
            server.BOT_FM_ENVIRONMENT_SCALE = 1.0;
            BotChannels.spreadTask(5, 1, false, true);  // wave 5's HenesysBots
            BotChannels.spreadTask(5, 0, false);        // wave 5's FM room dressing
            BotChannels.spreadTask(4, 1, true, false);  // wave 4's FM entrance
            int town = BotStore.planSlot(5, 1, 0);
            int rooms = BotStore.planSlot(5, 0, 0);
            int fm = BotStore.planSlot(4, 1, 0);
            server.BOT_TOWN_DRESSING_SCALE = -1;
            assertEquals(0.5, BotChannels.environmentScale(town), 1e-12, "-1 follows BOT_ENVIRONMENT_SCALE");
            server.BOT_TOWN_DRESSING_SCALE = 0.25;
            assertEquals(0.25, BotChannels.environmentScale(town), 1e-12);
            assertEquals(0.5, BotChannels.environmentScale(rooms), 1e-12, "FM rooms keep BOT_ENVIRONMENT_SCALE");
            assertEquals(1.0, BotChannels.environmentScale(fm), 1e-12, "the FM entrance keeps its own scale");
            assertTrue(BotChannels.isSpreadSlot(town), "still spread over the channels");
            for (int i = 0; i < 50; i++) {
                int n = BotChannels.envCount(town, 20);
                assertEquals(5, n, "a quarter of the town dressing");
            }
            server.BOT_TOWN_DRESSING_SCALE = 0;
            assertEquals(0, BotChannels.envCount(town, 20), "0 = no Henesys dressing");
            assertEquals(10, BotChannels.envCount(rooms, 20), "0 does not touch the FM rooms");
            server.BOT_TOWN_DRESSING_SCALE = 3;
            assertEquals(1.0, BotChannels.environmentScale(town), 1e-12, "clamped to 1");
            BotChannels.spreadTask(5, 1, false);
            server.BOT_TOWN_DRESSING_SCALE = 0.25;
            assertEquals(0.5, BotChannels.environmentScale(town), 1e-12, "re-marking a task plain drops the town scale");
        } finally {
            server.BOT_ENVIRONMENT_SCALE = savedEnv;
            server.BOT_FM_ENVIRONMENT_SCALE = savedFm;
            server.BOT_TOWN_DRESSING_SCALE = savedTown;
        }
    }

    @Test
    void aZeroWeightChannelIsNeverPickedEvenWhenEveryChannelIsEmpty() {
        double[] shares = BotChannels.shares("0,40,28,20,12", 5);
        assertEquals(1, BotChannels.pickByDeficit(new int[5], shares), "an empty world never starts on the zero channel");
        int[] counts = new int[5];
        for (int i = 0; i < 1000; i++) {
            counts[BotChannels.pickByDeficit(counts, shares)]++;
        }
        assertEquals(0, counts[0]);
    }

    @Test
    void oneHundredThenZeroPutsEveryBotBackOnChannelOne() {
        double[] shares = BotChannels.shares("100,0", 8);
        int[] counts = new int[8];
        for (int i = 0; i < 200; i++) {
            counts[BotChannels.pickByDeficit(counts, shares)]++;
        }
        assertEquals(200, counts[0], "the 1.4.1 behaviour, as a config switch");
    }
}
