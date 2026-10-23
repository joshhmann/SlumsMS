package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.HiredMerchant;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.0 rc6 bug hunt (P0): a haggle overflow could leave a listing at 0 mesos (a free item) or a negative price (on
 * 1.4.1, where the total wasn't range-checked, buying it paid the buyer). Every shop purchase path refuses a listing
 * priced 0 or less, whatever wrote it.
 */
class ZeroPriceListingTest {

    private static final int POTION = 2000000;

    @BeforeEach
    void reset() {
        BotHoldings.clearForTest();
    }

    private static Character bot(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Test" + id);
        return chr;
    }

    private static PlayerShopItem listing(int price) {
        return new PlayerShopItem(new Item(POTION, (short) 0, (short) 1, -1), (short) 3, price);
    }

    @Test
    void aHiredMerchantRefusesAPlayerBuyingAFreeOrNegativeListing() {
        for (int price : new int[]{0, -2_094_967_296}) {
            Character owner = mock(Character.class);
            when(owner.getPosition()).thenReturn(new Point(0, 0));
            when(owner.getClient()).thenReturn(mock(Client.class));
            HiredMerchant merchant = new HiredMerchant(owner, "test", 5030000);
            PlayerShopItem item = listing(price);
            assertTrue(merchant.addItem(item));

            Character buyer = mock(Character.class);
            Client c = mock(Client.class);
            when(c.getPlayer()).thenReturn(buyer);
            merchant.buy(c, 0, (short) 1);

            verify(buyer, never()).gainMeso(anyInt(), anyBoolean());
            assertEquals(3, item.getBundles(), "nothing sold at " + price);
            assertTrue(item.isExist());
        }
    }

    @Test
    void aHiredMerchantRefusesABotBuyingAFreeListing() {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getClient()).thenReturn(mock(Client.class));
        HiredMerchant merchant = new HiredMerchant(owner, "test", 5030000);
        PlayerShopItem item = listing(0);
        assertTrue(merchant.addItem(item));
        Character buyer = bot(BotStore.BOT_ID_BASE + 2_000);
        assertTrue(BotHoldings.grantMeso(buyer, 1_000));

        assertFalse(merchant.botBuy(buyer, item, (short) 1));
        assertEquals(1_000, buyer.getMeso());
        assertEquals(3, item.getBundles());
    }

    @Test
    void aPlayerShopRefusesABotBuyingAFreeOrNegativeListing() {
        for (int price : new int[]{0, -5}) {
            Character owner = bot(BotStore.BOT_ID_BASE + 2_001);
            PlayerShop shop = new PlayerShop(owner, "test stall", 0);
            PlayerShopItem item = listing(price);
            assertTrue(shop.addItem(item));
            shop.setOpen(true);
            Character buyer = bot(BotStore.BOT_ID_BASE + 2_002);
            assertTrue(BotHoldings.grantMeso(buyer, 1_000));

            assertFalse(shop.botBuy(buyer, item, 0, (short) 1));
            assertEquals(1_000, buyer.getMeso(), "the buyer neither paid nor was paid at " + price);
            assertEquals(0, owner.getMeso());
            assertEquals(3, item.getBundles());
        }
    }
}
