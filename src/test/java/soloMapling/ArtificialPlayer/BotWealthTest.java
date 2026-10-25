package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;

class BotWealthTest {

    @BeforeEach
    void setUp() {
        BotWealth.resetForTest(() -> 0L);
    }

    @AfterEach
    void tearDown() {
        BotWealth.resetForTest(null);
        System.clearProperty("solomapling.botWealthCeiling");
    }

    static Character bot(int level, int meso) throws Exception {
        Character b = Character.getDefault(new BotClient(0, 0));
        Field f = Character.class.getDeclaredField("id");
        f.setAccessible(true);
        f.setInt(b, 600_000 + level);
        b.setName("rich" + level);
        b.setLevel(level);
        b.gainMeso(meso - b.getMeso(), false, false, false);
        return b;
    }

    @Test
    void ceilingScalesWithLevelSquaredAndTheMesoRate() {
        assertEquals(1_080_000, BotWealth.ceiling(30, 10));
        assertEquals(5_880_000, BotWealth.ceiling(70, 10));
        assertEquals(9_720_000, BotWealth.ceiling(90, 10));
        assertEquals(17_280_000, BotWealth.ceiling(120, 10));
        assertEquals(1_000_000, BotWealth.ceiling(5, 10), "the floor");
        assertEquals(588_000, BotWealth.ceiling(70, 1));
        assertEquals(11_760_000, BotWealth.hardLine(5_880_000));
    }

    @Test
    void intakeFallsFromTheCeilingToTheHardLine() {
        assertEquals(1.0, BotWealth.intakeFactor(5_000_000, 10_000_000));
        assertEquals(1.0, BotWealth.intakeFactor(10_000_000, 10_000_000));
        assertEquals(0.5, BotWealth.intakeFactor(15_000_000, 10_000_000), 1e-9);
        assertEquals(0.0, BotWealth.intakeFactor(20_000_000, 10_000_000));
        assertEquals(0.0, BotWealth.intakeFactor(90_000_000, 10_000_000));
    }

    @Test
    void aBotOverItsCeilingTakesLessAndStopsAtTheHardLine() throws Exception {
        // No server in a test: the bot's meso rate is 1, so level 50 has a 300,000 ceiling, 600,000 hard.
        Character under = bot(50, 200_000);
        assertEquals(1_000, BotWealth.cutMesos(under, 1_000));
        assertTrue(BotWealth.takeDropMesos(under, new Random(1)));
        assertTrue(BotWealth.mayTakeMesos(under));

        Character half = bot(50, 450_000);
        assertEquals(500, BotWealth.cutMesos(half, 1_000));
        int taken = 0;
        Random rng = new Random(5);
        for (int i = 0; i < 10_000; i++) {
            taken += BotWealth.takeDropMesos(half, rng) ? 1 : 0;
        }
        assertEquals(5_000, taken, 250);

        Character capped = bot(50, 700_000);
        assertEquals(0, BotWealth.cutMesos(capped, 1_000));
        assertFalse(BotWealth.mayTakeMesos(capped));
        assertFalse(BotWealth.takeDropMesos(capped, new Random(1)));
        assertTrue(BotWealth.intakeRefused.get() > 0);
        assertEquals(500 + 1_000, BotWealth.intakeCutMesos.get());
    }

    @Test
    void playersAreNeverLimited() {
        Character player = mock(Character.class);
        assertEquals(1_000, BotWealth.cutMesos(player, 1_000));
        assertTrue(BotWealth.mayTakeMesos(player));
        assertEquals(0, BotWealth.excess(player));
    }

    @Test
    void offSwitch() throws Exception {
        System.setProperty("solomapling.botWealthCeiling", "false");
        Character capped = bot(50, 700_000);
        assertEquals(1_000, BotWealth.cutMesos(capped, 1_000));
        assertEquals(0, BotWealth.excess(capped));
    }

