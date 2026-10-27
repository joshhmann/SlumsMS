package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.server.History;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Where bots got stuck (ticket 04c). The movement code already notices a stuck bot - the unstuck hop, giving up on
 * a launch window it never gets into, a hung rope climb, the frozen-in-the-air and fell-off-the-map recoveries, a
 * planner with no route or a bot standing off the graph - and each of those now also counts here: per UTC day,
 * map, kind, 50px spot and the edge the bot was on. Counting is one map update per event, and events are seconds
 * apart per bot. The counts go to {@code <cache>/stuck/<day>.json} every five minutes and at shutdown;
 * {@link NavStuckReport} turns a day into the hotspot report, and the nightly bake bakes the worst maps first.
 */
public final class NavStuckLog {

    private static final Logger log = LoggerFactory.getLogger(NavStuckLog.class);

    /** What the bot ran into. */
    public enum Kind {
        /** Stood in one spot with a move to make until the unstuck hop fired. */
        UNSTUCK,
        /** Gave up on an edge whose launch window it never got into. */
        GAVE_UP,
        /** Hung on a rope without climbing until the grind brain kicked it off. */
        ROPE_HUNG,
        /** Frozen in the air until the watchdog put it on the ground. */
        AIR_FROZEN,
        /** Left the map's bounds and was put back. */
        FELL_OFF_MAP,
        /** Stood where the graph has no region. */
        NO_REGION,
        /** The planner found no route to the target's region. */
        NO_PATH,
        /** Swam against something without getting closer to the next swim waypoint (ticket 34). */
        SWIM_STUCK
    }

    static final int SPOT_PX = 50;
    static final int MAX_SPOTS_PER_DAY = 20_000;
    /** A bot's NO_PATH / NO_REGION is counted at most this often (the planner asks every AI tick). */
    static final long PLAN_FAILURE_EVERY_MS = 30_000;

    /** One hotspot's counts. */
    static final class Spot {
        final int mapId;
        final Kind kind;
        final int x;
        final int y;
        final String edge;
        final String graph;
        int count;
        long first;
        long last;
        int sampleX;
        int sampleY;

        Spot(int mapId, Kind kind, int x, int y, String edge, String graph) {
            this.mapId = mapId;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.edge = edge;
            this.graph = graph;
        }
    }

    private static final class Day {
        final LocalDate date;
        final Map<String, Spot> spots = new ConcurrentHashMap<>();
        volatile boolean dirty;

        Day(LocalDate date) {
            this.date = date;
        }
    }

    private static volatile Path dir = defaultDir();
    private static volatile Clock clock = Clock.systemUTC();
    private static volatile Day today;
    private static volatile boolean flusherStarted;

    private NavStuckLog() {
    }

