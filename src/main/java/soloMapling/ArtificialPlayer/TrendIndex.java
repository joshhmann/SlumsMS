package soloMapling.ArtificialPlayer;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntToDoubleFunction;

/**
 * 1.6.0 S9, population learning: what real players do, by level band and time of day, so bots follow the server's
 * trends the way players follow each other (owner, social grill 2026-10-26: "a mind of their own that still follows
 * trends").
 *
 * <p>Three kinds of trend, each a small score per key, 0..1:
 * <ul>
 *   <li><b>Maps</b>, per (level band x 6-hour block of the day): the fields real players hunt. Every
 *   {@link #SAMPLE_MS} the online real players are looked at; one standing on a field with mobs counts 1 when its EXP
 *   or level went up since the last look on that map, else 0.25 (standing around).</li>
 *   <li><b>Items</b>, world-wide: what real players buy and sell, read from the price board's new player samples
 *   ({@link soloMapling.Economy.PriceBook}: shop, merchant, trade window and bot-shop sales with a player on one side),
 *   with a smoothed price per item. Nothing reads the price to set a bot's price (no economy change); it is there to
 *   talk about and for fads.</li>
 *   <li><b>PQs</b>, per (band x block): the party quests and bosses real players clear (the S3 {@code PQ_CLEAR} bus
 *   event), only from a closed list of names.</li>
 * </ul>
 *
 * <p>Owner rules (audit §7, 2026-10-26): <b>places, items and prices only</b> and <b>at most about 20% from any one
 * player</b>. So there is no text input anywhere in this class (a PQ name outside {@link #PQ_NAMES} is refused, chat
 * never reaches it), and a day's sample gives each player one vote spread over what they did, divided by
 * {@code max(players, 5)}: one player alone moves any key by at most 0.2 of a full trend.
 *
 * <p>Daily stepping, like {@link PlayerWealthIndex}: the day's votes feed an EMA (time constant {@link #TAU_DAYS} days)
 * once a day; scores are clamped to 0..1, the weakest pruned (at most {@link #MAX_MAPS_PER_CELL} maps a cell,
 * {@link #MAX_ITEMS} items). Saved to {@code reports/bot-trend-index.json} (the scores; a restart loses only the day's votes), read once.
 *
 * <p>What reads it: {@code TrainingMapChooser} weighs a trending map up to x(1 + {@link #MAP_BOOST}), and the brain's
 * plug-in bias ({@link #bias}, through {@code LifeLearning}) leans MOVE_MAP toward leaving an untrendy map and
 * PARTY_QUEST toward the band's trending PQ. Knob: {@code -Dsolomapling.trendIndex=false} (off: weight 1, bias 0).
 */
public final class TrendIndex {

    private static final Logger log = LoggerFactory.getLogger(TrendIndex.class);

    /** Upper level of each band: 1-20, 21-35, 36-50, 51-70, 71-100, 101-130, 131+. */
    static final int[] BAND_TOP = {20, 35, 50, 70, 100, 130, Integer.MAX_VALUE};
    /** Hours per block of the day (4 blocks: night, morning, afternoon, evening, server time). */
    static final int BLOCK_HOURS = 6;
    /** One player's vote counts at most this share of a full trend (the owner's ~20% rule). */
    public static final double PLAYER_SHARE_CAP = .20;
    /** EMA time constant, days. */
    static final double TAU_DAYS = 3;
    /** A fully trending map weighs this much more in the training-map pick (x1.5 at most). */
    public static final double MAP_BOOST = .5;
    /** The brain's pulls at a full trend: leave an untrendy map, join the trending PQ. */
    static final double MOVE_PULL = .10, PQ_PULL = .08;
    static final int MAX_MAPS_PER_CELL = 8, MAX_ITEMS = 40, MAX_PQ_PER_CELL = 4;
    /** Votes kept per day before the step (memory bound): players and keys. */
    static final int MAX_VOTERS = 200, MAX_KEYS_PER_VOTER = 32;
    static final long SAMPLE_MS = 5 * 60_000L, ITEM_SCAN_MS = 60 * 60_000L;
    static final double PRUNE_BELOW = .005;

