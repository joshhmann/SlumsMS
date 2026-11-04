package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import net.packet.InPacket;
import net.server.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.TimerManager;
import service.NoteService;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Vega's Spell (5610000/5610001) must apply the scroll the player feeds it. The branch used to call the
 * 1-arg {@code scrollEquipWithId}, which never looks at the fed scroll and -- at level 15 and above, where its
 * level table returns 0 -- changes nothing while the scroll is consumed anyway.
 */
class VegaAppliesFedScrollTest {
    private static final int SPELL = ItemId.VEGAS_SPELL_60;
    private static final int SCROLL = 2043101;   // 60% scroll, the rate Vega's Spell (60%) accepts
    private static final int EQUIP = 1302000;

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test
    void theFedScrollsStatsApplyOnAnEquipAtLevel15() {
        Map<String, Integer> scrollStats = new LinkedHashMap<>();
        scrollStats.put("success", 60);
        scrollStats.put("cursed", 0);
        scrollStats.put("STR", 2);
        scrollStats.put("PAD", 3);

        // A spy over the real provider: the scroll engine runs for real, only the WZ lookup of this scroll is stubbed.
        ItemInformationProvider ii = spy(ItemInformationProvider.getInstance());
        doReturn(scrollStats).when(ii).getEquipStats(SCROLL);

        Equip equip = new Equip(EQUIP, (short) 1, 5);
        equip.setLevel((byte) 15);

        Character player = mock(Character.class);
        Inventory equipInv = mock(Inventory.class);
        Inventory useInv = mock(Inventory.class);
        Inventory cashInv = mock(Inventory.class);
        Inventory equippedInv = mock(Inventory.class);
        when(player.getInventory(InventoryType.EQUIP)).thenReturn(equipInv);
        when(player.getInventory(InventoryType.USE)).thenReturn(useInv);
        when(player.getInventory(InventoryType.CASH)).thenReturn(cashInv);
        when(player.getInventory(InventoryType.EQUIPPED)).thenReturn(equippedInv);
        when(equipInv.getItem((short) 1)).thenReturn(equip);
        when(useInv.getItem((short) 2)).thenReturn(new Item(SCROLL, (short) 2, (short) 1));
        when(cashInv.getItem((short) 1)).thenReturn(new Item(SPELL, (short) 1, (short) 1));

        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(player);

        InPacket p = mock(InPacket.class);
        when(p.readShort()).thenReturn((short) 1);
        // cash slot, cash item id, then 561's own reads: marker 1, equip slot, marker 2, use slot
        when(p.readInt()).thenReturn(SPELL, 1, 1, 2, 2);

        try (var iip = mockStatic(ItemInformationProvider.class, CALLS_REAL_METHODS);
             var manipulator = mockStatic(InventoryManipulator.class);
             var timers = mockStatic(TimerManager.class);
             var server = mockStatic(Server.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(ii);
            iip.when(() -> ItemInformationProvider.rollSuccessChance(anyDouble())).thenReturn(true);
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            Server s = mock(Server.class);
            when(s.getCurrentTime()).thenReturn(10_000L);
            server.when(Server::getInstance).thenReturn(s);

            new UseCashItemHandler(mock(NoteService.class)).handlePacket(p, client);
        }

        assertEquals(2, equip.getStr(), "the fed scroll's STR applies");
        assertEquals(3, equip.getWatk(), "the fed scroll's ATT applies");
        assertEquals(4, equip.getUpgradeSlots(), "the fed scroll spends one of the five slots");
        assertEquals(16, equip.getLevel());
    }
}
