package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// b271: the item pool does not value every item. A missing market value means "no known value", so the bot skips the
// item, keeps scanning the shop and warns about the item id once per JVM; a value the pool knows is judged as before.
class FMBotMarketValueTest {

    // The warning set is per JVM, so every test uses its own item id.
    private static final int NO_VALUE_BUY = 9_007_001;
    private static final int NO_VALUE_WARN = 9_007_010;
    private static final int NO_VALUE_THROUGH_DECISION = 9_007_020;

    @Test
    void anItemWithNoMarketValueIsNotBought() {
        assertFalse(FMBot.shouldBuyListedItem(NO_VALUE_BUY, 1, null, null),
                "no known value must mean no purchase, not a free item");
    }

    @Test
    void aMissingMarketValueIsWarnedOncePerItemId() {
        assertTrue(FMBot.noteMissingMarketValue(NO_VALUE_WARN), "the first sighting is logged");
        assertFalse(FMBot.noteMissingMarketValue(NO_VALUE_WARN), "the same item id is not logged again");
        assertTrue(FMBot.noteMissingMarketValue(NO_VALUE_WARN + 1), "another item id is its own warning");
    }

    @Test
    void theMissingValueWarningSurvivesThePurchaseAttempt() {
        FMBot.shouldBuyListedItem(NO_VALUE_THROUGH_DECISION, 500, null, null);
        assertFalse(FMBot.noteMissingMarketValue(NO_VALUE_THROUGH_DECISION),
                "the purchase decision already warned about this id");
    }

    @Test
    void aMissingAskingPriceIsNotBought() {
        assertFalse(FMBot.shouldPurchaseItem(null, 100), "no asking price is not a throw");
        assertFalse(FMBot.shouldPurchaseItem(100, null), "no market value is not a throw");
    }

    @Test
    void itemsWithAKnownValueAreJudgedByPrice() {
        assertTrue(FMBot.shouldBuyListedItem(1, 700, 1_000, null), "0.7x the value is always taken");
        assertFalse(FMBot.shouldBuyListedItem(1, 120_001, 100_000, null), "over 1.2x the value is never taken");
        assertFalse(FMBot.shouldBuyListedItem(1, 700, 1_000, "not a type bots buy"),
                "a real player's item the seller rules exclude stays unbought");
    }

    @Test
    void aPlayerShopItemWithNoFairPriceAndNoPoolValueStaysUnvalued() {
        // b271: the live NPE at FMBot.purchaseShopItems:529 - `fair > 0 ? (int) ... : itemMarketValue` unboxed a null
        assertTrue(FMBot.playerShopValue(0, null) == null);
        assertTrue(FMBot.playerShopValue(1_500, null) == 1_500);
        assertTrue(FMBot.playerShopValue(0, 700) == 700);
    }
}
