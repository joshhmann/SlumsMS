package soloMapling.Economy;

import client.inventory.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 162 part 5 follow-up (owner 2026-10-30: "if bots create scrolls they should check the prices of scrolls ...
 * drive it by some learning algo"): a scroll's price learned from what it really sells for.
 *
 * <p><b>What it learns from:</b> every sale the price board keeps ({@link PriceBook#record(PriceBook.Sample)}): FM
 * player shop and hired merchant sales, bot shop sales, bots buying from FM shops (bot seeds, capped per item per
 * day), single-item meso trades in the trade window and bot direct trades. Only sales: a listing that never sold
 * leaves no sample. The board already refuses bot-to-bot trades and sales between linked accounts, so this does too.
 * After a restart the board reads its window (7 days) of samples back from {@code pricesamples} and replays them
 * here in time order, so the learned price survives a restart with no table of its own.
 *
 * <p><b>The rule</b>, per scroll id (constants in {@code economy.yaml} under {@code scrollMarket.*}):
 * <ul>
 *   <li>An exponentially weighted average of sale prices: each sale moves the estimate {@code 1 - (1 - alpha)^w}
 *   of the way to its price ({@code alpha} 0.2), {@code w} its weight: 1 with a real player on a side, 0.5 for a
 *   sale between bots ({@code botWeight}), times {@code sqrt(quantity)} up to 3x for a bigger fill.</li>
 *   <li>A sale under {@code outlierLow} (0.2x) or over {@code outlierHigh} (5x) of the current estimate (the yaml
 *   price before the first sale) is ignored and counted as an outlier.</li>
 *   <li>The estimate moves at most {@code dayStep} (15%) a day from where it started that day, and always stays
 *   inside the price board's band around the yaml price ({@code priceBook.bandLow}..{@code bandHigh}, 0.25x..4x).</li>
 *   <li>The price used is a blend with the yaml price by how much it has seen: {@code n / (n + blendK)} learned
 *   ({@code blendK} 4), {@code n} the summed sale weights, halving every {@code halfLifeDays} (5) without sales, so
 *   a scroll nobody trades any more drifts back to its yaml price.</li>
 * </ul>
 * Switch: {@code -Dslums.scroll.marketPrice} (default: on when {@code -Dslums.scroll.ev} is on). Off, every caller
 * gets the yaml price back unchanged and nothing is learned.
 */
public final class ScrollMarketPrice {

    static final long DAY_MS = 86_400_000L;

    private static final Map<Integer, State> states = new ConcurrentHashMap<>();

    private ScrollMarketPrice() {
    }

    /** One scroll's learning state. Guarded by itself. */
    static final class State {
        double estimate;      // 0 = no sale yet
        double weight;        // summed sale weights, decayed to lastAt
        long lastAt;
        long day = Long.MIN_VALUE;
        double dayStart;
        long yaml = -1;       // the yaml price at the first sale (-1 = not looked up)
        int sales;
        int outliers;
        long lastPrice;
    }

    /** What the learner knows about a scroll, for the read-outs. */
    public record View(int itemId, long yaml, long learned, long price, double weight, int sales, int outliers,
                       long lastSale, long lastAt) {
        public String line(String name) {
            if (sales == 0) {
                return String.format("%s (%d): no sales learned yet (%d outliers); priced at its yaml %,d", name,
                        itemId, outliers, yaml);
            }
            return String.format("%s (%d): learned %,d from %d sales (weight %.1f, %d outliers ignored), yaml %,d;"
                            + " bots use %,d (%.0f%% learned)", name, itemId, learned, sales, weight, outliers, yaml,
                    price, 100 * blend(weight));
        }
    }

    // ---- switch and constants ----

    /** -Dslums.scroll.marketPrice; unset, it follows -Dslums.scroll.ev. */
    public static boolean enabled() {
        String v = System.getProperty("slums.scroll.marketPrice");
        return v == null ? Boolean.getBoolean("slums.scroll.ev") : Boolean.parseBoolean(v.trim());
    }

    /** A scroll the learner prices: item ids 204xxxx (plain, dark and chaos scrolls). */
    public static boolean isScroll(int itemId) {
        return itemId / 10_000 == 204;
    }

    static double alpha() {
        return clamp(EconomyConfig.getDouble("scrollMarket.alpha", 0.2), 0.01, 1.0);
    }

    static double botWeight() {
        return clamp(EconomyConfig.getDouble("scrollMarket.botWeight", 0.5), 0.0, 1.0);
    }

    static double outlierLow() {
        return clamp(EconomyConfig.getDouble("scrollMarket.outlierLow", 0.2), 0.0, 1.0);
    }

    static double outlierHigh() {
        return Math.max(1.0, EconomyConfig.getDouble("scrollMarket.outlierHigh", 5.0));
    }

    static double dayStep() {
        return clamp(EconomyConfig.getDouble("scrollMarket.dayStep", 0.15), 0.0, 1.0);
    }

    static double blendK() {
        return Math.max(0.0, EconomyConfig.getDouble("scrollMarket.blendK", 4.0));
    }

    static double halfLifeMs() {
        return Math.max(0.1, EconomyConfig.getDouble("scrollMarket.halfLifeDays", 5.0)) * DAY_MS;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- learning ----

    /** A kept sale from the price board (new, or read back after a restart). Never throws. */
    static void observe(PriceBook.Sample s) {
        if (s == null || !enabled() || !isScroll(s.itemId()) || s.unitPrice() <= 0) {
            return;
        }
        try {
            State st = states.computeIfAbsent(s.itemId(), id -> new State());
            synchronized (st) {
                if (st.yaml < 0) {
                    st.yaml = Math.max(0, yamlOf(s.itemId()));
                }
                learn(st, s.unitPrice(), Math.max(1, s.quantity()), s.playerSide(), s.at());
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger(ScrollMarketPrice.class).debug("scroll price sample", e);
        }
    }

    /** The rule itself, on one state (caller holds its lock). */
    static void learn(State st, long price, int quantity, boolean playerSide, long at) {
        double ref = st.estimate > 0 ? st.estimate : st.yaml > 0 ? st.yaml : price;
        if (price < ref * outlierLow() || price > ref * outlierHigh()) {
            st.outliers++;
            return;
        }
        if (st.lastAt > 0 && at > st.lastAt) {
            st.weight *= Math.pow(0.5, (at - st.lastAt) / halfLifeMs());
        }
        double est = st.estimate > 0 ? st.estimate : ref;
        long day = Math.floorDiv(at, DAY_MS);
        if (day != st.day) {
            st.day = day;
            st.dayStart = est;
        }
        double w = (playerSide ? 1.0 : botWeight()) * Math.min(3.0, Math.sqrt(quantity));
        if (w <= 0) {
            return;
        }
        double a = 1.0 - Math.pow(1.0 - alpha(), w);
        double next = est + a * (price - est);
        double step = dayStep();
        next = clamp(next, st.dayStart * (1 - step), st.dayStart * (1 + step));
        if (st.yaml > 0) {
            double lo = Math.max(0, EconomyConfig.getDouble("priceBook.bandLow", 0.25));
            double hi = Math.max(lo, EconomyConfig.getDouble("priceBook.bandHigh", 4.0));
            next = clamp(next, st.yaml * lo, st.yaml * hi);
        }
        st.estimate = Math.max(1, next);
        st.weight += w;
        st.sales++;
        st.lastPrice = price;
        st.lastAt = Math.max(st.lastAt, at);
    }

    private static double blend(double weight) {
        double k = blendK();
        return weight <= 0 ? 0 : k <= 0 ? 1 : weight / (weight + k);
    }

    private static double weightNow(State st, long now) {
        if (st.lastAt <= 0 || now <= st.lastAt) {
            return st.weight;
        }
        return st.weight * Math.pow(0.5, (now - st.lastAt) / halfLifeMs());
    }

    // ---- reading ----

    /**
     * What a scroll is worth now: the learned price blended with {@code yaml} by how much was learned. Returns
     * {@code yaml} unchanged when the switch is off, the id is not a scroll or nothing sold yet.
     */
    public static long price(int scrollId, long yaml) {
        if (!enabled() || !isScroll(scrollId)) {
            return yaml;
        }
        PriceBook.warm();
        State st = states.get(scrollId);
        if (st == null) {
            return yaml;
        }
        synchronized (st) {
            if (st.estimate <= 0) {
                return yaml;
            }
            if (yaml <= 0) {
                return Math.round(st.estimate);
            }
            double b = blend(weightNow(st, PriceBook.now()));
            return Math.max(1, Math.round(b * st.estimate + (1 - b) * yaml));
        }
    }

    /** {@link #price(int, long)} for the item pool's nullable prices: null stays null. */
    public static Integer price(int scrollId, Integer yaml) {
        if (yaml == null) {
            return null;
        }
        return (int) Math.min(Integer.MAX_VALUE, price(scrollId, (long) yaml));
    }

    /** The read-out for one scroll, {@code yaml} as the caller knows it (-1: look it up). */
    public static View view(int scrollId, long yaml) {
        PriceBook.warm();
        long y = yaml >= 0 ? yaml : Math.max(0, yamlOf(scrollId));
        State st = states.get(scrollId);
        if (st == null) {
            return new View(scrollId, y, 0, y, 0, 0, 0, 0, 0);
        }
        synchronized (st) {
            return new View(scrollId, y, Math.round(st.estimate), price(scrollId, y), weightNow(st, PriceBook.now()),
                    st.sales, st.outliers, st.lastPrice, st.lastAt);
        }
    }

    /** The scrolls with learned prices, most traded first, at most {@code limit}: for the dashboard. */
    public static List<Map<String, Object>> snapshot(int limit) {
        List<View> views = new ArrayList<>();
        for (Integer id : states.keySet()) {
            View v = view(id, -1);
            if (v.sales() > 0 || v.outliers() > 0) {
                views.add(v);
            }
        }
        views.sort((a, b) -> Integer.compare(b.sales(), a.sales()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (View v : views.subList(0, Math.min(Math.max(0, limit), views.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.itemId());
            row.put("yaml", v.yaml());
            row.put("learned", v.learned());
            row.put("price", v.price());
            row.put("sales", v.sales());
            row.put("weight", Math.round(v.weight() * 10) / 10.0);
            row.put("outliers", v.outliers());
            row.put("lastAt", v.lastAt());
            out.add(row);
        }
        return out;
    }

    /** The yaml (item pool) price of a scroll, through the price board's static value (so tests can stub it). */
    static long yamlOf(int scrollId) {
        return PriceBook.staticValue(new Item(scrollId, (short) 0, (short) 1));
    }

    // ---- test seams ----

    /** Forgets everything learned (PriceBook.useForTest calls this). */
    static void clearForTest() {
        states.clear();
    }
}
