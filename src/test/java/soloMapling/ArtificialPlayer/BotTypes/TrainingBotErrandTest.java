package soloMapling.ArtificialPlayer.BotTypes;

import constants.id.MapId;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Need;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST bot watch 2026-10-26 (t-bw-1): roguez's RESTOCK loop in a town with no store and its logout mid-RESTOCK, and
 * emorocker's SELL stop in the Free Market with nothing to sell.
 */
class TrainingBotErrandTest {

    static final int TEMPLE_OF_TIME = 270000000;

    @Test
    void aTownWithoutAStoreCannotAnswerARestock() {
        assertFalse(TrainingBot.restockPossible(TEMPLE_OF_TIME, () -> false), "Temple of Time sells nothing");
        assertTrue(TrainingBot.restockPossible(MapId.HENESYS, () -> true));
        assertTrue(TrainingBot.restockPossible(MapId.SLEEPYWOOD, () -> false), "Sleepywood's restock is the sauna");
        assertTrue(TrainingBot.restockPossible(-1, () -> false), "no home town yet: as before");
        assertTrue(TrainingBot.restockPossible(TEMPLE_OF_TIME, () -> { throw new IllegalStateException("no data"); }),
                "no map data: as before");
    }

    @Test
    void supplyPressureThatNoStoreCanAnswerIsDropped() {
        Map<Need, Double> needs = new EnumMap<>(Need.class);
        needs.put(Need.SUPPLIES, 1.0); // a ranger out of MP potions
        needs.put(Need.PROGRESS, .5);
        TrainingBot.dropUnanswerableSupplies(needs, false);
        assertFalse(needs.containsKey(Need.SUPPLIES));
        assertEquals(.5, needs.get(Need.PROGRESS));
        needs.put(Need.SUPPLIES, 1.0);
        TrainingBot.dropUnanswerableSupplies(needs, true);
        assertEquals(1.0, needs.get(Need.SUPPLIES), "a town with a store keeps the pressure");
    }

    @Test
    void aRestockTripHoldsTheLogoutUntilTheShoppingIsDone() {
        assertTrue(TrainingBot.onErrand(TrainingBot.Phase.GO_TOWN, false));
        assertTrue(TrainingBot.onErrand(TrainingBot.Phase.SHOP_TRAVEL, false));
        assertTrue(TrainingBot.onErrand(TrainingBot.Phase.SHOP_DWELL, false));
        assertTrue(TrainingBot.onErrand(TrainingBot.Phase.IN_TOWN, true), "walking up to the town's shopkeeper");
        assertFalse(TrainingBot.onErrand(TrainingBot.Phase.IN_TOWN, false), "standing about in town after it");
        assertFalse(TrainingBot.onErrand(TrainingBot.Phase.SHOP_RETURN, false));
        assertFalse(TrainingBot.onErrand(TrainingBot.Phase.GRIND, false));
        assertFalse(TrainingBot.onErrand(TrainingBot.Phase.LIFE_DWELL, false));
    }

    @Test
    void aSellStopWithNothingToSellIsCutShort() {
        long planned = 12 * 60_000L;
        assertEquals(TrainingBot.SELL_NOTHING_STAY_MS, TrainingBot.sellStayMs(false, 0, planned));
        assertEquals(planned, TrainingBot.sellStayMs(false, 44, planned), "a junk sale keeps the stay");
        assertEquals(planned, TrainingBot.sellStayMs(true, 0, planned), "an open shop keeps the stay");
        assertEquals(20_000L, TrainingBot.sellStayMs(false, 0, 20_000L), "never longer than planned");
    }
}
