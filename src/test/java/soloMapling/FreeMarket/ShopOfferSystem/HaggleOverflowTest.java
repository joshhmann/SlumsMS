package soloMapling.FreeMarket.ShopOfferSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.0 rc6 bug hunt (P0): a haggle offer is parsed as a long with k/m/b suffixes and the accepted price was cast
 * back to int, so "4294967296" listed the item at 0 mesos and "2.2b" at a negative price. An offer a listing can't
 * hold is now never a price.
 */
class HaggleOverflowTest {

    @Test
    void anOfferAListingCantHoldIsNotAPrice() {
        assertEquals(-1, OfferParser.extractPrice("i'll give you 4294967296 for it"), "wrapped to 0 before");
        assertEquals(-1, OfferParser.extractPrice("2.2b for the claw"), "wrapped negative before");
        assertEquals(-1, OfferParser.extractPrice("9999999999999999999999 pls"));
        assertEquals(-1, OfferParser.extractPrice("0 mesos"));
    }

    @Test
    void ordinaryOffersStillParse() {
        assertEquals(500_000, OfferParser.extractPrice("500k for the claw"));
        assertEquals(2_100_000_000L, OfferParser.extractPrice("2.1b"));
        assertEquals(Integer.MAX_VALUE, OfferParser.extractPrice("2147483647"));
        assertEquals(1_500_000, OfferParser.extractPrice("3b no wait 1.5m"), "an out-of-range number is skipped, not chosen");
    }

    @Test
    void theEvaluatorDeclinesAnythingOutOfRangeHoweverGoodTheRatio() {
        for (int i = 0; i < 50; i++) {
            assertEquals(OfferEvaluator.Decision.DECLINE, OfferEvaluator.evaluate(4_294_967_296L, 1_000_000));
            assertEquals(OfferEvaluator.Decision.DECLINE, OfferEvaluator.evaluate(2_200_000_000L, 1_000_000));
            assertEquals(OfferEvaluator.Decision.DECLINE, OfferEvaluator.evaluate(0, 1_000_000));
            assertEquals(OfferEvaluator.Decision.DECLINE, OfferEvaluator.evaluate(-5, 1_000_000));
        }
        boolean sawAccept = false;
        for (int i = 0; i < 200 && !sawAccept; i++) {
            sawAccept = OfferEvaluator.evaluate(Integer.MAX_VALUE, 1_000_000) == OfferEvaluator.Decision.ACCEPT;
        }
        assertTrue(sawAccept, "the top of the range is still a real offer");
    }
}
