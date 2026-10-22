package soloMapling.Economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyMetersTest {

    EconomyTestSupport t;

    @BeforeEach
    void setUp() {
        t = new EconomyTestSupport();
    }

    @AfterEach
    void tearDown() {
        EconomyTestSupport.reset();
    }

    @Test
    void theWorldBudgetForBuyingFromPlayers() {
        EconomyConfig.useForTest(Map.of("botBuying.worldDailyBudget", "10000000"));
        assertTrue(EconomyMeters.tryBotBuy(6_000_000));
        assertFalse(EconomyMeters.tryBotBuy(5_000_000), "over the day's budget");
        EconomyMeters.releaseBotBuy(6_000_000);
        assertTrue(EconomyMeters.tryBotBuy(9_000_000), "a failed purchase gives its share back");
        assertEquals(9_000_000, EconomyMeters.get(EconomyMeters.BOT_BUY));
    }

    @Test
    void metersSurviveARestartAndStartAgainEachDay() {
        EconomyMeters.add(EconomyMeters.GIFTS, 3);
        EconomyMeters.useForTest(t.store, Runnable::run, () -> java.time.LocalDate.ofEpochDay(100));
        assertEquals(3, EconomyMeters.get(EconomyMeters.GIFTS), "read back from economyday");
        EconomyMeters.useForTest(t.store, Runnable::run, () -> java.time.LocalDate.ofEpochDay(101));
        assertEquals(0, EconomyMeters.get(EconomyMeters.GIFTS), "a new day");
        assertTrue(EconomyMeters.line().contains("bots paid players 0 of 40,000,000"), EconomyMeters.line());
    }
}
