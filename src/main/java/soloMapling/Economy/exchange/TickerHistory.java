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

    /** The rolling 24 h numbers. {@code change} is last minus the reference (the last price before the window, else
     * the first in it); null fields mean no trade. */
    public record Stats(Long last, Long reference, Long change, Long high, Long low, long qty, long mesos, int fills) {
    }

    private final TreeMap<Long, Candle> candles = new TreeMap<>();
    private final Deque<FillRow> day = new ArrayDeque<>();
    /** The newest fill ever seen (it may be older than the candles kept). */
    private FillRow last;
    /** The last fill that fell out of the 24 h window (the change reference). */
    private FillRow beforeDay;

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
            beforeDay = day.pollFirst();
        }
    }

    public Stats stats(long now) {
        prune(now);
        if (day.isEmpty()) {
            Long l = last == null ? null : last.price();
            return new Stats(l, l, l == null ? null : 0L, null, null, 0, 0, 0);
        }
        long hi = Long.MIN_VALUE, lo = Long.MAX_VALUE, qty = 0, mesos = 0;
        for (FillRow f : day) {
            hi = Math.max(hi, f.price());
            lo = Math.min(lo, f.price());
            qty += f.nx();
            mesos += f.mesos();
        }
        long ref = beforeDay != null ? beforeDay.price() : day.peekFirst().price();
        return new Stats(last.price(), ref, last.price() - ref, hi, lo, qty, mesos, day.size());
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
