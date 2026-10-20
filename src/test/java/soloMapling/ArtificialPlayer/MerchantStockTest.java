package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInventory;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.NXMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 26: a merchant knows what it is selling or buying, from its live trade state. */
class MerchantStockTest {

    @AfterEach
    void realNames() {
        MerchantStock.useItemNamesForTest(null);
    }

    private static <T extends BotSM> T merchant(Class<T> type, BotTradeSM.TradeMode mode, Item forSale,
                                               BotTradeWants wants) {
        T bot = mock(type);
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setName("Shopz");
        when(bot.getChr()).thenReturn(chr);
        BotTradeInventory inventory = new BotTradeInventory();
        if (forSale != null) {
            inventory.setItemForSaleMain(forSale);
        }
        when(bot.getTradeInventory()).thenReturn(inventory);
        when(bot.getTradeWants()).thenReturn(wants);
        when(bot.getTradeMode()).thenReturn(mode);
        return bot;
    }

    @Test
    void aSellerKnowsItsItemAndPrice() {
        MerchantStock.useItemNamesForTest(id -> id == 2070006 ? "Ilbi Throwing-Stars" : null);
        BotTradeWants wants = new BotTradeWants();
        wants.setMesoWanted(4_200_000);
        MerchantStock.Stock stock = MerchantStock.of(merchant(SellingMerchantBot.class, BotTradeSM.TradeMode.SELLING,
                new Item(2070006, (short) 0, (short) 1), wants));
        assertEquals(new MerchantStock.Stock(true, "Ilbi Throwing-Stars", 4_200_000, "ilbi"), stock);
        assertTrue(stock.fact().startsWith("You are selling Ilbi Throwing-Stars for 4.2m right now"), stock.fact());
        String line = stock.scripted(new Random(1));
        assertTrue(line.contains("ilbi throwing-stars") && line.contains("4.2m"), line);
        assertTrue(line.length() < 60 && line.equals(line.toLowerCase()), line);
    }

    @Test
    void aBuyerKnowsWhatItWantsAndPays() {
        MerchantStock.useItemNamesForTest(id -> "Dark Scroll for Claw for ATT 30%");
        BotTradeWants wants = new BotTradeWants();
        wants.addItemWanted(2044705, 1);
        wants.setMesoOffering(800_000);
        MerchantStock.Stock stock = MerchantStock.of(merchant(BuyingMerchantBot.class, BotTradeSM.TradeMode.BUYING,
                null, wants));
        assertEquals(false, stock.selling());
        assertEquals("claw", stock.keyWord());
        assertTrue(stock.fact().contains("You are buying Dark Scroll for Claw for ATT 30% right now and pay 800k"));
    }

    @Test
    void theNxMerchantSellsItsCodeAndAnIdleBotHasNoStock() {
        assertSame(MerchantStock.NX_CODE, MerchantStock.of(mock(NXMerchantBot.class)));
        assertNull(MerchantStock.of(merchant(SellingMerchantBot.class, BotTradeSM.TradeMode.NULL, null, new BotTradeWants())));
        assertNull(MerchantStock.of(mock(SellingMerchantBot.class)), "nothing set up yet");
        MerchantStock.useItemNamesForTest(id -> null);
        BotTradeWants wants = new BotTradeWants();
        wants.setMesoWanted(5_000);
        assertNull(MerchantStock.of(merchant(SellingMerchantBot.class, BotTradeSM.TradeMode.SELLING,
                new Item(1, (short) 0, (short) 1), wants)), "an item with no name is not talked about");
    }

    @Test
    void pricesReadTheWayPlayersTypeThem() {
        assertEquals("900", MerchantStock.mesos(900));
        assertEquals("250k", MerchantStock.mesos(250_000));
        assertEquals("1.5m", MerchantStock.mesos(1_500_000));
        assertEquals("12m", MerchantStock.mesos(12_000_000));
        assertEquals("120m", MerchantStock.mesos(120_400_000));
        assertEquals("2.1b", MerchantStock.mesos(2_100_000_000));
        assertEquals("gloves", MerchantStock.keyWord("Scroll for Gloves for ATT 60%"));
        assertEquals("elixir", MerchantStock.keyWord("Elixir"));
    }
}
