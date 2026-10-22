package soloMapling.Economy;

import client.inventory.Equip;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.itemPool.ItemUtilities;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * 1.5.0 rc3 (owner decision 1 and 5): the price board. Every real sale and trade leaves a sample; an item's price is
 * the <b>median of the last {@code priceBook.windowDays} (7) days</b> of samples, and {@code @price <item>} shows that
 * median, the range and the sample count.
 * <ul>
 *   <li><b>What counts:</b> player shop and hired merchant sales, single-item meso trades in the trade window
 *   (player-player and player-bot), bot shop sales to players, and, while players are few, bot purchases from the
 *   FM's bot shops ({@link Source#BOT_SEED}).</li>
 *   <li><b>Bots seed, players decide:</b> once an item has {@code priceBook.playerOnlyAt} (5) samples with a real
 *   player on one side in the window, the median uses only those; before that, bot samples count too. Bot seed
 *   samples are capped at {@code priceBook.botSeedPerItemPerDay} (6) per item per day so they never drown a market.</li>
 *   <li><b>No samples:</b> fewer than {@code priceBook.minSamples} (3) and the board falls back to the static value
 *   the bots already used (the item pool's price, or {@code EquipValuation} for an equip).</li>
 *   <li><b>Equips:</b> two equips of one id differ by their stats, so an equip's sample is stored as its
 *   clean-equivalent price (price / its {@link GearGrade#quality quality}); a quote for a given equip multiplies the
 *   median back by that equip's quality.</li>
 * </ul>
 * Samples are kept in memory for the window and written to {@code pricesamples} (migration 020) off the game thread;
 * at the first read after a restart the window is read back. Older rows are pruned after {@code priceBook.keepDays}.
 */
public final class PriceBook {

    private static final Logger log = LoggerFactory.getLogger(PriceBook.class);

    /** Where a sample came from. */
    public enum Source {
        SHOP, MERCHANT, TRADE, BOT_SHOP, BOT_TRADE, BOT_SEED;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Source parse(String s) {
            for (Source x : values()) {
                if (x.key().equalsIgnoreCase(s == null ? "" : s.trim())) {
                    return x;
                }
            }
            return null;
        }
    }

    /** One sale: mesos per unit as paid, the clean-equivalent per unit, and whether a real player was a party. */
    public record Sample(int itemId, long unitPrice, long normPrice, int quantity, Source source, boolean playerSide,
                         long at) {
    }

    /** What the board says about an item. {@code fromSamples} false = the static fallback, no trades seen. */
    public record Quote(int itemId, long median, long low, long high, int samples, int playerSamples,
                        boolean fromSamples, boolean playerOnly) {
        public String describe(String name) {
            if (!fromSamples) {
                return String.format("%s: no recent trades (%d seen); usual value about %s", name, samples,
                        median > 0 ? mesos(median) : "unknown");
            }
            return String.format("%s: median %s over the last %d days (range %s - %s, %d sales%s)", name,
                    mesos(median), windowDays(), mesos(low), mesos(high), samples,
                    playerOnly ? ", players' trades only" : playerSamples > 0
                            ? ", " + playerSamples + " with players" : ", seeded by bot trades");
        }
    }

    private static final Map<Integer, Deque<Sample>> byItem = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> seedsToday = new ConcurrentHashMap<>();
    private static volatile EconomyStore store = new EconomyStore.Jdbc();
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile ToLongFunction<Item> staticValue = PriceBook::staticValueOf;
    private static volatile boolean loaded;
    private static volatile long lastPruneAt;

    static final int MAX_PER_ITEM = 400;

    private PriceBook() {
    }

    // ---- config ----

    static int windowDays() {
        return Math.max(1, EconomyConfig.getInt("priceBook.windowDays", 7));
    }

    static int playerOnlyAt() {
        return Math.max(1, EconomyConfig.getInt("priceBook.playerOnlyAt", 5));
    }

    static int minSamples() {
        return Math.max(1, EconomyConfig.getInt("priceBook.minSamples", 3));
    }

    static long windowMs() {
        return windowDays() * 86_400_000L;
    }

    // ---- recording ----

    /**
     * A sale of {@code item} for {@code totalPrice} mesos in all ({@code quantity} units). Cash and untradeable items
     * and free gifts are skipped.
     */
    public static void record(Item item, long totalPrice, int quantity, Source source, boolean playerSide) {
        if (item == null || totalPrice <= 0 || quantity <= 0 || source == null) {
            return;
        }
        try {
            long unit = Math.max(1, totalPrice / quantity);
            double q = item instanceof Equip eq ? GearGrade.quality(eq) : 1.0;
            long norm = Math.max(1, Math.round(unit / Math.max(0.1, q)));
            record(new Sample(item.getItemId(), unit, norm, quantity, source, playerSide, clock.getAsLong()));
        } catch (RuntimeException | LinkageError e) {
            log.debug("price sample skipped for {}", item.getItemId(), e);
        }
    }

    /**
     * Trade.completeTrade: a trade between two real players. When one side gave items of a single id and the other
     * only mesos, that is a price: one sample. {@code aItems} are what {@code a} put in, {@code aMeso} the mesos
     * {@code a} receives. Bot trades leave theirs through {@link BotDirectTrade}. Never throws.
     */
    public static void onWindowTrade(client.Character a, List<Item> aItems, int aMeso, client.Character b,
                                     List<Item> bItems, int bMeso) {
        try {
            if (a == null || b == null || soloMapling.ArtificialPlayer.BotHelpers.isBot(a)
                    || soloMapling.ArtificialPlayer.BotHelpers.isBot(b)) {
                return;
            }
            // xItems: what x put in; xMeso: the mesos x receives (Trade.getExchangeMesos)
            List<Item> items = !aItems.isEmpty() && bItems.isEmpty() && aMeso > 0 && bMeso == 0 ? aItems
                    : aItems.isEmpty() && !bItems.isEmpty() && bMeso > 0 && aMeso == 0 ? bItems : null;
            if (items == null || items.stream().map(Item::getItemId).distinct().count() != 1) {
                return;
            }
            int units = items.stream().mapToInt(i -> Math.max(1, i.getQuantity())).sum();
            record(items.get(0), aMeso > 0 ? aMeso : bMeso, units, Source.TRADE, true);
        } catch (RuntimeException e) {
            log.debug("trade sample", e);
        }
    }

    /** Adds one sample (bot seeds over the day's cap are dropped). True when it was kept. */
    public static boolean record(Sample s) {
        if (s == null || s.unitPrice() <= 0 || !EconomyConfig.getBool("priceBook.enabled", true)) {
            return false;
        }
        ensureLoaded();
        if (s.source() == Source.BOT_SEED) {
            long key = ((long) s.itemId() << 20) ^ (s.at() / 86_400_000L);
            int cap = Math.max(0, EconomyConfig.getInt("priceBook.botSeedPerItemPerDay", 6));
            int n = seedsToday.merge(key, 1, Integer::sum);
            if (n > cap) {
                return false;
            }
            if (seedsToday.size() > 50_000) {
                seedsToday.clear();
            }
        }
        add(s);
        EconomyStore st = store;
        writer.execute(() -> st.insertSample(s));
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, String.format("PRICE sample %d: %,d each x%d (%s%s); board now: %s",
                    s.itemId(), s.unitPrice(), s.quantity(), s.source().key(), s.playerSide() ? ", player" : ", bots",
                    quote(s.itemId(), 0).describe(String.valueOf(s.itemId()))));
        }
        maybePrune();
        return true;
    }

    private static void add(Sample s) {
        Deque<Sample> d = byItem.computeIfAbsent(s.itemId(), id -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(s);
            while (d.size() > MAX_PER_ITEM) {
                d.pollFirst();
            }
        }
    }

    // ---- reading ----

    /** The board's quote for an item id; {@code fallback} is the static value used when there are too few samples. */
    public static Quote quote(int itemId, long fallback) {
        ensureLoaded();
        long since = clock.getAsLong() - windowMs();
        List<Long> all = new ArrayList<>();
        List<Long> players = new ArrayList<>();
        Deque<Sample> d = byItem.get(itemId);
        if (d != null) {
            synchronized (d) {
                d.removeIf(s -> s.at() < since);
                for (Sample s : d) {
                    all.add(s.normPrice());
                    if (s.playerSide()) {
                        players.add(s.normPrice());
                    }
                }
            }
        }
        boolean playerOnly = players.size() >= playerOnlyAt();
        List<Long> used = playerOnly ? players : all;
        if (used.size() < minSamples()) {
            return new Quote(itemId, Math.max(0, fallback), 0, 0, all.size(), players.size(), false, false);
        }
        used.sort(Long::compare);
        return new Quote(itemId, median(used), used.get(0), used.get(used.size() - 1), used.size(), players.size(),
                true, playerOnly);
    }

    /** The board's quote for this item, with its static value as the fallback. */
    public static Quote quote(Item item) {
        return quote(item.getItemId(), staticValue.applyAsLong(item));
    }

    /**
     * What one unit of this item is fairly worth now: the 7-day median (times this equip's quality), or its static
     * value when there are too few trades. 0 when nobody can price it.
     */
    public static long fairValue(Item item) {
        if (item == null) {
            return 0;
        }
        Quote q = quote(item.getItemId(), -1);
        if (!q.fromSamples()) {
            return Math.max(0, staticValue.applyAsLong(item));
        }
        double quality = item instanceof Equip eq ? GearGrade.quality(eq) : 1.0;
        return Math.max(1, Math.round(q.median() * quality));
    }

    static long median(List<Long> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** The static value the bots used before the board: the item pool's price or EquipValuation; 0 when none. */
    static long staticValueOf(Item item) {
        try {
            Integer v = ItemUtilities.getItemMarketValue(item);
            return v == null ? 0 : Math.max(0, v);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    // ---- persistence ----

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (PriceBook.class) {
            if (loaded) {
                return;
            }
            loaded = true;
            List<Sample> rows = store.samplesSince(clock.getAsLong() - windowMs());
            for (Sample s : rows) {
                add(s);
            }
            if (!rows.isEmpty()) {
                log.info("[PriceBook] {} price samples read back for the last {} days", rows.size(), windowDays());
            }
        }
    }

    private static void maybePrune() {
        long now = clock.getAsLong();
        if (now - lastPruneAt < 6 * 3_600_000L) {
            return;
        }
        lastPruneAt = now;
        long before = now - Math.max(windowDays(), EconomyConfig.getInt("priceBook.keepDays", 14)) * 86_400_000L;
        EconomyStore st = store;
        writer.execute(() -> st.pruneSamples(before));
    }

    /** Number of items with samples in memory (for !economy). */
    public static int itemsTracked() {
        return byItem.size();
    }

    static String mesos(long v) {
        if (v >= 1_000_000_000L) {
            return trim(v / 1e9) + "b";
        }
        if (v >= 1_000_000L) {
            return trim(v / 1e6) + "m";
        }
        if (v >= 10_000L) {
            return trim(v / 1e3) + "k";
        }
        return String.format("%,d", v);
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** "1.25m", "300k": how bots write a price in chat. */
    public static String shorthand(long v) {
        return mesos(v);
    }

    // ---- test seams ----

    public static void useForTest(EconomyStore testStore, Executor testWriter, LongSupplier testClock,
                                  ToLongFunction<Item> testStatic) {
        store = testStore == null ? new EconomyStore.Jdbc() : testStore;
        writer = testWriter == null ? ExecutorServiceManager::runAsync : testWriter;
        clock = testClock == null ? System::currentTimeMillis : testClock;
        staticValue = testStatic == null ? PriceBook::staticValueOf : testStatic;
        byItem.clear();
        seedsToday.clear();
        loaded = false;
        lastPruneAt = clock.getAsLong();
    }
}
