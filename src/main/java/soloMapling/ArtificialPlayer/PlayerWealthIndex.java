package soloMapling.ArtificialPlayer;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingConstants;
import tools.DatabaseConnection;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.6.0: the player wealth index. Bots should "ride" with real players' wealth (owner, 2026-10-25): at 10x meso a
 * farming player holds 30x what a bot's ceiling allows, so the bot ceiling ({@link BotWealth}) is scaled by how rich
 * real players are against that same ceiling formula.
 * <p>
 * <b>Index</b> = a quantile (default the median) of {@code held / baseCeiling(level)} over real, non-GM human
 * characters of level 10+ (ids under the bot base). Online characters are read from memory, the rest from one cheap
 * aggregate query. Fewer than {@code minSample} (5) characters feeds 1.0. The index is smoothed with an EMA in log
 * space (time constant {@code tauDays}, 3), and the <b>multiplier</b> is that, clamped to [1, 10], and moved toward
 * it at most x1.5 (or /1.5) per day, so one rich player cannot swing it. It starts at 1.0 and is saved across
 * restarts. The multiplier only raises how much a bot may <i>keep</i>; it creates no mesos (bots still gain only
 * from the existing sources, which the ceiling merely stops cutting off sooner).
 * <p>
 * Driven by {@link BotPopulationDirector}'s tick via {@link #maybeUpdate()} (every {@code intervalMinutes}, 60),
 * the work runs on a virtual thread. Knobs, all {@code -Dsolomapling.*}: {@code wealthIndex=false} (off, mult 1),
 * {@code wealthIndexMin} (1.0, never under 1), {@code wealthIndexMax} (10), {@code wealthIndexMinSample} (5),
 * {@code wealthIndexMinLevel} (10), {@code wealthIndexQuantile} (0.5), {@code wealthIndexTauDays} (3),
 * {@code wealthIndexDailyStep} (1.5), {@code wealthIndexIntervalMinutes} (60), {@code wealthIndexFile}.
 */
public final class PlayerWealthIndex {

    private static final Logger log = LoggerFactory.getLogger(PlayerWealthIndex.class);

    /** One human character's holdings. */
    public record Sample(int level, long held) {
    }

    private static final Object lock = new Object();
    private static volatile double multiplier = 1.0;
    private static double emaLog = 0.0;          // ln of the smoothed index
    private static double lastRaw = Double.NaN;  // last raw index (NaN = none yet / too few players)
    private static int lastSample = 0;
    private static long lastUpdateMs = 0;
    private static volatile long nextRunAt = 0;
    private static boolean loaded = false;
    private static final AtomicBoolean running = new AtomicBoolean();

    private PlayerWealthIndex() {
    }

    // ---- the number the rest of the server reads ----

    /** The ceiling multiplier, in [1, max]. 1.0 when the index is off or has no data. */
    public static double multiplier() {
        return enabled() ? multiplier : 1.0;
    }

    public static double smoothedIndex() {
        synchronized (lock) {
            return Math.exp(emaLog);
        }
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.wealthIndex", "true"));
    }

    /** For the dashboard / economy stats: everything about the index as plain values. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (lock) {
            m.put("enabled", enabled());
            m.put("multiplier", round(multiplier()));
            m.put("smoothedIndex", round(Math.exp(emaLog)));
            m.put("rawIndex", Double.isNaN(lastRaw) ? null : round(lastRaw));
            m.put("sample", lastSample);
            m.put("updatedAt", lastUpdateMs);
        }
        return m;
    }

    public static String line() {
        synchronized (lock) {
            return String.format("player wealth index %s: raw %s (%d players), smoothed %.2f, bot ceiling x%.2f"
                            + " (bounds %.1f-%.1f)", enabled() ? "on" : "OFF",
                    Double.isNaN(lastRaw) ? "n/a" : String.format("%.2f", lastRaw), lastSample, Math.exp(emaLog),
                    multiplier(), minMult(), maxMult());
        }
    }

    // ---- the maths (pure, tested) ----

    /** What a level holds under the formula with no multiplier: the yardstick players are measured against. */
    static long yardstick(int level, int mesoRate) {
        return BotWealth.baseCeiling(level, mesoRate);
    }

