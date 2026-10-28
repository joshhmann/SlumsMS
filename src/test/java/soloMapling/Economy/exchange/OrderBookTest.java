package soloMapling.Economy.exchange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import soloMapling.Economy.exchange.OrderBook.Closed;
import soloMapling.Economy.exchange.OrderBook.Fill;
import soloMapling.Economy.exchange.OrderBook.Order;
import soloMapling.Economy.exchange.OrderBook.Reason;
import soloMapling.Economy.exchange.OrderBook.Result;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 113: the pure order book. */
class OrderBookTest {

    private long ids = 1;
    private long seq = 1;

    private Order order(long owner, Side side, long price, long nx) {
        return order(owner, side, price, nx, 0);
    }

    private Order order(long owner, Side side, long price, long nx, long expiresAt) {
        long hold = side == Side.BUY ? OrderBook.cost(nx, price) : 0;
        return new Order(ids++, owner, false, side, price, nx, nx, hold, 0, expiresAt, seq++);
    }

    @Test
    void costRoundsDownAndOverflowThrows() {
        assertEquals(1_000_000, OrderBook.cost(1000, 1_000_000));
        assertEquals(1, OrderBook.cost(1, 1999));
        assertEquals(0, OrderBook.cost(1, 999));
        assertThrows(ArithmeticException.class, () -> OrderBook.cost(Long.MAX_VALUE / 2, 3));
    }

    @Test
    void ordersThatDoNotCrossRest() {
        OrderBook b = new OrderBook();
        assertTrue(b.place(order(1, Side.BUY, 900, 100), 0).resting());
        assertTrue(b.place(order(2, Side.SELL, 1000, 100), 0).resting());
        assertEquals(900, b.best(Side.BUY));
        assertEquals(1000, b.best(Side.SELL));
        assertEquals(2, b.size());
        b.check();
    }

    @Test
    void aCrossingOrderFillsAtTheMakersPrice() {
        OrderBook b = new OrderBook();
        Order bid = order(1, Side.BUY, 1_200_000, 1000);
        b.place(bid, 0);
        Result r = b.place(order(2, Side.SELL, 1_000_000, 1000), 5);
        assertEquals(1, r.fills().size());
        Fill f = r.fills().get(0);
        assertEquals(1_200_000, f.price(), "the resting bid sets the price");
        assertEquals(1_200_000, f.mesos());
        assertEquals(Side.SELL, f.taker());
        assertEquals(5, f.at());
        assertEquals(0, b.size());
        // both closed filled; the bid had exactly enough
        assertEquals(2, r.closed().size());
        assertTrue(r.closed().stream().allMatch(c -> c.reason() == Reason.FILLED));
        assertEquals(0, r.closed().stream().mapToLong(Closed::mesosReleased).sum());
    }

    @Test
    void aBuyerPayingLessThanItsLimitGetsTheRestBack() {
        OrderBook b = new OrderBook();
        b.place(order(1, Side.SELL, 1_000_000, 1000), 0);
        Order buy = order(2, Side.BUY, 1_500_000, 1000);
        Result r = b.place(buy, 0);
        assertEquals(1_000_000, r.fills().get(0).mesos());
        Closed mine = r.closed().stream().filter(c -> c.orderId() == buy.id).findFirst().orElseThrow();
        assertEquals(500_000, mine.mesosReleased());
        assertEquals(0, buy.mesosHeld());
    }

    @Test
    void bestPriceFirstThenOldestFirst() {
        OrderBook b = new OrderBook();
        Order a100 = order(1, Side.SELL, 100_000, 100);
        Order a90first = order(2, Side.SELL, 90_000, 100);
        Order a90second = order(3, Side.SELL, 90_000, 100);
        b.place(a100, 0);
        b.place(a90first, 0);
        b.place(a90second, 0);
        Result r = b.place(order(9, Side.BUY, 100_000, 250), 0);
        assertEquals(List.of(a90first.id, a90second.id, a100.id), r.fills().stream().map(Fill::sellOrder).toList());
        assertEquals(List.of(100L, 100L, 50L), r.fills().stream().map(Fill::nx).toList());
        assertEquals(50, a100.nxLeft());
        assertFalse(r.resting());
        b.check();
    }

