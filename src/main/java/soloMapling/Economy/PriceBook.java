package soloMapling.Economy;

import client.inventory.Equip;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.itemPool.ItemUtilities;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import constants.inventory.ItemConstants;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *   <li><b>Stars and bullets</b> are priced per set: the item pool's price is the price of a whole stack and the trade
 *   window always moves the whole stack, so one stack is one unit ({@link #units}).</li>
 *   <li><b>Against pumping</b> (1.5.0 rc3 review): a trade between two players that share an account, an address
 *   (now or lately) or a machine is no price and leaves no sample; one pair of accounts leaves at most one sample per
 *   item per day; player samples replace bot seeds only once {@code priceBook.distinctTraders} (3) different sellers
 *   and buyers made them; and {@link #fairValue} stays within {@code priceBook.bandLow}..{@code priceBook.bandHigh}
 *   (0.25x..4x) of the item's static value ({@code priceBook.equipBandLow}..{@code equipBandHigh}, 0.25x..8x, for an
 *   equip, whose static value is {@code EquipValuation}'s).</li>
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

    /**
     * One sale: mesos per unit as paid, the clean-equivalent per unit, and whether a real player was a party.
     * {@code seller}/{@code buyer}: the accounts on each side (a bot is minus its character id), 0 when unknown (bot
     * seeds, and every sample read back after a restart: the table keeps no parties).
     */
    public record Sample(int itemId, long unitPrice, long normPrice, int quantity, Source source, boolean playerSide,
                         long at, int seller, int buyer) {
        public Sample(int itemId, long unitPrice, long normPrice, int quantity, Source source, boolean playerSide,
                      long at) {
            this(itemId, unitPrice, normPrice, quantity, source, playerSide, at, 0, 0);
        }
    }

    /**
     * One side of a sale: its account (a bot: minus its character id), the address it plays from now and its machine
     * id (null when not known, e.g. a hired merchant's owner who is offline).
     */
    public record Party(int account, String ip, String hwid) {
        public static Party of(client.Character c) {
            if (c == null) {
                return null;
            }
            if (soloMapling.ArtificialPlayer.BotHelpers.isBot(c)) {
                return new Party(-c.getId(), null, null);
            }
            String ip = null, hwid = null;
            try {
                client.Client cl = c.getClient();
                if (cl != null) {
                    ip = cl.getRemoteAddress();
                    hwid = cl.getHwid() == null ? null : cl.getHwid().hwid();
                }
            } catch (RuntimeException e) {
                // no session: the account alone
            }
            return new Party(c.getAccountID(), ip, hwid);
        }
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
    private static final Map<Integer, Map<String, Long>> recentIps = new ConcurrentHashMap<>();
    private static final Set<String> pairsToday = ConcurrentHashMap.newKeySet();
    private static volatile EconomyStore store = new EconomyStore.Jdbc();
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile ToLongFunction<Item> staticValue = PriceBook::staticValueOf;
    private static volatile ToLongFunction<Item> npcValue = PriceBook::npcValueOf;
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

    static int distinctTraders() {
        return Math.max(1, EconomyConfig.getInt("priceBook.distinctTraders", 3));
    }

    // ---- units ----

    /**
     * How many priced units a stack is: a star or bullet stack is one set (its price is the price of the set, and the
     * trade window and shops move the whole stack), anything else its quantity.
     */
    public static int units(Item item) {
        if (item == null) {
            return 0;
        }
        return ItemConstants.isRechargeable(item.getItemId()) ? 1 : Math.max(1, item.getQuantity());
    }

    /** What the whole stack is fairly worth: {@link #fairValue} x {@link #units}, at least 1 per unit. */
    public static long stackValue(Item item) {
        return Math.max(1, fairValue(item)) * Math.max(1, units(item));
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
        record(item, totalPrice, quantity, source, playerSide, 0, 0);
    }

    private static boolean record(Item item, long totalPrice, int quantity, Source source, boolean playerSide,
                                  int seller, int buyer) {
        try {
            if (ItemConstants.isRechargeable(item.getItemId()) && quantity > 1 && quantity >= item.getQuantity()) {
                quantity = 1;   // a star or bullet stack's price is the price of the set, not of each star
            }
            long unit = Math.max(1, totalPrice / quantity);
            double q = item instanceof Equip eq ? GearGrade.quality(eq) : 1.0;
            long norm = Math.max(1, Math.round(unit / Math.max(0.1, q)));
            return record(new Sample(item.getItemId(), unit, norm, quantity, source, playerSide, clock.getAsLong(),
                    seller, buyer));
        } catch (RuntimeException | LinkageError e) {
            log.debug("price sample skipped for {}", item.getItemId(), e);
            return false;
        }
    }

    /**
     * A sale between two known parties ({@code seller} gave the item, {@code buyer} the mesos). Left out when they
     * are linked ({@link #linked}: one account, an address now or lately, one machine) or when this pair of accounts
     * already left a sample for this item today. True when it was kept.
     */
    public static boolean recordBetween(Item item, long totalPrice, int quantity, Source source, Party seller,
                                        Party buyer) {
        if (item == null || totalPrice <= 0 || quantity <= 0 || source == null || seller == null || buyer == null) {
            return false;
        }
        try {
            noteParty(seller);
            noteParty(buyer);
            if (linked(seller, buyer)) {
                qaSkip(item.getItemId(), "the two sides share an account, an address or a machine");
                return false;
            }
            long day = clock.getAsLong() / 86_400_000L;
            int a = Math.min(seller.account(), buyer.account()), b = Math.max(seller.account(), buyer.account());
            String pair = item.getItemId() + ":" + a + ":" + b + ":" + day;
            if (pairsToday.size() > 100_000) {
                pairsToday.clear();
            }
            if (!pairsToday.add(pair)) {
                qaSkip(item.getItemId(), "this pair already left a sample for it today");
                return false;
            }
            boolean kept = record(item, totalPrice, quantity, source, true, seller.account(), buyer.account());
            if (!kept) {
                pairsToday.remove(pair);
            }
            return kept;
        } catch (RuntimeException e) {
            log.debug("price sample between parties", e);
            return false;
        }
    }

    private static void qaSkip(int itemId, String why) {
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, "PRICE no sample for " + itemId + ": " + why);
        }
    }

    /** Remembers the address an account traded from, for {@link #linked} (kept for the board's window). */
    static void noteParty(Party p) {
        if (p == null || p.account() <= 0 || p.ip() == null || p.ip().isBlank()) {
            return;
        }
        long now = clock.getAsLong();
        Map<String, Long> ips = recentIps.computeIfAbsent(p.account(), k -> new ConcurrentHashMap<>());
        ips.put(p.ip(), now);
        ips.values().removeIf(at -> at < now - windowMs());
        if (recentIps.size() > 50_000) {
            recentIps.clear();
        }
    }

    /**
     * True when two sides of a sale are likely one person: the same account, the same address now, an address both
     * accounts used lately, or the same machine id.
     */
    public static boolean linked(Party a, Party b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.account() != 0 && a.account() == b.account()) {
            return true;
        }
        if (a.ip() != null && !a.ip().isBlank() && a.ip().equals(b.ip())) {
            return true;
        }
        if (a.hwid() != null && !a.hwid().isBlank() && a.hwid().equals(b.hwid())) {
            return true;
        }
        Set<String> ipsA = recentIpsOf(a);
        if (ipsA.isEmpty()) {
            return false;
        }
        for (String ip : recentIpsOf(b)) {
            if (ipsA.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> recentIpsOf(Party p) {
        Set<String> out = new HashSet<>();
        if (p.ip() != null && !p.ip().isBlank()) {
            out.add(p.ip());
        }
        Map<String, Long> ips = p.account() > 0 ? recentIps.get(p.account()) : null;
        if (ips != null) {
            long since = clock.getAsLong() - windowMs();
            ips.forEach((ip, at) -> {
                if (at >= since) {
                    out.add(ip);
                }
            });
        }
        return out;
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
            onWindowTrade(Party.of(a), aItems, aMeso, Party.of(b), bItems, bMeso);
        } catch (RuntimeException e) {
            log.debug("trade sample", e);
        }
    }

    /** As above, for two players already known to be real: {@code a} and {@code b} are their sides. */
    static boolean onWindowTrade(Party a, List<Item> aItems, int aMeso, Party b, List<Item> bItems, int bMeso) {
        // xItems: what x put in; xMeso: the mesos x receives (Trade.getExchangeMesos)
        boolean aSells = !aItems.isEmpty() && bItems.isEmpty() && aMeso > 0 && bMeso == 0;
        boolean bSells = aItems.isEmpty() && !bItems.isEmpty() && bMeso > 0 && aMeso == 0;
        List<Item> items = aSells ? aItems : bSells ? bItems : null;
        if (items == null || items.stream().map(Item::getItemId).distinct().count() != 1) {
            return false;
        }
        int units = items.stream().mapToInt(PriceBook::units).sum();
        return recordBetween(items.get(0), aSells ? aMeso : bMeso, units, Source.TRADE, aSells ? a : b,
                aSells ? b : a);
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
        Set<Integer> sellers = new HashSet<>();
        Set<Integer> buyers = new HashSet<>();
        Deque<Sample> d = byItem.get(itemId);
        if (d != null) {
            synchronized (d) {
                d.removeIf(s -> s.at() < since);
                for (Sample s : d) {
                    all.add(s.normPrice());
                    if (s.playerSide()) {
                        players.add(s.normPrice());
                        if (s.seller() != 0) {
                            sellers.add(s.seller());
                        }
                        if (s.buyer() != 0) {
                            buyers.add(s.buyer());
                        }
                    }
                }
            }
        }
        // players' samples replace the bot seeds only once several different people sold and bought (one person's
        // alts trading among themselves cannot take the board over)
        boolean playerOnly = players.size() >= playerOnlyAt() && sellers.size() >= distinctTraders()
                && buyers.size() >= distinctTraders();
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
        return clampToStatic(item, Math.max(1, Math.round(q.median() * quality)));
    }

    /**
     * Keeps a price from trades inside a band around the item's static value (the item pool's price or
     * EquipValuation's for an equip; the NPC price when it has neither), so a handful of rigged trades cannot move
     * it far. Unchanged when the item has no static value at all.
     */
    static long clampToStatic(Item item, long price) {
        long anchor = Math.max(0, staticValue.applyAsLong(item));
        boolean equip = item instanceof Equip;
        if (anchor <= 0 && !equip) {
            anchor = Math.max(0, npcValue.applyAsLong(item));
        }
        if (anchor <= 0) {
            return price;
        }
        double lo = equip ? EconomyConfig.getDouble("priceBook.equipBandLow", 0.25)
                : EconomyConfig.getDouble("priceBook.bandLow", 0.25);
        double hi = equip ? EconomyConfig.getDouble("priceBook.equipBandHigh", 8.0)
                : EconomyConfig.getDouble("priceBook.bandHigh", 4.0);
        long low = Math.max(1, Math.round(anchor * Math.max(0, lo)));
        long high = Math.max(low, Math.round(anchor * Math.max(lo, hi)));
        return Math.max(low, Math.min(high, price));
    }

    /**
     * What a bot pays at most for one unit ({@link #units}) of this item bought from a player: its fair value, and
     * for a low-value item (static value up to {@code botBuying.npcCapBelow}, 100k) never more than
     * {@code botBuying.npcValueMultiple} (3) times what an NPC pays for it (for stars, the empty star plus its
     * recharge). Gear is left to {@link GearGrade}; items an NPC prices at 1 meso (scrolls) have no NPC value.
     */
    public static long botBuyValue(Item item) {
        long fair = fairValue(item);
        if (fair <= 0 || item instanceof Equip) {
            return fair;
        }
        long anchor = Math.max(0, staticValue.applyAsLong(item));
        long npc = Math.max(0, npcValue.applyAsLong(item));
        if (npc <= 1 || (anchor > 0 ? anchor : fair) > EconomyConfig.getLong("botBuying.npcCapBelow", 100_000L)) {
            return fair;
        }
        long cap = Math.max(1, Math.round(npc * Math.max(1.0,
                EconomyConfig.getDouble("botBuying.npcValueMultiple", 3.0))));
        return Math.min(fair, cap);
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

    /** What an NPC shop pays for one unit ({@link #units}) of it: the WZ price; a star stack's is star + recharge. */
    static long npcValueOf(Item item) {
        try {
            int qty = ItemConstants.isRechargeable(item.getItemId()) ? Math.max(1, item.getQuantity()) : 1;
            return Math.max(0, server.ItemInformationProvider.getInstance().getPrice(item.getItemId(), qty));
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

    public static void useNpcValueForTest(ToLongFunction<Item> testNpc) {
        npcValue = testNpc == null ? PriceBook::npcValueOf : testNpc;
    }

    public static void useForTest(EconomyStore testStore, Executor testWriter, LongSupplier testClock,
                                  ToLongFunction<Item> testStatic) {
        store = testStore == null ? new EconomyStore.Jdbc() : testStore;
        writer = testWriter == null ? ExecutorServiceManager::runAsync : testWriter;
        clock = testClock == null ? System::currentTimeMillis : testClock;
        staticValue = testStatic == null ? PriceBook::staticValueOf : testStatic;
        npcValue = testStatic == null ? PriceBook::npcValueOf : item -> 0L;
        byItem.clear();
        seedsToday.clear();
        recentIps.clear();
        pairsToday.clear();
        loaded = false;
        lastPruneAt = clock.getAsLong();
    }
}
