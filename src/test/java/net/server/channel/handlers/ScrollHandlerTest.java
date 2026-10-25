package net.server.channel.handlers;

import constants.inventory.ItemConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ScrollHandlerTest {

    @Test
    public void testIsChaosScrollRecognizesAllVariants() {
        // Standard chaos scroll
        assertTrue(ItemConstants.isChaosScroll(2049100)); // Chaos Scroll 60%
        assertTrue(ItemConstants.isChaosScroll(2049101)); // Liar Tree Sap
        assertTrue(ItemConstants.isChaosScroll(2049102)); // Maple Syrup
        assertTrue(ItemConstants.isChaosScroll(2049103)); // Beach Sandals Scroll
        assertTrue(ItemConstants.isChaosScroll(2049104)); // Agent Equipment Scroll
        assertTrue(ItemConstants.isChaosScroll(2049112)); // King Pepe's Weapon Scroll
        assertTrue(ItemConstants.isChaosScroll(2049113)); // Normal Witch Scroll
        assertTrue(ItemConstants.isChaosScroll(2049114)); // Witch's Belt Scroll

        // Non-chaos scrolls
        assertFalse(ItemConstants.isChaosScroll(2049000)); // Clean slate
        assertFalse(ItemConstants.isChaosScroll(2040000)); // Hat scroll
        assertFalse(ItemConstants.isChaosScroll(2049105)); // 6th Anniversary glove scroll (regular stats)
    }
}
