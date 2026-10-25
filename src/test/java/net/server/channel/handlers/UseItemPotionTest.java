package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.StatEffect;
import server.maps.MapleMap;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (owner 2026-10-25): a potion heals only the one who drinks it. MapleRoot applied it to every character on the
 * map while a Lion King's Castle "expedition" flag was set, and that flag leaked to every other map (death, GM or
 * script warps never cleared it), where potions then did nothing once the count ran out.
 */
class UseItemPotionTest {

    private static final int RED_POTION = 2000000;

    @org.junit.jupiter.api.BeforeAll
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

    @Test
    void aPotionHealsOnlyTheDrinkerEvenWithALeakedExpeditionFlag() throws Exception {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        Character neighbour = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory use = mock(Inventory.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);
        when(chr.getMap()).thenReturn(map);
        when(map.getCharacters()).thenReturn(List.of(chr, neighbour));
        when(use.getItem((short) 3)).thenReturn(new Item(RED_POTION, (short) 3, (short) 10));
        leakExpeditionFlag(chr);

        InPacket p = mock(InPacket.class);
        when(p.readShort()).thenReturn((short) 3);
        when(p.readInt()).thenReturn(0, RED_POTION);

        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        StatEffect potion = mock(StatEffect.class);
        when(ii.getItemEffect(RED_POTION)).thenReturn(potion);
        try (var iip = mockStatic(ItemInformationProvider.class); var im = mockStatic(InventoryManipulator.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(ii);
            new UseItemHandler().handlePacket(p, c);
        }

        verify(potion).applyTo(chr);
        verify(potion, never()).applyTo(neighbour);
        verify(chr, never()).dropMessage(org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** Sets MapleRoot's leaked flags the way a death inside Lion King's Castle left them, while they still exist. */
    private static void leakExpeditionFlag(Character chr) throws Exception {
        for (var f : Character.class.getFields()) {
            if (f.getName().equals("inExpedition")) {
                f.setBoolean(chr, true);
            }
        }
    }
}
