package soloMapling.qa;

import client.inventory.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The vb-* reset empties each slot with the item's own quantity. The rework asked for 32767, which an equip refuses
 * (quantity must stay 0..1), so the reset threw on the first equip and left the player half wiped.
 */
class GameQaWorldWipeTest {

    @Test
    void aSingleItemOrEquipIsWipedWithQuantityOne() {
        assertEquals(1, GameQaWorld.wipeQuantity(new Item(1322027, (short) 1, (short) 1)));
    }

    @Test
    void aStackIsWipedWithItsWholeCount() {
        assertEquals(37, GameQaWorld.wipeQuantity(new Item(4000000, (short) 1, (short) 37)));
    }

    @Test
    void anEmptiedSlotStillAsksForAtLeastOne() {
        assertEquals(1, GameQaWorld.wipeQuantity(new Item(4000000, (short) 1, (short) 0)));
    }
}