    @Test
    void bidsSortHighestFirst() {
        OrderBook b = new OrderBook();
        b.place(order(1, Side.BUY, 80_000, 100), 0);
        Order high = order(2, Side.BUY, 95_000, 100);
        b.place(high, 0);
        Result r = b.place(order(3, Side.SELL, 70_000, 100), 0);
        assertEquals(high.id, r.fills().get(0).buyOrder());
        assertEquals(95_000, r.fills().get(0).price());
    }

    @Test
    void aPartiallyFilledTakerRestsItsRemainder() {
        OrderBook b = new OrderBook();
        b.place(order(1, Side.SELL, 1000, 300), 0);
        Order buy = order(2, Side.BUY, 1000, 1000);
        Result r = b.place(buy, 0);
        assertTrue(r.resting());
        assertEquals(700, buy.nxLeft());
        assertEquals(700, buy.mesosHeld(), "1000 held, 300 paid");
        assertEquals(1000, b.best(Side.BUY));
        assertEquals(0, b.best(Side.SELL));
        b.check();
    }

    @Test
    void aPartiallyFilledMakerKeepsItsPlace() {
        OrderBook b = new OrderBook();
        Order first = order(1, Side.SELL, 1000, 500);
        Order second = order(2, Side.SELL, 1000, 500);
        b.place(first, 0);
        b.place(second, 0);
        b.place(order(3, Side.BUY, 1000, 200), 0);
        assertEquals(300, first.nxLeft());
        Result r = b.place(order(4, Side.BUY, 1000, 300), 0);
        assertEquals(first.id, r.fills().get(0).sellOrder(), "still first in line");
        assertNull(b.get(first.id));
        assertNotNull(b.get(second.id));
    }

    @Test
    void selfTradeCancelsTheNewestAndKeepsEarlierFills() {
        OrderBook b = new OrderBook();
        Order other = order(2, Side.SELL, 90_000, 100);
        Order own = order(1, Side.SELL, 95_000, 100);
        b.place(other, 0);
        b.place(own, 0);
        Order buy = order(1, Side.BUY, 100_000, 300);
        Result r = b.place(buy, 0);
        assertEquals(1, r.fills().size(), "filled the other owner's ask");
        assertFalse(r.resting());
        Closed c = r.closed().stream().filter(x -> x.orderId() == buy.id).findFirst().orElseThrow();
        assertEquals(Reason.SELF_TRADE, c.reason());
        assertEquals(OrderBook.cost(300, 100_000) - OrderBook.cost(100, 90_000), c.mesosReleased());
        assertNotNull(b.get(own.id), "the resting own order stays");
        b.check();
    }

    @Test
    void selfTradeWithNothingElseCancelsTheTakerWhole() {
        OrderBook b = new OrderBook();
        b.place(order(7, Side.BUY, 100_000, 100), 0);
        Result r = b.place(order(7, Side.SELL, 90_000, 100), 0);
        assertTrue(r.fills().isEmpty());
        assertEquals(Reason.SELF_TRADE, r.closed().get(0).reason());
        assertEquals(100, r.closed().get(0).nxReleased());
        assertEquals(1, b.size());
    }

    @Test
    void cancelReleasesOnceAndOnlyForTheOwner() {
        OrderBook b = new OrderBook();
        Order buy = order(1, Side.BUY, 1500, 1000);
        b.place(buy, 0);
        assertNull(b.cancel(buy.id, 2, Reason.CANCELLED), "not theirs");
        Closed c = b.cancel(buy.id, 1, Reason.CANCELLED);
        assertEquals(1500, c.mesosReleased());
        assertNull(b.cancel(buy.id, 1, Reason.CANCELLED), "double cancel does nothing");
        assertNull(b.cancel(999, 1, Reason.CANCELLED));
        assertEquals(0, b.size());
        b.check();
    }

