package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.Item;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.OffscreenRewardBudget;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 15: what bots may take out of the shared economy.
 * <p>
 * Every meso and item a bot takes from the world - a mob drop it loots on screen, or its share of a map's yield
 * while nobody watches (OffscreenRewardBudget) - passes two meters here before it is credited:
 * <ul>
 *   <li><b>Per-map yield cap.</b> One meter per map instance (a map on one channel), shared by every bot on it,
 *   observed or not. Bots together may take at most {@code share} x the instance's own expected yield over a
 *   window: its spawn table, respawn times, live occupancy and the mobs' real drop tables at the world's rates,
 *   the same model as the offscreen budget. The meter drains continuously (a leaky bucket), so the cap is a rate,
 *   not a window that resets in one step. A map whose yield cannot be estimated (no spawn table) is not capped
 *   by map; the daily cap still holds there.</li>
 *   <li><b>Daily meso cap per bot.</b> Mesos a bot takes from the world in one server day, scaled by its level
 *   and the world's meso rate. It survives a restart (table {@code botyield}, migration 006).</li>
 * </ul>
 * What is refused stays where it was: a drop stays on the floor for a player, an offscreen share is forfeited.
 * <p>
 * Players are never metered: a non-bot passes untouched, and so does a drop a player dropped (a trade, a casino
 * bet, an item handed over) - that is value changing hands, not value leaving the world. Mesos and items a bot
 * gets from players (trades, its shop's sales) are transfers too and are not counted here. A bot starts with
 * nothing, so with every intake capped here, what bots can hand on through trades and shops is bounded by it.
 * Any new way for a bot to take value from the world (selling to an NPC shop, say) must call
 * {@link #takeMesos}/{@link #takeItem} the same way.
 * <p>
 * Tuned with JVM system properties, not config.yaml keys (the server's YAML is strict and an unknown key stops
 * the boot):
 * <ul>
 *   <li>{@code -Dsolomapling.botYieldCaps=false} switches both caps off.</li>
 *   <li>{@code -Dsolomapling.botMapYieldShare} (default {@value #DEFAULT_MAP_SHARE}): the share of a map
 *   instance's yield bots may take together.</li>
 *   <li>{@code -Dsolomapling.botYieldWindowMs} (default {@value #DEFAULT_WINDOW_MS}): the window the map cap is
 *   measured over, which is also the most a quiet map lets bots take in one burst.</li>
 *   <li>{@code -Dsolomapling.botDailyMesoPerLevel} (default {@value #DEFAULT_DAILY_MESO_PER_LEVEL}) and
 *   {@code -Dsolomapling.botDailyMesoFloor} (default {@value #DEFAULT_DAILY_MESO_FLOOR}): a bot's daily meso
 *   cap is max(floor, level x per-level) x the world's meso rate.</li>
 * </ul>
 */
public final class BotYieldCaps {

    private static final Logger log = LoggerFactory.getLogger(BotYieldCaps.class);

    static final double DEFAULT_MAP_SHARE = 1.0;
    static final long DEFAULT_WINDOW_MS = 600_000;
    static final long DEFAULT_DAILY_MESO_PER_LEVEL = 10_000;
    static final long DEFAULT_DAILY_MESO_FLOOR = 100_000;

    // How long a map instance's yield estimate is reused. Occupancy moves the estimate (the spawn rate follows
    // it), so it is refreshed about once a minute rather than on every pickup.
    static final long YIELD_REFRESH_MS = 60_000;

    /** The tunables, read once from the system properties. */
    record Limits(boolean enabled, double mapShare, long windowMs, long dailyMesoPerLevel, long dailyMesoFloor) {

        static Limits fromSystemProperties() {
            return new Limits(
                    !"false".equalsIgnoreCase(System.getProperty("solomapling.botYieldCaps", "true").trim()),
                    Math.max(0, doubleProperty("solomapling.botMapYieldShare", DEFAULT_MAP_SHARE)),
                    Math.max(60_000, longProperty("solomapling.botYieldWindowMs", DEFAULT_WINDOW_MS)),
                    Math.max(0, longProperty("solomapling.botDailyMesoPerLevel", DEFAULT_DAILY_MESO_PER_LEVEL)),
                    Math.max(0, longProperty("solomapling.botDailyMesoFloor", DEFAULT_DAILY_MESO_FLOOR)));
        }

        long dailyMesoCap(int level, int mesoRate) {
            long base = Math.max(dailyMesoFloor, dailyMesoPerLevel * Math.max(1, level));
            return saturatedMultiply(base, Math.max(1, mesoRate));
        }
    }

    /** Where a map instance's expected yield over one minute comes from; tests substitute one. */
    @FunctionalInterface
    interface YieldSource {
        OffscreenRewardBudget.Expected perMinute(MapleMap map);
    }

    // One map instance's meter. Its own monitor guards it; a take holds it before a bot's daily monitor.
    static final class MapMeter {
        final int mapId;
        double mesosUsed;
        double itemsUsed;
        long lastDrainMs;
        OffscreenRewardBudget.Expected perMinute;
        long estimatedAtMs = Long.MIN_VALUE;
        long mesosTaken;      // read-out: everything bots took here since the meter was made
        long itemsTaken;
        long refused;         // read-out: takes this meter turned down

        MapMeter(int mapId) {
            this.mapId = mapId;
        }

        double mesoCap(Limits limits) {
            return perMinute.mesos() * limits.mapShare() * limits.windowMs() / 60_000.0;
        }

        double itemCap(Limits limits) {
            return perMinute.itemUnits() * limits.mapShare() * limits.windowMs() / 60_000.0;
        }

        // The bucket empties at the cap's own rate: a full window of quiet frees a full cap.
        void drain(long nowMs, Limits limits) {
            if (lastDrainMs != 0 && nowMs > lastDrainMs) {
                double fraction = (double) (nowMs - lastDrainMs) / limits.windowMs();
                mesosUsed = Math.max(0, mesosUsed - mesoCap(limits) * fraction);
                itemsUsed = Math.max(0, itemsUsed - itemCap(limits) * fraction);
            }
            lastDrainMs = Math.max(lastDrainMs, nowMs);
        }
    }

    // One bot's take today. Its own monitor guards it.
    static final class Daily {
        final int charId;
        volatile String name;   // read-out only; unknown for a count restored from the store until it takes again
        LocalDate day;
        long mesos;
        long refused;
        boolean dirty;

        Daily(int charId, LocalDate day) {
            this.charId = charId;
            this.day = day;
        }

        void rollTo(LocalDate today) {
            if (!today.equals(day)) {
                day = today;
                mesos = 0;
                refused = 0;
                dirty = true;
            }
        }
    }

    private static volatile Limits limits = Limits.fromSystemProperties();
    private static volatile YieldSource yieldSource = BotYieldCaps::liveYield;
    private static volatile Clock clock = Clock.systemDefaultZone();

    // Weakly keyed by the instance itself: a disposed map instance takes its meter with it.
    private static final Map<MapleMap, MapMeter> meters = Collections.synchronizedMap(new WeakHashMap<>());
    // Keyed by character id; one small entry per bot that took mesos today.
    private static final Map<Integer, Daily> dailies = new ConcurrentHashMap<>();

    private BotYieldCaps() {
    }

    // Ticket 31: how spent each map's bot yield cap is, by map id (the fullest instance), 0..1. A snapshot rebuilt
    // at most every SATURATION_REFRESH_MS, so the leveling path can steer bots off maps the bots there already
    // drain, without walking the meters on every decision.
    static final long SATURATION_REFRESH_MS = 15_000;
    private static volatile Map<Integer, Double> saturation = Map.of();
    private static volatile long saturationAtMs = Long.MIN_VALUE;

    public static double mapSaturation(int mapId) {
        Limits limits = BotYieldCaps.limits;
        if (!limits.enabled()) {
            return 0;
        }
        long now = clock.millis();
        if (saturationAtMs == Long.MIN_VALUE || now - saturationAtMs >= SATURATION_REFRESH_MS) {
            saturationAtMs = now;
            Map<Integer, Double> next = new java.util.HashMap<>();
            List<MapMeter> snapshot;
            synchronized (meters) {
                snapshot = new ArrayList<>(meters.values());
            }
            for (MapMeter meter : snapshot) {
                double fill;
                synchronized (meter) {
                    if (meter.perMinute == null || meter.perMinute.isEmpty()) {
                        continue;
                    }
                    meter.drain(now, limits);
                    double cap = meter.mesoCap(limits);
                    fill = cap <= 0 ? 0 : Math.min(1.0, meter.mesosUsed / cap);
                }
                next.merge(meter.mapId, fill, Math::max);
            }
            saturation = next;
        }
        return saturation.getOrDefault(mapId, 0.0);
    }

    // ---- what the loot paths call ----

    /** Non-mutating: true when the caps let this bot take this drop now. Always true for a player. */
    public static boolean mayTakeDrop(Character bot, MapItem drop) {
        if (!metered(bot, drop)) {
            return true;
        }
        if (drop.getMeso() > 0 && !BotWealth.mayTakeMesos(bot)) {
            return false;   // 56B: at its hard wealth line; the mesos stay on the floor for a player
        }
        return reserve(bot, bot.getMap(), Math.max(0, drop.getMeso()), dropUnits(drop), false, false) >= 0;
    }

    /**
     * Takes this drop out of the caps. False when a cap refuses it: the drop must stay on the floor. A caller
     * whose credit then fails gives the take back with {@link #returnDrop}.
     */
    public static boolean takeDrop(Character bot, MapItem drop) {
        if (!metered(bot, drop)) {
            return true;
        }
        if (drop.getMeso() > 0 && !BotWealth.takeDropMesos(bot, java.util.concurrent.ThreadLocalRandom.current())) {
            return false;   // 56B: over its wealth ceiling, intake slowed (stopped at the hard line)
        }
        boolean ok = reserve(bot, bot.getMap(), Math.max(0, drop.getMeso()), dropUnits(drop), false, true) >= 0;
        if (ok && drop.getMeso() > 0) {
            soloMapling.Economy.stats.EconomyCounters.recordMobMesoDrop(true, drop.getMeso());
        }
        return ok;
    }

    /** Gives back a {@link #takeDrop} whose credit failed. */
    public static void returnDrop(Character bot, MapItem drop) {
        if (metered(bot, drop)) {
            release(bot, bot.getMap(), Math.max(0, drop.getMeso()), dropUnits(drop));
        }
    }

    /**
     * Takes up to {@code mesos} from the world for a bot (an offscreen share): the part the caps allow, possibly
     * 0. A player gets the whole amount. Give back what could not be credited with {@link #returnMesos}.
     */
    public static long takeMesos(Character bot, long mesos) {
        if (mesos <= 0) {
            return 0;
        }
        if (!isMeteredBot(bot)) {
            return mesos;
        }
        mesos = BotWealth.cutMesos(bot, mesos);   // 56B: cut in proportion over the wealth ceiling
        if (mesos <= 0) {
            return 0;
        }
        long taken = Math.max(0, reserve(bot, bot.getMap(), mesos, 0, true, true));
        if (taken > 0) {
            soloMapling.Economy.stats.EconomyCounters.recordMobMesoDrop(true, taken);
        }
        return taken;
    }

    public static void returnMesos(Character bot, long mesos) {
        if (mesos > 0 && isMeteredBot(bot)) {
            release(bot, bot.getMap(), mesos, 0);
        }
    }

    /** Takes an item from the world for a bot (an offscreen share): all of its units, or none. */
    public static boolean takeItem(Character bot, Item item) {
        if (item == null || !isMeteredBot(bot)) {
            return true;
        }
        return reserve(bot, bot.getMap(), 0, Math.max(1, item.getQuantity()), false, true) >= 0;
    }

    public static void returnItem(Character bot, Item item) {
        if (item != null && isMeteredBot(bot)) {
            release(bot, bot.getMap(), 0, Math.max(1, item.getQuantity()));
        }
    }

    // ---- the meters ----

    private static boolean isMeteredBot(Character bot) {
        return limits.enabled() && BotHelpers.isBot(bot);
    }

    // A player's drop is value changing hands, and a prop is no value at all: neither is taken from the world.
    private static boolean metered(Character bot, MapItem drop) {
        return drop != null && !drop.isPlayerDrop() && !drop.isConjured() && isMeteredBot(bot);
    }

    private static int dropUnits(MapItem drop) {
        if (drop.getMeso() > 0) {
            return 0;
        }
        Item item = drop.getItem();
        return item == null ? 1 : Math.max(1, item.getQuantity());
    }

    /**
     * Checks (and with {@code commit}, records) a take against the bot's map instance and its day.
     *
     * @param partial true when the mesos may be cut down to what is left (an offscreen share); false when it is
     *                all or nothing (a drop on the floor)
     * @return the mesos granted (0 for an item-only take), or -1 when the take is refused
     */
    static long reserve(Character bot, MapleMap map, long mesos, int units, boolean partial, boolean commit) {
        Limits limits = BotYieldCaps.limits;
        long now = clock.millis();
        MapMeter meter = meterFor(map, now, limits);
        Daily daily = mesos > 0 ? dailyFor(bot, today()) : null;
        long dailyCap = daily == null ? 0 : limits.dailyMesoCap(bot.getLevel(), mesoRateOf(bot));
        if (meter == null) {
            return daily == null ? 0 : reserveDaily(daily, dailyCap, mesos, partial, commit);
        }
        synchronized (meter) {
            meter.drain(now, limits);
            double mesoRoom = meter.mesoCap(limits) - meter.mesosUsed;
            double itemRoom = meter.itemCap(limits) - meter.itemsUsed;
            if (units > 0 && units > itemRoom + 1e-9) {
                if (commit) {
                    meter.refused++;
                }
                return -1;
            }
            long granted = 0;
            if (mesos > 0) {
                long room = (long) Math.floor(Math.max(0, mesoRoom) + 1e-9);
                long want = partial ? Math.min(mesos, room) : mesos;
                if (want > room || want <= 0) {
                    if (commit) {
                        meter.refused++;
                    }
                    return partial && units == 0 ? 0 : -1;
                }
                granted = reserveDaily(daily, dailyCap, want, partial, commit);
                if (granted < 0) {
                    return -1;
                }
            }
            if (commit) {
                meter.mesosUsed += granted;
                meter.itemsUsed += units;
                meter.mesosTaken += granted;
                meter.itemsTaken += units;
            }
            return granted;
        }
    }

    private static long reserveDaily(Daily daily, long cap, long mesos, boolean partial, boolean commit) {
        synchronized (daily) {
            daily.rollTo(today());
            long room = Math.max(0, cap - daily.mesos);
            long want = partial ? Math.min(mesos, room) : mesos;
            if (want > room || want <= 0) {
                if (commit) {
                    daily.refused++;
                }
                return partial ? 0 : -1;
            }
            if (commit) {
                daily.mesos += want;
                daily.dirty = true;
            }
            return want;
        }
    }

    static void release(Character bot, MapleMap map, long mesos, int units) {
        MapMeter meter = map == null ? null : meters.get(map);
        if (meter != null) {
            synchronized (meter) {
                meter.mesosUsed = Math.max(0, meter.mesosUsed - mesos);
                meter.itemsUsed = Math.max(0, meter.itemsUsed - units);
                meter.mesosTaken = Math.max(0, meter.mesosTaken - mesos);
                meter.itemsTaken = Math.max(0, meter.itemsTaken - units);
            }
        }
        if (mesos > 0) {
            Daily daily = dailies.get(bot.getId());
            if (daily != null) {
                synchronized (daily) {
                    daily.mesos = Math.max(0, daily.mesos - mesos);
                    daily.dirty = true;
                }
            }
        }
    }

    // The instance's meter, its estimate refreshed when stale. Null when the map is not capped: no map, the cap
    // switched off, or no yield to measure against (a map without a spawn table).
    private static MapMeter meterFor(MapleMap map, long now, Limits limits) {
        if (map == null) {
            return null;
        }
        MapMeter meter = meters.computeIfAbsent(map, m -> new MapMeter(m.getId()));
        synchronized (meter) {
            if (meter.perMinute == null || now - meter.estimatedAtMs >= YIELD_REFRESH_MS) {
                OffscreenRewardBudget.Expected estimate = null;
                try {
                    estimate = yieldSource.perMinute(map);
                } catch (RuntimeException e) {
                    log.debug("No yield estimate for map {}", meter.mapId, e);
                }
                // Draining at the old rate up to now keeps a new estimate from forgiving or charging the past.
                meter.drain(now, limits);
                meter.perMinute = estimate == null ? OffscreenRewardBudget.Expected.NONE : estimate;
                meter.estimatedAtMs = now;
            }
            return meter.perMinute.isEmpty() ? null : meter;
        }
    }

    // The live estimate: the map's spawn table at its current occupancy, the world's drop and meso rates, and a
    // spawn multiplier an event may have put on the instance.
    private static OffscreenRewardBudget.Expected liveYield(MapleMap map) {
        World world = map.getWorldServer();
        int dropRate = world == null ? 1 : world.getDropRate();
        int mesoRate = world == null ? 1 : world.getMesoRate();
        OffscreenRewardBudget.Expected perWindow = OffscreenRewardBudget.expectedPerWindow(map.getId(),
                map.getCharacters().size(), dropRate, mesoRate);
        int multiplier = Math.max(1, map.getSpawnRateMultiplier());
        double perMinute = 60_000.0 / OffscreenRewardBudget.WINDOW_MS * multiplier;
        return new OffscreenRewardBudget.Expected(perWindow.mesos() * perMinute, perWindow.itemUnits() * perMinute);
    }

    private static int mesoRateOf(Character bot) {
        try {
            World world = bot.getWorldServer();
            return world == null ? 1 : Math.max(1, world.getMesoRate());
        } catch (RuntimeException e) {
            return 1;   // no world in this environment (a headless test): the base rate
        }
    }

    private static Daily dailyFor(Character bot, LocalDate today) {
        BotYieldStore.ensureLoaded(today);
        Daily daily = dailies.computeIfAbsent(bot.getId(), id -> new Daily(id, today));
        daily.name = bot.getName();
        return daily;
    }

    static LocalDate today() {
        return LocalDate.now(clock);
    }

    private static long saturatedMultiply(long a, long b) {
        long product = a * b;
        return (a != 0 && product / a != b) ? Long.MAX_VALUE : product;
    }

    private static double doubleProperty(String key, double fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Ignoring -D{}={}: not a number", key, value);
            return fallback;
        }
    }

    private static long longProperty(String key, long fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            log.warn("Ignoring -D{}={}: not a whole number", key, value);
            return fallback;
        }
    }

    // ---- persistence hooks (BotYieldStore) ----

    /** Adds a stored day's take to a bot's in-memory count (the store's one load at boot). */
    static void restore(int charId, LocalDate day, long mesos) {
        Daily daily = dailies.computeIfAbsent(charId, id -> new Daily(id, day));
        synchronized (daily) {
            daily.rollTo(day);
            daily.mesos += mesos;
        }
    }

    /** The bots whose count changed since the last write: (char id, day, mesos), marked clean. */
    static List<long[]> drainDirty() {
        List<long[]> out = new ArrayList<>();
        for (Daily daily : dailies.values()) {
            synchronized (daily) {
                if (daily.dirty) {
                    daily.dirty = false;
                    out.add(new long[]{daily.charId, daily.day.toEpochDay(), daily.mesos});
                }
            }
        }
        return out;
    }

    /** Marks counts dirty again after a write that failed, unless a newer change already did. */
    static void markDirty(List<long[]> rows) {
        for (long[] row : rows) {
            Daily daily = dailies.get((int) row[0]);
            if (daily != null) {
                synchronized (daily) {
                    daily.dirty = true;
                }
            }
        }
    }

    /** Writes the changed daily counts (the hourly autosave and the shutdown sweep call this). */
    public static void flush() {
        BotYieldStore.flush();
    }

    // ---- GM read-out ----

    public static List<String> report(int top) {
        Limits limits = BotYieldCaps.limits;
        List<String> lines = new ArrayList<>();
        if (!limits.enabled()) {
            lines.add("Bot yield caps are OFF (-Dsolomapling.botYieldCaps=false).");
            return lines;
        }
        lines.add(String.format("Bot yield caps: map share %.2f of yield per %d min; daily meso cap max(%,d, lv x %,d)"
                        + " x meso rate; store %s.", limits.mapShare(), limits.windowMs() / 60_000,
                limits.dailyMesoFloor(), limits.dailyMesoPerLevel(), BotYieldStore.status()));
        List<MapMeter> busiest;
        synchronized (meters) {
            busiest = new ArrayList<>(meters.values());
        }
        long now = clock.millis();
        busiest.removeIf(m -> m.perMinute == null || m.perMinute.isEmpty());
        busiest.sort(Comparator.comparingLong((MapMeter m) -> m.mesosTaken).reversed());
        lines.add("Maps (bots' take since boot / room left now):");
        for (MapMeter m : busiest.subList(0, Math.min(top, busiest.size()))) {
            synchronized (m) {
                m.drain(now, limits);
                lines.add(String.format("  map %d: %,d mesos, %,d items taken; room %,d mesos / %,d items; %d refused",
                        m.mapId, m.mesosTaken, m.itemsTaken, (long) Math.max(0, m.mesoCap(limits) - m.mesosUsed),
                        (long) Math.max(0, m.itemCap(limits) - m.itemsUsed), m.refused));
            }
        }
        List<Daily> earners = new ArrayList<>(dailies.values());
        LocalDate today = today();
        earners.removeIf(d -> !today.equals(d.day));
        earners.sort(Comparator.comparingLong((Daily d) -> d.mesos).reversed());
        lines.add("Bots (mesos taken today):");
        for (Daily d : earners.subList(0, Math.min(top, earners.size()))) {
            lines.add(String.format("  %s (%d): %,d mesos, %d refused", d.name == null ? "?" : d.name, d.charId,
                    d.mesos, d.refused));
        }
        lines.addAll(BotSellerCaps.report(top));   // ticket 56A
        return lines;
    }

    /** One bot's line: its take today against its cap. */
    public static String report(Character bot) {
        Daily daily = dailies.get(bot.getId());
        long cap = limits.dailyMesoCap(bot.getLevel(), mesoRateOf(bot));
        long taken = 0;
        long refused = 0;
        if (daily != null) {
            synchronized (daily) {
                daily.rollTo(today());
                taken = daily.mesos;
                refused = daily.refused;
            }
        }
        return String.format("%s (lv %d): %,d of %,d mesos taken today, %d takes refused", bot.getName(),
                bot.getLevel(), taken, cap, refused);
    }

    // ---- test seams ----

    static void setForTest(Limits newLimits, YieldSource source, Clock newClock) {
        limits = newLimits == null ? Limits.fromSystemProperties() : newLimits;
        yieldSource = source == null ? BotYieldCaps::liveYield : source;
        clock = newClock == null ? Clock.systemDefaultZone() : newClock;
    }

    static void clearForTest() {
        meters.clear();
        dailies.clear();
        BotYieldStore.resetForTest();
    }

    static long dailyMesosForTest(int charId) {
        Daily daily = dailies.get(charId);
        return daily == null ? 0 : daily.mesos;
    }
}
