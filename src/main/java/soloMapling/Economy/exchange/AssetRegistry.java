package soloMapling.Economy.exchange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ticket 270: what the exchange can trade. An {@link Asset} is the ledger's unit: {@code NX} and {@code MESO} are
 * money, every other asset is one item id counted in whole items. The six 1.6.3 assets are seeded here; the catalog
 * ({@code -Dslums.exchange.catalog=scrolls,use,etc}) registers one asset per item from {@code exchangeTickers.yaml}.
 * <pre>
 *   id        what {@code exchange_orders.ticker}, {@code exchange_ledger.asset} and {@code exchange_escrow.asset}
 *             store; at most 8 characters because those columns are VARCHAR(8)
 *   itemId    the item; 0 for NX and MESO
 *   category  NX, MESO, LEAF, SCROLL, USE, ETC, ITEM (ITEM = an item without a category)
 *   anchor    a starting price in mesos, 0 for none: the catalog's price, or the old starting price for the six
 * </pre>
 * The ticker id of an item asset is the asset id itself ({@code S2040805}), so a fill can be priced without a second
 * table. An id the registry does not know still resolves ({@link #of}) by its prefix — a ledger row written before a
 * ticker was switched off must stay readable, so reconcile never throws on an old asset.
 * <p>
 * Money is never registered: {@link #NX} is per account, {@link #MESO} and every item per character
 * ({@link Asset#perCharacter()})\), which is how {@code WALLET} and {@code PAYOUT} buckets pick their id.
 */
public final class AssetRegistry {

    private AssetRegistry() {
    }

    /** What an asset is, for caps and for the site's filter. */
    public enum Category {
        NX, MESO, LEAF, SCROLL, USE, ETC, ITEM;

        public static Category of(String s) {
            if (s == null || s.isBlank()) {
                return ITEM;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ITEM;
            }
        }

        /** The item categories the catalog can switch on ({@code -Dslums.exchange.catalog}). */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One tradeable asset. Identity is the id: the registry hands out one instance per id. */
    public static final class Asset {

        private final String id;
        private final int itemId;
        private final String name;
        private final Category category;
        private final long anchor;

        private Asset(String id, int itemId, String name, Category category, long anchor) {
            this.id = id;
            this.itemId = itemId;
            this.name = name == null || name.isBlank() ? id : name;
            this.category = category;
            this.anchor = Math.max(0, anchor);
        }

        /** The ledger and ticker id ({@code NX}, {@code MESO}, {@code LEAF}, {@code S2040805}). */
        public String id() {
            return id;
        }

        /** The enum-style name the old code used: the same string as {@link #id()}. */
        public String name() {
            return id;
        }

        /** The item id; 0 for NX and MESO. */
        public int itemId() {
            return itemId;
        }

        public boolean item() {
            return itemId > 0;
        }

        /** What the site and the commands call it ("Glove for ATT 10%"); the id for money. */
        public String display() {
            return name;
        }

        public Category category() {
            return category;
        }

        /** A starting price in mesos, 0 for none. */
        public long anchor() {
            return anchor;
        }

        /** A payout of this asset is kept per character (mesos, items) rather than per account (NX). */
        public boolean perCharacter() {
            return !NX.equals(this);
        }

        public static final Asset NX = new Asset("NX", 0, "NX", Category.NX, 0);
        public static final Asset MESO = new Asset("MESO", 0, "mesos", Category.MESO, 0);
        public static final Asset LEAF = new Asset("LEAF", 4001126, "Maple Leaf", Category.LEAF, 500_000L);
        public static final Asset CHAOS = new Asset("CHAOS", 2049100, "Chaos Scroll 60%", Category.SCROLL,
                20_000_000L);
        public static final Asset WS = new Asset("WS", 2340000, "White Scroll", Category.SCROLL, 10_000_000L);
        public static final Asset ELIXIR = new Asset("ELIXIR", 2000005, "Power Elixir", Category.USE, 6_000L);

        @Override
        public String toString() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Asset a && id.equals(a.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    public static final Asset NX = Asset.NX;
    public static final Asset MESO = Asset.MESO;
    public static final Asset LEAF = Asset.LEAF;
    public static final Asset CHAOS = Asset.CHAOS;
    public static final Asset WS = Asset.WS;
    public static final Asset ELIXIR = Asset.ELIXIR;

    private static final List<Asset> seeds = List.of(NX, MESO, LEAF, CHAOS, WS, ELIXIR);
    private static final Map<String, Asset> byId = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<Integer, Asset> byItem = Collections.synchronizedMap(new LinkedHashMap<>());

    static {
        for (Asset a : seeds) {
            byId.put(a.id(), a);
            if (a.item()) {
                byItem.putIfAbsent(a.itemId(), a);
            }
        }
    }

    /**
     * Registers an asset (or returns the one already there for that id). The first registration of an item id wins
     * the reverse lookup, so the six 1.6.3 ids keep their short names ({@code S2049100} resolves to {@code CHAOS}).
     */
    public static Asset register(String id, int itemId, String name, Category category, long anchor) {
        if (id == null || id.isBlank() || id.length() > 8) {
            throw new IllegalArgumentException("asset id must be 1..8 chars: " + id);
        }
        if (itemId < 0) {
            throw new IllegalArgumentException("bad item id for " + id + ": " + itemId);
        }
        synchronized (byId) {
            Asset have = byId.get(id);
            if (have != null) {
                return have;
            }
            Asset a = new Asset(id, itemId, name, category == null ? Category.ITEM : category, anchor);
            byId.put(id, a);
            if (a.item()) {
                byItem.putIfAbsent(itemId, a);
            }
            return a;
        }
    }

    /** Every registered asset, seeds first, in registration order. */
    public static List<Asset> all() {
        synchronized (byId) {
            return List.copyOf(byId.values());
        }
    }

    /** Every registered item asset (no NX, no MESO). */
    public static List<Asset> items() {
        List<Asset> out = new ArrayList<>();
        for (Asset a : all()) {
            if (a.item()) {
                out.add(a);
            }
        }
        return out;
    }

    /** The asset for an id: registered, else built from its prefix ({@code S2040805}) so old rows stay readable. */
    public static Asset of(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim().toUpperCase(Locale.ROOT);
        synchronized (byId) {
            Asset have = byId.get(key);
            if (have != null) {
                return have;
            }
            Asset cached = derived.get(key);
            if (cached != null) {
                return cached;
            }
            int itemId = parseItemId(key);
            if (itemId <= 0) {
                return null;
            }
            Asset a = new Asset(key, itemId, key, categoryOfPrefix(key), 0);
            derived.put(key, a);
            return a;
        }
    }

    /** The asset for an item id: the short 1.6.3 id when there is one, else the first registration, else null. */
    public static Asset byItem(int itemId) {
        synchronized (byId) {
            return byItem.get(itemId);
        }
    }

    private static final Map<String, Asset> derived = Collections.synchronizedMap(new LinkedHashMap<>());

    /** {@code S2040805} -> 2040805; anything else 0. */
    static int parseItemId(String key) {
        if (key.isEmpty() || key.charAt(0) < 'A' || key.charAt(0) > 'Z') {
            return 0;
        }
        String digits = key.substring(1);
        if (digits.isEmpty() || digits.length() > 7) {
            return 0;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return 0;
            }
        }
        int id = Integer.parseInt(digits);
        return id >= 1_000_000 ? id : 0;
    }

    private static Category categoryOfPrefix(String key) {
        return switch (key.charAt(0)) {
            case 'S' -> Category.SCROLL;
            case 'U' -> Category.USE;
            case 'E' -> Category.ETC;
            default -> Category.ITEM;
        };
    }

    /** Test seam: drops every catalog asset (the six seeds stay). */
    static void clearCatalog() {
        synchronized (byId) {
            byId.keySet().retainAll(seeds.stream().map(Asset::id).toList());
            byItem.keySet().retainAll(seeds.stream().filter(Asset::item).map(Asset::itemId).toList());
            derived.clear();
        }
    }
}
