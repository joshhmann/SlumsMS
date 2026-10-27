package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.History;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * What the bots make of real players' movement (ticket 04 follow-up): counts every move {@link NavObservedEdges}
 * captured by its {@link NavObservedEdges.Outcome outcome}, per map and per UTC day, logs an hourly INFO summary
 * when anything changed, and keeps the data under {@code cache/bot-nav/players/} so it survives restarts and can be
 * replayed offline ({@link NavPlayerReplay}):
 * <ul>
 *   <li>{@code moves-<day>.jsonl}: one line per captured move - time, map, kind, both ends (foothold, x, y), the
 *       outcome and graph version. Real players only and nothing that names them (no character id, no name).
 *       Capped at {@link #MAP_DAY_CAP} lines per map per day and {@link #DAY_FILE_CAP_BYTES} per day. Nothing is
 *       deleted (history is kept): days older than {@link #KEEP_DAYS} and, while the folder is over
 *       {@link #TOTAL_CAP_BYTES}, the oldest days are gzipped to {@code .jsonl.gz}; {@code -Dslums.history.navKeepDays=N}
 *       deletes after N days.</li>
 *   <li>{@code sightings.json}: the new moves still being counted towards adoption (at most {@link #SIGHTINGS_CAP},
 *       unseen for {@link #SIGHTING_TTL_DAYS} days dropped).</li>
 *   <li>{@code stats.json}: the counters (totals since the first start, every day, the busiest
 *       maps), read by the dashboard export.</li>
 *   <li>{@code portals.json}: which portal real players take from one map to another, counted.</li>
 * </ul>
 * Everything is counted under one lock off the packet thread (portal use is a map merge on it) and written by the
 * scheduler every {@link #FLUSH_MINUTES} minutes and at shutdown.
 */
public final class NavPlayerLearning {

    private static final Logger log = LoggerFactory.getLogger(NavPlayerLearning.class);

    static final Path DIR = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "players");
    /** Days a moves file stays plain text before it is gzipped (-Dslums.history.navGzipDays, default 30). */
    static final int KEEP_DAYS = History.gzipDays("nav");
    static final long DAY_FILE_CAP_BYTES = 4L << 20;
    static final long TOTAL_CAP_BYTES = 64L << 20;
    static final int MAP_DAY_CAP = 2_000;
    static final int PENDING_CAP = 20_000;
    static final int SIGHTINGS_CAP = 20_000;
    static final int SIGHTING_TTL_DAYS = 90;
    static final int TOP_MAPS = 50;
    static final int PORTAL_ROUTES_CAP = Integer.getInteger("bot.nav.portalRoutesCap", 50_000);
    static final long FLUSH_MINUTES = 5;
    static final long SUMMARY_MINUTES = 60;

    private static final NavObservedEdges.Outcome[] OUTCOMES = NavObservedEdges.Outcome.values();
    private static final Object LOCK = new Object();

    // guarded by LOCK
    private static long[] totals = new long[OUTCOMES.length];
    private static long fhMismatch;
    private static long dropped;
    private static long since;
    private static final Map<Integer, long[]> byMap = new HashMap<>();
    private static final Map<String, long[]> byDay = new TreeMap<>();
    private static final Map<String, Integer> linesByMapDay = new HashMap<>();
    private static final Map<String, Map<String, Integer>> portals = new TreeMap<>();
    private static boolean loaded;
    private static boolean statsDirty;
    private static boolean portalsDirty;
    private static boolean sightingsDirty;
    private static long[] lastSummary = new long[OUTCOMES.length];
    private static long lastSummaryMismatch;

    private static final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pendingSize = new AtomicInteger();
    private static volatile boolean sightingsLoaded;
    private static volatile boolean started;
    /** Off in tests: no scheduler. */
    static volatile boolean autoFlush = true;
    private static volatile Path dir = DIR;

    private NavPlayerLearning() {
    }

    // ── recording ───────────────────────────────────────────────────────────────────────────────

    /** One captured move and what became of it. {@code ends} and {@code graphVersion} when a graph was there. */
    static void record(NavObservedEdges.Transition t, NavObservedEdges.Outcome outcome, NavObservedEdges.Ends ends,
                       int graphVersion) {
        start();
        long now = System.currentTimeMillis();
        String day = day(now);
        synchronized (LOCK) {
            ensureLoaded();
            int o = outcome.ordinal();
            totals[o]++;
            byMap.computeIfAbsent(t.mapId(), k -> new long[OUTCOMES.length])[o]++;
            byDay.computeIfAbsent(day, k -> new long[OUTCOMES.length])[o]++;
            if (ends != null && ends.fhMismatch()) {
                fhMismatch++;
            }
            statsDirty = true;
            if (outcome == NavObservedEdges.Outcome.SIGHTED || outcome == NavObservedEdges.Outcome.PROVE_FAILED
                    || outcome == NavObservedEdges.Outcome.ADOPTED) {
                sightingsDirty = true;
            }
            if (linesByMapDay.merge(day + ":" + t.mapId(), 1, Integer::sum) > MAP_DAY_CAP) {
                dropped++;
                return;
            }
        }
        if (pendingSize.incrementAndGet() > PENDING_CAP) {
            pendingSize.decrementAndGet();
            synchronized (LOCK) {
                dropped++;
            }
            return;
        }
        pending.add(line(t, outcome, ends, graphVersion, now));
    }

    /** One stored move: {"t","m","k","a":[fh,x,y],"b":[fh,x,y],"o","g","r":[from,to]}. */
    static String line(NavObservedEdges.Transition t, NavObservedEdges.Outcome outcome, NavObservedEdges.Ends ends,
                       int graphVersion, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", now);
        m.put("m", t.mapId());
        m.put("k", t.kind().name());
        m.put("a", List.of(t.fromFh(), t.takeoff().x, t.takeoff().y));
        m.put("b", List.of(t.toFh(), t.landing().x, t.landing().y));
        m.put("o", outcome.name());
        if (graphVersion >= 0) {
            m.put("g", graphVersion);
        }
        if (ends != null) {
            m.put("r", List.of(ends.from(), ends.to()));
        }
        return Json.write(m);
    }

    /** A stored move read back, or null when the line is not one. */
    static NavObservedEdges.Transition parseLine(String line) {
        try {
            if (Json.parse(line) instanceof Map<?, ?> m && m.get("m") instanceof Number map && m.get("k") instanceof String k
                    && m.get("a") instanceof List<?> a && m.get("b") instanceof List<?> b && a.size() == 3 && b.size() == 3) {
                return new NavObservedEdges.Transition(map.intValue(), NavObservedEdges.Kind.valueOf(k),
                        num(a, 0), new java.awt.Point(num(a, 1), num(a, 2)), num(b, 0), new java.awt.Point(num(b, 1), num(b, 2)));
            }
        } catch (RuntimeException e) {
            // not a move
        }
        return null;
    }

    private static int num(List<?> l, int i) {
        return ((Number) l.get(i)).intValue();
    }

    /**
     * A real player went through a portal from one map to another (unscripted or scripted). Counted per map pair and
     * portal name, so the world graph can learn which way players actually go.
     */
    public static void onPortal(Character player, int fromMapId, String portalName, int toMapId) {
        if (player == null || fromMapId == toMapId || fromMapId <= 0 || toMapId <= 0) {
            return;
        }
        start();
        String route = fromMapId + ">" + toMapId;
        synchronized (LOCK) {
            ensureLoaded();
            Map<String, Integer> byPortal = portals.get(route);
            if (byPortal == null) {
                if (portals.size() >= PORTAL_ROUTES_CAP) {
                    return;
                }
                byPortal = new TreeMap<>();
                portals.put(route, byPortal);
            }
            byPortal.merge(portalName == null || portalName.isEmpty() ? "?" : portalName, 1, Integer::sum);
            portalsDirty = true;
        }
    }

    /** How often real players took each portal from one map to the other (empty when never). */
    public static Map<String, Integer> portalChoices(int fromMapId, int toMapId) {
        synchronized (LOCK) {
            ensureLoaded();
            Map<String, Integer> byPortal = portals.get(fromMapId + ">" + toMapId);
            return byPortal == null ? Map.of() : Map.copyOf(byPortal);
        }
    }

    // ── sightings ───────────────────────────────────────────────────────────────────────────────

    /** Loads the stored sightings once (NavObservedEdges calls this before counting one). */
    static void ensureSightingsLoaded() {
        if (sightingsLoaded) {
            return;
        }
        synchronized (NavPlayerLearning.class) {
            if (sightingsLoaded) {
                return;
            }
            sightingsLoaded = true;
            Map<String, long[]> stored = new LinkedHashMap<>();
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SIGHTING_TTL_DAYS);
            if (readJson(dir.resolve("sightings.json")) instanceof Map<?, ?> root && root.get("sightings") instanceof Map<?, ?> all) {
                all.forEach((k, v) -> {
                    if (v instanceof List<?> l && l.size() == 2 && l.get(0) instanceof Number c && l.get(1) instanceof Number at
                            && at.longValue() >= cutoff) {
                        stored.put(String.valueOf(k), new long[]{c.longValue(), at.longValue()});
                    }
                });
            }
            NavObservedEdges.restoreSightings(stored);
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    private static void start() {
        if (started || !autoFlush) {
            return;
        }
        synchronized (NavPlayerLearning.class) {
            if (started) {
                return;
            }
            started = true;
            var scheduler = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService();
            var flusher = scheduler.scheduleAtFixedRate(NavPlayerLearning::flushQuietly, FLUSH_MINUTES, FLUSH_MINUTES,
                    TimeUnit.MINUTES);
            var summary = scheduler.scheduleAtFixedRate(NavPlayerLearning::summarizeQuietly, SUMMARY_MINUTES,
                    SUMMARY_MINUTES, TimeUnit.MINUTES);
            soloMapling.server.BotShutdown.onQuiesce(() -> {
                flusher.cancel(false);
                summary.cancel(false);
                flushQuietly();
            });
        }
    }

    private static void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Could not save players' moves", e);
        }
    }

    private static void summarizeQuietly() {
        try {
            String line = summarize();
            if (line != null) {
                log.info(line);
            }
        } catch (RuntimeException e) {
            log.debug("Players' moves summary failed", e);
        }
    }

    /** Reads stats and portal counts once, so counts carry on across restarts. Under LOCK. */
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        since = System.currentTimeMillis();
        if (readJson(dir.resolve("stats.json")) instanceof Map<?, ?> root) {
            if (root.get("since") instanceof Number s) {
                since = s.longValue();
            }
            if (root.get("fhMismatch") instanceof Number n) {
                fhMismatch += n.longValue();
            }
            if (root.get("dropped") instanceof Number n) {
                dropped += n.longValue();
            }
            addCounts(totals, root.get("totals"));
            if (root.get("days") instanceof Map<?, ?> days) {
                days.forEach((d, c) -> addCounts(byDay.computeIfAbsent(String.valueOf(d), k -> new long[OUTCOMES.length]), c));
            }
            if (root.get("maps") instanceof Map<?, ?> mapCounts) {
                mapCounts.forEach((m, c) -> {
                    try {
                        addCounts(byMap.computeIfAbsent(Integer.parseInt(String.valueOf(m)), k -> new long[OUTCOMES.length]), c);
                    } catch (NumberFormatException e) {
                        // skip
                    }
                });
            }
            lastSummary = totals.clone();
            lastSummaryMismatch = fhMismatch;
        }
        if (readJson(dir.resolve("portals.json")) instanceof Map<?, ?> root && root.get("routes") instanceof Map<?, ?> routes) {
            routes.forEach((route, byPortal) -> {
                if (byPortal instanceof Map<?, ?> m) {
                    Map<String, Integer> into = portals.computeIfAbsent(String.valueOf(route), k -> new TreeMap<>());
                    m.forEach((name, n) -> {
                        if (n instanceof Number count) {
                            into.merge(String.valueOf(name), count.intValue(), Integer::sum);
                        }
                    });
                }
            });
        }
    }

    private static void addCounts(long[] into, Object counts) {
        if (counts instanceof Map<?, ?> m) {
            for (NavObservedEdges.Outcome o : OUTCOMES) {
                if (m.get(o.name()) instanceof Number n) {
                    into[o.ordinal()] += n.longValue();
                }
            }
        }
    }

    private static Map<String, Object> countsJson(long[] counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NavObservedEdges.Outcome o : OUTCOMES) {
            if (counts[o.ordinal()] > 0) {
                out.put(o.name(), counts[o.ordinal()]);
            }
        }
        return out;
    }

    private static long sum(long[] counts) {
        long n = 0;
        for (long c : counts) {
            n += c;
        }
        return n;
    }

    /** Writes the queued moves, the counters, the portal counts and the sightings; prunes old day files. */
    static synchronized void flush() {
        long now = System.currentTimeMillis();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.debug("Could not create {}", dir, e);
            return;
        }
        // 1. moves, by the day they were made
        Map<String, StringBuilder> byFile = new TreeMap<>();
        Map<String, Integer> linesByFile = new HashMap<>();
        String line;
        while ((line = pending.poll()) != null) {
            pendingSize.decrementAndGet();
            String day = dayOfLine(line, now);
            byFile.computeIfAbsent(day, k -> new StringBuilder()).append(line).append('\n');
            linesByFile.merge(day, 1, Integer::sum);
        }
        byFile.forEach((day, text) -> {
            Path file = dir.resolve("moves-" + day + ".jsonl");
            try {
                long size = Files.exists(file) ? Files.size(file) : 0;
                if (size >= DAY_FILE_CAP_BYTES) {
                    synchronized (LOCK) {
                        dropped += linesByFile.get(day);
                    }
                    return;
                }
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.debug("Could not append players' moves to {}", file, e);
            }
        });
        // 2. counters and portals
        Map<String, Object> stats = null;
        Map<String, Object> portalJson = null;
        boolean writeSightings;
        String today = day(now);
        synchronized (LOCK) {
            linesByMapDay.keySet().removeIf(k -> !k.startsWith(today));
            if (statsDirty) {
                statsDirty = false;
                stats = statsJson(now);
            }
            if (portalsDirty) {
                portalsDirty = false;
                Map<String, Object> routes = new TreeMap<>();
                portals.forEach((route, byPortal) -> routes.put(route, new TreeMap<>(byPortal)));
                portalJson = new LinkedHashMap<>();
                portalJson.put("format", 1);
                portalJson.put("updated", now);
                portalJson.put("routes", routes);
            }
            writeSightings = sightingsDirty;
            sightingsDirty = false;
        }
        if (stats != null) {
            writeAtomically(dir.resolve("stats.json"), Json.write(stats));
        }
        if (portalJson != null) {
            writeAtomically(dir.resolve("portals.json"), Json.write(portalJson));
        }
        if (writeSightings) {
            writeAtomically(dir.resolve("sightings.json"), Json.write(sightingsJson(now)));
        }
        prune(now);
    }

    /** Under LOCK. */
    private static Map<String, Object> statsJson(long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", 1);
        out.put("since", since);
        out.put("updated", now);
        out.put("moves", sum(totals));
        out.put("totals", countsJson(totals));
        out.put("fhMismatch", fhMismatch);
        out.put("dropped", dropped);
        Map<String, Object> days = new TreeMap<>();
        byDay.forEach((d, c) -> days.put(d, countsJson(c)));
        out.put("days", days);
        Map<String, Object> mapsOut = new LinkedHashMap<>();
        byMap.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Integer, long[]> e) -> -sum(e.getValue())).thenComparingInt(Map.Entry::getKey))
                .limit(TOP_MAPS)
                .forEach(e -> mapsOut.put(String.valueOf(e.getKey()), countsJson(e.getValue())));
        out.put("maps", mapsOut);
        out.put("sightings", NavObservedEdges.sightingCount());
        return out;
    }

    private static Map<String, Object> sightingsJson(long now) {
        long cutoff = now - TimeUnit.DAYS.toMillis(SIGHTING_TTL_DAYS);
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(NavObservedEdges.sightingsSnapshot().entrySet());
        rows.removeIf(e -> e.getValue()[1] < cutoff);
        rows.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -e.getValue()[1]));
        Map<String, Object> all = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : rows.subList(0, Math.min(rows.size(), SIGHTINGS_CAP))) {
            all.put(e.getKey(), List.of(e.getValue()[0], e.getValue()[1]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", 1);
        out.put("updated", now);
        out.put("sightings", all);
        return out;
    }

    /**
     * History is kept: move files older than KEEP_DAYS are gzipped in place, then the oldest plain ones while the
     * folder's plain files are over TOTAL_CAP_BYTES (never the newest). Deleted only with -Dslums.history.navKeepDays=N.
     */
    private static void prune(long now) {
        LocalDate today = LocalDate.ofEpochDay(Math.floorDiv(now, 86_400_000L));
        History.sweep(dir, MOVES_FILE, today, KEEP_DAYS, History.keepDays("nav"));
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(f -> f.getFileName().toString().matches("moves-\\d{4}-\\d{2}-\\d{2}\\.jsonl")).sorted().toList();
        } catch (IOException e) {
            return;
        }
        long total = 0;
        for (Path f : files) {
            total += size(f);
        }
        for (int i = 0; i < files.size() - 1 && total > TOTAL_CAP_BYTES; i++) { // never the newest
            total -= size(files.get(i));
            try {
                History.gzip(files.get(i));
            } catch (IOException e) {
                log.debug("Could not compress {}", files.get(i), e);
            }
        }
    }

    private static final java.util.regex.Pattern MOVES_FILE = java.util.regex.Pattern.compile("moves-\\d{4}-\\d{2}-\\d{2}\\.jsonl");

    private static long size(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void writeAtomically(Path file, String text) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.debug("Could not write {}", file, e);
        }
    }

    static Object readJson(Path file) {
        try {
            return Files.exists(file) ? Json.parse(Files.readString(file, StandardCharsets.UTF_8)) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String day(long ms) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).toString();
    }

    private static String dayOfLine(String line, long fallback) {
        int at = line.indexOf("\"t\":");
        if (at >= 0) {
            int end = at + 4;
            while (end < line.length() && java.lang.Character.isDigit(line.charAt(end))) {
                end++;
            }
            try {
                return day(Long.parseLong(line.substring(at + 4, end)));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return day(fallback);
    }

    // ── read-outs ───────────────────────────────────────────────────────────────────────────────

    /**
     * The summary of what changed since the last one, or null when nothing did. "known" are moves the graph already
     * makes; "same platform" hops; "unknown platform" an end on no platform of the graph.
     */
    static String summarize() {
        long[] delta;
        long mismatch;
        String since;
        long[] all;
        List<String> top = new ArrayList<>();
        synchronized (LOCK) {
            ensureLoaded();
            delta = new long[OUTCOMES.length];
            for (int i = 0; i < delta.length; i++) {
                delta[i] = totals[i] - lastSummary[i];
            }
            mismatch = fhMismatch - lastSummaryMismatch;
            if (sum(delta) == 0 && mismatch == 0) {
                return null;
            }
            lastSummary = totals.clone();
            lastSummaryMismatch = fhMismatch;
            all = totals.clone();
            since = day(NavPlayerLearning.since);
        }
        return "Players' moves in the last hour: " + counts(delta) + (mismatch > 0 ? "; " + mismatch
                + " with a foothold id the server does not have there (found by position)" : "")
                + ". Since " + since + ": " + sum(all) + " moves, " + all[NavObservedEdges.Outcome.ADOPTED.ordinal()]
                + " learned, " + NavObservedEdges.sightingCount() + " new moves being counted" + topMaps(5);
    }

    private static String counts(long[] c) {
        StringBuilder sb = new StringBuilder().append(sum(c)).append(" (");
        String[][] names = {
                {"ADOPTED", "learned"}, {"PROVE_FAILED", "proof failed"}, {"SIGHTED", "new, counting"},
                {"ALREADY_JOINED", "known"}, {"SAME_REGION", "same platform"}, {"UNKNOWN_REGION", "unknown platform"},
                {"NO_GRAPH", "no graph"}, {"ROPE_SKILL", "skill to/from rope"}, {"NO_MAP", "no map"}};
        boolean first = true;
        for (String[] n : names) {
            long v = c[NavObservedEdges.Outcome.valueOf(n[0]).ordinal()];
            if (v > 0) {
                sb.append(first ? "" : ", ").append(v).append(' ').append(n[1]);
                first = false;
            }
        }
        return sb.append(')').toString();
    }

    private static String topMaps(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            byMap.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<Integer, long[]> e) -> -sum(e.getValue())))
                    .limit(n)
                    .forEach(e -> sb.append(sb.isEmpty() ? "; busiest maps: " : ", ").append(e.getKey()).append(' ')
                            .append(sum(e.getValue())).append(" (").append(e.getValue()[NavObservedEdges.Outcome.ALREADY_JOINED.ordinal()])
                            .append(" known)"));
        }
        return sb.toString();
    }

    /** For the GM read-out. */
    public static String describe() {
        long[] all;
        long mismatch;
        int routes;
        synchronized (LOCK) {
            ensureLoaded();
            all = totals.clone();
            mismatch = fhMismatch;
            routes = portals.size();
        }
        return "players' moves since " + day(since) + ": " + counts(all) + (mismatch > 0 ? ", " + mismatch + " fh id mismatches" : "")
                + "; portal routes seen: " + routes;
    }

    /** Counts by outcome name (for tests and the replay). */
    static Map<String, Long> totals() {
        synchronized (LOCK) {
            Map<String, Long> out = new LinkedHashMap<>();
            for (NavObservedEdges.Outcome o : OUTCOMES) {
                out.put(o.name(), totals[o.ordinal()]);
            }
            return out;
        }
    }

    static Path dir() {
        return dir;
    }

    /** Test seam: a scratch directory (null restores the cache dir), no scheduler, and a clean slate. */
    static void useDirForTest(Path testDir) {
        dir = testDir == null ? DIR : testDir;
        autoFlush = testDir == null;
        synchronized (LOCK) {
            totals = new long[OUTCOMES.length];
            lastSummary = new long[OUTCOMES.length];
            lastSummaryMismatch = 0;
            fhMismatch = 0;
            dropped = 0;
            byMap.clear();
            byDay.clear();
            linesByMapDay.clear();
            portals.clear();
            loaded = false;
            statsDirty = false;
            portalsDirty = false;
            sightingsDirty = false;
        }
        pending.clear();
        pendingSize.set(0);
        sightingsLoaded = false;
    }
}
