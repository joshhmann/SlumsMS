package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** 1.5.2: @qs 1 32767 looped forever (a short counter wraps at 32767) and hung the channel. */
class QuickSellRangeTest {
    @Test void theLastSlotIsCappedAtTheInventorySize() {
        assertArrayEquals(new int[]{1, 96}, Character.quickSellRange(1, Short.MAX_VALUE, 96));
    }

    @Test void negativeSlotsNeverReachTheEquippedItems() {
        assertArrayEquals(new int[]{1, 24}, Character.quickSellRange(-100, 24, 96));
    }

    @Test void anOrdinaryRangeIsUnchanged() {
        assertArrayEquals(new int[]{5, 20}, Character.quickSellRange(5, 20, 96));
    }
}