    /**
     * The PQ and boss names the index accepts (SocialHooks.clearOf's names). Anything else, a chat line included, is
     * refused: the index never holds players' words.
     */
    public static final Set<String> PQ_NAMES = Set.of("KPQ", "LPQ", "OPQ", "HPQ", "EPQ", "PPQ", "APQ", "MPQ", "LMPQ",
            "the El Nath PQ", "GPQ", "CWKPQ", "the zak prequest", "the HT prequest", "boss rush", "treasure PQ",
            "zakum", "horntail", "papulatus", "pink bean", "balrog", "krexel", "cygnus", "the showa boss", "scarga");

    /** One vote set per player for a key type: player -> key -> weight. */
    private static final class Votes<K> {
        final Map<Integer, Map<K, Double>> byPlayer = new HashMap<>();

        boolean add(int player, K key, double weight) {
            if (!byPlayer.containsKey(player) && byPlayer.size() >= MAX_VOTERS) return false;
            Map<K, Double> mine = byPlayer.computeIfAbsent(player, p -> new HashMap<>());
            if (!mine.containsKey(key) && mine.size() >= MAX_KEYS_PER_VOTER) return false;
            mine.merge(key, weight, Double::sum);
            return true;
        }
    }

    private static final Object lock = new Object();
    /** Per (band, block) cell: today's map votes and the learned map scores. */
    private static final Map<Integer, Votes<Integer>> mapVotes = new HashMap<>();
    private static final Map<Integer, Map<Integer, Double>> mapScores = new HashMap<>();
    private static final Map<Integer, Votes<String>> pqVotes = new HashMap<>();
    private static final Map<Integer, Map<String, Double>> pqScores = new HashMap<>();
    private static final Votes<Integer> itemVotes = new Votes<>();
    private static final Map<Integer, List<Long>> itemPricesToday = new HashMap<>();
    private static final Map<Integer, Double> itemScores = new HashMap<>();
    /** Smoothed price per item, in log space. */
    private static final Map<Integer, Double> itemLogPrice = new HashMap<>();
    /** Last look per player: map id, EXP, level (to tell hunting from standing). */
    private static final Map<Integer, long[]> lastLook = new HashMap<>();
    private static long day = -1;
    private static long lastItemScanAt;
    private static long nextSampleAt;
    private static long votesToday;
    private static boolean loaded;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile ZoneId zone = ZoneId.systemDefault();

