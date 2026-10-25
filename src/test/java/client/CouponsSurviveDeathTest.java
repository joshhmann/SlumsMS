package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.1 (owner): dying clears every buff except EXP and drop coupons. */
class CouponsSurviveDeathTest {

    @Test
    void rateCouponsAreKeptOtherBuffsAreNot() {
        assertTrue(Character.keptOnDeath(5211048), "2x EXP card");
        assertTrue(Character.keptOnDeath(5360042), "2x drop card");
        assertFalse(Character.keptOnDeath(2001002), "Magic Guard (a skill buff) still ends on death");
        assertFalse(Character.keptOnDeath(2022179), "an item buff still ends on death");
    }
}