    static Path defaultDir() {
        return Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "stuck");
    }

    // ── recording (bot tick threads) ────────────────────────────────────────────────────────────

    /** A grind brain's view: a bot and where it stands, no nav edge. */
    public static void record(Kind kind, Character bot) {
        if (bot != null) {
            record(kind, bot.getMapId(), bot.getPosition(), null, "");
        }
    }

    static void record(Kind kind, MapleMap map, Point pos, BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        if (map != null) {
            record(kind, map.getId(), pos, graph, edge);
        }
    }

    static void record(Kind kind, int mapId, Point pos, BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        record(kind, mapId, pos, graph, describe(edge));
    }

    static void record(Kind kind, int mapId, Point pos, BotNavigationGraph graph, String edgeName) {
        if (pos == null || mapId <= 0) {
            return;
        }
        try {
            startFlusher();
            long now = clock.millis();
            Day day = day(now);
            int bx = Math.floorDiv(pos.x, SPOT_PX) * SPOT_PX + SPOT_PX / 2;
            int by = Math.floorDiv(pos.y, SPOT_PX) * SPOT_PX + SPOT_PX / 2;
            String graphKey = graph != null ? NavEdgeLearning.bookKey(graph) : "";
            String key = mapId + "|" + kind + "|" + bx + "|" + by + "|" + edgeName;
            Spot spot = day.spots.get(key);
            if (spot == null) {
                if (day.spots.size() >= MAX_SPOTS_PER_DAY) {
                    return; // a runaway day: the hotspots are already in
                }
                spot = day.spots.computeIfAbsent(key, k -> new Spot(mapId, kind, bx, by, edgeName, graphKey));
            }
            synchronized (spot) {
                if (spot.count++ == 0) {
                    spot.first = now;
                }
                spot.last = now;
                spot.sampleX = pos.x;
                spot.sampleY = pos.y;
            }
            day.dirty = true;
        } catch (RuntimeException e) {
            log.debug("Could not count a stuck bot on map {}", mapId, e); // never let book-keeping hurt a bot
        }
    }

    /** An edge as the report names it: type, regions and launch window. */
    static String describe(BotNavigationGraph.Edge edge) {
        if (edge == null) {
            return "";
        }
        String s = edge.type + " r" + edge.fromRegionId + ">r" + edge.toRegionId + " x" + edge.launchMinX
                + (edge.launchMaxX != edge.launchMinX ? ".." + edge.launchMaxX : "");
        if (edge.hasLaunchYWindow()) {
            s += " y" + edge.launchMinY + ".." + edge.launchMaxY;
        }
        return edge.launchStepX != 0 ? s + (edge.launchStepX > 0 ? " right" : " left") : s;
    }

    private static Day day(long now) {
        LocalDate date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC);
        Day current = today;
        if (current != null && current.date.equals(date)) {
            return current;
        }
        synchronized (NavStuckLog.class) {
            current = today;
            if (current != null && current.date.equals(date)) {
                return current;
            }
            if (current != null) {
                write(current); // the day turned: its file is final
            }
            Day fresh = new Day(date);
            load(fresh); // a restart the same day keeps counting on
            today = fresh;
            return fresh;
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    private static void startFlusher() {
        if (flusherStarted) {
            return;
        }
        synchronized (NavStuckLog.class) {
            if (flusherStarted) {
                return;
            }
            flusherStarted = true;
            try {
                var future = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                        .scheduleAtFixedRate(NavStuckLog::flush, 5, 5, TimeUnit.MINUTES);
                soloMapling.server.BotShutdown.onQuiesce(() -> {
                    future.cancel(false);
                    flush();
                });
            } catch (RuntimeException e) {
                log.debug("No flusher for the stuck log", e);
            }
        }
    }

    /** Writes today's counts if they changed. Called every five minutes and at shutdown. */
    public static void flush() {
        Day day = today;
        if (day != null && day.dirty) {
            write(day);
        }
    }

    private static void write(Day day) {
        day.dirty = false;
        List<Object> spots = new ArrayList<>();
        for (Spot s : day.spots.values()) {
            synchronized (s) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("map", s.mapId);
                o.put("kind", s.kind.name());
                o.put("x", s.x);
                o.put("y", s.y);
                o.put("edge", s.edge);
                o.put("graph", s.graph);
                o.put("count", s.count);
                o.put("first", s.first);
                o.put("last", s.last);
                o.put("sampleX", s.sampleX);
                o.put("sampleY", s.sampleY);
                spots.add(o);
            }
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("day", day.date.toString());
        json.put("spots", spots);
        try {
            Files.createDirectories(dir);
            Path file = fileFor(dir, day.date);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(json), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            day.dirty = true;
            log.debug("Could not write the stuck log of {}", day.date, e);
        }
    }

    private static void load(Day day) {
        // a new day: gzip the stuck logs older than a month (history is kept; slums.history.stuckKeepDays deletes)
        History.sweep(dir, DAY_FILE, History.gzipDays("stuck"), History.keepDays("stuck"));
        for (Spot s : read(fileFor(dir, day.date))) {
            day.spots.put(s.mapId + "|" + s.kind + "|" + s.x + "|" + s.y + "|" + s.edge, s);
        }
    }

    private static final java.util.regex.Pattern DAY_FILE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.json");

    static Path fileFor(Path dir, LocalDate date) {
        return dir.resolve(date + ".json");
    }

    /** A day file's spots; empty when the file is missing or unreadable. */
    static List<Spot> read(Path file) {
        List<Spot> spots = new ArrayList<>();
        if (!History.exists(file)) {
            return spots;
        }
        try {
            if (Json.parse(History.readString(file)) instanceof Map<?, ?> root
                    && root.get("spots") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Kind kind;
                        try {
                            kind = Kind.valueOf(String.valueOf(m.get("kind")));
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        Spot s = new Spot(num(m, "map"), kind, num(m, "x"), num(m, "y"),
                                String.valueOf(m.get("edge") == null ? "" : m.get("edge")),
                                String.valueOf(m.get("graph") == null ? "" : m.get("graph")));
                        s.count = num(m, "count");
                        s.first = m.get("first") instanceof Number n ? n.longValue() : 0;
                        s.last = m.get("last") instanceof Number n ? n.longValue() : 0;
                        s.sampleX = num(m, "sampleX");
                        s.sampleY = num(m, "sampleY");
                        spots.add(s);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable stuck log {}", file, e);
        }
        return spots;
    }

    private static int num(Map<?, ?> m, String key) {
        return m.get(key) instanceof Number n ? n.intValue() : 0;
    }

    /** Stuck events per map over the last {@code days} UTC days, today included (plan failures left out). */
    static Map<Integer, Integer> countsByMap(Path dir, int days) {
        Map<Integer, Integer> counts = new HashMap<>();
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        for (int i = 0; i < days; i++) {
            for (Spot s : read(fileFor(dir, date.minusDays(i)))) {
                if (s.kind != Kind.NO_PATH) {
                    counts.merge(s.mapId, s.count, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** For the GM read-out: today's count and the worst spot. */
    public static String describe() {
        Day day = today;
        if (day == null || day.spots.isEmpty()) {
            return "stuck today: none";
        }
        int total = 0;
        Spot worst = null;
        for (Spot s : day.spots.values()) {
            total += s.count;
            if (s.kind != Kind.NO_PATH && (worst == null || s.count > worst.count)) {
                worst = s;
            }
        }
        return "stuck today: " + total + " events at " + day.spots.size() + " spots"
                + (worst == null ? "" : "; worst " + worst.kind + " map " + worst.mapId + " (" + worst.x + ","
                + worst.y + ") x" + worst.count + (worst.edge.isEmpty() ? "" : " on " + worst.edge));
    }

    /** For {@code !gcmove stuck}: today's worst spots, one line each (route failures left out). */
    public static List<String> topToday(int n) {
        List<String> lines = new ArrayList<>();
        for (Spot s : todaySpots()) {
            if (s.kind == Kind.NO_PATH) {
                continue;
            }
            if (lines.size() >= n) {
                break;
            }
            lines.add(s.count + "x " + s.kind + " map " + s.mapId + " (" + s.x + "," + s.y + ")"
                    + (s.edge.isEmpty() ? "" : " " + s.edge));
        }
        if (lines.isEmpty()) {
            lines.add("stuck today: none");
        }
        return lines;
    }

    /** Spots of today, most counted first (tests and the GM read-out). */
    static List<Spot> todaySpots() {
        Day day = today;
        List<Spot> spots = day == null ? new ArrayList<>() : new ArrayList<>(day.spots.values());
        spots.sort(Comparator.comparingInt((Spot s) -> -s.count));
        return spots;
    }

    /** Test seam: a scratch directory and clock (nulls restore the real ones), and a clean slate. */
    static void useForTest(Path testDir, Clock testClock) {
        dir = testDir == null ? defaultDir() : testDir;
        clock = testClock == null ? Clock.systemUTC() : testClock;
        today = null;
        flusherStarted = true; // tests flush by hand
    }
}
