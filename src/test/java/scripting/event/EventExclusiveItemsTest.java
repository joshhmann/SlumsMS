package scripting.event;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 162 runfix (t-vb-3 on TEST 10-30): a logged-out bot leaving a PQ must not stop the PQ's end. */
class EventExclusiveItemsTest {

    @Test
    void aCharacterWithNoLivePlayerOnItsClientLosesTheItemsWithoutThrowing() {
        // Fail-before: api.removeAll -> cl.getPlayer().getInventory(...) threw an NPE inside playerExit, the KPQ's
        // end() never finished and the channel's only Kerning PQ lobby stayed locked.
        Character chr = mock(Character.class);
        Client cl = mock(Client.class);
        when(chr.getClient()).thenReturn(cl);
        when(cl.getPlayer()).thenReturn(null);
        when(chr.getName()).thenReturn("loggedOutBot");
        Inventory etc = new Inventory(null, InventoryType.ETC, (byte) 24);
        etc.addItemFromDB(new Item(4001007, (short) 1, (short) 12));   // KPQ coupons
        etc.addItemFromDB(new Item(4001008, (short) 2, (short) 1));    // a KPQ pass
        when(chr.getInventory(InventoryType.ETC)).thenReturn(etc);
        assertDoesNotThrow(() -> EventInstanceManager.dropExclusiveItems(chr, List.of(4001007, 4001008)));
        assertEquals(0, etc.countById(4001007));
        assertEquals(0, etc.countById(4001008));
        verify(chr, never()).getAbstractPlayerInteraction();
    }
}
