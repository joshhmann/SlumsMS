package constants.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic 56a43dcea): ItemInformationProvider.getEquipmentSlot returns null for
 * an item with no WZ data or no info node, and canWearEquipment passed that straight to getFromTextSlot, which threw
 * a NullPointerException out of the equip handler. A missing slot reads like an empty one.
 */
class EquipSlotTextSlotTest {

    @Test
    void aMissingSlotReadsLikeAnEmptyOne() {
        assertSame(EquipSlot.getFromTextSlot(""), EquipSlot.getFromTextSlot(null));
    }
}
