package soloMapling.Economy.exchange;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.DataFiles;
import soloMapling.Economy.exchange.AssetRegistry.Asset;
import soloMapling.Economy.exchange.AssetRegistry.Category;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ticket 270: the item catalog, as data. {@code exchangeTickers.yaml} names every item the exchange may trade with its
 * starting price ({@code anchor}) and its caps; this class registers one {@link Asset} per row and hands
 * {@link Ticker#fromSystem} the ticker template for it. Nothing here decides what is open: that is
 * {@link ExchangeLimits#catalog()} and {@code -Dslums.exchange.tickers}.
 * <pre>
 *   tickers:
 *     - id: S2040805                # ledger id, at most 8 characters
 *       item_id: 2040805
 *       name: Glove for ATT 10%
 *       category: SCROLL            # SCROLL, USE, ETC, LEAF or ITEM
 *       anchor: 1200000             # mesos per item the price starts from; 0 = none
 *       max_qty: 1                  # largest order, in items
 *       max_open: 3                 # open orders per account
 *       per_day: 10                 # orders per account per 24 h
 *       min_price: 1
 *       max_price: 1000000000
 * </pre>
 * A row that leaves the caps out gets its category's defaults ({@link #caps}), and a file that is missing or broken
 * leaves the catalog empty with a warning: the exchange then behaves exactly as it did in 1.6.3.
 */
public final class ExchangeCatalog {

    private static final Logger log = LoggerFactory.getLogger(ExchangeCatalog.class);

    static final String PATH = "src/main/java/soloMapling/itemPool/itemConfig/exchangeTickers.yaml";

    /** Category defaults: {maxQty, maxOpen, perDay}. Scrolls are the owner's 2026-11-03 numbers (1/3/10). */
    static final Map<Category, long[]> CAPS = Map.of(
            Category.SCROLL, new long[]{1, 3, 10},
            Category.USE, new long[]{1_000, 10, 20},
            Category.ETC, new long[]{10_000, 10, 20},
            Category.LEAF, new long[]{10_000, 10, 20},
            Category.ITEM, new long[]{1_000, 10, 20});

    static final long MIN_PRICE = 1;
    static final long MAX_PRICE = Ticker.ITEM_MAX_PRICE;

    private static volatile Map<String, Ticker> templates;

    private ExchangeCatalog() {
    }

    /** The catalog's ticker templates by id (empty when the file is absent). Read once. */
    public static Map<String, Ticker> templates() {
        Map<String, Ticker> have = templates;
        if (have != null) {
            return have;
        }
        synchronized (ExchangeCatalog.class) {
            if (templates == null) {
                templates = read();
            }
            return templates;
        }
    }

    /** Test seam (public for the volatility tests, which live in another package): forget the catalog and unregister
     * its assets (the six 1.6.3 assets stay). Nothing in the game calls this; {@code !reload} does not need it. */
    public static synchronized void reset() {
        templates = null;
        AssetRegistry.clearCatalog();
    }

    private static Map<String, Ticker> read() {
        Map<String, Ticker> out = new LinkedHashMap<>();
        File f = DataFiles.optional(PATH);
        if (!f.isFile()) {
            log.warn("exchange catalog: {} not found, no catalog tickers", PATH);
            return out;
        }
        try (Reader r = new FileReader(f)) {
            Object root = new YamlReader(r).read();
            if (!(root instanceof Map<?, ?> m)) {
                log.warn("exchange catalog: {} has no map at the top", PATH);
                return out;
            }
            Object list = m.get("tickers");
            if (!(list instanceof List<?> rows)) {
                log.warn("exchange catalog: {} has no 'tickers' list", PATH);
                return out;
            }
            for (Object row : rows) {
                if (row instanceof Map<?, ?> mm) {
                    Ticker t = row(mm);
                    if (t != null && !out.containsKey(t.id())) {
                        out.put(t.id(), t);
                    }
                }
            }
            log.info("exchange catalog: {} tickers in {}", out.size(), PATH);
        } catch (Exception e) {
            log.error("exchange catalog: could not read " + PATH, e);
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** One row -> an asset and its ticker, or null (logged) when the row cannot be used. */
    private static Ticker row(Map<?, ?> row) {
        String id = str(row.get("id"));
        int itemId = (int) num(row.get("item_id"), 0);
        if (id == null || itemId < 1_000_000) {
            log.warn("exchange catalog: row without a usable id/item_id: {}", row);
            return null;
        }
        id = id.toUpperCase(Locale.ROOT);
        Asset seed = AssetRegistry.byItem(itemId);
        if (seed != null) {
            // LEAF, CHAOS, WS, ELIXIR keep their 1.6.3 ids; one book per item.
            log.debug("exchange catalog: {} skipped, {} already trades as {}", id, itemId, seed.id());
            return null;
        }
        Category cat = Category.of(str(row.get("category")));
        String name = str(row.get("name"));
        long anchor = Math.max(0, num(row.get("anchor"), 0));
        long[] caps = CAPS.getOrDefault(cat, CAPS.get(Category.ITEM));
        long maxQty = Math.max(1, num(row.get("max_qty"), caps[0]));
        int maxOpen = (int) Math.max(1, num(row.get("max_open"), caps[1]));
        int perDay = (int) Math.max(1, num(row.get("per_day"), caps[2]));
        long minPrice = Math.max(1, num(row.get("min_price"), MIN_PRICE));
        long maxPrice = Math.max(minPrice, num(row.get("max_price"), MAX_PRICE));
        Asset a = AssetRegistry.register(id, itemId, name, cat, anchor);
        try {
            return new Ticker(a.id(), a, a.display(), 1, 1, 1, maxQty, minPrice, maxPrice, "item",
                    "mesos per " + a.display(), maxOpen, perDay, Ticker.ITEM_EXPIRY_MS);
        } catch (IllegalArgumentException | ArithmeticException e) {
            log.warn("exchange catalog: bad row " + id + ": " + e.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static long num(Object o, long dflt) {
        if (o == null) {
            return dflt;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