    private TrendIndex() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.trendIndex", "true"));
    }

    // ── keys ──

    /** The level band of a level, 0..6. */
    public static int band(int level) {
        for (int i = 0; i < BAND_TOP.length; i++) if (level <= BAND_TOP[i]) return i;
        return BAND_TOP.length - 1;
    }

    /** The 6-hour block of the day at {@code now}, 0..3 (server time). */
    public static int block(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).getHour() / BLOCK_HOURS;
    }

    static int cell(int band, int block) {
        return band * 8 + block;
    }

    static long dayOf(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay();
    }

    // ── observations (ids and numbers only: never text) ──

    /** A real player on a hunting field: {@code weight} 1 = hunting (EXP went up), less = standing there. */
    public static boolean observeHunt(int playerId, int level, int mapId, double weight, long now) {
        if (!enabled() || playerId <= 0 || mapId <= 0 || level <= 0 || !(weight > 0 && weight <= 1)) return false;
        synchronized (lock) {
            ensureLoaded();
            roll(now);
            boolean kept = mapVotes.computeIfAbsent(cell(band(level), block(now)), c -> new Votes<>())
                    .add(playerId, mapId, weight);
            if (kept) votesToday++;
            return kept;
        }
    }

    /** A real player's account bought or sold one item at {@code unitPrice} mesos each. */
    public static boolean observeItem(int account, int itemId, long unitPrice, long now) {
        if (!enabled() || account <= 0 || itemId <= 0 || unitPrice <= 0) return false;
        synchronized (lock) {
            ensureLoaded();
            roll(now);
            if (!itemVotes.add(account, itemId, 1)) return false;
            List<Long> prices = itemPricesToday.computeIfAbsent(itemId, i -> new ArrayList<>());
            if (prices.size() < 20) prices.add(unitPrice);
            votesToday++;
            return true;
        }
    }

    /**
     * A real player cleared a PQ or boss. {@code name} must be one of {@link #PQ_NAMES}; anything else (a chat line, a
     * nickname, slang) is refused and returns false.
     */
    public static boolean observePq(int playerId, int level, String name, long now) {
        if (!enabled() || playerId <= 0 || level <= 0 || name == null || !PQ_NAMES.contains(name)) return false;
        synchronized (lock) {
            ensureLoaded();
            roll(now);
            boolean kept = pqVotes.computeIfAbsent(cell(band(level), block(now)), c -> new Votes<>())
                    .add(playerId, name, 1);
            if (kept) votesToday++;
            return kept;
        }
    }

    // ── the maths (pure, tested) ──

    /**
     * One day's sample from everyone's votes: each player's votes are spread to sum 1, then everything is divided by
     * {@code max(players, 1 / PLAYER_SHARE_CAP)}. So one player is at most 20% of the mass, and fewer than five
     * players make a weaker trend, never a louder one.
     */
    static <K> Map<K, Double> sample(Map<Integer, Map<K, Double>> byPlayer) {
        Map<K, Double> out = new HashMap<>();
        double voters = Math.max(byPlayer.size(), 1 / PLAYER_SHARE_CAP);
        for (Map<K, Double> votes : byPlayer.values()) {
            double total = votes.values().stream().mapToDouble(Double::doubleValue).filter(v -> v > 0).sum();
            if (total <= 0) continue;
            votes.forEach((key, v) -> { if (v > 0) out.merge(key, v / total / voters, Double::sum); });
        }
        return out;
    }

    /** The EMA step over {@code days} days toward {@code sample} (absent keys count 0), clamped, pruned, top {@code keep}. */
    static <K> void step(Map<K, Double> scores, Map<K, Double> sample, double days, int keep) {
        double alpha = 1 - Math.exp(-Math.max(0, days) / TAU_DAYS);
        Set<K> keys = new HashSet<>(scores.keySet());
        keys.addAll(sample.keySet());
        for (K key : keys) {
            double before = scores.getOrDefault(key, 0.0);
            double next = before + alpha * (Math.min(1, sample.getOrDefault(key, 0.0)) - before);
            next = Math.max(0, Math.min(1, next));
            if (next < PRUNE_BELOW) scores.remove(key); else scores.put(key, next);
        }
        if (scores.size() > keep) {
            List<Map.Entry<K, Double>> sorted = new ArrayList<>(scores.entrySet());
            sorted.sort(Map.Entry.<K, Double>comparingByValue().reversed());
            for (int i = keep; i < sorted.size(); i++) scores.remove(sorted.get(i).getKey());
        }
    }

    /** At a new day: the day's votes become one EMA step (more if days were skipped), then they start over. */
    private static void roll(long now) {
        long today = dayOf(now);
        if (day < 0) { day = today; return; }
        if (today <= day) return;
        double days = Math.min(14, today - day);
        Set<Integer> cells = new HashSet<>(mapScores.keySet());
        cells.addAll(mapVotes.keySet());
        for (int c : cells) {
            Votes<Integer> v = mapVotes.get(c);
            Map<Integer, Double> scores = mapScores.computeIfAbsent(c, k -> new HashMap<>());
            step(scores, v == null ? Map.of() : sample(v.byPlayer), days, MAX_MAPS_PER_CELL);
            if (scores.isEmpty()) mapScores.remove(c);
        }
        Set<Integer> pqCells = new HashSet<>(pqScores.keySet());
        pqCells.addAll(pqVotes.keySet());
        for (int c : pqCells) {
            Votes<String> v = pqVotes.get(c);
            Map<String, Double> scores = pqScores.computeIfAbsent(c, k -> new HashMap<>());
            step(scores, v == null ? Map.of() : sample(v.byPlayer), days, MAX_PQ_PER_CELL);
            if (scores.isEmpty()) pqScores.remove(c);
        }
        step(itemScores, sample(itemVotes.byPlayer), days, MAX_ITEMS);
        double alpha = 1 - Math.exp(-days / TAU_DAYS);
        itemPricesToday.forEach((item, prices) -> {
            long[] sorted = prices.stream().mapToLong(Long::longValue).sorted().toArray();
            double median = Math.log(Math.max(1, sorted[sorted.length / 2]));
            itemLogPrice.merge(item, median, (old, fresh) -> old + alpha * (fresh - old));
        });
        itemLogPrice.keySet().retainAll(itemScores.keySet());
        mapVotes.clear();
        pqVotes.clear();
        itemVotes.byPlayer.clear();
        itemPricesToday.clear();
        votesToday = 0;
        day = today;
        dirty = true;
    }

    private static boolean dirty;

    // ── reads ──

    /** A map's trend score for this level now, 0..1. */
    public static double mapScore(int level, int mapId, long now) {
        if (!enabled()) return 0;
        synchronized (lock) {
            ensureLoaded();
            Map<Integer, Double> scores = mapScores.get(cell(band(level), block(now)));
            return scores == null ? 0 : scores.getOrDefault(mapId, 0.0);
        }
    }

    /** The training-map weight factor: 1 (no trend) .. 1 + {@link #MAP_BOOST} (everyone in this band hunts here now). */
    public static double mapWeight(int level, int mapId, long now) {
        return 1 + MAP_BOOST * mapScore(level, mapId, now);
    }

    /** The band's trending maps now, best first, with scores. */
    public static List<Map.Entry<Integer, Double>> topMaps(int level, long now, int k) {
        synchronized (lock) {
            ensureLoaded();
            return top(mapScores.getOrDefault(cell(band(level), block(now)), Map.of()), k);
        }
    }

    /** The band's trending PQ now (null: none), and its score. */
    public static Map.Entry<String, Double> topPq(int level, long now) {
        synchronized (lock) {
            ensureLoaded();
            var top = top(pqScores.getOrDefault(cell(band(level), block(now)), Map.of()), 1);
            return top.isEmpty() ? null : top.getFirst();
        }
    }

    /** What real players trade most lately, best first (item id, score). */
    public static List<Map.Entry<Integer, Double>> hotItems(int k) {
        synchronized (lock) {
            ensureLoaded();
            return top(itemScores, k);
        }
    }

    /** The smoothed price real players paid for an item lately (0: none seen). */
    public static long priceOf(int itemId) {
        synchronized (lock) {
            ensureLoaded();
            Double p = itemLogPrice.get(itemId);
            return p == null ? 0 : Math.round(Math.exp(p));
        }
    }

    private static <K> List<Map.Entry<K, Double>> top(Map<K, Double> scores, int k) {
        return scores.entrySet().stream().sorted(Map.Entry.<K, Double>comparingByValue().reversed()
                .thenComparing(e -> String.valueOf(e.getKey()))).limit(Math.max(0, k))
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList();
    }

    /**
     * The brain's trend pull for one bot (pure given the index): MOVE_MAP by how much better the band's top map trends
     * than the bot's own, PARTY_QUEST by the band's top PQ. 0 for anything else.
     */
    public static double bias(Activity activity, int level, int mapId, long now) {
        if (!enabled() || level <= 0) return 0;
        return switch (activity) {
            case MOVE_MAP -> {
                var top = topMaps(level, now, 1);
                if (top.isEmpty() || top.getFirst().getKey() == mapId) yield 0;
                yield MOVE_PULL * Math.max(0, top.getFirst().getValue() - mapScore(level, mapId, now));
            }
            case PARTY_QUEST -> {
                var pq = topPq(level, now);
                yield pq == null ? 0 : PQ_PULL * pq.getValue();
            }
            default -> 0;
        };
    }

    // ── the periodic look at real players (off-thread, from LifeLearning's sweep) ──

    /** Who counts: a real, visible, non-GM human (no bot, no auto-QA pretend player, no bot-range id). */
    static boolean counts(Character c) {
        return c != null && !BotHelpers.isBot(c) && c.gmLevel() <= 0 && !c.isHidden()
                && !(c.getClient() instanceof client.SyntheticClient) && !BotHelpers.isArtificial(c.getId());
    }

    /** Cheap unless {@link #SAMPLE_MS} passed: looks at every online real player once, and the price board hourly. */
    public static void maybeSample(long now, IntToDoubleFunction mobLevel) {
        if (!enabled() || !running.compareAndSet(false, true)) return;
        try {
            synchronized (lock) {
                if (now < nextSampleAt) return;
                nextSampleAt = now + SAMPLE_MS;
            }
            List<Character> people = new ArrayList<>();
            try {
                for (var world : net.server.Server.getInstance().getWorlds()) {
                    if (world != null) people.addAll(world.getPlayerStorage().getAllCharacters());
                }
            } catch (RuntimeException | LinkageError none) {
                return;
            }
            for (Character c : people) {
                if (!counts(c) || c.getMap() == null || c.getMap().isTown()) continue;
                int mapId = c.getMapId();
                if (mobLevel.applyAsDouble(mapId) < 0) continue;
                long exp = c.getExp(), level = c.getLevel();
                long[] last;
                synchronized (lock) {
                    last = lastLook.put(c.getId(), new long[]{mapId, exp, level});
                    if (lastLook.size() > 500) lastLook.clear();
                }
                boolean hunting = last != null && last[0] == mapId && (exp > last[1] || level > last[2]);
                observeHunt(c.getId(), c.getLevel(), mapId, hunting ? 1 : .25, now);
            }
            scanItems(now);
            saveIfDirty();
        } finally {
            running.set(false);
        }
    }

    /** New player-side samples on the price board since the last scan (sales with a real player's account on a side). */
    static void scanItems(long now) {
        long since;
        synchronized (lock) {
            if (now - lastItemScanAt < ITEM_SCAN_MS) return;
            since = lastItemScanAt == 0 ? now - ITEM_SCAN_MS : lastItemScanAt;
            lastItemScanAt = now;
        }
        try {
            for (int itemId : List.copyOf(soloMapling.Economy.PriceBook.trackedItemIds())) {
                for (var s : soloMapling.Economy.PriceBook.recentSamples(itemId)) {
                    if (s.at() <= since || !s.playerSide() || s.source() == soloMapling.Economy.PriceBook.Source.BOT_SEED) {
                        continue;
                    }
                    if (s.seller() > 0) observeItem(s.seller(), itemId, s.unitPrice(), now);
                    if (s.buyer() > 0) observeItem(s.buyer(), itemId, s.unitPrice(), now);
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            log.debug("Price board not readable for trends", unavailable);
        }
    }

    // ── report ──

    public static Map<String, Object> report(long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (lock) {
            ensureLoaded();
            out.put("enabled", enabled());
            out.put("votesToday", votesToday);
            out.put("cells", mapScores.size());
            Map<String, Object> maps = new LinkedHashMap<>();
            int block = block(now);
            for (int b = 0; b < BAND_TOP.length; b++) {
                var top = top(mapScores.getOrDefault(cell(b, block), Map.of()), 3);
                if (!top.isEmpty()) maps.put(bandName(b), top.stream().map(e -> e.getKey() + ":" + round(e.getValue())).toList());
            }
            out.put("topMapsNow", maps);
            Map<String, Object> pqs = new LinkedHashMap<>();
            for (int b = 0; b < BAND_TOP.length; b++) {
                var top = top(pqScores.getOrDefault(cell(b, block), Map.of()), 2);
                if (!top.isEmpty()) pqs.put(bandName(b), top.stream().map(e -> e.getKey() + ":" + round(e.getValue())).toList());
            }
            out.put("topPqNow", pqs);
            out.put("hotItems", top(itemScores, 5).stream().map(e -> e.getKey() + ":" + round(e.getValue())).toList());
        }
        return out;
    }

    static String bandName(int band) {
        int low = band == 0 ? 1 : BAND_TOP[band - 1] + 1;
        return band == BAND_TOP.length - 1 ? low + "+" : low + "-" + BAND_TOP[band];
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ── persistence ──

    private static Path file() {
        return Path.of(System.getProperty("solomapling.trendIndexFile", "reports/bot-trend-index.json"));
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            if (Json.parse(Files.readString(f)) instanceof Map<?, ?> m) read(m);
        } catch (IOException | RuntimeException e) {
            log.warn("[Trends] could not read {}; starting empty", f, e);
            clearAll();
        }
    }

    static void read(Map<?, ?> m) {
        day = m.get("day") instanceof Number n ? n.longValue() : -1;
        readCells(m.get("maps"), mapScores, Integer::parseInt, MAX_MAPS_PER_CELL);
        readCells(m.get("pqs"), pqScores, k -> PQ_NAMES.contains(k) ? k : null, MAX_PQ_PER_CELL);
        readScores(m.get("items"), itemScores, Integer::parseInt, MAX_ITEMS);
        if (m.get("prices") instanceof Map<?, ?> prices) prices.forEach((k, v) -> {
            if (v instanceof Number n && itemScores.containsKey(Integer.parseInt(String.valueOf(k)))) {
                itemLogPrice.put(Integer.parseInt(String.valueOf(k)), n.doubleValue());
            }
        });
    }

    private static <K> void readCells(Object raw, Map<Integer, Map<K, Double>> into,
                                      java.util.function.Function<String, K> key, int keep) {
        if (!(raw instanceof Map<?, ?> cells)) return;
        cells.forEach((c, scores) -> {
            Map<K, Double> one = new HashMap<>();
            readScores(scores, one, key, keep);
            if (!one.isEmpty()) into.put(Integer.parseInt(String.valueOf(c)), one);
        });
    }

    private static <K> void readScores(Object raw, Map<K, Double> into, java.util.function.Function<String, K> key,
                                       int keep) {
        if (!(raw instanceof Map<?, ?> scores)) return;
        scores.forEach((k, v) -> {
            K parsed = key.apply(String.valueOf(k));
            if (parsed != null && v instanceof Number n && n.doubleValue() > 0 && into.size() < keep) {
                into.put(parsed, Math.min(1, n.doubleValue()));
            }
        });
    }

    static Map<String, Object> write() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day);
        Map<String, Object> maps = new LinkedHashMap<>();
        mapScores.forEach((c, s) -> maps.put(String.valueOf(c), stringKeys(s)));
        out.put("maps", maps);
        Map<String, Object> pqs = new LinkedHashMap<>();
        pqScores.forEach((c, s) -> pqs.put(String.valueOf(c), stringKeys(s)));
        out.put("pqs", pqs);
        out.put("items", stringKeys(itemScores));
        out.put("prices", stringKeys(itemLogPrice));
        return out;
    }

    private static <K> Map<String, Object> stringKeys(Map<K, Double> scores) {
        Map<String, Object> out = new TreeMap<>();
        scores.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static void saveIfDirty() {
        String json;
        synchronized (lock) {
            if (!dirty) return;
            dirty = false;
            json = Json.write(write());
        }
        Path f = file();
        try {
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("[Trends] could not save {}", f, e);
        }
    }

    private static void clearAll() {
        mapVotes.clear(); mapScores.clear(); pqVotes.clear(); pqScores.clear(); itemVotes.byPlayer.clear();
        itemPricesToday.clear(); itemScores.clear(); itemLogPrice.clear(); lastLook.clear();
        day = -1; votesToday = 0; lastItemScanAt = 0; nextSampleAt = 0; dirty = false;
    }

    /** Test seam: empty, nothing read or written, in this zone (null: the system's). */
    public static void resetForTest(ZoneId testZone) {
        synchronized (lock) {
            clearAll();
            loaded = true;
            zone = testZone == null ? ZoneId.systemDefault() : testZone;
        }
        running.set(false);
    }

    /** Test seam: the day step now, as if the day rolled over. */
    static void stepForTest(long now) {
        synchronized (lock) {
            roll(now);
        }
    }
}