    @Test
    void expiredOrdersCloseAndNeverFill() {
        OrderBook b = new OrderBook();
        Order old = order(1, Side.SELL, 1000, 100, 50);
        Order fresh = order(2, Side.SELL, 1100, 100, 0);
        b.place(old, 0);
        b.place(fresh, 0);
        Result r = b.place(order(3, Side.BUY, 1100, 100), 60);
        assertEquals(fresh.id, r.fills().get(0).sellOrder());
        Closed exp = r.closed().stream().filter(c -> c.orderId() == old.id).findFirst().orElseThrow();
        assertEquals(Reason.EXPIRED, exp.reason());
        assertEquals(100, exp.nxReleased());
        assertEquals(0, b.size());
    }

    @Test
    void expireClosesOnlyPastOrders() {
        OrderBook b = new OrderBook();
        b.place(order(1, Side.SELL, 1000, 100, 50), 0);
        b.place(order(2, Side.BUY, 900, 100, 100), 0);
        b.place(order(3, Side.BUY, 800, 100, 0), 0);
        assertTrue(b.expire(49).isEmpty());
        assertEquals(1, b.expire(50).size());
        List<Closed> later = b.expire(1000);
        assertEquals(1, later.size());
        assertEquals(90, later.get(0).mesosReleased(), "100 NX at 900 per 1,000");
        assertEquals(1, b.size());
        b.check();
    }

    @Test
    void badOrdersAreRefused() {
        OrderBook b = new OrderBook();
        assertThrows(IllegalArgumentException.class, () -> b.place(new Order(1, 1, false, Side.BUY, 1000, 0, 0, 0, 0, 0, 1), 0));
        assertThrows(IllegalArgumentException.class, () -> b.place(new Order(1, 1, false, Side.BUY, 0, 10, 10, 0, 0, 0, 1), 0));
        assertThrows(IllegalArgumentException.class, () -> b.place(new Order(1, 1, false, Side.BUY, 1000, 10, 10, 5, 0, 0, 1), 0),
                "a buy must hold exactly cost(nx, price)");
        b.place(order(1, Side.SELL, 1000, 10), 0);
        Order dup = new Order(1, 2, false, Side.SELL, 1000, 10, 10, 0, 0, 0, 99);
        assertThrows(IllegalArgumentException.class, () -> b.place(dup, 0));
    }

    @Test
    void copyIsDeepAndRestoreKeepsTimePriority() {
        OrderBook b = new OrderBook();
        Order first = order(1, Side.SELL, 1000, 100);
        Order second = order(2, Side.SELL, 1000, 100);
        b.place(first, 0);
        b.place(second, 0);
        OrderBook c = b.copy();
        b.place(order(3, Side.BUY, 1000, 150), 0);
        assertEquals(2, c.size());
        assertEquals(100, c.get(first.id).nxLeft());
        OrderBook r = new OrderBook();
        r.restoreAll(List.of(c.get(second.id), c.get(first.id)));
        Result res = r.place(order(4, Side.BUY, 1000, 100), 0);
        assertEquals(first.id, res.fills().get(0).sellOrder());
    }

    @Test
    void depthAggregatesLevels() {
        OrderBook b = new OrderBook();
        b.place(order(1, Side.BUY, 900, 100), 0);
        b.place(order(2, Side.BUY, 900, 200), 0);
        b.place(order(3, Side.BUY, 800, 100), 0);
        List<OrderBook.Level> d = b.depth(Side.BUY, 1);
        assertEquals(List.of(new OrderBook.Level(900, 300, 2)), d);
        assertEquals(2, b.depth(Side.BUY, 10).size());
    }

