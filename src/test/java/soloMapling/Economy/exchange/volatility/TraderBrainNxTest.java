package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 (owner: bots buy and sell NX): the brain prices per 1,000 NX, sizes orders in lots of 100 (100 NX at least),
 * and buys only what the mesos plus the fee cover. Whole items are unchanged.
 */
class TraderBrainNxTest {

    static final long PRICE = 5_000_000L; // mesos per 1,000 NX, the exchange's starting price

    static TraderBrain.View nx(long fair, int feeBps) {
        return new TraderBrain.View("NX", PRICE, 0, 0, 0, 0, fair, 0, 2_500_000L, 10_000_000L, 50_000, feeBps,
                0, 0, 1_000, 100, 100);
    }

    @Test
    void pricesArePerThousandNx() {
        assertEquals(5_000_000, TraderBrain.total(1_000, PRICE, 1_000, 0));
        assertEquals(500_000, TraderBrain.total(100, PRICE, 1_000, 0), "100 NX at 5,000 mesos a NX");
        assertEquals(1_250_000, TraderBrain.total(250, PRICE, 1_000, 0));
        assertEquals(5_050_000, TraderBrain.total(1_000, PRICE, 1_000, 100), "the 1% buyer's fee on top");
        assertEquals(7_000, TraderBrain.total(7, 1_000, 1, 0), "whole items: 7 at 1,000 mesos each");
        assertEquals(7_070, TraderBrain.total(7, 1_000, 1, 100), "plus the fee");
    }

    @Test
    void aBuyIsCoveredByTheMesosAndTheFee() {
        assertEquals(1_000, TraderBrain.maxAfford(5_000_000, PRICE, 1_000, 0));
        assertEquals(990, TraderBrain.maxAfford(5_000_000, PRICE, 1_000, 100), "the fee leaves 990, not 1,000");
        assertEquals(0, TraderBrain.maxAfford(100, PRICE, 1_000, 100), "100 mesos is not one lot");
    }

    @Test
    void ordersAreWholeLotsOfAtLeastOneHundred() {
        TraderBrain.Holdings h = new TraderBrain.Holdings(0, 250, List.of(), 5, 5);
        TraderBrain.View v = nx(PRICE, 0);
        List<TraderBrain.Action> kept = TraderBrain.guard(List.of(new TraderBrain.Place(Side.SELL, PRICE, 250)), v, h);
        assertEquals(1, kept.size());
        assertEquals(200, ((TraderBrain.Place) kept.get(0)).qty(), "whole lots only");
        assertEquals(1, TraderBrain.guard(List.of(new TraderBrain.Place(Side.SELL, PRICE, 100)), v, h).size());
        assertTrue(TraderBrain.guard(List.of(new TraderBrain.Place(Side.SELL, PRICE, 75)), v, h).isEmpty(),
                "75 NX is under the smallest order");
        List<TraderBrain.Action> capped = TraderBrain.guard(List.of(new TraderBrain.Place(Side.SELL, PRICE, 400)), v, h);
        assertEquals(200, ((TraderBrain.Place) capped.get(0)).qty(), "never more than the 250 it holds");
    }

    @Test
    void aBuyIsTrimmedToTheLotsTheMesosCanPay() {
        TraderBrain.Holdings h = new TraderBrain.Holdings(5_000_000, 0, List.of(), 5, 5);
        List<TraderBrain.Action> kept = TraderBrain.guard(List.of(new TraderBrain.Place(Side.BUY, PRICE, 2_000)),
                nx(PRICE, 100), h);
        assertEquals(1, kept.size());
        assertEquals(900, ((TraderBrain.Place) kept.get(0)).qty(), "990 affordable, 900 as whole lots");
        assertTrue(TraderBrain.guard(List.of(new TraderBrain.Place(Side.BUY, PRICE, 100)),
                nx(PRICE, 100), new TraderBrain.Holdings(500_000, 0, List.of(), 5, 5)).isEmpty(),
                "100 NX costs 500,000 mesos plus the fee: the bot has 500,000");
    }

    @Test
    void aSmallPurseOffersItsSmallestOrderNotAFractionTheGuardWouldDrop() {
        // value sells well above fair half the time: 250 NX in the purse, a third of it would be 75 and vanish, and the
        // smallest offer is 100. The buy it also tries is about 46 NX, under the smallest order, so it is dropped.
        TraderBrain.View v = nx(PRICE, 0);
        TraderBrain.Holdings h = new TraderBrain.Holdings(10_000_000, 250, List.of(), 5, 5);
        int sells = 0;
        for (int seed = 1; seed <= 40; seed++) {
            List<TraderBrain.Action> placed = TraderBrain.guard(
                    TraderBrain.decide(TraderBrain.Archetype.VALUE, v, h, 1_800_000_000_000L, new Random(seed)), v, h);
            long qty = 0;
            for (TraderBrain.Action a : placed) {
                if (a instanceof TraderBrain.Place p) {
                    assertEquals(Side.SELL, p.side(), "a buy under the smallest order is dropped");
                    assertEquals(0, p.qty() % 100, "whole lots: " + p.qty());
                    assertTrue(p.qty() >= 100, "the smallest order: " + p.qty());
                    assertTrue(p.price() >= 2_500_000 && p.price() <= 10_000_000, "the bot band: " + p.price());
                    qty += p.qty();
                    sells++;
                }
            }
            assertTrue(qty <= 250, "never more than the purse: " + qty);
        }
        assertTrue(sells > 10, "the holder sells on its turns: " + sells);
    }

    @Test
    void wholeItemsTradeAsBefore() {
        TraderBrain.View leaf = new TraderBrain.View("LEAF", 600_000, 600_000, 610_000, 0, 0, 600_000, 0,
                100_000, 2_000_000, 100, 100);
        TraderBrain.Holdings h = new TraderBrain.Holdings(1_000_000, 7, List.of(), 5, 5);
        List<TraderBrain.Action> kept = TraderBrain.guard(List.of(new TraderBrain.Place(Side.SELL, 600_000, 7)), leaf, h);
        assertEquals(7, ((TraderBrain.Place) kept.get(0)).qty(), "one leaf each, as in 1.6.1");
    }
}
