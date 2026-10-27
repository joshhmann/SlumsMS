package soloMapling.Economy.exchange;

import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 113: the regulars' quotes. */
class RegularsTest {

    @Test
    void quotesNestAroundTheReference() {
        List<Regulars.Quote> q = Regulars.quotes(1_000_000L, 3, 300, 500, 1000, 100_000_000);
        assertEquals(6, q.size());
        assertEquals(new Regulars.Quote(1, Side.BUY, 970_000, 500), q.get(0));
        assertEquals(new Regulars.Quote(1, Side.SELL, 1_030_000, 500), q.get(1));
        assertEquals(new Regulars.Quote(3, Side.BUY, 910_000, 500), q.get(4));
        for (Regulars.Quote x : q) {
            assertTrue(x.side() == Side.BUY ? x.price() < 1_000_000 : x.price() > 1_000_000, "never crosses itself");
        }
    }

    @Test
    void noReferenceNoRegularsNoQuotes() {
        assertTrue(Regulars.quotes(null, 3, 300, 500, 1000, 1_000_000).isEmpty());
        assertTrue(Regulars.quotes(1_000_000L, 0, 300, 500, 1000, 1_000_000_000).isEmpty());
    }

    @Test
    void clampedToThePriceRangeAndDroppedWhenItWouldCollapse() {
        List<Regulars.Quote> q = Regulars.quotes(1000L, 2, 5000, 500, 1000, 100_000_000);
        assertEquals(4, q.size());
        assertTrue(q.stream().allMatch(x -> x.price() >= 1000), "bids clamp up to the minimum price");
        assertTrue(Regulars.quotes(1000L, 2, 300, 500, 1000, 1000).isEmpty(), "no room for a spread: no quotes");
        assertEquals(-2, Regulars.owner(2));
    }
}
