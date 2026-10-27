package soloMapling.Economy.exchange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Ticket 113: the NX/meso order book. Pure: no game, no database, no clock (callers pass {@code now}), not thread
 * safe (the {@link Exchange} serialises every call).
 * <p>
 * Every order is a limit order for a whole number of NX at a price in <b>mesos per 1,000 NX</b>. A {@link Side#BUY}
 * order buys NX and pays mesos; a {@link Side#SELL} order sells NX for mesos. Matching is price-time priority: the
 * best price first (highest bid, lowest ask), then the oldest order (lowest {@code seq}). A fill trades at the resting
 * (maker) order's price.
 * <p>
 * Money rules, all in whole units:
 * <ul>
 *   <li>A fill of {@code q} NX at price {@code p} moves {@link #cost(long, long) floor(q * p / 1000)} mesos: the buyer
 *   pays and the seller receives the same number, so a fill never creates or destroys a meso or an NX.</li>
 *   <li>A sell order holds its unfilled NX ({@code nxLeft}). A buy order holds {@code cost(nxTotal, limit)} mesos when
 *   placed and pays each fill out of that; since floor(a) + floor(b) &lt;= floor(a + b) and a fill's price is never
 *   above the limit, what is held always covers the rest at the limit ({@code mesosHeld >= cost(nxLeft, limit)}), so a
 *   hold never goes negative. Whatever is left when the order closes is released back to the buyer.</li>
 *   <li>Self-trade prevention: an order never fills against an order with the same owner. If the best counter order is
 *   the taker's own, the taker's remainder is cancelled (cancel newest; fills already made stand).</li>
 *   <li>A resting order whose {@code expiresAt} has passed never fills: matching closes it as expired.</li>
 * </ul>
 */
public final class OrderBook {

    /** Prices are mesos per this many NX. */
    public static final long PER = 1000;

    public enum Side {
        BUY, SELL;

        public Side other() {
            return this == BUY ? SELL : BUY;
        }

        public static Side parse(Object o) {
            if (o == null) {
                return null;
            }
            String s = o.toString().trim().toUpperCase(java.util.Locale.ROOT);
            return switch (s) {
                case "BUY", "B", "BID" -> BUY;
                case "SELL", "S", "ASK" -> SELL;
                default -> null;
            };
        }
    }

    public enum Reason {
        FILLED, CANCELLED, EXPIRED, SELF_TRADE, HALTED
    }

    /** Mesos for {@code nx} NX at {@code price} mesos per 1,000 NX, rounded down. Throws on overflow. */
    public static long cost(long nx, long price) {
        return Math.multiplyExact(nx, price) / PER;
    }

    /** One order. Mutable fields change only inside the book. */
    public static final class Order {
        public final long id;
        /** Account id for players; a negative id for a regular ({@code -regularId}). */
        public final long owner;
        public final boolean regular;
        public final Side side;
        public final long price;
        public final long nxTotal;
        public final long placedAt;
        /** 0 = never. */
        public final long expiresAt;
        public final long seq;
        long nxLeft;
        long mesosHeld;

        public Order(long id, long owner, boolean regular, Side side, long price, long nxTotal, long nxLeft,
                     long mesosHeld, long placedAt, long expiresAt, long seq) {
            this.id = id;
            this.owner = owner;
            this.regular = regular;
            this.side = side;
            this.price = price;
            this.nxTotal = nxTotal;
            this.nxLeft = nxLeft;
            this.mesosHeld = mesosHeld;
            this.placedAt = placedAt;
            this.expiresAt = expiresAt;
            this.seq = seq;
        }

        public long nxLeft() {
            return nxLeft;
        }

        /** Mesos still held for a buy order; 0 for a sell order. */
        public long mesosHeld() {
            return mesosHeld;
        }

        /** What the order holds in escrow: NX for a sell, mesos for a buy. */
        public long escrow() {
            return side == Side.SELL ? nxLeft : mesosHeld;
        }

        boolean expired(long now) {
            return expiresAt > 0 && expiresAt <= now;
        }

        Order copy() {
            return new Order(id, owner, regular, side, price, nxTotal, nxLeft, mesosHeld, placedAt, expiresAt, seq);
        }

        @Override
        public String toString() {
            return side + "#" + id + "[" + nxLeft + "/" + nxTotal + "nx @" + price + " owner " + owner + "]";
        }
    }

    /** One fill. {@code mesos} = {@link #cost}(nx, price); the buyer pays it, the seller receives it. */
    public record Fill(long buyOrder, long sellOrder, long buyer, long seller, boolean buyerRegular,
                       boolean sellerRegular, long nx, long price, long mesos, Side taker, long at) {
        public boolean bothRegular() {
            return buyerRegular && sellerRegular;
        }
    }

    /** An order leaving the book, with what its escrow releases back to its owner. */
    public record Closed(long orderId, long owner, Side side, Reason reason, long nxReleased, long mesosReleased) {
    }

    /** What {@link #place} did. {@code resting}: the order now rests in the book with this much left. */
    public record Result(Order order, List<Fill> fills, List<Closed> closed, boolean resting) {
    }

    /** One price level. */
    public record Level(long price, long nx, int orders) {
    }

    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> byId = new LinkedHashMap<>();

    /** A deep copy (the {@link Exchange} keeps one to roll back to when a store write fails). */
    public OrderBook copy() {
        OrderBook b = new OrderBook();
        for (Order o : allBySeq()) {
            b.restore(o.copy());
        }
        return b;
    }

    /**
     * Places {@code o} (fresh: {@code nxLeft == nxTotal}; a buy holds {@code cost(nxTotal, price)}) and matches it.
     * The caller has checked the inputs; this throws on a bad order rather than guess.
     */
    public Result place(Order o, long now) {
        if (o.nxTotal <= 0 || o.price <= 0 || o.nxLeft != o.nxTotal) {
            throw new IllegalArgumentException("bad order " + o);
        }
        if (byId.containsKey(o.id)) {
            throw new IllegalArgumentException("duplicate order id " + o.id);
        }
        long wantHeld = o.side == Side.BUY ? cost(o.nxTotal, o.price) : 0;
        if (o.mesosHeld != wantHeld) {
            throw new IllegalArgumentException("buy hold must be " + wantHeld + ": " + o);
        }
        List<Fill> fills = new ArrayList<>();
        List<Closed> closed = new ArrayList<>();
        NavigableMap<Long, Deque<Order>> book = o.side == Side.BUY ? asks : bids;
        boolean selfTrade = false;
        while (o.nxLeft > 0 && !book.isEmpty()) {
            Map.Entry<Long, Deque<Order>> best = book.firstEntry();
            long p = best.getKey();
            if (o.side == Side.BUY ? p > o.price : p < o.price) {
                break;
            }
            Deque<Order> level = best.getValue();
            Order maker = level.peekFirst();
            if (maker.expired(now)) {
                level.pollFirst();
                byId.remove(maker.id);
                closed.add(release(maker, Reason.EXPIRED));
                if (level.isEmpty()) {
                    book.remove(p);
                }
                continue;
            }
            if (maker.owner == o.owner) {
                selfTrade = true;
                break;
            }
            long q = Math.min(o.nxLeft, maker.nxLeft);
            long mesos = cost(q, p);
            Order buy = o.side == Side.BUY ? o : maker;
            Order sell = o.side == Side.BUY ? maker : o;
            buy.nxLeft -= q;
            buy.mesosHeld -= mesos;
            sell.nxLeft -= q;
            if (buy.mesosHeld < 0) {
                throw new IllegalStateException("buy hold went negative: " + buy);
            }
            fills.add(new Fill(buy.id, sell.id, buy.owner, sell.owner, buy.regular, sell.regular, q, p, mesos,
                    o.side, now));
            if (maker.nxLeft == 0) {
                level.pollFirst();
                byId.remove(maker.id);
                closed.add(release(maker, Reason.FILLED));
                if (level.isEmpty()) {
                    book.remove(p);
                }
            }
        }
        boolean resting = false;
        if (o.nxLeft == 0) {
            closed.add(release(o, Reason.FILLED));
        } else if (selfTrade) {
            closed.add(release(o, Reason.SELF_TRADE));
        } else {
            rest(o);
            resting = true;
        }
        return new Result(o, List.copyOf(fills), List.copyOf(closed), resting);
    }

    /** Releases what a closing order holds (sets it to 0) and says so. */
    private static Closed release(Order o, Reason why) {
        long nx = o.side == Side.SELL ? o.nxLeft : 0;
        long mesos = o.side == Side.BUY ? o.mesosHeld : 0;
        if (o.side == Side.SELL) {
            o.nxLeft = 0;
        } else {
            o.mesosHeld = 0;
        }
        return new Closed(o.id, o.owner, o.side, why, nx, mesos);
    }

    private void rest(Order o) {
        (o.side == Side.BUY ? bids : asks).computeIfAbsent(o.price, k -> new ArrayDeque<>()).addLast(o);
        byId.put(o.id, o);
    }

    /**
     * Puts back an order loaded from the store (recovery). Orders must be restored in {@code seq} order so each level
     * keeps time priority; {@link #restoreAll} sorts for you.
     */
    public void restore(Order o) {
        if (o.nxLeft <= 0 || byId.containsKey(o.id)) {
            throw new IllegalArgumentException("cannot restore " + o);
        }
        rest(o);
    }

    public void restoreAll(List<Order> orders) {
        List<Order> sorted = new ArrayList<>(orders);
        sorted.sort(Comparator.comparingLong(x -> x.seq));
        sorted.forEach(this::restore);
    }

    /** Cancels an open order of {@code owner}. Null when there is no such open order (unknown, closed, not theirs). */
    public Closed cancel(long orderId, long owner, Reason why) {
        Order o = byId.get(orderId);
        if (o == null || o.owner != owner) {
            return null;
        }
        remove(o);
        return release(o, why);
    }

    /** Closes every open order whose expiry has passed. */
    public List<Closed> expire(long now) {
        List<Closed> out = new ArrayList<>();
        for (Order o : new ArrayList<>(byId.values())) {
            if (o.expired(now)) {
                remove(o);
                out.add(release(o, Reason.EXPIRED));
            }
        }
        return out;
    }

    private void remove(Order o) {
        NavigableMap<Long, Deque<Order>> book = o.side == Side.BUY ? bids : asks;
        Deque<Order> level = book.get(o.price);
        if (level != null) {
            level.remove(o);
            if (level.isEmpty()) {
                book.remove(o.price);
            }
        }
        byId.remove(o.id);
    }

    public Order get(long id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    /** Open orders of an owner, oldest first. */
    public List<Order> ordersOf(long owner) {
        List<Order> out = new ArrayList<>();
        for (Order o : allBySeq()) {
            if (o.owner == owner) {
                out.add(o);
            }
        }
        return out;
    }

    /** Every open order, oldest first. */
    public List<Order> allBySeq() {
        List<Order> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparingLong(x -> x.seq));
        return all;
    }

    /** The best {@code levels} levels of one side, best first. */
    public List<Level> depth(Side side, int levels) {
        List<Level> out = new ArrayList<>();
        for (Map.Entry<Long, Deque<Order>> e : (side == Side.BUY ? bids : asks).entrySet()) {
            if (out.size() >= levels) {
                break;
            }
            long nx = 0;
            for (Order o : e.getValue()) {
                nx += o.nxLeft;
            }
            out.add(new Level(e.getKey(), nx, e.getValue().size()));
        }
        return Collections.unmodifiableList(out);
    }

    /** Best bid or ask price, or 0 when that side is empty. */
    public long best(Side side) {
        NavigableMap<Long, Deque<Order>> book = side == Side.BUY ? bids : asks;
        return book.isEmpty() ? 0 : book.firstKey();
    }

    /** Total NX held by sell orders and mesos held by buy orders (for conservation checks). */
    public long[] heldTotals() {
        long nx = 0, mesos = 0;
        for (Order o : byId.values()) {
            nx += o.side == Side.SELL ? o.nxLeft : 0;
            mesos += o.side == Side.BUY ? o.mesosHeld : 0;
        }
        return new long[]{nx, mesos};
    }

    /** Checks the book's own invariants; throws on the first broken one (tests and recovery). */
    public void check() {
        int n = 0;
        for (NavigableMap<Long, Deque<Order>> book : List.of(bids, asks)) {
            for (Map.Entry<Long, Deque<Order>> e : book.entrySet()) {
                long lastSeq = Long.MIN_VALUE;
                if (e.getValue().isEmpty()) {
                    throw new IllegalStateException("empty level " + e.getKey());
                }
                for (Iterator<Order> it = e.getValue().iterator(); it.hasNext(); ) {
                    Order o = it.next();
                    n++;
                    if (o.price != e.getKey() || o.nxLeft <= 0 || o.nxLeft > o.nxTotal || byId.get(o.id) != o) {
                        throw new IllegalStateException("bad resting order " + o);
                    }
                    if (o.seq <= lastSeq) {
                        throw new IllegalStateException("time priority broken at " + o);
                    }
                    lastSeq = o.seq;
                    if (o.side == Side.BUY && o.mesosHeld < cost(o.nxLeft, o.price)) {
                        throw new IllegalStateException("buy hold below what the rest needs: " + o);
                    }
                    if (o.side == Side.SELL && o.mesosHeld != 0) {
                        throw new IllegalStateException("sell order holding mesos: " + o);
                    }
                }
            }
        }
        if (n != byId.size()) {
            throw new IllegalStateException("index has " + byId.size() + " orders, levels " + n);
        }
        if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) {
            // allowed only when the crossing orders share an owner (self-trade prevention left both resting)
            Order b = bids.firstEntry().getValue().peekFirst();
            Order a = asks.firstEntry().getValue().peekFirst();
            if (b.owner != a.owner) {
                throw new IllegalStateException("book crossed: " + b + " vs " + a);
            }
        }
    }
}
