package constants.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.1: MapleRoot's data has faces in 2xxxx and 5xxxx and hair in 3xxxx, 4xxxx and 6xxxx. The old ranges left every
 * stock face and all newer hair without a name, so salons and the face/eye coupon NPCs said "no options available".
 */
class CosmeticRangesTest {

    @Test
    void facesFromBothBlocks() {
        assertTrue(ItemConstants.isFace(20000));
        assertTrue(ItemConstants.isFace(21700));
        assertTrue(ItemConstants.isFace(54859));
        assertFalse(ItemConstants.isFace(30000));
        assertFalse(ItemConstants.isFace(60000));
    }

    @Test
    void hairFromAllThreeBlocks() {
        assertTrue(ItemConstants.isHair(30060));
        assertTrue(ItemConstants.isHair(36000));
        assertTrue(ItemConstants.isHair(44000));
        assertTrue(ItemConstants.isHair(64467));
        assertFalse(ItemConstants.isHair(20000));
        assertFalse(ItemConstants.isHair(54859));
    }
}