    /**
     * Randomised: thousands of places, cancels and expiries among a few owners with wallets. After every step the
     * book's invariants hold, no wallet or hold is negative, every fill is priced between the two limits and moves
     * exactly cost(nx, price) mesos, and the total NX and mesos (wallets + held) never change.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 1234, 99_999})
    void conservationUnderRandomTrading(long seed) {
        Random rnd = new Random(seed);
        OrderBook b = new OrderBook();
        int owners = 5;
        Map<Long, long[]> wallet = new HashMap<>(); // owner -> {nx, mesos}
        for (long o = 1; o <= owners; o++) {
            wallet.put(o, new long[]{1_000_000, 5_000_000_000L});
        }
        long totalNx = owners * 1_000_000L, totalMesos = owners * 5_000_000_000L;
        Map<Long, Order> placed = new HashMap<>();
        long now = 0;
        for (int step = 0; step < 4000; step++) {
            now += rnd.nextInt(10);
            int op = rnd.nextInt(10);
            if (op < 7) {
                long owner = 1 + rnd.nextInt(owners);
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                long price = 900 + rnd.nextInt(300) + (rnd.nextInt(5) == 0 ? rnd.nextInt(1000) : 0);
                long nx = 1 + rnd.nextInt(rnd.nextBoolean() ? 50 : 3000);
                long expires = rnd.nextInt(4) == 0 ? now + rnd.nextInt(200) : 0;
                Order o = order(owner, side, price, nx, expires);
                long[] w = wallet.get(owner);
                if (side == Side.SELL ? w[0] < nx : w[1] < o.mesosHeld()) {
                    continue;
                }
                if (side == Side.SELL) {
                    w[0] -= nx;
                } else {
                    w[1] -= o.mesosHeld();
                }
                placed.put(o.id, o);
                Result r = b.place(o, now);
                for (Fill f : r.fills()) {
                    Order buy = placed.get(f.buyOrder()), sell = placed.get(f.sellOrder());
                    assertTrue(f.price() <= buy.price && f.price() >= sell.price, "priced between the limits");
                    assertEquals(OrderBook.cost(f.nx(), f.price()), f.mesos());
                    assertTrue(f.buyer() != f.seller(), "never trades with itself");
                    wallet.get(f.buyer())[0] += f.nx();
                    wallet.get(f.seller())[1] += f.mesos();
                }
                apply(wallet, r.closed());
            } else if (op < 9 && !placed.isEmpty()) {
                List<Long> keys = new ArrayList<>(placed.keySet());
                Order o = placed.get(keys.get(rnd.nextInt(keys.size())));
                Closed c = b.cancel(o.id, o.owner, Reason.CANCELLED);
                if (c != null) {
                    apply(wallet, List.of(c));
                    assertNull(b.cancel(o.id, o.owner, Reason.CANCELLED));
                }
            } else {
                apply(wallet, b.expire(now));
            }
            b.check();
            long[] held = b.heldTotals();
            long nx = held[0], mesos = held[1];
            for (long[] w : wallet.values()) {
                assertTrue(w[0] >= 0 && w[1] >= 0, "wallet negative");
                nx += w[0];
                mesos += w[1];
            }
            for (Order o : b.allBySeq()) {
                assertTrue(o.nxLeft() > 0 && o.mesosHeld() >= 0);
            }
            assertEquals(totalNx, nx, "NX conserved at step " + step);
            assertEquals(totalMesos, mesos, "mesos conserved at step " + step);
        }
    }

    private static void apply(Map<Long, long[]> wallet, List<Closed> closed) {
        for (Closed c : closed) {
            assertTrue(c.nxReleased() >= 0 && c.mesosReleased() >= 0);
            wallet.get(c.owner())[0] += c.nxReleased();
            wallet.get(c.owner())[1] += c.mesosReleased();
        }
    }

    /** The buy hold always covers the rest at the limit, whatever the split of fills (the rounding argument). */
    @Test
    void buyHoldCoversTheRestUnderAnySplit() {
        Random rnd = new Random(7);
        for (int t = 0; t < 2000; t++) {
            OrderBook b = new OrderBook();
            long limit = 1 + rnd.nextInt(5000);
            long nx = 1 + rnd.nextInt(500);
            Order buy = order(1, Side.BUY, limit, nx);
            b.place(buy, 0);
            long paid = 0;
            while (b.get(buy.id) != null) {
                long q = 1 + rnd.nextInt((int) Math.min(buy.nxLeft(), 40));
                long p = 1 + rnd.nextInt((int) limit);
                Result r = b.place(order(2, Side.SELL, p, q), 0);
                paid += r.fills().stream().mapToLong(Fill::mesos).sum();
                if (b.get(buy.id) != null) {
                    assertTrue(buy.mesosHeld() >= OrderBook.cost(buy.nxLeft(), limit));
                }
                if (r.resting()) {
                    b.cancel(r.order().id, 2, Reason.CANCELLED);
                }
            }
            assertTrue(paid <= OrderBook.cost(nx, limit));
        }
    }
}
