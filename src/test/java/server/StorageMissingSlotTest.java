package server;

import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 1.5.2 auto-QA vb-7: a take-out packet for a slot the storage does not have threw "Index: -1, Size: 0" inside the
 * storage handler (getSlot found nothing, getItem(-1) threw). The handler already ignores a null item.
 */
class StorageMissingSlotTest {

    private static Storage storage() throws Exception {
        Constructor<Storage> c = Storage.class.getDeclaredConstructor(int.class, byte.class, int.class);
        c.setAccessible(true);
        return c.newInstance(1, (byte) 4, 0);
    }

    @Test
    void anEmptyStorageHasNoSlotAndNoItem() throws Exception {
        Storage s = storage();
        assertEquals(-1, s.getSlot(InventoryType.ETC, (byte) 0));
        assertNull(s.getItem(s.getSlot(InventoryType.ETC, (byte) 0)));
    }

    @Test
    void aSlotPastTheItemsOfThatTypeIsNoSlot() throws Exception {
        Storage s = storage();
        Item snail = new Item(4000000, (short) 0, (short) 1);
        s.store(snail);
        assertEquals(0, s.getSlot(InventoryType.ETC, (byte) 0));
        assertSame(snail, s.getItem((byte) 0));
        assertEquals(-1, s.getSlot(InventoryType.ETC, (byte) 1));
        assertEquals(-1, s.getSlot(InventoryType.USE, (byte) 0), "no USE items stored at all");
        assertNull(s.getItem((byte) 3));
    }
}
