package soloMapling.Economy.exchange;

import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 113: the regulars, a few traders that keep small orders on both sides so the book is never empty (owner
 * 2026-10-27). They quote around a reference price (the official rate, else the mid of the book, else nothing: they
 * never invent a first price), each a little wider than the last, with small fixed sizes. Their orders escrow from
 * the HOUSE buckets, which only a real (staff) wallet can fund, so they never mint NX or mesos; a regular that
 * cannot afford a quote simply does not post it. Fills between two regulars never count towards the official rate
 * ({@link OfficialRate}). Pure: the {@link Exchange} places what this returns.
 */
public final class Regulars {

    private Regulars() {
    }

    /** One quote: regular {@code regular} (1-based) wants this order resting. */
    public record Quote(int regular, OrderBook.Side side, long price, long nx) {
    }

    /** The engine owner id of regular {@code n}: negative, so it never collides with an account id. */
    public static long owner(int regular) {
        return -regular;
    }

    /**
     * The quotes for {@code count} regulars around {@code reference}: regular i bids at
     * reference x (1 - i x spread) and asks at reference x (1 + i x spread), rounded to a whole meso, clamped to
     * [minPrice, maxPrice]. Empty without a reference or regulars.
     */
    public static List<Quote> quotes(Long reference, int count, int spreadBps, long nx, long minPrice, long maxPrice) {
        List<Quote> out = new ArrayList<>();
        if (reference == null || reference <= 0 || count <= 0 || nx <= 0) {
            return out;
        }
        for (int i = 1; i <= count; i++) {
            long off = Math.multiplyExact(reference, (long) spreadBps * i) / 10_000;
            long bid = clamp(reference - Math.max(1, off), minPrice, maxPrice);
            long ask = clamp(reference + Math.max(1, off), minPrice, maxPrice);
            if (bid < ask) {
                out.add(new Quote(i, OrderBook.Side.BUY, bid, nx));
                out.add(new Quote(i, OrderBook.Side.SELL, ask, nx));
            }
        }
        return out;
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
