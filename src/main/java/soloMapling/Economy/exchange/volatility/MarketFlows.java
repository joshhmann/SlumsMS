package soloMapling.Economy.exchange.volatility;

import java.util.HashMap;
import java.util.Map;

/**
 * 1.6.1 volatility, fair value: the real flows of the item tickers' items over a rolling 24 h, in hourly buckets.
 * <ul>
 *   <li><b>supply</b>: items that entered the world: monster drops (players' global and per-mob rolls, the bots'
 *   off-screen finds), plus the exchange's taker sells;</li>
 *   <li><b>demand</b>: items that left it at a sink: NPC scripts taking them (Rooty, the mastery books, the Root shop,
 *   Coco), NPC shop sells, bots using them up, plus the exchange's taker buys (so net exchange buying is demand).</li>
 * </ul>
 * Cheap counters only (a synchronized map add per event); never throws into the game. {@link FairValue} reads them
 * hourly.
 */
public final class MarketFlows {

    static final long HOUR = 3_600_000L;
    static final int HOURS = 24;


    private static final MarketFlows INSTANCE = new MarketFlows();

    /** ticker -> hour start -> {supply, demand}. */
    private final Map<String, Map<Long, double[]>> buckets = new HashMap<>();

    public static MarketFlows get() {
        return INSTANCE;
    }

    /** The ticker an item belongs to (the registry's reverse lookup), or null (the hooks' fast filter). */
    public static String tickerOf(int itemId) {
        soloMapling.Economy.exchange.AssetRegistry.Asset a =
                soloMapling.Economy.exchange.AssetRegistry.byItem(itemId);
        return a == null ? null : a.id();
    }

    /** True for an item ticker the exchange knows; null and NX/MESO are false. */
    public static boolean known(String ticker) {
        soloMapling.Economy.exchange.AssetRegistry.Asset a =
                soloMapling.Economy.exchange.AssetRegistry.of(ticker);
        return a != null && a.item();
    }

    /** {@code qty} of {@code itemId} entered the world (a drop, a find). No-op for other items. */
    public static void supplied(int itemId, long qty) {
        String t = tickerOf(itemId);
        if (t != null && qty > 0) {
            INSTANCE.add(t, qty, 0, System.currentTimeMillis());
        }
    }

    /** {@code qty} of {@code itemId} left the world at a sink (an NPC took it, a shop bought it, it was used up). */
    public static void consumed(int itemId, long qty) {
        String t = tickerOf(itemId);
        if (t != null && qty > 0) {
            INSTANCE.add(t, 0, qty, System.currentTimeMillis());
        }
    }

    /** An exchange fill on an item ticker: a taker buy is demand, a taker sell is supply. */
    public static void exchangeFill(String ticker, boolean takerBuys, long qty, long now) {
        if (ticker != null && qty > 0 && known(ticker)) {
            INSTANCE.add(ticker, takerBuys ? 0 : qty, takerBuys ? qty : 0, now);
        }
    }

    public synchronized void add(String ticker, double supply, double demand, long now) {
        long h = Math.floorDiv(now, HOUR) * HOUR;
        Map<Long, double[]> m = buckets.computeIfAbsent(ticker, k -> new HashMap<>());
        double[] b = m.computeIfAbsent(h, k -> new double[2]);
        b[0] += supply;
        b[1] += demand;
        m.keySet().removeIf(k -> k <= h - HOURS * HOUR);
    }

    /** {supply, demand} of the last 24 h. */
    public synchronized double[] window(String ticker, long now) {
        long h = Math.floorDiv(now, HOUR) * HOUR;
        double[] out = new double[2];
        Map<Long, double[]> m = buckets.get(ticker);
        if (m == null) {
            return out;
        }
        for (Map.Entry<Long, double[]> e : m.entrySet()) {
            if (e.getKey() > h - HOURS * HOUR) {
                out[0] += e.getValue()[0];
                out[1] += e.getValue()[1];
            }
        }
        return out;
    }

    public synchronized void clear() {
        buckets.clear();
    }
}
