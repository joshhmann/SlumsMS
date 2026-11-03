package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.AssetRegistry.Category;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Ticket 270: the knobs that switch the item catalog on, and the guard that keeps a player from selling a hoard out
 * in one day. All of them are JVM properties, like the rest of the exchange, because {@code config.yaml} is strict.
 * <pre>
 *   -Dslums.exchange.catalog=scrolls,use,etc        which catalog categories open (default: none, 1.6.3 behaviour)
 *   -Dslums.exchange.itemMaker=true                 regulars quote item tickers at their fair value (default: off)
 *   -Dslums.exchange.regularItems=5                 units per item-maker quote (default 5)
 *   -Dslums.exchange.items.sellCapMesosPerDay=0     mesos one account may take from item sells per UTC day
 *                                                   (0 = no cap; the owner's number is 20,000,000)
 * </pre>
 * The cap counts the mesos an account's item sell orders have filled since UTC midnight plus the mesos its open item
 * sell orders still hope for, so it binds at placement and again at collection. It is per account (not per
 * character), so splitting a hoard over characters does not help.
 */
public final class ExchangeLimits {

    public static final String CATALOG = "slums.exchange.catalog";
    public static final String ITEM_MAKER = "slums.exchange.itemMaker";
    public static final String REGULAR_ITEMS = "slums.exchange.regularItems";
    public static final String SELL_CAP = "slums.exchange.items.sellCapMesosPerDay";

    /** A buy's mesos hold must fit a character's mesos (2,147,483,647), so the biggest single buy is this. */
    public static final long MAX_BUY_MESOS = Integer.MAX_VALUE;

    private ExchangeLimits() {
    }

    /** The catalog categories switched on, lower-case; empty when the catalog is off. */
    public static Set<String> catalog() {
        Set<String> out = new LinkedHashSet<>();
        String raw = System.getProperty(CATALOG);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split(",")) {
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && !"none".equals(k) && !"off".equals(k)) {
                out.add(k);
                if (k.endsWith("s")) {
                    out.add(k.substring(0, k.length() - 1)); // "scrolls" and "scroll" are the same switch
                }
            }
        }
        return out;
    }

    public static boolean catalogHas(Category c) {
        return c != null && catalog().contains(c.key());
    }

    /** True when any category is on (the catalog file is then read once at startup). */
    public static boolean catalogOn() {
        return !catalog().isEmpty();
    }

    public static boolean itemMaker() {
        return Boolean.getBoolean(ITEM_MAKER);
    }

    public static long regularItems() {
        return l(REGULAR_ITEMS, 5);
    }

    /** 0 = no cap. */
    public static long sellCapMesosPerDay() {
        return Math.max(0, l(SELL_CAP, 0));
    }

    /** The UTC midnight at or before {@code now}. */
    public static long dayStart(long now) {
        return now - Math.floorMod(now, 86_400_000L);
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
