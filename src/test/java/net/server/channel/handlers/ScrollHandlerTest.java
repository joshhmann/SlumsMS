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

    @Test
    public void testCanScrollMatchesEquipCategory() {
        // Weapon scrolls match weapon items
        assertTrue(ScrollHandler.canScroll(2043000, 1302000)); // 1H sword scroll on 1H sword
        assertFalse(ScrollHandler.canScroll(2043000, 1402000)); // 1H sword scroll on 2H sword

        // Accessory scrolls (20492xx) match ring, pendant, belt
        assertTrue(ScrollHandler.canScroll(2049200, 1112000)); // Ring
        assertTrue(ScrollHandler.canScroll(2049200, 1122000)); // Pendant
        assertTrue(ScrollHandler.canScroll(2049200, 1132000)); // Belt
        assertFalse(ScrollHandler.canScroll(2049200, 1002000)); // Hat
    }
}
