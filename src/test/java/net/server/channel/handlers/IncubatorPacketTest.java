package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import service.NoteService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P0-6 through the real packet path: UseCashItemHandler.handlePacket with an Incubator (5060002, CASH slot 3)
 * pointed at a Pigmy Egg (4170000, ETC slot 5). Written against the base code too: it only uses public API.
 */
class IncubatorPacketTest {
    // The inherited reward table (id -> quantity), duplicated so the test does not depend on the fix.
    private static final int[] IDS = {1012070, 1302049, 1302063, 1322027, 2000004, 2000005, 2020013, 2020015, 2040307,
            2040509, 2040519, 2040521, 2040533, 2040715, 2040717, 2040810, 2040811, 2070005, 2070006, 4020009};
    private static final int[] QTY = {1, 1, 1, 1, 240, 200, 200, 200, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3};

    private static final short CASH_POS = 3;
    private static final short EGG_POS = 5;

    @org.junit.jupiter.api.BeforeAll
    static void loadItemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test
    void anEggAndTheIncubatorAreConsumedOnceAndATableRewardIsGranted() {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.getLastUsedCashItem()).thenReturn(Long.MIN_VALUE / 4); // not used recently

        Inventory cash = mock(Inventory.class);
        Inventory etc = mock(Inventory.class);
        Item incubator = new Item(5060002, CASH_POS, (short) 1);
        Item egg = new Item(4170000, EGG_POS, (short) 1);
        when(cash.getItem(CASH_POS)).thenReturn(incubator);
        when(etc.getItem(EGG_POS)).thenReturn(egg);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        when(chr.getInventory(InventoryType.CASH)).thenReturn(cash);
        when(chr.getInventory(InventoryType.ETC)).thenReturn(etc);

        ByteBufOutPacket out = new ByteBufOutPacket();
        out.writeShort(CASH_POS);
        out.writeInt(5060002);
        out.writeInt(InventoryType.ETC.getType());
        out.writeInt(EGG_POS);

        try (var im = mockStatic(InventoryManipulator.class);
             var iip = mockStatic(server.ItemInformationProvider.class)) {
            iip.when(server.ItemInformationProvider::getInstance).thenReturn(mock(server.ItemInformationProvider.class));
            im.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), anyString())).thenReturn(true);
            im.when(() -> InventoryManipulator.addById(any(Client.class), anyInt(), anyShort())).thenReturn(true);

            new UseCashItemHandler(mock(NoteService.class))
                    .handlePacket(new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes())), c);

            // egg removed exactly once, incubator (cash) removed exactly once
            im.verify(() -> InventoryManipulator.removeFromSlot(eq(c), eq(InventoryType.ETC), eq(EGG_POS), eq((short) 1), anyBoolean()));
            im.verify(() -> InventoryManipulator.removeFromSlot(eq(c), eq(InventoryType.CASH), eq(CASH_POS), eq((short) 1), anyBoolean(), anyBoolean()));

            // exactly one reward, from the table, with its quantity
            ArgumentCaptor<Integer> id = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Short> qty = ArgumentCaptor.forClass(Short.class);
            im.verify(() -> InventoryManipulator.addById(eq(c), id.capture(), qty.capture()));
            List<Integer> ids = new ArrayList<>(id.getAllValues());
            assertEquals(1, ids.size(), "one grant expected");
            int idx = -1;
            for (int i = 0; i < IDS.length; i++) {
                if (IDS[i] == ids.get(0)) idx = i;
            }
            assertTrue(idx >= 0, "reward " + ids.get(0) + " must come from the table");
            assertEquals(QTY[idx], qty.getValue().intValue(), "reward quantity");
        }
    }
}
