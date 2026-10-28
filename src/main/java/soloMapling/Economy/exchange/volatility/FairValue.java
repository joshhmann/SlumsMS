package soloMapling.Economy.exchange.volatility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.6.1 volatility (owner 2026-10-28): a fair value per item ticker, from real game data, recomputed hourly.
 * <pre>
 *   floor    the NPC shop's buy price (LEAF 500,000; the others their item's shop price)
 *   ceiling  what the item buys at its sinks (LEAF: the best of Rooty's Maple Weapons per leaf, and while Rooty's
 *            Bonus runs a Chaos Scroll's fair value / 40; the scrolls and elixirs: 1.5x their price board value),
 *            never under 1.2x the floor
 *   supply   24 h of drops (players and bots) + exchange taker sells      ({@link MarketFlows})
 *   demand   24 h of sinks (Rooty, shops, crafts, use) + exchange taker buys
 *   raw      clamp(floor x (1 + k x (demand - supply) / max(supply, 1)), floor, ceiling)        k = 0.5
 *   fair     EMA: fair + alpha x (raw - fair) each hour                                         alpha = 0.3
 * </pre>
 * Value and maker traders anchor to it; momentum and panic deviate from it; guild-war news jumps on top. Shown in
 * {@code !exchange status} and as {@code fair} per ticker in {@code exchange-tickers.json}.
 */
public final class FairValue {

    /** Where the floors and ceilings come from (the game's shops and price board, or a simulation's numbers). */
    public interface Sources {
        long floor(String ticker);

        /** The ceiling, given the other tickers' current fair values (LEAF's Rooty's Bonus leg reads CHAOS). */
        long ceiling(String ticker, Map<String, Long> fairs);
    }

    /** One ticker's last computation, for status and tests. */
    public record Point(long floor, long ceiling, double supply, double demand, long raw, long fair) {
    }

    public static final List<String> TICKERS = List.of("LEAF", "CHAOS", "WS", "ELIXIR");

    private static volatile FairValue current;

    private final double k, alpha;
    private final Map<String, Point> points = new LinkedHashMap<>();
    private long nextAt;

    public FairValue(double k, double alpha) {
        this.k = k;
        this.alpha = Math.max(0.01, Math.min(1, alpha));
    }

    public static FairValue fromSystem() {
        return new FairValue(d("slums.market.fair.k", 0.5), d("slums.market.fair.alpha", 0.3));
    }

    /** The server's fair values (null until the volatility thread made one). */
    public static FairValue current() {
        return current;
    }

    static void setCurrent(FairValue f) {
        current = f;
    }

    /** A ticker's fair value from the server's instance, or null (the exchange's status and the site export). */
    public static Long of(String ticker) {
        FairValue f = current;
        return f == null ? null : f.fair(ticker);
    }

    /** The formula before smoothing. */
    public static long raw(long floor, long ceiling, double supply, double demand, double k) {
        long lo = Math.max(1, floor), hi = Math.max(lo, ceiling);
        double v = lo * (1 + k * (demand - supply) / Math.max(supply, 1));
        return Math.max(lo, Math.min(hi, Math.round(v)));
    }

    /** One EMA step; the first value is taken as it is. */
    public static long ema(Long prev, long raw, double alpha) {
        return prev == null ? raw : Math.round(prev + alpha * (raw - prev));
    }

    /** Recomputes every ticker now (CHAOS first: LEAF's ceiling reads it). */
    public synchronized void update(long now, MarketFlows flows, Sources src) {
        Map<String, Long> fairs = new LinkedHashMap<>();
        points.forEach((t, p) -> fairs.put(t, p.fair()));
        for (String t : List.of("CHAOS", "WS", "ELIXIR", "LEAF")) {
            long floor = src.floor(t);
            long ceiling = Math.max(Math.round(Math.max(1, floor) * 1.2), src.ceiling(t, fairs));
            double[] w = flows.window(t, now);
            long r = raw(floor, ceiling, w[0], w[1], k);
            Point old = points.get(t);
            long f = Math.max(Math.max(1, floor), Math.min(ceiling, ema(old == null ? null : old.fair(), r, alpha)));
            points.put(t, new Point(floor, ceiling, w[0], w[1], r, f));
            fairs.put(t, f);
        }
    }

    /** Recomputes once an hour (the volatility tick calls this every 30 s). */
    public synchronized boolean maybeUpdate(long now, MarketFlows flows, Sources src) {
        if (now < nextAt) {
            return false;
        }
        nextAt = now + MarketFlows.HOUR;
        update(now, flows, src);
        return true;
    }

    public synchronized Long fair(String ticker) {
        Point p = points.get(ticker);
        return p == null ? null : p.fair();
    }

    public synchronized Point point(String ticker) {
        return points.get(ticker);
    }

    private static double d(String key, double dflt) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? dflt : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ---------------------------------------------------------------- the game's sources

    /** Rooty's Maple Weapons: leaves per tier and one weapon of the tier to price it. */
    static final long[][] ROOTY_TIERS = {{5, 1302020}, {10, 1302030}, {15, 1302064}, {20, 1302142}};
    static final Map<String, Integer> ITEM = Map.of("LEAF", 4001126, "CHAOS", 2049100, "WS", 2340000,
            "ELIXIR", 2000005);

    /** The running game's floors (shop prices) and ceilings (sink utility from the price board). */
    public static Sources game(MarketEvents events) {
        return new Sources() {
            public long floor(String t) {
                if ("LEAF".equals(t)) {
                    return 500_000L;
                }
                try {
                    return Math.max(1, (long) server.ItemInformationProvider.getInstance().getPrice(ITEM.get(t), 1));
                } catch (RuntimeException e) {
                    return 1;
                }
            }

            public long ceiling(String t, Map<String, Long> fairs) {
                try {
                    if ("LEAF".equals(t)) {
                        long best = 0;
                        for (long[] tier : ROOTY_TIERS) {
                            best = Math.max(best, value((int) tier[1]) / tier[0]);
                        }
                        int bonus = MarketEvents.rootyBonusLeaves();
                        Long chaos = fairs.get("CHAOS");
                        if (bonus > 0 && chaos != null) {
                            best = Math.max(best, chaos / bonus);
                        }
                        return best;
                    }
                    return Math.round(value(ITEM.get(t)) * 1.5);
                } catch (RuntimeException e) {
                    return 0;
                }
            }
        };
    }

    private static long value(int itemId) {
        return soloMapling.Economy.PriceBook.fairValue(new client.inventory.Item(itemId, (short) 0, (short) 1));
    }
}