    @Test
    void noScrollBonusWithoutAWorld() {
        assertEquals(0, BotWealth.scrollBonus(70, 50_000_000));
    }

    @Test
    void scrollBonusUsesBeraRateWhenOnlyWorldOneIsRegistered() {
        net.server.world.World bera = mock(net.server.world.World.class);
        doReturn(1).when(bera).getId();
        doReturn(10).when(bera).getMesoRate();
        net.server.Server.getInstance().registerWorld(bera, 0, java.util.Map.of(), "Welcome to Bera");
        try {
            // At level 70, meso rate 10, ceiling is 5,880,000.
            // With 50,000,000 held, excess is 44,120,000, so bonus is 22,060,000.
            assertEquals(22_060_000, BotWealth.scrollBonus(70, 50_000_000));
            // A bot under its ceiling receives 0 bonus
            assertEquals(0, BotWealth.scrollBonus(70, 5_000_000));

            // Verify ScrollBudget.maxPrice() consumes the corrected bonus:
            // Daily cap without bonus is 1,050,000. With bonus, cap is 23,110,000,
            // allowing maxPrice to reach 25% of spare (12,387,500).
            long maxPrice = soloMapling.ArtificialPlayer.BotScrollSystem.ScrollBudget.maxPrice(999, 70, 50_000_000, false);
            assertEquals(12_387_500, maxPrice, "maxPrice consumes the wealth bonus");
        } finally {
            net.server.Server.getInstance().resetServerWorlds();
            BotWealth.resetForTest(null);
        }
    }

    @Test
    void wealthLineShowsTheGap() throws Exception {
        String line = BotWealth.line(bot(50, 450_000));
        assertTrue(line.contains("450,000 held, ceiling 300,000 (hard 600,000)"), line);
        assertTrue(line.contains("over by 150,000"), line);
        assertTrue(line.contains("intake 50%"), line);
    }

    @Test
    void upkeepStopsWithinOnePercentOfTheCeiling() {
        // rc6 t-wc-1: "over by 1,078 of 12,000,000" paid 108 mesos a step forever.
        assertEquals(0, BotWealth.upkeep(1_078, 12_000_000, 0.10));
        assertEquals(0, BotWealth.upkeep(120_000, 12_000_000, 0.10), "exactly 1% over pays nothing");
        assertEquals(12_001, BotWealth.upkeep(120_001, 12_000_000, 0.10), "just past 1% pays the usual 10%");
        assertEquals(39_537, BotWealth.upkeep(395_365, 12_000_000, 0.10));
        assertEquals(0, BotWealth.upkeep(0, 12_000_000, 0.10));
        assertFalse(BotWealth.upkeepDue(-5, 12_000_000));
    }

    @Test
    void aStepWithinOnePercentBurnsNothing() throws Exception {
        // Level 50 with no server: ceiling 300,000, so 1% is 3,000.
        Character near = bot(50, 302_000);
        BotWealth.step(near, new Random(1), true);
        assertEquals(302_000, near.getMeso());
        assertEquals(0, BotWealth.decayed.get());

        Character far = bot(51, 400_000);
        long ceiling = BotWealth.ceiling(far);
        BotWealth.step(far, new Random(1), true);
        assertEquals(400_000 - (long) Math.ceil((400_000 - ceiling) * 0.10), far.getMeso());
        assertTrue(BotWealth.decayed.get() > 0);
    }

    @Test
    void repeatedUpkeepSettlesWithinOnePercentAndStops() {
        long ceiling = 12_000_000, excess = 5_000_000;
        int steps = 0;
        while (BotWealth.upkeepDue(excess, ceiling)) {
            excess -= BotWealth.upkeep(excess, ceiling, 0.10);
            steps++;
            assertTrue(steps < 100, "upkeep never stopped");
        }
        assertTrue(excess > 0 && excess * 100 <= ceiling, "settled at " + excess);
        assertEquals(0, BotWealth.upkeep(excess, ceiling, 0.10));
    }
}
