package client.inventory;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic 6050d1d9d): Inventory.list() handed out a live view of the inventory map
 * and released the lock before the caller walked it. A bot tick counting potions (or any findById) on one thread while
 * the inventory changed on another threw ConcurrentModificationException out of the walk. The caller must get a copy
 * taken under the lock. The write here stands in for the other thread, so the race is hit every run.
 */
class InventoryListSnapshotTest {

    private static Inventory inventoryWith(int... itemIds) {
        Inventory inv = new Inventory(null, InventoryType.ETC, (byte) 24);
        short slot = 1;
        for (int id : itemIds) {
            inv.addItemFromDB(new Item(id, slot++, (short) 1));
        }
        return inv;
    }

    @Test
    void aWriteDuringAWalkOfListDoesNotBreakTheWalk() {
        Inventory inv = inventoryWith(4000000, 4000001);
        Collection<Item> items = inv.list();
        Iterator<Item> it = items.iterator();
        it.next();

        inv.addItemFromDB(new Item(4000002, (short) 3, (short) 1));
        inv.removeSlot((short) 1);

        assertDoesNotThrow(() -> {
            while (it.hasNext()) {
                it.next();
            }
        });
        assertEquals(2, items.size(), "the copy keeps what was there when it was taken");
        assertEquals(2, inv.list().size());
    }

    @Test
    void aWriteDuringAWalkOfTheIteratorDoesNotBreakTheWalk() {
        Inventory inv = inventoryWith(4000000, 4000001);
        Iterator<Item> it = inv.iterator();
        it.next();

        inv.addItemFromDB(new Item(4000002, (short) 3, (short) 1));

        assertDoesNotThrow(() -> {
            while (it.hasNext()) {
                it.next();
            }
        });
    }
}
