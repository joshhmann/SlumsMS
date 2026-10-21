package soloMapling.ArtificialPlayer.BotScrollSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollBudgetTest {

    final AtomicLong day = new AtomicLong(20_000);

    @BeforeEach
    void setUp() {
        ScrollBudget.clearForTest();
        ScrollBudget.useDayClockForTest(day::get);
    }

    @AfterEach
    void tearDown() {
        ScrollBudget.useDayClockForTest(null);
        ScrollBudget.clearForTest();
    }

    @Test
    void aBotKeepsItsReserveAndSpendsAtMostAQuarterOfTheRest() {
        // lv 50: reserve 350,000, daily cap 750,000
        assertEquals(0, ScrollBudget.maxPrice(1, 50, 350_000, false), "nothing above the reserve");
        assertEquals(25_000, ScrollBudget.maxPrice(1, 50, 450_000, false), "a quarter of the 100,000 spare");
        assertFalse(ScrollBudget.tryBuy(1, 50, 450_000, 30_000, false));
        assertTrue(ScrollBudget.tryBuy(1, 50, 450_000, 25_000, false));
    }

    @Test
    void theDailyCapScalesWithLevelAndResetsNextDay() {
        long rich = 100_000_000;
        assertEquals(750_000, ScrollBudget.maxPrice(2, 50, rich, false));
        assertTrue(ScrollBudget.tryBuy(2, 50, rich, 700_000, false));
        assertEquals(50_000, ScrollBudget.maxPrice(2, 50, rich, false));
        assertFalse(ScrollBudget.tryBuy(2, 50, rich, 60_000, false));
        day.incrementAndGet();
        assertEquals(750_000, ScrollBudget.maxPrice(2, 50, rich, false), "a new day");
    }

    @Test
    void countsCapBuysPlayerBuysAndRolls() {
        long rich = 1_000_000_000;
        for (int i = 0; i < ScrollBudget.MAX_FROM_PLAYERS; i++) {
            assertTrue(ScrollBudget.tryBuy(3, 120, rich, 1_000, true));
        }
        assertFalse(ScrollBudget.tryBuy(3, 120, rich, 1_000, true), "3 a day from real players");
        assertTrue(ScrollBudget.tryBuy(3, 120, rich, 1_000, false), "NPC and bot shops still");
        ScrollBudget.refund(3, 1_000, true);
        assertTrue(ScrollBudget.tryBuy(3, 120, rich, 1_000, true), "a refunded player buy frees its count");
        while (ScrollBudget.tryBuy(3, 120, rich, 1_000, false)) {
            // up to the daily count
        }
        assertEquals(0, ScrollBudget.maxPrice(3, 120, rich, false));
        for (int i = 0; i < ScrollBudget.MAX_ROLLS; i++) {
            assertTrue(ScrollBudget.tryRoll(3));
        }
        assertFalse(ScrollBudget.tryRoll(3));
        assertEquals(0, ScrollBudget.rollsLeft(3));
    }
}
