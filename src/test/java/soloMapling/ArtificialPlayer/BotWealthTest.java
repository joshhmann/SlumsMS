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
    void wealthLineShowsTheGap() throws Exception {
        String line = BotWealth.line(bot(50, 450_000));
        assertTrue(line.contains("450,000 held, ceiling 300,000 (hard 600,000)"), line);
        assertTrue(line.contains("over by 150,000"), line);
        assertTrue(line.contains("intake 50%"), line);
    }
}