    /**
     * The raw index of these characters, or NaN when too few qualify. Characters under {@code minLevel} are ignored.
     */
    static double rawIndex(Collection<Sample> samples, int mesoRate, int minLevel, int minSample, double quantile) {
        double[] ratios = samples.stream().filter(s -> s.level() >= minLevel && s.held() >= 0)
                .mapToDouble(s -> (double) s.held() / Math.max(1, yardstick(s.level(), mesoRate))).sorted().toArray();
        if (ratios.length == 0 || ratios.length < Math.max(1, minSample)) {
            return Double.NaN;
        }
        return quantile(ratios, quantile);
    }

    /** The q-quantile (0.5 = median) of a sorted array, interpolating between neighbours. */
    static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double pos = Math.max(0, Math.min(1, q)) * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    /** One EMA step in log space after {@code dtMs}; {@code tauDays} is the time constant. */
    static double emaStep(double prevLog, double sampleIndex, long dtMs, double tauDays) {
        double target = Math.log(Math.max(1e-6, sampleIndex));
        double alpha = tauDays <= 0 ? 1.0 : 1.0 - Math.exp(-(dtMs / 86_400_000.0) / tauDays);
        return prevLog + alpha * (target - prevLog);
    }

    /** The multiplier after {@code dtMs}: toward {@code wanted} (clamped to [min, max]) by at most dailyStep per day. */
    static double nextMultiplier(double current, double wanted, long dtMs, double dailyStep, double min, double max) {
        double lo = Math.max(1.0, min);
        double hi = Math.max(lo, max);
        double goal = Math.max(lo, Math.min(hi, wanted));
        double step = Math.pow(Math.max(1.0, dailyStep), Math.max(0, dtMs) / 86_400_000.0);
        double next = Math.max(current / step, Math.min(current * step, goal));
        return Math.max(lo, Math.min(hi, next));
    }

    /** Feeds one batch of samples at {@code now}; returns the new multiplier. */
    static double apply(Collection<Sample> samples, int mesoRate, long now) {
        synchronized (lock) {
            double raw = rawIndex(samples, mesoRate, intProp("wealthIndexMinLevel", 10),
                    intProp("wealthIndexMinSample", 5), dblProp("wealthIndexQuantile", 0.5));
            lastSample = (int) samples.stream().filter(s -> s.level() >= intProp("wealthIndexMinLevel", 10)).count();
            lastRaw = raw;
            long dt = lastUpdateMs == 0 ? 0 : Math.max(0, now - lastUpdateMs);
            double fed = Double.isNaN(raw) ? 1.0 : raw;    // too few players: no evidence of wealth, drift to 1
            emaLog = emaStep(emaLog, fed, dt, dblProp("wealthIndexTauDays", 3.0));
            multiplier = nextMultiplier(multiplier, Math.exp(emaLog), dt, dblProp("wealthIndexDailyStep", 1.5),
                    minMult(), maxMult());
            lastUpdateMs = now;
            return multiplier;
        }
    }

    static double minMult() {
        return Math.max(1.0, dblProp("wealthIndexMin", 1.0));
    }

    static double maxMult() {
        return Math.max(minMult(), dblProp("wealthIndexMax", 10.0));
    }

    // ---- the periodic update ----

