package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.DebugTools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ticket 157, kill calibration (report-only; the idea of nutnnut/Cosmic's {@code BotKillCalibration}, dev 77e0c5bc9,
 * no code copied): while a grinding bot is on full simulation (a watched or pinned map) its real kills are counted
 * against the time they took, and set beside what {@link OffscreenCombat} would have modelled for the same bot on the
 * same map over the same time. Kept per (job, level band of 10) as faded sums, so the ratio reads "real kills / modelled
 * kills" for that kind of bot lately. Exported as the dashboard's {@code calibration.json} and shown by
 * {@code !killcalib}. Nothing tunes itself from it.
 *
 * <p>How a kill is billed (the upstream lesson): each kill counts against exactly the time since the bot's previous
 * kill on that map, so an AoE swing that kills three adds three kills and almost no time, and the rate is
 * {@code kills / time} (a sustained rate), never an average of per-kill rates (which would report a peak). A gap over
 * {@link #MAX_GAP_MS}, a map change or a level change starts a new stretch without billing the idle time. A bucket
 * reports a ratio only after {@link #MIN_ACTIVE_MS} of billed time.
 *
 * <p>Cost: one small anchor per bot that kills on full simulation (dropped with the bot's combat state), one bucket
 * per (job, band), a lock per bucket for a few adds. No allocation per kill once a bot's anchor exists; the modelled
 * rate is re-read from {@link OffscreenCombat#rate} (itself cached) at most once a minute per bot or on a map or level
 * change. Off on live ({@code -Dslums.killcalib}, following {@link DebugTools}).
 */
public final class KillCalibration {

    public static final String PROPERTY = "slums.killcalib";
    /** A longer gap between two kills is travel or a break, not grinding: start a new stretch. */
    static final long MAX_GAP_MS = 90_000;
    /** Billed time a bucket needs before its ratio means anything. */
    static final long MIN_ACTIVE_MS = 60_000;
    /** Per-sample fade of a bucket's sums (half-life about 140 kills), so old spots and old builds fade out. */
    static final double DECAY = 0.995;
    /** How often a bot's modelled rate is re-read while it stays on one map and level. */
    static final long MODEL_REFRESH_MS = 60_000;
    static final int BAND = 10;

    private static volatile Boolean enabled;
    private static final KillCalibration INSTANCE = new KillCalibration();

    /** One bot's current stretch. Touched only from the kill path (one kill at a time per bot in practice). */
    static final class Anchor {
        long lastKillAt;
        int mapId = -1;
        int level = -1;
        double modelKpm;
        long modelAt;
    }

    /** One (job, band): faded sums plus raw totals. Guarded by itself. */
    static final class Bucket {
        final int jobId;
        final int band;
        double kills;
        double activeMs;
        double modelKills;
        long samples;
        long totalKills;
        long totalActiveMs;
        long unmodelled;
        long lastAt;

        Bucket(int jobId, int band) {
            this.jobId = jobId;
            this.band = band;
        }
    }

    /** One bucket as read for the report. {@code ratio} is null until the bucket has {@link #MIN_ACTIVE_MS}. */
    public record Row(int jobId, String job, int band, long samples, long totalKills, long totalActiveMs,
                      double realKpm, double modelKpm, Double ratio, long unmodelled, long lastAt) {
        public String bandLabel() {
            return band * BAND + "-" + (band * BAND + BAND - 1);
        }
    }

    private final Map<Integer, Anchor> anchors = new ConcurrentHashMap<>();
    private final Map<Integer, Bucket> buckets = new ConcurrentHashMap<>();
    final LongAdder seen = new LongAdder();
    final LongAdder billed = new LongAdder();
    final LongAdder skippedNotGrinding = new LongAdder();
    final LongAdder skippedParty = new LongAdder();
    final LongAdder anchored = new LongAdder();

    KillCalibration() {
    }

    // ── switch ──────────────────────────────────────────────────────────────────────────────────

    /** Whether calibration runs on this server: on where the debug tools are (TEST), off on live, -D override. */
    public static boolean enabled() {
        Boolean on = enabled;
        if (on == null) {
            on = DebugTools.featureOn(PROPERTY);
            enabled = on;
        }
        return on;
    }

    /** Tests: read the switch again on next use. */
    public static void resetSwitch() {
        enabled = null;
    }

    static KillCalibration instance() {
        return INSTANCE;
    }

    // ── game hooks ──────────────────────────────────────────────────────────────────────────────

    /**
     * A real kill credited to {@code killer} (MapleMap's kill path). Counts only a grinding, solo bot on a full
     * simulation map; everything else returns at once. Never throws.
     */
    public static void onRealKill(Character killer) {
        if (killer == null || !enabled() || !BotHelpers.isBot(killer)) {
            return;
        }
        try {
            INSTANCE.realKill(killer, System.currentTimeMillis());
        } catch (RuntimeException e) {
            // report-only: a failed sample never reaches the kill path
        }
    }

    /** The bot left (combat state cleared): drop its stretch. */
    public static void forget(int botId) {
        INSTANCE.anchors.remove(botId);
    }

    private void realKill(Character bot, long now) {
        int mapId = bot.getMapId();
        if (!GCMovement.isMapObserved(bot)) {
            return;   // offscreen: no real kills there anyway
        }
        seen.increment();
        if (!GCMovement.isGrinding(bot)) {
            skippedNotGrinding.increment();
            return;
        }
        if (bot.getParty() != null) {
            skippedParty.increment();   // the model is a solo grind; party kills are shared
            return;
        }
        int level = bot.getLevel();
        Anchor a = anchors.computeIfAbsent(bot.getId(), k -> new Anchor());
        double model = a.modelKpm;
        if (a.mapId != mapId || a.level != level || now - a.modelAt >= MODEL_REFRESH_MS) {
            OffscreenCombat.Rate rate = OffscreenCombat.rate(bot, mapId);
            model = rate == null ? 0 : rate.killsPerMinute();
            synchronized (a) {
                a.modelAt = now;
            }
        }
        record(bot.getId(), bot.getJob() == null ? 0 : bot.getJob().getId(), level, mapId, now, model);
    }

    /**
     * One kill of a bot (the single path for the game and the tests): bills it to the time since the bot's previous
     * kill on this map and level, against {@code modelKpm} (OffscreenCombat's kills a minute for this bot and map;
     * 0 when it has none). Returns whether the kill was billed (the first kill of a stretch only anchors it).
     */
    boolean record(int botId, int jobId, int level, int mapId, long now, double modelKpm) {
        Anchor a = anchors.computeIfAbsent(botId, k -> new Anchor());
        boolean sameStretch;
        synchronized (a) {
            sameStretch = a.mapId == mapId && a.level == level && a.lastKillAt > 0 && now >= a.lastKillAt
                    && now - a.lastKillAt <= MAX_GAP_MS;
            long gap = now - a.lastKillAt;
            a.lastKillAt = now;
            a.mapId = mapId;
            a.level = level;
            a.modelKpm = modelKpm;
            if (!sameStretch) {
                anchored.increment();
                return false;
            }
            bill(jobId, level, gap, modelKpm, now);
        }
        billed.increment();
        return true;
    }

    private void bill(int jobId, int level, long gapMs, double modelKpm, long now) {
        int band = Math.max(0, level) / BAND;
        Bucket b = buckets.computeIfAbsent(jobId * 1000 + band, k -> new Bucket(jobId, band));
        synchronized (b) {
            b.kills = b.kills * DECAY + 1;
            b.activeMs = b.activeMs * DECAY + gapMs;
            if (modelKpm > 0) {
                b.modelKills = b.modelKills * DECAY + modelKpm * gapMs / 60_000.0;
            } else {
                b.modelKills = b.modelKills * DECAY;
                b.unmodelled++;
            }
            b.samples++;
            b.totalKills++;
            b.totalActiveMs += gapMs;
            b.lastAt = now;
        }
    }

    // ── report ──────────────────────────────────────────────────────────────────────────────────

    /** Every bucket, most samples first. */
    List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (Bucket b : buckets.values()) {
            synchronized (b) {
                double real = b.activeMs > 0 ? b.kills * 60_000.0 / b.activeMs : 0;
                double model = b.activeMs > 0 ? b.modelKills * 60_000.0 / b.activeMs : 0;
                Double ratio = b.totalActiveMs >= MIN_ACTIVE_MS && b.modelKills > 0 ? b.kills / b.modelKills : null;
                out.add(new Row(b.jobId, jobName(b.jobId), b.band, b.samples, b.totalKills, b.totalActiveMs,
                        real, model, ratio, b.unmodelled, b.lastAt));
            }
        }
        out.sort(Comparator.comparingLong(Row::samples).reversed().thenComparingInt(Row::jobId)
                .thenComparingInt(Row::band));
        return out;
    }

    /** The {@code calibration.json} document. */
    Map<String, Object> json(long now) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("at", now);
        doc.put("enabled", enabled());
        doc.put("reportOnly", true);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("bandSize", BAND);
        rules.put("maxGapMs", MAX_GAP_MS);
        rules.put("minActiveMs", MIN_ACTIVE_MS);
        rules.put("decayPerKill", DECAY);
        doc.put("rules", rules);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("killsSeen", seen.sum());
        totals.put("billed", billed.sum());
        totals.put("stretchStarts", anchored.sum());
        totals.put("skippedNotGrinding", skippedNotGrinding.sum());
        totals.put("skippedParty", skippedParty.sum());
        totals.put("bots", anchors.size());
        doc.put("totals", totals);
        List<Object> rows = new ArrayList<>();
        for (Row r : rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", r.jobId());
            m.put("job", r.job());
            m.put("band", r.bandLabel());
            m.put("samples", r.samples());
            m.put("kills", r.totalKills());
            m.put("activeMinutes", round(r.totalActiveMs() / 60_000.0));
            m.put("realKpm", round(r.realKpm()));
            m.put("modelKpm", round(r.modelKpm()));
            m.put("ratio", r.ratio() == null ? null : round(r.ratio()));
            m.put("unmodelled", r.unmodelled());
            m.put("lastAt", r.lastAt());
            rows.add(m);
        }
        doc.put("buckets", rows);
        return doc;
    }

    /** The {@code !killcalib} lines: a header and up to {@code max} buckets. */
    List<String> summary(int max) {
        List<String> out = new ArrayList<>();
        List<Row> rows = rows();
        out.add(String.format(Locale.ROOT, "Kill calibration (report only, %s): %d kills seen on full simulation, %d billed,"
                        + " %d not grinding, %d in a party; %d buckets", enabled() ? "on" : "off", seen.sum(),
                billed.sum(), skippedNotGrinding.sum(), skippedParty.sum(), rows.size()));
        for (Row r : rows.subList(0, Math.min(max, rows.size()))) {
            out.add(String.format(Locale.ROOT, "%s %s: real %.1f/min vs model %.1f/min, %s; %d kills over %.0f min",
                    r.job(), r.bandLabel(), r.realKpm(), r.modelKpm(),
                    r.ratio() == null ? "too little data" : String.format(Locale.ROOT, "x%.2f", r.ratio()),
                    r.totalKills(), r.totalActiveMs() / 60_000.0));
        }
        if (rows.isEmpty()) {
            out.add("No samples yet: watch or pin (!env lod pin) a map where solo bots grind.");
        }
        return out;
    }

    public static Map<String, Object> snapshotJson(long now) {
        return INSTANCE.json(now);
    }

    public static List<String> summaryLines(int max) {
        return INSTANCE.summary(max);
    }

    private static String jobName(int jobId) {
        try {
            Job job = Job.getById(jobId);
            return job == null ? String.valueOf(jobId) : job.name();
        } catch (RuntimeException e) {
            return String.valueOf(jobId);
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
