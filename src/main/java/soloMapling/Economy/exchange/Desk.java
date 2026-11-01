package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.server.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ticket 162: the NX house desk. One rate R (mesos per 1,000 NX) is <b>the</b> official NX rate while the desk runs
 * ({@link Exchange#officialRate()} reads it): the house pays {@link #buy()} for 1,000 NX ({@code floor(R*(1-spread))})
 * and charges {@link #sell()} ({@code ceil(R*(1+spread))}). A player trades at the quote, at once, in whole NX of at
 * least {@link DeskConfig#minQty()}; there is no book, no queue and no partial fill.
 * <p>
 * <b>R moves on the hour.</b> Each hourly step turns the window's net flow into a drift: the desk's NX sold to players
 * minus the NX it bought from them, as a percentage of the float (every NX in accounts, escrow and payouts), times
 * {@link DeskConfig#driftBpsPerPct()}; clamped to +-{@link DeskConfig#maxStepBps()} for the step, to
 * +-{@link DeskConfig#dayCapPct()} around the day's first R, and always inside
 * {@link NxGuard#NX_LOWEST}..{@link NxGuard#NX_HIGHEST}. Buying NX from the desk pushes the rate up; selling to it
 * pushes the rate down. A gap longer than an hour applies one step, not one per hour.
 * <p>
 * This class holds the arithmetic and the state (R, the day's anchor, the hourly history, the last fills); the money
 * itself moves through {@link Exchange} as {@link Ledger.Postings} in the same transaction, and the desk's own
 * inventory is two ordinary HOUSE buckets ({@link #HOUSE}) checked by {@link Exchange#reconcile}.
 */
public final class Desk {

    /** The desk's ledger bucket id (HOUSE {@code NX} and {@code MESO}; the regulars use their own positive ids). */
    public static final long HOUSE = -100;

    static final long HOUR_MS = 3_600_000L;
    static final long DAY_MS = 86_400_000L;
    /** The site's history: hourly, the last 168 h (a week), oldest first. */
    static final int KEEP_HOURS = 168;
    /** The desk's own fill log for {@code mine} (a playing account's last 20 come from here). */
    static final int KEEP_FILLS = 200;
    /** The persisted state's version (the JSON blob in {@code exchange_desk.state_json}). */
    static final int VERSION = 1;

    /** One hour of the desk's history: the quote at its end, and what traded in it. */
    public record Hour(long hour, long mid, long buy, long sell, long volumeNx, int fills) {
    }

    /** One desk fill, for {@code mine} (the FillRow in {@code exchange_fills} keeps the chart's copy). */
    public record Fill(long fillId, long at, long account, Side side, long nx, long price, long mesos) {
    }

    /** What is persisted: R, the day's anchor, the last hourly step and the history. */
    public record State(long mid, long dayAnchorMid, long dayAnchorAt, long lastTickAt, long lastStepBps,
                        List<Hour> hours, List<Fill> fills) {
    }

    private final DeskConfig config;
    private long mid;
    private long dayAnchorMid;
    private long dayAnchorAt;
    private long lastTickAt;
    private long lastStepBps;
    /** The house's NX sold minus bought since the last hourly step (fills only; not persisted). */
    private long netFlowNx;
    private final TreeMap<Long, Hour> hours = new TreeMap<>();
    private final Deque<Fill> fills = new ArrayDeque<>();

    private Desk(DeskConfig config) {
        this.config = config;
    }

    /**
     * Starts the desk: R from the stored state, else the official rate the exchange has now (the VWAP the book
     * published until now), else {@link NxGuard#START_PRICE}; always inside the hard window.
     */
    public static Desk start(DeskConfig config, State state, Long officialNow, long now) {
        Desk d = new Desk(config);
        long start = state != null ? state.mid() : officialNow != null ? officialNow : NxGuard.START_PRICE;
        d.mid = NxGuard.clamp(start);
        if (state != null) {
            d.dayAnchorMid = state.dayAnchorMid();
            d.dayAnchorAt = state.dayAnchorAt();
            d.lastTickAt = state.lastTickAt();
            d.lastStepBps = state.lastStepBps();
            for (Hour h : state.hours()) {
                d.hours.put(h.hour(), h);
            }
            for (Fill f : state.fills()) {
                d.fills.addLast(f);
            }
            d.trim();
        }
        d.rollDay(now);
        return d;
    }

    public DeskConfig config() {
        return config;
    }

    /** The official NX rate: mesos per 1,000 NX. */
    public long mid() {
        return mid;
    }

    /** What the house pays the player for 1,000 NX. */
    public long buy() {
        return config.buy(mid);
    }

    /** What the house charges the player for 1,000 NX. */
    public long sell() {
        return config.sell(mid);
    }

    public long quote(Side side) {
        return side == Side.BUY ? sell() : buy();
    }

    public long spreadBps() {
        return config.spreadBps();
    }

    public long feeBps() {
        return config.feeBps();
    }

    public long dayCapPct() {
        return config.dayCapPct();
    }

    /** How far R has moved from the day's first R, in percent (either way). */
    public double movedTodayPct() {
        return dayAnchorMid <= 0 ? 0 : (mid - dayAnchorMid) * 100.0 / dayAnchorMid;
    }

    public double movedTodayPct(long at) {
        rollDay(at);
        return movedTodayPct();
    }

    /** The last hourly step's move, in basis points (what it did, after the caps). */
    public long lastStepBps() {
        return lastStepBps;
    }

    /** The hourly history, oldest first. */
    public List<Hour> hours() {
        return new ArrayList<>(hours.values());
    }

    /** One account's newest desk fills, newest first (what {@code mine} shows). */
    public List<Fill> fillsOf(long account, int n) {
        List<Fill> out = new ArrayList<>();
        Iterator<Fill> it = fills.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            Fill f = it.next();
            if (f.account() == account) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Mesos for {@code nx} NX at a per-1,000 quote: the player pays a BUY rounded up (so the house never gives NX for
     * less than the quote) and gets a SELL rounded down.
     */
    public long mesos(Side side, long nx, long quote) {
        long raw = Math.multiplyExact(nx, quote);
        return side == Side.BUY ? -Math.floorDiv(-raw, 1_000) : Math.floorDiv(raw, 1_000);
    }

    /** A fill's total mesos, the desk's own fee included (0 under the owner's set B). */
    public long total(Side side, long nx, long quote) {
        long mesos = mesos(side, nx, quote);
        return Math.addExact(mesos, config.fee(mesos));
    }

    /**
     * Records one fill: it feeds the hour's volume and the next hourly step's net flow (the house selling NX to a
     * player is {@code +nx}). R does not move on a fill; only {@link #tick} moves it.
     */
    public void recordFill(long fillId, long at, long account, Side side, long nx, long price, long mesos) {
        netFlowNx += side == Side.BUY ? nx : -nx;
        long hour = at / HOUR_MS;
        Hour h = hours.get(hour);
        long v = h == null ? 0 : h.volumeNx();
        int n = h == null ? 0 : h.fills();
        hours.put(hour, new Hour(hour, mid, buy(), sell(), v + nx, n + 1));
        fills.addLast(new Fill(fillId, at, account, side, nx, price, mesos));
        trim();
    }

    /**
     * Takes a recorded fill back when the store refused the batch that carried it: the money did not move, so neither
     * the hour's volume nor the next step's flow may count it (the exchange would otherwise drift on a fill that never
     * happened).
     */
    public void undoFill(Fill f) {
        netFlowNx -= f.side() == Side.BUY ? f.nx() : -f.nx();
        long hour = f.at() / HOUR_MS;
        Hour h = hours.get(hour);
        if (h != null) {
            long v = h.volumeNx() - f.nx();
            int n = h.fills() - 1;
            if (v <= 0 && n <= 0) {
                hours.remove(hour);
            } else {
                hours.put(hour, new Hour(hour, h.mid(), h.buy(), h.sell(), v, n));
            }
        }
        fills.removeLastOccurrence(f);
    }

    /**
     * The hourly step (nothing when the last one is less than an hour old). {@code floatNx} is every NX in accounts,
     * escrow and payouts. Returns the move it made in basis points (0 when nothing was due or nothing traded).
     */
    public long tick(long now, long floatNx) {
        if (now - lastTickAt < HOUR_MS) {
            return 0;
        }
        rollDay(now);
        long flow = netFlowNx;
        netFlowNx = 0;
        long before = mid;
        long raw = floatNx <= 0 ? 0 : Math.round(config.driftBpsPerPct() * (flow * 100.0 / floatNx));
        long step = Math.max(-config.maxStepBps(), Math.min(config.maxStepBps(), raw));
        long next = step == 0 ? mid : mid + mid * step / 10_000;
        if (step > 0) {
            next = Math.max(next, mid + 1);   // a step that rounds to nothing still moves a meso
        } else if (step < 0) {
            next = Math.min(next, mid - 1);
        }
        next = Math.min(next, dayHigh());
        next = Math.max(next, dayLow());
        mid = NxGuard.clamp(next);
        lastTickAt = now;
        lastStepBps = before == 0 ? 0 : Math.round((mid - before) * 10_000.0 / before);
        long hour = now / HOUR_MS;
        Hour h = hours.get(hour);
        hours.put(hour, new Hour(hour, mid, buy(), sell(), h == null ? 0 : h.volumeNx(),
                h == null ? 0 : h.fills()));
        trim();
        return lastStepBps;
    }

    /** When the last hourly step ran (0 before the first). */
    public long lastTickAt() {
        return lastTickAt;
    }

    private long dayLow() {
        return dayAnchorMid * (100L - config.dayCapPct()) / 100;
    }

    private long dayHigh() {
        return -Math.floorDiv(-dayAnchorMid * (100L + config.dayCapPct()), 100);
    }

    /** A new UTC day: its first R is today's anchor. */
    private void rollDay(long now) {
        long day = now - Math.floorMod(now, DAY_MS);
        if (dayAnchorAt != day) {
            dayAnchorAt = day;
            dayAnchorMid = mid;
        }
    }

    private void trim() {
        while (hours.size() > KEEP_HOURS) {
            hours.pollFirstEntry();
        }
        while (fills.size() > KEEP_FILLS) {
            fills.removeFirst();
        }
    }

    /** The whole state, for the store (and for a test's restart). */
    public State state() {
        return new State(mid, dayAnchorMid, dayAnchorAt, lastTickAt, lastStepBps, hours(), new ArrayList<>(fills));
    }

    /** The state as the store keeps it (one JSON column). */
    public static String encode(State s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", VERSION);
        m.put("mid", s.mid());
        m.put("day_anchor_mid", s.dayAnchorMid());
        m.put("day_anchor_at", s.dayAnchorAt());
        m.put("last_tick_at", s.lastTickAt());
        m.put("last_step_bps", s.lastStepBps());
        List<Map<String, Object>> hs = new ArrayList<>();
        for (Hour h : s.hours()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("hour", h.hour());
            e.put("mid", h.mid());
            e.put("buy", h.buy());
            e.put("sell", h.sell());
            e.put("volume_nx", h.volumeNx());
            e.put("fills", h.fills());
            hs.add(e);
        }
        m.put("hours", hs);
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Fill f : s.fills()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("fill", f.fillId());
            e.put("at", f.at());
            e.put("account", f.account());
            e.put("side", f.side().name());
            e.put("nx", f.nx());
            e.put("price", f.price());
            e.put("mesos", f.mesos());
            fs.add(e);
        }
        m.put("fills", fs);
        return Json.write(m);
    }

    /** A stored state, or null when the text is empty or not one of ours. */
    public static State decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Object doc;
        try {
            doc = Json.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(doc instanceof Map<?, ?> m)) {
            return null;
        }
        List<Hour> hs = new ArrayList<>();
        if (m.get("hours") instanceof List<?> rows) {
            for (Object o : rows) {
                if (!(o instanceof Map<?, ?> r)) {
                    continue;
                }
                hs.add(new Hour(num(r.get("hour")), num(r.get("mid")), num(r.get("buy")), num(r.get("sell")),
                        num(r.get("volume_nx")), (int) num(r.get("fills"))));
            }
        }
        List<Fill> fs = new ArrayList<>();
        if (m.get("fills") instanceof List<?> rows) {
            for (Object o : rows) {
                if (!(o instanceof Map<?, ?> r)) {
                    continue;
                }
                Side side = Side.parse(r.get("side"));
                if (side == null) {
                    continue;
                }
                fs.add(new Fill(num(r.get("fill")), num(r.get("at")), num(r.get("account")), side, num(r.get("nx")),
                        num(r.get("price")), num(r.get("mesos"))));
            }
        }
        long mid = num(m.get("mid"));
        if (mid <= 0) {
            return null;
        }
        return new State(mid, num(m.get("day_anchor_mid")), num(m.get("day_anchor_at")), num(m.get("last_tick_at")),
                num(m.get("last_step_bps")), hs, fs);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
