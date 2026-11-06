package server.maps;

import client.Character;
import client.Client;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.awt.Point;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 1.7.0 P0-2 fail-before at public entry points that exist on the 1.6.5 base too ({@code HiredMerchant.forceClose},
 * {@code HiredMerchant.buy}, {@code PlayerShop.buy}): a close that hits a SQL error must leave no half-written
 * Fredrick state, the retry must work, and a bad shop index must be refused without a throw or a change.
 * The database is the recording {@link MerchantFakeDb}; no MySQL is needed.
 */
class MerchantEntryPointTest {

    private static HiredMerchant shopWithTwoLines() {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getClient()).thenReturn(mock(Client.class));
        when(owner.getId()).thenReturn(77);
        HiredMerchant shop = new HiredMerchant(owner, "test", 5030000);
        shop.addItem(new PlayerShopItem(new Item(4000000, (short) 0, (short) 5), (short) 2, 100));
        shop.addItem(new PlayerShopItem(new Item(4000001, (short) 0, (short) 1), (short) 1, 250));
        shop.setOpen(true);
        return shop;
    }

    @Test
    void aSqlErrorMidCloseLeavesNothingHalfWrittenAndTheRetrySucceedsOnce() {
        MerchantFakeDb db = new MerchantFakeDb();
        HiredMerchant shop = shopWithTwoLines();
        try (MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class)) {
            dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> db.open());

            db.failOn = "INSERT INTO `inventoryitems`";
            assertDoesNotThrow(shop::forceClose);

            assertFalse(db.anyCommitted("DELETE FROM `inventorymerchant`"),
                    "the old Fredrick rows must not be deleted when the new ones cannot be written");
            assertFalse(db.anyCommitted("DELETE `inventoryitems`"), "the stored items must not be deleted either");
            assertFalse(db.anyCommitted("INSERT INTO `fredstorage`"), "no Fredrick log for a close that failed");
            assertFalse(db.anyCommitted("HasMerchant"), "the flag stays set: the close did not happen");

            db.failOn = null;
            assertDoesNotThrow(shop::forceClose);

            assertTrue(db.anyCommitted("INSERT INTO `inventoryitems`"), "the retry stores the lines");
            assertTrue(db.anyCommitted("INSERT INTO `fredstorage`"));
            long flagWrites = db.conns.stream().flatMap(c -> c.durable.stream()).filter(q -> q.contains("HasMerchant")).count();
            assertEquals(1, flagWrites, "the retry clears the flag exactly once");
            long inserts = db.conns.stream().flatMap(c -> c.durable.stream())
                    .filter(q -> q.contains("INSERT INTO `inventoryitems`")).count();
            assertEquals(2, inserts, "both lines stored once, nothing duplicated by the failed first try");
        }
    }

    private static Client buyerClient(Character buyer) {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(buyer);
        return c;
    }

    @Test
    void hiredMerchantBuyRefusesABadOrStaleIndexAndChangesNothing() {
        HiredMerchant shop = shopWithTwoLines();
        Character buyer = mock(Character.class);
        when(buyer.getMeso()).thenReturn(1_000_000);
        Client c = buyerClient(buyer);
        PlayerShopItem sold = shop.getItems().get(1);
        sold.setBundles((short) 0);
        sold.setDoesExist(false);

        for (int index : new int[]{-1, 2, 99, 1 /* stale: sold out */}) {
            assertDoesNotThrow(() -> shop.buy(c, index, (short) 1), "index " + index);
        }

        verify(buyer, never()).gainMeso(anyInt(), anyBoolean());
        assertEquals(2, shop.getItems().get(0).getBundles(), "the live line is untouched");
        assertEquals(0, shop.getItems().get(1).getBundles());
        verify(c, atLeast(4)).sendPacket(any());
    }

    @Test
    void playerShopBuyRefusesABadOrStaleIndexAndChangesNothing() throws Exception {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        PlayerShop shop = new PlayerShop(owner, "test", 4080000);
        shop.addItem(new PlayerShopItem(new Item(4000000, (short) 0, (short) 5), (short) 2, 100));
        PlayerShopItem gone = new PlayerShopItem(new Item(4000001, (short) 0, (short) 1), (short) 0, 250);
        gone.setDoesExist(false);
        shop.addItem(gone);
        Character buyer = mock(Character.class);
        when(buyer.getMeso()).thenReturn(1_000_000);
        Field visitors = PlayerShop.class.getDeclaredField("visitors");
        visitors.setAccessible(true);
        ((Character[]) visitors.get(shop))[0] = buyer;
        Client c = buyerClient(buyer);

        for (int index : new int[]{-1, 2, 99, 1}) {
            int i = index;
            assertFalse(assertDoesNotThrow(() -> shop.buy(c, i, (short) 1), "index " + i), "index " + i);
        }

        verify(buyer, never()).gainMeso(anyInt(), anyBoolean());
        verify(owner, never()).gainMeso(anyInt(), anyBoolean());
    }

    @Test
    void aBotCannotBuyAStaleOrForeignLine() {
        HiredMerchant shop = shopWithTwoLines();
        Character bot = mock(Character.class);
        when(bot.getMeso()).thenReturn(1_000_000);
        PlayerShopItem foreign = new PlayerShopItem(new Item(4000009, (short) 0, (short) 1), (short) 1, 10);

        assertFalse(shop.botBuy(bot, foreign, (short) 1), "a line that is not in this shop");
        assertFalse(shop.botBuy(bot, shop.getItems().get(0), (short) 3), "more bundles than the line has");

        verify(bot, never()).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        assertEquals(2, shop.getItems().get(0).getBundles());
    }

    @Test
    void hiredMerchantTakeItemBackRefusesABadOrStaleSlotAndChangesNothing() {
        HiredMerchant shop = shopWithTwoLines();
        Character owner = mock(Character.class);

        for (int slot : new int[]{-1, 2, 99}) {
            assertDoesNotThrow(() -> shop.takeItemBack(slot, owner), "slot " + slot);
        }

        assertEquals(2, shop.getItems().size(), "no line was removed");
        assertEquals(2, shop.getItems().get(0).getBundles());
        verify(owner, never()).gainMeso(anyInt(), anyBoolean());
    }

    @Test
    void playerShopTakeItemBackRefusesABadOrStaleSlotAndChangesNothing() {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        PlayerShop shop = new PlayerShop(owner, "test", 4080000);
        shop.addItem(new PlayerShopItem(new Item(4000000, (short) 0, (short) 5), (short) 2, 100));

        for (int slot : new int[]{-1, 1, 99}) {
            assertDoesNotThrow(() -> shop.takeItemBack(slot, owner), "slot " + slot);
        }

        assertEquals(1, shop.getItems().size());
        assertEquals(2, shop.getItems().get(0).getBundles());
    }

    @Test
    void onlySalesWithARealPlayerAreLoggedAtInfo() {
        assertFalse(MarketLog.isBotToBot(MarketLog.saleLine("merchant", "real", "bot")));
        assertFalse(MarketLog.isBotToBot(MarketLog.saleLine("merchant", "bot", "real")));
        assertFalse(MarketLog.isBotToBot(MarketLog.refusedLine("playershop", "real", "real", "funds")));
        assertTrue(MarketLog.isBotToBot(MarketLog.saleLine("merchant", "bot", "bot")));
    }
}
