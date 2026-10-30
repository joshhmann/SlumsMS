package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 162 runfix (t-oa-1 on TEST 10-30): the live pot scan must survive the scrolls and arrows a bot carries. */
class PartySupportPotsTest {

    // As BotScrollingTest: load ItemInformationProvider once with no DB and no WZ behind it.
    @org.junit.jupiter.api.BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test
    void thePotScanSkipsItemsWithNoEffectData() throws Exception {
        // Fail-before: getItemEffect threw an NPE for the scroll (as the real provider does for scrolls, arrows, stars,
        // bullets, summon sacks), pots() threw out of tick() and an owned alt's tick died before its gate every time.
        BotPartySupport.useForTest(null);
        Field f = BotPartySupport.class.getDeclaredField("effects");
        f.setAccessible(true);
        BotPartySupport.Effects live = (BotPartySupport.Effects) f.get(null);
        Character bot = mock(Character.class);
        Inventory use = new Inventory(null, InventoryType.USE, (byte) 24);
        use.addItemFromDB(new Item(2043001, (short) 1, (short) 2));   // a scroll
        use.addItemFromDB(new Item(2060000, (short) 2, (short) 500)); // arrows
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        server.ItemInformationProvider ii = mock(server.ItemInformationProvider.class);
        when(ii.getItemEffect(org.mockito.ArgumentMatchers.anyInt())).thenThrow(
                new NullPointerException("Cannot invoke \"provider.Data.getChildByPath(String)\" because \"data\" is null"));
        try (var provider = mockStatic(server.ItemInformationProvider.class)) {
            provider.when(server.ItemInformationProvider::getInstance).thenReturn(ii);
            List<BotPartySupport.Pot> pots = assertDoesNotThrow(() -> live.pots(bot));
            assertTrue(pots.isEmpty(), "pots " + pots);
        }
        assertFalse(BotPartySupport.mayBePot(2043001));
        assertFalse(BotPartySupport.mayBePot(2060000));
        assertFalse(BotPartySupport.mayBePot(2340000));
        assertTrue(BotPartySupport.mayBePot(2002000));
        assertTrue(BotPartySupport.mayBePot(2022000));
    }
}
