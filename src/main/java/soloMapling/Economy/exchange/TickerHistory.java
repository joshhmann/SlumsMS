package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.ExchangeStore.FillRow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ticket 113, tickers: one ticker's price history for the site. Hourly OHLC candles (UTC hours, only hours that
 * traded) for the last {@link #KEEP_MS} (30 days), and the fills of the last 24 h for the rolling stats. Rebuilt from
 * the store's fills at start-up ({@link ExchangeStore#fillsSince}), then fed each new fill. Pure, not thread safe
 * (the {@link Exchange} serialises).
 */
public final class TickerHistory {

    public static final long HOUR_MS = 3_600_000L;
    public static final long DAY_MS = 24 * HOUR_MS;
    public static final long KEEP_MS = 30 * DAY_MS;

    /** One hour: open, high, low, close (prices), qty traded and mesos turned over. */
    public record Candle(long hour, long open, long high, long low, long close, long qty, long mesos, int fills) {
        Candle with(FillRow f) {
            return new Candle(hour, open, Math.max(high, f.price()), Math.min(low, f.price()), f.price(), qty + f.nx(),
                    mesos + f.mesos(), fills + 1);
        }
    }

    /**
     * The rolling 24 h numbers; null fields mean no trade. {@code last}, high, low and volume count every fill.
     * <p>
     * {@code change} (1.6.1 exchange fix, TEST 2026-10-29: NX showed +901% from the QA check's single 100 NX fills) is
     * only for a market that really traded: it counts player-to-player fills only (no regular took part: the same rule
     * as the official rate, so the QA checks' test regular never moves it) and needs at least {@code minFills} of them
     * in the window, else it is null. It is the last counted price minus {@code reference}: the volume-weighted average
     * of the counted fills of the 24 h before the window when those are at least {@code minFills}, else of the counted
     * fills in the window itself. A single odd lot or a lone print at a strange price can no longer make it shout.
     */
    public record Stats(Long last, Long reference, Long change, Long high, Long low, long qty, long mesos, int fills) {
    }

    /** The fewest counted fills for a change when the caller does not say ({@link ExchangeConfig#rateMinFills}). */
    public static final int MIN_FILLS = 3;

    private final TreeMap<Long, Candle> candles = new TreeMap<>();
    private final Deque<FillRow> day = new ArrayDeque<>();
    /** The fills of the 24 h before the window (the change reference). */
    private final Deque<FillRow> dayBefore = new ArrayDeque<>();
    /** The newest fill ever seen (it may be older than the candles kept). */
    private FillRow last;

    public static long hourOf(long at) {
        return Math.floorDiv(at, HOUR_MS) * HOUR_MS;
    }

    /** Adds a fill (fills arrive in time order; an older one only updates its own candle). */
    public void add(FillRow f) {
        long h = hourOf(f.at());
        Candle c = candles.get(h);
        candles.put(h, c == null ? new Candle(h, f.price(), f.price(), f.price(), f.price(), f.nx(), f.mesos(), 1)
                : c.with(f));
        if (last == null || f.at() >= last.at()) {
            last = f;
        }
        day.addLast(f);
    }

    /** Drops what is older than the windows. */
    public void prune(long now) {
        candles.headMap(hourOf(now - KEEP_MS), false).clear();
        while (!day.isEmpty() && day.peekFirst().at() <= now - DAY_MS) {
            dayBefore.addLast(day.pollFirst());
        }
        while (!dayBefore.isEmpty() && dayBefore.peekFirst().at() <= now - 2 * DAY_MS) {
            dayBefore.pollFirst();
        }
    }

    public Stats stats(long now) {
        return stats(now, MIN_FILLS);
    }

    /** The rolling numbers; a change needs {@code minFills} counted fills (see {@link Stats}). */
    public Stats stats(long now, int minFills) {
        prune(now);
        if (day.isEmpty()) {
            return new Stats(last == null ? null : last.price(), null, null, null, null, 0, 0, 0);
        }
        long hi = Long.MIN_VALUE, lo = Long.MAX_VALUE, qty = 0, mesos = 0;
        for (FillRow f : day) {
            hi = Math.max(hi, f.price());
            lo = Math.min(lo, f.price());
            qty += f.nx();
            mesos += f.mesos();
        }
        Long ref = null, change = null;
        FillRow lastCounted = null;
        int counted = 0;
        for (FillRow f : day) {
            if (counted(f)) {
                counted++;
                lastCounted = f;
            }
        }
        if (counted >= Math.max(1, minFills)) {
            ref = vwap(dayBefore, minFills);
            if (ref == null) {
                ref = vwap(day, minFills);
            }
            change = ref == null ? null : lastCounted.price() - ref;
        }
        return new Stats(last.price(), ref, change, hi, lo, qty, mesos, day.size());
    }

    /** A player-to-player fill (no regular, no QA test regular). */
    static boolean counted(FillRow f) {
        return !f.buyerRegular() && !f.sellerRegular() && f.nx() > 0;
    }

    /** The counted fills' volume-weighted price, or null with fewer than {@code minFills} of them. */
    private static Long vwap(Deque<FillRow> fills, int minFills) {
        double qty = 0, value = 0;
        int n = 0;
        for (FillRow f : fills) {
            if (counted(f)) {
                qty += f.nx();
                value += (double) f.price() * f.nx();
                n++;
            }
        }
        return n < Math.max(1, minFills) || qty <= 0 ? null : Math.round(value / qty);
    }

    /** The candles kept, oldest first. */
    public List<Candle> candles(long now) {
        prune(now);
        return new ArrayList<>(candles.values());
    }

    /** Test access. */
    Map<Long, Candle> candleMap() {
        return candles;
    }
}
