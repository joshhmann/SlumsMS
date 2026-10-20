package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Movement that learns from its own failures (bot-persistence ticket 16, SlumsMS ticket 04). Every time a bot gives
 * up on a planned move - parked against a jump or drop window it never gets into, stuck until the unstuck hop, or
 * launched and came down in a region the move does not lead to - that edge of the map's navigation graph gets a
 * failure; every time a bot completes a move it gets a success. The planner multiplies an edge's cost by
 * {@link #factor}: an edge that keeps failing becomes up to 3x as expensive, one that nearly always fails 8x, so
 * routes bend around a bad jump without any edge ever being forbidden (a map never becomes unreachable because of
 * what was learned). Counts fade with a 12-hour half-life, so a fixed map or a bot with better stats gets another
 * chance.
 *
 * <p>Every edge type of graph v68 is learned: walks (fork walks), jumps, drops, rope moves (the Y launch window is
 * part of the key), teleports and flash jumps. Only genuine failures count ({@link Cause}): a bot that could not use
 * a job-gated skill edge, and a move a bot fumbled on purpose (ticket 33, {@link #tagDeliberateFlaw}), never raise
 * an edge's cost.
 *
 * <p>Learned counts are kept per navigation graph (map, movement profile) under {@code <cache>/learned/}, stamped
 * with the graph version. When the graph version changes, each learned edge moves to the new graph's edge with the
 * same type, launch window and landing ({@link #migrate}); only edges the new graph no longer has are dropped.
 */
public final class NavEdgeLearning {

    private static final Logger log = LoggerFactory.getLogger(NavEdgeLearning.class);

    static final Path DIR = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "learned");
    static final long HALF_LIFE_MS = TimeUnit.HOURS.toMillis(12);
    static final double MAX_FACTOR = 3.0;
    static final double BAD_FACTOR = 8.0;
    /** File format: 1 = region/window keys without the landing (v68 before ticket 04); 2 = {@link #edgeKey}. */
    static final int FORMAT = 2;

    /** Why a move failed. Only {@link #GENUINE} failures teach the graph anything. */
    enum Cause {
        /** The move really did not work. */
        GENUINE,
        /** The bot fumbled it on purpose (ticket 33's human flaws): the move itself is fine. */
        DELIBERATE_FLAW,
        /** A teleport / flash-jump edge the bot's job (or the map) does not allow: says nothing about the edge. */
        SKILL_UNAVAILABLE
    }

    /** One edge's fading record. */
    static final class Stats {
        double failures;
        double successes;
        long at;

        void fade(long now) {
            if (at > 0 && now > at) {
                double keep = Math.pow(0.5, (now - at) / (double) HALF_LIFE_MS);
                failures *= keep;
                successes *= keep;
            }
            at = now;
        }
    }

    /** Everything learned on one graph. */
    static final class Book {
        final int version;
        final int epoch;
        final Map<String, Stats> edges = new ConcurrentHashMap<>();
        /** The planner asks per edge object on every relaxation: resolve each edge's key once. */
        final Map<BotNavigationGraph.Edge, Stats> byEdge = new ConcurrentHashMap<>();
        volatile boolean dirty;

        Book(int version) {
            this.version = version;
            this.epoch = NavEdgeLearning.epoch;
        }
    }

    private static final Map<String, Book> books = new ConcurrentHashMap<>();
    /** Marks an edge that has no record, so the planner does not look it up again. */
    private static final Stats NONE = new Stats();
    private static final Map<Cause, AtomicLong> ignored = new EnumMap<>(Cause.class);
    private static volatile Path dir = DIR;
    /** Bumped by the test seam, so a book cached on a graph before a reset is not used after it. */
    private static volatile int epoch;

    static {
        for (Cause cause : Cause.values()) {
            ignored.put(cause, new AtomicLong());
        }
    }

    private NavEdgeLearning() {
    }

    // ── what bots report ────────────────────────────────────────────────────────────────────────

    /** A move failed, with no bot to ask why: a genuine failure. */
    static void failure(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        record(graph, edge, true, System.currentTimeMillis());
    }

    /** A bot completed this edge. */
    static void success(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        record(graph, edge, false, System.currentTimeMillis());
    }

    /** A bot gave up on this edge: counted only when the failure is genuine ({@link #classify}). */
    static void failure(BotMovementState entry, BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        if (edge == null) {
            return;
        }
        Cause cause = classify(entry, edge);
        if (entry != null && entry.navFlawEdge == edge) {
            entry.navFlawEdge = null; // the flaw played out
        }
        if (cause == Cause.GENUINE) {
            failure(graph, edge);
        } else {
            ignored.get(cause).incrementAndGet();
        }
    }

    /** A bot completed this edge (a fumble that came off anyway is still a make). */
    static void success(BotMovementState entry, BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        if (entry != null && entry.navFlawEdge == edge) {
            entry.navFlawEdge = null;
        }
        success(graph, edge);
    }

    /**
     * Ticket 33's hook: the bot is about to fumble this move on purpose (a mistimed jump, a rope slip, an overshoot).
     * Its failure - whichever way it shows up: given up, stuck, or landed in the wrong place - is not counted. The
     * tag is spent by the edge's next outcome.
     */
    static void tagDeliberateFlaw(BotMovementState entry, BotNavigationGraph.Edge edge) {
        if (entry != null) {
            entry.navFlawEdge = edge;
        }
    }

    /** Ticket 33's hook for a flaw reported after the fact: a failure that learning ignores (only counted for GMs). */
    static void deliberateFailure(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        ignored.get(Cause.DELIBERATE_FLAW).incrementAndGet();
    }

    /** Why this bot's failure on this edge happened. */
    static Cause classify(BotMovementState entry, BotNavigationGraph.Edge edge) {
        if (entry == null) {
            return Cause.GENUINE;
        }
        if (entry.navFlawEdge != null && entry.navFlawEdge == edge) {
            return Cause.DELIBERATE_FLAW;
        }
        if (edge.isMovementSkill() && !NavMovementSkills.allows(edge, NavMovementSkills.heldMask(entry.bot))) {
            return Cause.SKILL_UNAVAILABLE;
        }
        return Cause.GENUINE;
    }

    /**
     * Called by the navigator each tick with the region the bot is in. Counts the make when the bot reached the
     * committed edge's region (once per edge), and follows a launched move through the air: if it comes down in a
     * region the move neither starts nor ends in, that is a miss - unless a hit knocked the bot off course.
     */
    static void observe(BotMovementState entry, BotNavigationGraph graph, int regionId) {
        BotNavigationGraph.Edge edge = entry.navEdge;
        if (edge != null && edge != entry.navLearnedEdge
                && regionId == edge.toRegionId && regionId != edge.fromRegionId) {
            success(entry, graph, edge);
            entry.navLearnedEdge = edge;
        }
        if (entry.navAirborneEdge != null && entry.mobHitCooldownMs > entry.navAirborneHitMark) {
            entry.navAirborneHit = true; // the i-frame countdown only goes up when a hit lands
        }
        entry.navAirborneHitMark = entry.mobHitCooldownMs;
        if (entry.inAir) {
            // A different launched move while still airborne: the bot landed and chained straight into the next move
            // within one tick, so the last one's landing went unseen - follow the new one instead.
            if (edge != null && edge != entry.navAirborneEdge && isLaunched(edge)) {
                entry.navAirborneEdge = edge;
                entry.navAirborneGraph = graph;
                entry.navAirborneHit = false;
            }
            return;
        }
        BotNavigationGraph.Edge flown = entry.navAirborneEdge;
        if (flown == null) {
            return;
        }
        boolean hit = entry.navAirborneHit;
        BotNavigationGraph flownGraph = entry.navAirborneGraph;
        entry.navAirborneEdge = null;
        entry.navAirborneGraph = null;
        entry.navAirborneHit = false;
        if (regionId < 0 || flownGraph != graph || flown == entry.navLearnedEdge) {
            return;
        }
        if (regionId == flown.toRegionId) {
            success(entry, graph, flown);
            entry.navLearnedEdge = flown;
        } else if (regionId != flown.fromRegionId && !hit) {
            failure(entry, graph, flown);
            entry.navLearnedEdge = flown;
        }
    }

    /** A move that leaves the ground, so where it comes down says whether it worked. */
    private static boolean isLaunched(BotNavigationGraph.Edge edge) {
        return switch (edge.type) {
            case JUMP, FLASH_JUMP, DROP -> true;
            case CLIMB -> edge.launchStepX != 0 || edge.hasLaunchYWindow();
            default -> false;
        };
    }

    /** A teleport's outcome is known the moment it lands: count it right there. */
    static void teleported(BotMovementState entry, BotNavigationGraph graph, BotNavigationGraph.Edge edge,
                           int landedRegionId) {
        if (landedRegionId == edge.toRegionId) {
            success(entry, graph, edge);
        } else if (landedRegionId != edge.fromRegionId) {
            failure(entry, graph, edge);
        }
        entry.navLearnedEdge = edge;
    }

    private static volatile boolean flusherStarted;

    static void record(BotNavigationGraph graph, BotNavigationGraph.Edge edge, boolean failed, long now) {
        if (graph == null || edge == null || edge.type == BotNavigationGraph.EdgeType.PORTAL) {
            return; // a portal does not fail by technique
        }
        startFlusher();
        Book book = bookOf(graph);
        BotNavigationGraph.Edge canonical = canonical(graph, edge);
        Stats stats = book.edges.computeIfAbsent(edgeKey(canonical), k -> new Stats());
        book.byEdge.put(canonical, stats);
        synchronized (stats) {
            stats.fade(now);
            if (failed) {
                stats.failures += 1;
            } else {
                stats.successes += 1;
            }
        }
        book.dirty = true;
    }

    /**
     * The graph's own edge for one the navigator built on the fly: a plan that starts with zero-length walks is
     * committed as one merged edge (BotNavigationManager.collapseLeadingWalkEdges) with the first walk's source
     * region but the real move's geometry. The planner asks about the real move, so that is what learns.
     */
    static BotNavigationGraph.Edge canonical(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        for (BotNavigationGraph.Edge own : NavObservedEdges.outgoing(graph, edge.fromRegionId)) {
            if (own == edge) {
                return edge;
            }
        }
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge own : NavObservedEdges.outgoing(graph, region.id)) {
                if (sameMove(own, edge)) {
                    return own;
                }
            }
        }
        return edge;
    }

    private static boolean sameMove(BotNavigationGraph.Edge a, BotNavigationGraph.Edge b) {
        return a.toRegionId == b.toRegionId && a.type == b.type && a.launchMinX == b.launchMinX
                && a.launchMaxX == b.launchMaxX && a.launchMinY == b.launchMinY && a.launchMaxY == b.launchMaxY
                && a.launchStepX == b.launchStepX && a.startPoint.equals(b.startPoint) && a.endPoint.equals(b.endPoint);
    }

    /** How much more this edge costs in the planner than it was built to: 1.0 for anything never seen failing. */
    static double factor(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        if (graph == null || edge == null) {
            return 1.0;
        }
        Book book = bookOf(graph);
        if (book.edges.isEmpty()) {
            return 1.0; // nearly every graph: nothing learned, nothing to look up
        }
        Stats stats = book.byEdge.get(edge);
        if (stats == null) {
            Book resolved = book;
            stats = book.byEdge.computeIfAbsent(edge, e -> resolved.edges.getOrDefault(edgeKey(e), NONE));
        }
        if (stats == NONE) {
            return 1.0;
        }
        double f;
        double s;
        synchronized (stats) {
            stats.fade(System.currentTimeMillis());
            f = stats.failures;
            s = stats.successes;
        }
        return factorFor(f, s);
    }

    static double factorFor(double failures, double successes) {
        double seen = failures + successes;
        if (failures < 0.5 || seen <= 0) {
            return 1.0;
        }
        double failRate = failures / seen;
        if (failures >= 5 && failRate >= 0.8) {
            return BAD_FACTOR;
        }
        double confidence = Math.min(1.0, seen / 4.0);
        return 1.0 + (MAX_FACTOR - 1.0) * failRate * confidence;
    }

    /** The planner's cost for an edge: its built cost, scaled by what was learned. */
    static int scale(BotNavigationGraph graph, BotNavigationGraph.Edge edge, int cost) {
        if (cost <= 0) {
            return cost;
        }
        double factor = factor(graph, edge);
        return factor == 1.0 ? cost : (int) Math.min(Integer.MAX_VALUE / 4, Math.round(cost * factor));
    }

    /**
     * An edge's identity inside its graph: its type, the regions it joins, its X and Y launch windows (a rope exit's
     * climb heights, graph v62+), launch step, and where it starts and lands. The landing tells a left from an up
     * teleport out of one window apart; together they let a later graph version find the same move
     * ({@link #migrate}).
     */
    static String edgeKey(BotNavigationGraph.Edge edge) {
        return edge.type + ":" + edge.fromRegionId + ">" + edge.toRegionId
                + ":x" + edge.launchMinX + ".." + edge.launchMaxX + ":y" + edge.launchMinY + ".." + edge.launchMaxY
                + ":s" + edge.launchStepX
                + ":" + edge.startPoint.x + "," + edge.startPoint.y + ">" + edge.endPoint.x + "," + edge.endPoint.y;
    }

    /** The key format-1 files used (graph v68 before ticket 04), to read them on the graph they were written for. */
    static String legacyKey(BotNavigationGraph.Edge edge) {
        String key = edge.fromRegionId + ">" + edge.toRegionId + ":" + edge.type + ":" + edge.launchMinX + ":" + edge.launchMaxX
                + ":" + edge.startPoint.x + "," + edge.startPoint.y;
        return edge.hasLaunchYWindow()
                ? key + ":y" + edge.launchMinY + ".." + edge.launchMaxY + ":s" + edge.launchStepX
                : key;
    }

    static String bookKey(BotNavigationGraph graph) {
        BotMovementProfile p = graph.movementProfile;
        return graph.mapId + "-s" + p.totalSpeedStat() + "-j" + p.totalJumpStat() + (p.snowShoes() ? "-ss" : "");
    }

    /**
     * The graph's book, cached on the graph itself: the planner asks on every edge relaxation, and a shared map
     * behind one lock (as this used to be) serialised every planning thread.
     */
    private static Book bookOf(BotNavigationGraph graph) {
        Book book = graph.learningBook;
        if (book != null && book.epoch == epoch) {
            return book;
        }
        book = book(graph);
        graph.learningBook = book;
        return book;
    }

    private static Book book(BotNavigationGraph graph) {
        return books.compute(bookKey(graph), (key, existing) -> {
            if (existing != null && existing.version == graph.version) {
                return existing;
            }
            Book loaded = load(key, graph);
            return loaded != null ? loaded : new Book(graph.version);
        });
    }

    // ── a new graph version ─────────────────────────────────────────────────────────────────────

    /** A learned edge as its key describes it. */
    record Move(BotNavigationGraph.EdgeType type, int minX, int maxX, int minY, int maxY, int stepX,
                Point start, Point end) {
    }

    /** Reads a format-2 key; null for anything else. */
    static Move parseKey(String key) {
        try {
            String[] p = key.split(":");
            if (p.length != 6 || !p[2].startsWith("x") || !p[3].startsWith("y") || !p[4].startsWith("s")) {
                return null;
            }
            int[] x = range(p[2].substring(1));
            int[] y = range(p[3].substring(1));
            String[] ends = p[5].split(">");
            return new Move(BotNavigationGraph.EdgeType.valueOf(p[0]), x[0], x[1], y[0], y[1],
                    Integer.parseInt(p[4].substring(1)), point(ends[0]), point(ends[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] range(String s) {
        int dots = s.indexOf("..", 1);
        return new int[]{Integer.parseInt(s.substring(0, dots)), Integer.parseInt(s.substring(dots + 2))};
    }

    private static Point point(String s) {
        int comma = s.indexOf(',', 1);
        return new Point(Integer.parseInt(s.substring(0, comma)), Integer.parseInt(s.substring(comma + 1)));
    }

    /** How far apart the takeoffs and landings of one move may be in a rebuilt graph. */
    static final int MIGRATE_LAUNCH_PX = 12;
    static final int MIGRATE_LAND_X_PX = 40;
    static final int MIGRATE_LAND_Y_PX = 16;

    /**
     * Carries what was learned on an older graph version over to this graph. Region ids may be renumbered, so moves
     * are matched by what they are: the same type and launch direction, overlapping launch windows from the same
     * height (or the same rope), and a landing on the same spot. A learned move with no match is dropped; two that
     * land on one new edge are added up.
     */
    static Map<String, Stats> migrate(Map<String, Stats> old, BotNavigationGraph graph) {
        List<BotNavigationGraph.Edge> candidates = new ArrayList<>();
        for (BotNavigationGraph.Region region : graph.regions) {
            candidates.addAll(NavObservedEdges.outgoing(graph, region.id));
        }
        Map<String, Stats> moved = new HashMap<>();
        for (Map.Entry<String, Stats> e : old.entrySet()) {
            Move move = parseKey(e.getKey());
            BotNavigationGraph.Edge match = move == null ? null : bestMatch(move, candidates);
            if (match == null) {
                continue;
            }
            moved.merge(edgeKey(match), e.getValue(), (a, b) -> {
                Stats sum = new Stats();
                long now = Math.max(a.at, b.at);
                a.fade(now);
                b.fade(now);
                sum.failures = a.failures + b.failures;
                sum.successes = a.successes + b.successes;
                sum.at = now;
                return sum;
            });
        }
        return moved;
    }

    static BotNavigationGraph.Edge bestMatch(Move move, List<BotNavigationGraph.Edge> candidates) {
        BotNavigationGraph.Edge best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BotNavigationGraph.Edge edge : candidates) {
            if (edge.type != move.type() || Integer.signum(edge.launchStepX) != Integer.signum(move.stepX())) {
                continue;
            }
            boolean ropeLaunch = move.maxY() > move.minY() || edge.hasLaunchYWindow();
            boolean launches = ropeLaunch
                    ? Math.abs(edge.startPoint.x - move.start().x) <= MIGRATE_LAUNCH_PX / 3
                            && edge.launchMinY <= move.maxY() + MIGRATE_LAUNCH_PX
                            && edge.launchMaxY >= move.minY() - MIGRATE_LAUNCH_PX
                    : edge.launchMinX <= move.maxX() + MIGRATE_LAUNCH_PX
                            && edge.launchMaxX >= move.minX() - MIGRATE_LAUNCH_PX
                            && Math.abs(edge.startPoint.y - move.start().y) <= MIGRATE_LAUNCH_PX;
            int landX = Math.abs(edge.endPoint.x - move.end().x);
            int landY = Math.abs(edge.endPoint.y - move.end().y);
            if (!launches || landX > MIGRATE_LAND_X_PX || landY > MIGRATE_LAND_Y_PX) {
                continue;
            }
            int distance = Math.abs(edge.startPoint.x - move.start().x) + Math.abs(edge.startPoint.y - move.start().y)
                    + landX + landY;
            if (distance < bestDistance) {
                best = edge;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    /** Writes what was learned every five minutes, and once more when bot activity stops at shutdown. */
    private static void startFlusher() {
        if (flusherStarted) {
            return;
        }
        synchronized (NavEdgeLearning.class) {
            if (flusherStarted) {
                return;
            }
            flusherStarted = true;
            var future = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                    .scheduleAtFixedRate(NavEdgeLearning::flush, 5, 5, TimeUnit.MINUTES);
            soloMapling.server.BotShutdown.onQuiesce(() -> {
                future.cancel(false);
                flush();
            });
        }
    }

    /** Writes every book that changed since the last flush. Called periodically and at shutdown. */
    public static int flush() {
        int written = 0;
        for (Map.Entry<String, Book> entry : books.entrySet()) {
            Book book = entry.getValue();
            if (!book.dirty) {
                continue;
            }
            book.dirty = false;
            Map<String, Object> edges = new LinkedHashMap<>();
            long now = System.currentTimeMillis();
            book.edges.forEach((key, stats) -> {
                synchronized (stats) {
                    stats.fade(now);
                    if (stats.failures + stats.successes >= 0.05) {
                        edges.put(key, List.of(round(stats.failures), round(stats.successes), stats.at));
                    }
                }
            });
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("version", book.version);
            json.put("format", FORMAT);
            json.put("edges", edges);
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(entry.getKey() + ".json"), Json.write(json), StandardCharsets.UTF_8);
                written++;
            } catch (IOException e) {
                book.dirty = true;
                log.debug("Could not write learned nav data {}", entry.getKey(), e);
            }
        }
        return written;
    }

    private static Book load(String key, BotNavigationGraph graph) {
        Path file = dir.resolve(key + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)
                    || !(root.get("version") instanceof Number v)
                    || !(root.get("edges") instanceof Map<?, ?> edges)) {
                return null;
            }
            int format = root.get("format") instanceof Number f ? f.intValue() : 1;
            Map<String, Stats> read = new HashMap<>();
            for (Map.Entry<?, ?> e : edges.entrySet()) {
                if (e.getValue() instanceof List<?> l && l.size() == 3) {
                    Stats stats = new Stats();
                    stats.failures = ((Number) l.get(0)).doubleValue();
                    stats.successes = ((Number) l.get(1)).doubleValue();
                    stats.at = ((Number) l.get(2)).longValue();
                    read.put(String.valueOf(e.getKey()), stats);
                }
            }
            Book book = new Book(graph.version);
            if (format < FORMAT) {
                if (v.intValue() != graph.version) {
                    return null; // an old key without its landing cannot be found in a rebuilt graph
                }
                read = fromLegacyKeys(read, graph);
                book.dirty = true;
            } else if (v.intValue() != graph.version) {
                int had = read.size();
                read = migrate(read, graph);
                book.dirty = true;
                log.info("Nav learning for {} carried from graph v{} to v{}: {} of {} edges", key, v.intValue(),
                        graph.version, read.size(), had);
            }
            book.edges.putAll(read);
            return book;
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable learned nav data {}", file, e);
            return null;
        }
    }

    /** Format-1 keys on the graph they were written for: re-key each to the edge it names. */
    private static Map<String, Stats> fromLegacyKeys(Map<String, Stats> read, BotNavigationGraph graph) {
        Map<String, Stats> rekeyed = new HashMap<>();
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : NavObservedEdges.outgoing(graph, region.id)) {
                Stats stats = read.get(legacyKey(edge));
                if (stats != null) {
                    rekeyed.putIfAbsent(edgeKey(edge), stats);
                }
            }
        }
        return rekeyed;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** For the GM read-out: graphs with learned data, the edges on them currently penalised, and what was not counted. */
    public static String describe() {
        int penalised = 0;
        for (Book book : books.values()) {
            for (Stats stats : book.edges.values()) {
                if (factorFor(stats.failures, stats.successes) > 1.0) {
                    penalised++;
                }
            }
        }
        return "nav learning: " + books.size() + " graphs, " + penalised + " edges penalised; not counted: "
                + ignored.get(Cause.DELIBERATE_FLAW).get() + " deliberate flaws, "
                + ignored.get(Cause.SKILL_UNAVAILABLE).get() + " skill moves the bot could not use";
    }

    /** Test seam: a scratch directory (null restores the cache dir) and a clean slate. */
    static void useDirForTest(Path testDir) {
        dir = testDir == null ? DIR : testDir;
        books.clear();
        epoch++;
        ignored.values().forEach(c -> c.set(0));
    }

    static long ignoredForTest(Cause cause) {
        return ignored.get(cause).get();
    }

    static Set<String> loadedBooks() {
        return books.keySet();
    }
}