    /** Cheap: returns at once unless the interval has passed; the work runs on a virtual thread. */
    public static void maybeUpdate() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRunAt || !running.compareAndSet(false, true)) {
            return;
        }
        nextRunAt = now + Math.max(1, intProp("wealthIndexIntervalMinutes", 60)) * 60_000L;
        ExecutorServiceManager.runAsync(() -> {
            try {
                updateNow();
            } catch (RuntimeException | LinkageError e) {
                log.warn("[Wealth] index update failed; the multiplier is unchanged", e);
            } finally {
                running.set(false);
            }
        });
    }

    /** Reads the humans and updates. A DB failure leaves the multiplier alone. */
    static void updateNow() {
        int rate = BotWealth.worldMesoRate();
        if (rate <= 0) {
            return;
        }
        loadOnce();
        int minLevel = intProp("wealthIndexMinLevel", 10);
        Map<Integer, Sample> byId = new LinkedHashMap<>();
        try {
            byId.putAll(offlineSamples(minLevel));
        } catch (SQLException | RuntimeException e) {
            log.warn("[Wealth] could not read offline players' mesos; skipping this update", e);
            return;
        }
        byId.putAll(onlineSamples());   // memory is fresher than the saved row
        double before = multiplier;
        double mult = apply(byId.values(), rate, System.currentTimeMillis());
        save();
        log.info("[Wealth] index={} smoothed={} players={} mult={} (was {})", Double.isNaN(lastRaw) ? "n/a"
                : String.format("%.2f", lastRaw), String.format("%.2f", smoothedIndex()), lastSample,
                String.format("%.3f", mult), String.format("%.3f", before));
    }

    static Map<Integer, Sample> offlineSamples(int minLevel) throws SQLException {
        Map<Integer, Sample> out = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, level, meso FROM characters WHERE id < ? AND gm = 0 AND level >= ?"
                             + " AND name NOT LIKE 'autoqa%'")) { // 1.6.0: no pretend players or observers
            ps.setInt(1, SoloMaplingConstants.GameConstants.BOT_BASE_ID);
            ps.setInt(2, minLevel);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), new Sample(rs.getInt(2), rs.getLong(3)));
                }
            }
        }
        return out;
    }

    static Map<Integer, Sample> onlineSamples() {
        List<Character> all = new ArrayList<>();
        try {
            for (var w : net.server.Server.getInstance().getWorlds()) {
                if (w != null) {
                    all.addAll(w.getPlayerStorage().getAllCharacters());
                }
            }
        } catch (RuntimeException e) {
            log.debug("No online players to read", e);
        }
        return humans(all);
    }

    /**
     * The real, non-GM characters among these, by id. Bots, GMs and bot-range ids never count; 1.6.0 (owner
     * 2026-10-26): nor do auto-QA's pretend players and the social trace's observers (as on the site's boards).
     */
    static Map<Integer, Sample> humans(Collection<Character> chars) {
        Map<Integer, Sample> out = new LinkedHashMap<>();
        for (Character c : chars) {
            if (c == null || BotHelpers.isBot(c) || c.gmLevel() > 0
                    || c.getId() >= SoloMaplingConstants.GameConstants.BOT_BASE_ID
                    || c.getClient() instanceof client.SyntheticClient || soloMapling.site.SiteFeed.isQaName(c.getName())) {
                continue;
            }
            out.put(c.getId(), new Sample(c.getLevel(), c.getMeso()));
        }
        return out;
    }

    // ---- persistence (so a restart does not send the multiplier back to 1 and make it climb again) ----

    private static Path file() {
        return Path.of(System.getProperty("solomapling.wealthIndexFile", "reports/bot-wealth-index.properties"));
    }

    private static void loadOnce() {
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loaded = true;
            Path f = file();
            if (!Files.isRegularFile(f)) {
                return;
            }
            try (Reader r = Files.newBufferedReader(f)) {
                Properties p = new Properties();
                p.load(r);
                emaLog = Double.parseDouble(p.getProperty("emaLog", "0"));
                multiplier = Math.max(minMult(), Math.min(maxMult(), Double.parseDouble(p.getProperty("multiplier", "1"))));
                lastUpdateMs = Long.parseLong(p.getProperty("updatedAt", "0"));
            } catch (IOException | RuntimeException e) {
                log.warn("[Wealth] could not read {}; starting at 1.0", f, e);
                emaLog = 0;
                multiplier = 1.0;
                lastUpdateMs = 0;
            }
        }
    }

    private static void save() {
        Path f = file();
        Properties p = new Properties();
        synchronized (lock) {
            p.setProperty("emaLog", Double.toString(emaLog));
            p.setProperty("multiplier", Double.toString(multiplier));
            p.setProperty("updatedAt", Long.toString(lastUpdateMs));
        }
        try {
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            try (Writer w = Files.newBufferedWriter(f)) {
                p.store(w, "player wealth index state");
            }
        } catch (IOException e) {
            log.warn("[Wealth] could not save {}", f, e);
        }
    }

    // ---- helpers ----

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static int intProp(String key, int fallback) {
        return Integer.getInteger("solomapling." + key, fallback);
    }

    private static double dblProp(String key, double fallback) {
        String v = System.getProperty("solomapling." + key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Test seam: back to a fresh state (multiplier 1, nothing loaded, nothing saved). */
    static void resetForTest() {
        synchronized (lock) {
            multiplier = 1.0;
            emaLog = 0.0;
            lastRaw = Double.NaN;
            lastSample = 0;
            lastUpdateMs = 0;
            loaded = true;   // tests never read or write the state file
        }
        nextRunAt = 0;
        running.set(false);
    }
}
