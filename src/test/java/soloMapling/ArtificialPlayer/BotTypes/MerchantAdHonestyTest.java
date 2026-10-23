package soloMapling.ArtificialPlayer.BotTypes;

import client.inventory.Item;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 say-do: an "S> item" ad only for an item the bot holds; a "B> item" ad only for one on its wish list. */
class MerchantAdHonestyTest {

    @Test
    void sellOnlyWhatItHolds() {
        assertTrue(SellingMerchantBot.holdsForSale(new Item(2040804, (short) 0, (short) 1), 0, 2040804), "in the trade window");
        assertTrue(SellingMerchantBot.holdsForSale(null, 3, 2040804), "in the bag");
        assertFalse(SellingMerchantBot.holdsForSale(new Item(2040805, (short) 0, (short) 1), 0, 2040804));
        assertFalse(SellingMerchantBot.holdsForSale(null, 0, 2040804));
    }

    @Test
    void buyOnlyWhatItWants() {
        BotTradeWants wants = new BotTradeWants();
        wants.addItemWanted(2044703, 1);
        assertTrue(BuyingMerchantBot.wants(wants, 2044703));
        assertFalse(BuyingMerchantBot.wants(wants, 2040804));
        assertFalse(BuyingMerchantBot.wants(new BotTradeWants(), 2044703));
    }
}
