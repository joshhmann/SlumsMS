package server;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Trade items must not vanish when the receiver's inventory filled while the window was open, and the trade window
 * must refuse a tenth item and any item added after the offer was locked.
 * Ported from nutnnut/Cosmic (nutnnut), server/Trade.java.
 */
class TradeItemLossTest {

    @Test
    void itemThatDoesNotFitIsDroppedAtTheReceiversFeet() {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        MapleMap map = mock(MapleMap.class);
        Point pos = new Point(5, 6);
        when(chr.getClient()).thenReturn(client);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(pos);
        Item item = new Item(2000000, (short) 1, (short) 3, -1);

        try (MockedStatic<InventoryManipulator> im = mockStatic(InventoryManipulator.class)) {
            im.when(() -> InventoryManipulator.addFromDrop(client, item, false)).thenReturn(false);
            Trade.giveOrDrop(chr, item, false);
        }

        verify(map).spawnItemDrop(chr, chr, item, pos, true, true);
        verify(chr).dropMessage(eq(1), any(String.class));
    }

    @Test
    void itemThatFitsIsNotDropped() {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        MapleMap map = mock(MapleMap.class);
        when(chr.getClient()).thenReturn(client);
        when(chr.getMap()).thenReturn(map);
        Item item = new Item(2000000, (short) 1, (short) 3, -1);

        try (MockedStatic<InventoryManipulator> im = mockStatic(InventoryManipulator.class)) {
            im.when(() -> InventoryManipulator.addFromDrop(client, item, false)).thenReturn(true);
            Trade.giveOrDrop(chr, item, false);
        }

        verify(map, never()).spawnItemDrop(any(), any(), any(Item.class), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void windowHoldsNineItemsAndRefusesAfterLock() {
        Character chr = mock(Character.class);
        Character other = mock(Character.class);
        Trade mine = new Trade((byte) 0, chr);
        Trade theirs = new Trade((byte) 1, other);
        mine.setPartner(theirs);
        theirs.setPartner(mine);

        try (MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class)) {
            for (int slot = 1; slot <= 9; slot++) {
                assertTrue(mine.addItem(new Item(2000000, (short) slot, (short) 1, -1)), "slot " + slot);
            }
            assertFalse(mine.addItem(new Item(2000000, (short) 10, (short) 1, -1)), "tenth item");
        }

        Trade locking = new Trade((byte) 0, chr);
        Trade lockPartner = new Trade((byte) 1, other);
        locking.setPartner(lockPartner);
        lockPartner.setPartner(locking);
        try (MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class)) {
            java.lang.reflect.Method m = null;
            try {
                m = Trade.class.getDeclaredMethod("lockTrade");
                m.setAccessible(true);
                m.invoke(locking);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            assertFalse(locking.addItem(new Item(2000000, (short) 1, (short) 1, -1)), "locked trade");
        }
    }
}
