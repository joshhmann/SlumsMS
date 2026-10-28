package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.Ledger.Asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ticket 113, tickers (owner 2026-10-28): every ticker is a player order book for one asset, priced in mesos, on the
 * same engine as NX. No house price; nothing is minted or sunk but the fee of each side. NX keeps its numbers from
 * {@link ExchangeConfig} and its price per 1,000 NX; an item ticker is priced per one item and trades whole items.
 * <pre>
 *   NX      NX credit                    per 1,000 NX   (ExchangeConfig: 100..50,000 in lots of 100)
 *   LEAF    Maple Leaf 4001126           per item       1..1,000
 *   CHAOS   Chaos Scroll 60% 2049100     per item       1..100
 *   WS      White Scroll 2340000         per item       1..100
 *   ELIXIR  Power Elixir 2000005         per item       1..1,000
 * </pre>
 * Item tickers: prices 1..1,000,000,000 mesos per item (and a buy's total, fee included, must fit a character's
 * 2,147,483,647 mesos). JVM knobs, all optional: {@code -Dslums.exchange.tickers=NX,LEAF,CHAOS,WS,ELIXIR} (which are
 * open; NX always is), {@code -Dslums.exchange.<ticker>.maxQty}, {@code .minPrice}, {@code .maxPrice}.
 * <p>
 * Limits per ticker (owner 2026-10-28, raised for the item tickers; NX keeps {@link ExchangeConfig}'s): open orders
 * per account on this ticker, orders per account per 24 h on this ticker, and an order's life. Item tickers default to
 * 10 open, 20 a day each, 168 h (7 days), LEAF and ELIXIR orders up to 10,000 items, CHAOS and WS up to 1,000. Knobs:
 * {@code -Dslums.exchange.items.maxOpen|perDay|expiryHours} (every item ticker) and
 * {@code -Dslums.exchange.<ticker>.maxOpen|perDay|expiryHours} (one). The 10 s cooldown stays per account across
 * every ticker.
 *
 * @param per      prices are mesos per this many units (1,000 for NX, 1 for items)
 * @param unit     the export's unit key: "1000nx" or "item"
 * @param maxOpen  open orders an account may have on this ticker
 * @param perDay   orders an account may place on this ticker in 24 h
 * @param expiryMs an order's life (0 = good till cancelled)
 */
public record Ticker(String id, Asset asset, String name, long per, long lot, long minQty, long maxQty, long minPrice,
                     long maxPrice, String unit, String unitLabel, int maxOpen, int perDay, long expiryMs) {

    public static final String NX = "NX";
    /** The item tickers' default largest order, by ticker. */
    static final Map<String, Long> ITEM_MAX = Map.of("LEAF", 10_000L, "CHAOS", 1_000L, "WS", 1_000L,
            "ELIXIR", 10_000L);
    /** The item tickers' default limits (owner 2026-10-28): 10 open, 20 a day per ticker, 7 days. */
    static final int ITEM_MAX_OPEN = 10;
    static final int ITEM_PER_DAY = 20;
    static final long ITEM_EXPIRY_MS = 168L * 3_600_000L;
    static final Map<String, String> ITEM_NAME = Map.of("LEAF", "Maple Leaf", "CHAOS", "Chaos Scroll 60%",
            "WS", "White Scroll", "ELIXIR", "Power Elixir");
    static final long ITEM_MAX_PRICE = 1_000_000_000L;

    public Ticker {
        if (per <= 0 || lot <= 0 || minQty <= 0 || maxQty < minQty || minPrice <= 0 || maxPrice < minPrice
                || maxOpen <= 0 || perDay <= 0 || expiryMs < 0) {
            throw new IllegalArgumentException("bad ticker " + id);
        }
        Math.multiplyExact(maxQty, maxPrice);
    }

    public boolean item() {
        return asset.item();
    }

    /** Mesos for {@code qty} units at {@code price}, rounded down. */
    public long cost(long qty, long price) {
        return Math.multiplyExact(qty, price) / per;
    }

    /** What a buy order takes: the hold at its limit plus the buyer's fee reserve. */
    public long buyTotal(ExchangeConfig c, long qty, long price) {
        long cost = cost(qty, price);
        return Math.addExact(cost, c.fee(cost));
    }

    /** "1,000 NX" / "5 Maple Leaf". */
    public String amount(long qty) {
        return String.format("%,d %s", qty, item() ? name : "NX");
    }

    /** "mesos per 1,000 NX" / "mesos per Maple Leaf". */
    public String priceUnit() {
        return item() ? "mesos per " + name : "mesos per 1,000 NX";
    }

    /** The NX ticker, from the exchange's own config (its limits are the owner's 2026-10-27 decisions). */
    public static Ticker nx(ExchangeConfig c) {
        return new Ticker(NX, Asset.NX, "NX", OrderBook.PER, c.lotNx(), c.minNx(), c.maxNx(), c.minPrice(),
                c.maxPrice(), "1000nx", "mesos per 1,000 NX", c.maxOpen(), c.perDay(), c.expiryMs());
    }

    /** An item ticker with its defaults (no JVM properties). */
    public static Ticker itemDefault(Asset a) {
        if (!a.item()) {
            throw new IllegalArgumentException(a + " is not an item");
        }
        String name = ITEM_NAME.get(a.name());
        return new Ticker(a.name(), a, name, 1, 1, 1, ITEM_MAX.get(a.name()), 1, ITEM_MAX_PRICE, "item",
                "mesos per " + name, ITEM_MAX_OPEN, ITEM_PER_DAY, ITEM_EXPIRY_MS);
    }

    /** Every ticker with defaults: NX first, then the items in {@link Asset} order. */
    public static List<Ticker> defaults(ExchangeConfig c) {
        List<Ticker> out = new ArrayList<>();
        out.add(nx(c));
        for (Asset a : Asset.values()) {
            if (a.item()) {
                out.add(itemDefault(a));
            }
        }
        return out;
    }

    /** The open tickers from the JVM properties (see the class comment); NX is always there. */
    public static List<Ticker> fromSystem(ExchangeConfig c) {
        String only = System.getProperty("slums.exchange.tickers");
        List<String> want = null;
        if (only != null && !only.isBlank()) {
            want = new ArrayList<>();
            for (String s : only.split(",")) {
                want.add(s.trim().toUpperCase(Locale.ROOT));
            }
        }
        List<Ticker> out = new ArrayList<>();
        for (Ticker t : defaults(c)) {
            if (!t.item()) {
                out.add(t);
                continue;
            }
            if (want != null && !want.contains(t.id())) {
                continue;
            }
            String k = "slums.exchange." + t.id().toLowerCase(Locale.ROOT) + ".";
            try {
                String g = "slums.exchange.items.";
                long expiryH = l(k + "expiryHours", l(g + "expiryHours", t.expiryMs() / 3_600_000L));
                out.add(new Ticker(t.id(), t.asset(), t.name(), t.per(), t.lot(), t.minQty(),
                        l(k + "maxQty", t.maxQty()), l(k + "minPrice", t.minPrice()), l(k + "maxPrice", t.maxPrice()),
                        t.unit(), t.unitLabel(), (int) l(k + "maxOpen", l(g + "maxOpen", t.maxOpen())),
                        (int) l(k + "perDay", l(g + "perDay", t.perDay())), Math.multiplyExact(expiryH, 3_600_000L)));
            } catch (IllegalArgumentException | ArithmeticException e) {
                out.add(t);
            }
        }
        return out;
    }

    /** The tickers by id, in order. */
    public static Map<String, Ticker> byId(List<Ticker> list) {
        Map<String, Ticker> m = new LinkedHashMap<>();
        for (Ticker t : list) {
            m.put(t.id(), t);
        }
        return m;
    }

    /** The same ticker with other limits (tests, the QA staging). */
    public Ticker withLimits(int maxOpen, int perDay, long expiryMs) {
        return new Ticker(id, asset, name, per, lot, minQty, maxQty, minPrice, maxPrice, unit, unitLabel, maxOpen,
                perDay, expiryMs);
    }

    /** "leaf", "Leaf", "LEAF" -> "LEAF"; null for blank. */
    public static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static long l(String key, long dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
