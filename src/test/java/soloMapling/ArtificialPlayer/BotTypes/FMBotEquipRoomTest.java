package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * b181 F-08: making room looked the bought equip up by item id, so when the bot already owned a copy of the very
 * equip it bought, the older copy was the one destroyed. The record is the purchased instance now.
 */
class FMBotEquipRoomTest {

    private static final int SWORD = 1302000;

    // As BotScrollingTest: load ItemInformationProvider once with no DB and no WZ behind it.
    @org.junit.jupiter.api.BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = org.mockito.Mockito.mockStatic(tools.DatabaseConnection.class);
             var providers = org.mockito.Mockito.mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(org.mockito.Mockito.mock(java.sql.Connection.class,
                            org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                            org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class)))
                    .thenReturn(org.mockito.Mockito.mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test
    void makingRoomLetsGoOfTheBoughtCopyAndKeepsAnOlderOneOfTheSameEquip() throws Exception {
        Character chr = bot(64_777, "Shopr8");
        Equip ownedBefore = new Equip(SWORD, (short) 1, 7);
        chr.getInventory(InventoryType.EQUIP).addItem(ownedBefore);
        Equip bought = new Equip(SWORD, (short) 2, 0);
        chr.getInventory(InventoryType.EQUIP).addItem(bought);
        FMBot shopper = new FMBot(chr);
        track(shopper, bought);

        assertTrue(makeRoom(shopper), "it made room by letting a bought equip go");
        assertNotNull(chr.getInventory(InventoryType.EQUIP).getItem(ownedBefore.getPosition()),
                "the copy the bot already owned stays");
        assertNull(chr.getInventory(InventoryType.EQUIP).getItem(bought.getPosition()),
                "the copy this shop trip bought is the one that went");
    }

    @Test
    void aPurchaseIsRecordedAsTheInstanceItAdded() throws Exception {
        Character chr = bot(64_778, "Shopr9");
        Equip ownedBefore = new Equip(SWORD, (short) 1, 7);
        chr.getInventory(InventoryType.EQUIP).addItem(ownedBefore);
        List<Item> bagBeforePurchase = new ArrayList<>(chr.getInventory(InventoryType.EQUIP).list());

        FMBot shopper = new FMBot(chr);
        Equip bought = new Equip(SWORD, (short) 1, 0);
        chr.getInventory(InventoryType.EQUIP).addItem(bought);   // the purchase lands
        rememberBoughtEquip(shopper, bagBeforePurchase);

        assertSame(bought, tracked(shopper).peekLast(),
                "the record names the copy the purchase added, not the one already in the bag");
    }

    private static Character bot(int offset, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + offset);
        chr.setName(name);
        chr.setMapId(910000000);
        return chr;
    }

    @SuppressWarnings("unchecked")
    private static Deque<Object> tracked(FMBot bot) throws Exception {
        Field field = tracker();
        field.setAccessible(true);
        return (Deque<Object>) field.get(bot);
    }

    /** Records the purchase the way the shop loop does: the idle tracker holds item ids, the fixed one holds items. */
    private static void track(FMBot bot, Item purchase) throws Exception {
        Field field = tracker();
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Deque<Object> tracked = (Deque<Object>) field.get(bot);
        tracked.addLast(tracksIds(field) ? (Object) purchase.getItemId() : purchase);
    }

    private static Field tracker() throws NoSuchFieldException {
        try {
            return FMBot.class.getDeclaredField("boughtEquips");
        } catch (NoSuchFieldException oldName) {
            return FMBot.class.getDeclaredField("boughtEquipIds");
        }
    }

    private static boolean tracksIds(Field field) {
        return field.getGenericType() instanceof ParameterizedType type
                && type.getActualTypeArguments()[0] == Integer.class;
    }

    private static boolean makeRoom(FMBot bot) throws Exception {
        Method method = FMBot.class.getDeclaredMethod("makeRoom", InventoryType.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(bot, InventoryType.EQUIP);
    }

    private static void rememberBoughtEquip(FMBot bot, List<Item> bagBeforePurchase) throws Exception {
        Method method = FMBot.class.getDeclaredMethod("rememberBoughtEquip", List.class);
        method.setAccessible(true);
        method.invoke(bot, bagBeforePurchase);
    }
}
