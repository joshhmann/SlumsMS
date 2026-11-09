package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.packet.InPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.StatEffect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.8 useitem-script-item-eaten: QA's Cygnus cell on TEST-B (9 Nov) sent the Thunder Stone 2430200, whose Item.wz
 * spec runs dreamkey.js, as a plain item use (USE_ITEM 0x48) instead of SCRIPTED_ITEM (0x4E). The handler would have
 * removed it with no effect. A Use item with a script is now refused and keeps its slot.
 */
class UseItemScriptItemTest {

    private static final int THUNDER_STONE = 2430200;
    private static final int RED_POTION = 2000000;

    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    private record Used(Client c, Character chr) {
    }

    /** Sends USE_ITEM for {@code itemId} in USE slot 5 and returns the client and character it ran against. */
    private static Used use(int itemId, ItemInformationProvider ii) {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        Inventory use = mock(Inventory.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);
        when(use.getItem((short) 5)).thenReturn(new Item(itemId, (short) 5, (short) 1));
        InPacket p = mock(InPacket.class);
        when(p.readShort()).thenReturn((short) 5);
        when(p.readInt()).thenReturn(0, itemId);
        try (var iip = mockStatic(ItemInformationProvider.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(ii);
            new UseItemHandler().handlePacket(p, c);
        }
        return new Used(c, chr);
    }

    @Test
    void aScriptItemSentAsAPlainUseIsRefusedAndKept() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.getScriptedItemInfo(THUNDER_STONE))
                .thenReturn(new ItemInformationProvider.ScriptedItem(2142006, "dreamkey", false));
        StatEffect effect = mock(StatEffect.class);
        when(ii.getItemEffect(THUNDER_STONE)).thenReturn(effect);

        try (var im = mockStatic(InventoryManipulator.class)) {
            Used u = use(THUNDER_STONE, ii);
            im.verify(() -> InventoryManipulator.removeFromSlot(any(Client.class), eq(InventoryType.USE), anyShort(),
                    anyShort(), anyBoolean()), never());
            verify(effect, never()).applyTo(any());
            verify(u.c()).sendPacket(any());   // the client's actions are unlocked
        }
    }

    @Test
    void aPotionIsStillUsedAndTaken() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);   // no script info for 2000000
        StatEffect potion = mock(StatEffect.class);
        when(ii.getItemEffect(RED_POTION)).thenReturn(potion);

        try (var im = mockStatic(InventoryManipulator.class)) {
            Used u = use(RED_POTION, ii);
            im.verify(() -> InventoryManipulator.removeFromSlot(any(Client.class), eq(InventoryType.USE), eq((short) 5),
                    eq((short) 1), eq(false)));
            verify(potion).applyTo(u.chr());
        }
    }
}
