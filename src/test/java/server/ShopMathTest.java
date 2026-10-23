package server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc3 review: Shop.buy and HiredMerchant.buy charged a float total capped at Integer.MAX_VALUE. */
class ShopMathTest {

    @Test
    void aBigBulkBuyIsChargedExactlyOrRefused() {
        assertEquals(300_000_003L, ShopMath.total(100_000_001, 3), "a float would charge 300,000,000");
        assertEquals(300_000_000, (int) Math.min((float) 100_000_001 * 3, Integer.MAX_VALUE),
                "the old math really undercharged");
        assertEquals(Integer.MAX_VALUE, ShopMath.total(Integer.MAX_VALUE, 1));
        assertEquals(-1, ShopMath.total(1_000_000_000, 3), "3b is more than anyone can carry: refused, not capped");
        assertEquals(-1, ShopMath.total(Integer.MAX_VALUE, Short.MAX_VALUE));
        assertEquals(-1, ShopMath.total(-5, 2));
        assertEquals(0, ShopMath.total(7, 0));
    }

    @Test
    void aListingIsSaleableOnlyBetweenOneMesoAndTheMostAnyoneCanHold() {
        assertFalse(ShopMath.isSaleablePrice(0), "a haggle overflow wrote 0: never a free item");
        assertFalse(ShopMath.isSaleablePrice(-2_094_967_296), "nor a price that pays the buyer");
        assertFalse(ShopMath.isSaleablePrice(ShopMath.MAX_MESO + 1));
        assertTrue(ShopMath.isSaleablePrice(1));
        assertTrue(ShopMath.isSaleablePrice(Integer.MAX_VALUE));
    }
}
