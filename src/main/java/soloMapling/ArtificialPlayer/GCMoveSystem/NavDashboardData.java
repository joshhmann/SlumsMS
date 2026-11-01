package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Rope;
import soloMapling.server.Json;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only views of the nav data for the admin dashboard (ticket 35, {@code soloMapling.dashboard}). The graph
 * types are package-private, so this is the one public door: it turns a map's WZ geometry, a cached graph, the
 * deep-bake overlay ({@link NavDeepOverlay}), the jumps learned from players ({@link NavObservedEdges}), what nav
 * learning recorded per edge ({@link NavEdgeLearning}) and the stuck counts ({@link NavStuckLog}) into plain
 * maps and lists ready to write as JSON. It only reads files and never touches the server's live state, so the
 * export can run in its own JVM.
 */
public final class NavDashboardData {

    private static final Pattern KEY = Pattern.compile("(\\d+)-s(\\d+)-j(\\d+)(-ss)?");
    private static final Pattern BIN = Pattern.compile("(\\d+-s\\d+-j\\d+(?:-ss)?)\\.bin");

    /** Where an edge came from. */
    public static final int SOURCE_GRAPH = 0;
    public static final int SOURCE_DEEP = 1;
    public static final int SOURCE_PLAYERS = 2;

    /** The nav data folders. */
    public record Dirs(Path graphs, Path deepRoot, Path observed, Path learned, Path stuck) {

        /** The folders the server and the bake use, from {@code bot.nav.cacheDir}. */
        public static Dirs fromSystemProperties() {
            Path cache = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"));
            // Player-learned jumps and nav learning are written under cache/bot-nav whatever bot.nav.cacheDir says.
            Path observed = Files.isDirectory(cache.resolve("observed")) ? cache.resolve("observed") : NavObservedEdges.DIR;
            Path learned = Files.isDirectory(cache.resolve("learned")) ? cache.resolve("learned") : NavEdgeLearning.DIR;
            return new Dirs(cache.resolve(BotNavigationGraphProvider.cacheFolderName()), NavDeepOverlay.defaultRoot(),
                    observed, learned, NavStuckLog.defaultDir());
        }

        Path deepCurrent() {
            Path link = deepRoot.resolve(NavDeepOverlay.CURRENT);
            return Files.isDirectory(link) ? link : null;
        }
    }

    private NavDashboardData() {
    }

    public static int graphVersion() {
        return BotNavigationGraphProvider.GRAPH_VERSION;
    }

    // ── keys ────────────────────────────────────────────────────────────────────────────────────

    /** Every cached graph key, by map id (keys sorted). */
    public static Map<Integer, List<String>> cachedKeys(Dirs dirs) throws IOException {
        Map<Integer, List<String>> byMap = new TreeMap<>();
        if (!Files.isDirectory(dirs.graphs())) {
            return byMap;
        }
        try (Stream<Path> files = Files.list(dirs.graphs())) {
            files.forEach(f -> {
                Matcher m = BIN.matcher(f.getFileName().toString());
                if (m.matches()) {
                    byMap.computeIfAbsent(Integer.parseInt(m.group(1).split("-")[0]), k -> new ArrayList<>()).add(m.group(1));
                }
            });
        }
        byMap.values().forEach(list -> list.sort(Comparator.naturalOrder()));
        return byMap;
    }

    /** A key's movement stats: speed, jump, snowshoes (1/0); null when it is not a key. */
    public static int[] keyStats(String key) {
        Matcher m = KEY.matcher(key);
        return m.matches() ? new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4) != null ? 1 : 0}
                : null;
    }

    // ── geometry ────────────────────────────────────────────────────────────────────────────────

    /** A map's footholds, ropes, ladders, portals and view bounds from the WZ files (needs {@code wz-path}). */
    public static Map<String, Object> geometry(int mapId) {
        return geometry(BotNavigationMapLoader.loadMapGeometry(mapId));
    }

    static Map<String, Object> geometry(MapleMap map) {
        List<Object> footholds = new ArrayList<>();
        if (map.getFootholds() != null) {
            for (Foothold fh : map.getFootholds().getAllFootholds()) {
                // flags: 1 wall, 2 no fall-through
                int flags = (fh.isWall() ? 1 : 0) | (fh.isForbidFallDown() ? 2 : 0);
                footholds.add(List.of(fh.getId(), fh.getX1(), fh.getY1(), fh.getX2(), fh.getY2(), flags));
            }
        }
        List<Object> ropes = new ArrayList<>();
        for (Rope rope : map.getRopes()) {
            ropes.add(List.of(rope.x(), rope.topY(), rope.bottomY(), rope.isLadder() ? 1 : 0));
        }
        List<Object> portals = new ArrayList<>();
        for (Portal p : map.getPortals()) {
            if (p.getPosition() == null) {
                continue;
            }
            // [name, type, x, y, toMap, toPortal]: ticket 157 appends the target portal's name ("" for none) as p[5];
            // readers of the old five fields keep working
            portals.add(List.of(String.valueOf(p.getName()), p.getType(), p.getPosition().x, p.getPosition().y,
                    p.getTargetMapId(), p.getTarget() == null ? "" : p.getTarget()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Rectangle area = map.getMapArea();
        if (area != null && area.width > 0 && area.width < 100_000) {
            out.put("bounds", List.of(area.x, area.y, area.width, area.height));
        }
        out.put("footholds", footholds);
        out.put("ropes", ropes);
        out.put("portals", portals);
        if (map.isSwim()) {
            out.put("swim", true); // b109: swimmers do not use the ground graph, so the dead-end check skips the map
        }
        return out;
    }

    /**
     * Ticket 157 follow-up / b109 (the Website lane's dead-end check): the regions of an exported graph ({@link #graph}'s
     * {@code regions} rows, {@code [id, rope, minX, minY, maxX, maxY]}, and {@code edges} rows, {@code [from, to, type,
     * ...]}) that {@link NavPits} treats as pits on a map with this {@link #geometry} ({@code bounds}, {@code portals},
     * {@code swim}), sorted: ground under the view bottom (none without bounds), and traps with no way back to an exit
     * (none on a swim map or a map without a portal to another map). Same rule as the planner, on the same rows.
     */
    public static List<Integer> pitRegions(int mapId, Object regions, Object edges, Map<String, Object> geo) {
        if (!(regions instanceof List<?> rows)) {
            return List.of();
        }
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        List<NavPits.Row> parsed = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof List<?> r && r.size() >= 6 && r.get(0) instanceof Number id && r.get(1) instanceof Number rope
                    && r.get(2) instanceof Number minX && r.get(3) instanceof Number minY
                    && r.get(4) instanceof Number maxX && r.get(5) instanceof Number maxY) {
                parsed.add(new NavPits.Row(id.intValue(), rope.intValue() != 0, minX.intValue(), minY.intValue(),
                        maxX.intValue(), maxY.intValue()));
            }
        }
        parsed.sort(Comparator.comparingInt(NavPits.Row::id));
        Object bounds = geo == null ? null : geo.get("bounds");
        if (bounds instanceof List<?> b && b.size() >= 4 && b.get(1) instanceof Number y && b.get(2) instanceof Number w
                && b.get(3) instanceof Number h && w.intValue() > 0 && h.intValue() > 0) {
            int bottom = y.intValue() + h.intValue();
            for (NavPits.Row r : parsed) {
                if (NavPits.isPitRegion(r.rope(), r.id(), r.minY(), bottom)) {
                    out.add(r.id());
                }
            }
        }
        boolean swim = geo != null && geo.get("swim") instanceof Boolean sw && sw;
        if (!swim && geo != null && geo.get("portals") instanceof List<?> ps && edges instanceof List<?> es) {
            Map<Integer, Set<Integer>> fwd = new HashMap<>();
            for (Object e : es) {
                if (e instanceof List<?> l && l.size() >= 3 && l.get(0) instanceof Number from && l.get(1) instanceof Number to
                        && !"TELEPORT".equals(l.get(2)) && !"FLASH_JUMP".equals(l.get(2))) {
                    fwd.computeIfAbsent(from.intValue(), k -> new HashSet<>()).add(to.intValue());
                }
            }
            List<NavPits.PortalRow> portals = new ArrayList<>();
            for (Object p : ps) {
                if (p instanceof List<?> l && l.size() >= 5 && l.get(1) instanceof Number type && l.get(2) instanceof Number x
                        && l.get(3) instanceof Number py && l.get(4) instanceof Number toMap) {
                    portals.add(new NavPits.PortalRow(type.intValue(), x.intValue(), py.intValue(), toMap.intValue()));
                }
            }
            out.addAll(NavPits.traps(parsed, fwd, portals, mapId));
        }
        return List.copyOf(out);
    }

    // ── graphs ──────────────────────────────────────────────────────────────────────────────────

    /** One cached graph with every overlay, or null when the key has no usable cache file. */
    public static Map<String, Object> graph(String key, Dirs dirs, long now) {
        int[] stats = keyStats(key);
        if (stats == null) {
            return null;
        }
        int mapId = Integer.parseInt(key.split("-")[0]);
        BotNavigationGraph graph = load(dirs.graphs().resolve(key + ".bin"),
                new BotNavigationGraphProvider.GraphCacheKey(mapId, stats[0], stats[1], stats[2] == 1));
        return graph == null ? null : graph(graph, dirs, now);
    }

    /** Reads a cache file without registering it with the server's graph tables. */
    private static BotNavigationGraph load(Path file, BotNavigationGraphProvider.GraphCacheKey key) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (var in = new java.io.ObjectInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            return in.readObject() instanceof BotNavigationGraph g && BotNavigationGraphProvider.isGraphFor(g, key) ? g : null;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Regions and edges. An edge is {@code [from, to, type, sx, sy, ex, ey, launchMinX, launchMaxX, cost, source,
     * factor, failures, successes]}: source is {@link #SOURCE_GRAPH}, {@link #SOURCE_DEEP} or {@link #SOURCE_PLAYERS};
     * factor is nav learning's current cost multiplier and failures/successes its faded counts (0 when none).
     */
    static Map<String, Object> graph(BotNavigationGraph graph, Dirs dirs, long now) {
        String key = NavEdgeLearning.bookKey(graph);
        Map<String, double[]> learned = learned(dirs.learned().resolve(key + ".json"), graph.version, now);

        List<Object> regions = new ArrayList<>();
        List<BotNavigationGraph.Region> sorted = new ArrayList<>(graph.regions);
        sorted.sort(Comparator.comparingInt(r -> r.id));
        for (BotNavigationGraph.Region r : sorted) {
            regions.add(List.of(r.id, r.isRopeRegion ? (r.isLadder ? 2 : 1) : 0, r.minX, r.minY, r.maxX, r.maxY));
        }

        List<Object> edges = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        int penalised = 0;
        Set<String> seen = new HashSet<>();
        List<Integer> regionIds = new ArrayList<>(graph.outgoingByRegionId.keySet());
        regionIds.sort(Comparator.naturalOrder());
        for (int regionId : regionIds) {
            for (BotNavigationGraph.Edge e : graph.getOutgoing(regionId)) {
                penalised += addEdge(edges, counts, seen, e, SOURCE_GRAPH, learned);
            }
        }
        int deep = 0;
        Path current = dirs.deepCurrent();
        if (current != null) {
            for (BotNavigationGraph.Edge e : deepEdges(current.resolve(key + ".json"), graph)) {
                penalised += addEdge(edges, counts, seen, e, SOURCE_DEEP, learned);
                deep++;
            }
        }
        int players = 0;
        for (BotNavigationGraph.Edge e : observedEdges(dirs.observed().resolve(graph.mapId + ".json"), graph)) {
            penalised += addEdge(edges, counts, seen, e, SOURCE_PLAYERS, learned);
            players++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("map", graph.mapId);
        out.put("version", graph.version);
        out.put("speed", graph.movementProfile.totalSpeedStat());
        out.put("jump", graph.movementProfile.totalJumpStat());
        out.put("snowShoes", graph.movementProfile.snowShoes());
        out.put("regions", regions);
        out.put("edges", edges);
        out.put("byType", counts);
        out.put("deepEdges", deep);
        out.put("playerEdges", players);
        out.put("learnedEdges", learned.size());
        out.put("penalised", penalised);
        return out;
    }

    /** Adds one edge; 1 when nav learning currently penalises it. */
    private static int addEdge(List<Object> edges, Map<String, Integer> counts, Set<String> seen,
                               BotNavigationGraph.Edge e, int source, Map<String, double[]> learned) {
        String id = NavEdgeLearning.edgeKey(e);
        if (!seen.add(id + "|" + source)) {
            return 0;
        }
        double[] fs = learned.get(id);
        double factor = fs == null ? 1.0 : NavEdgeLearning.factorFor(fs[0], fs[1]);
        edges.add(List.of(e.fromRegionId, e.toRegionId, e.type.name(), e.startPoint.x, e.startPoint.y,
                e.endPoint.x, e.endPoint.y, e.launchMinX, e.launchMaxX, e.cost, source, round(factor),
                fs == null ? 0 : round(fs[0]), fs == null ? 0 : round(fs[1])));
        counts.merge(e.type.name(), 1, Integer::sum);
        return factor > 1.0 ? 1 : 0;
    }

    /** A learned book's faded counts by edge key: {failures, successes}. Empty for another graph version. */
    static Map<String, double[]> learned(Path file, int version, long now) {
        Map<String, double[]> out = new HashMap<>();
        Object root = readJson(file);
        if (root instanceof Map<?, ?> m && m.get("version") instanceof Number v && v.intValue() == version
                && m.get("edges") instanceof Map<?, ?> edges) {
            for (Map.Entry<?, ?> e : edges.entrySet()) {
                if (e.getValue() instanceof List<?> l && l.size() == 3 && l.get(0) instanceof Number f
                        && l.get(1) instanceof Number s && l.get(2) instanceof Number at) {
                    NavEdgeLearning.Stats stats = new NavEdgeLearning.Stats();
                    stats.failures = f.doubleValue();
                    stats.successes = s.doubleValue();
                    stats.at = at.longValue();
                    stats.fade(now);
                    out.put(String.valueOf(e.getKey()), new double[]{stats.failures, stats.successes});
                }
            }
        }
        return out;
    }

    private static List<BotNavigationGraph.Edge> deepEdges(Path file, BotNavigationGraph graph) {
        List<BotNavigationGraph.Edge> out = new ArrayList<>();
        if (readJson(file) instanceof Map<?, ?> m && m.get("version") instanceof Number v && v.intValue() == graph.version
                && NavDeepOverlay.signature(graph).equals(m.get("signature")) && m.get("edges") instanceof List<?> list) {
            for (Object o : list) {
                BotNavigationGraph.Edge e = NavDeepOverlay.fromJson(o);
                if (e != null) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** The player-learned moves of the graph's map that apply to it (every stored format, profile-gated). */
    private static List<BotNavigationGraph.Edge> observedEdges(Path file, BotNavigationGraph graph) {
        return NavObservedEdges.storedEdges(file, graph);
    }

    // ── stuck ───────────────────────────────────────────────────────────────────────────────────

    /** One stuck spot summed over days: map, kind, 50px spot, edge and count. */
    public record StuckSpot(int mapId, String kind, int x, int y, String edge, int count, long last) {
    }

    /** The stuck spots of the last {@code days} UTC days ending {@code today}, summed per spot, and per-day totals by map. */
    public record Stuck(List<StuckSpot> spots, Map<String, Map<Integer, Integer>> byDay) {
    }

    public static Stuck stuck(Dirs dirs, LocalDate today, int days) {
        Map<String, StuckSpot> spots = new HashMap<>();
        Map<String, Map<Integer, Integer>> byDay = new TreeMap<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Map<Integer, Integer> perMap = new TreeMap<>();
            for (NavStuckLog.Spot s : NavStuckLog.read(NavStuckLog.fileFor(dirs.stuck(), day))) {
                String k = s.mapId + "|" + s.kind + "|" + s.x + "|" + s.y + "|" + s.edge;
                spots.merge(k, new StuckSpot(s.mapId, s.kind.name(), s.x, s.y, s.edge, s.count, s.last),
                        (a, b) -> new StuckSpot(a.mapId(), a.kind(), a.x(), a.y(), a.edge(), a.count() + b.count(),
                                Math.max(a.last(), b.last())));
                perMap.merge(s.mapId, s.count, Integer::sum);
            }
            byDay.put(day.toString(), perMap);
        }
        return new Stuck(new ArrayList<>(spots.values()), byDay);
    }

    public static LocalDate todayUtc() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    // ── learning totals ─────────────────────────────────────────────────────────────────────────

    /** Deep-bake edges per graph key in the current generation, and the generation's name ("" when none). */
    public record Deep(String generation, Map<String, Integer> edgesByKey) {
    }

    public static Deep deep(Dirs dirs) {
        Path current = dirs.deepCurrent();
        Map<String, Integer> byKey = new TreeMap<>();
        if (current == null) {
            return new Deep("", byKey);
        }
        String gen;
        try {
            gen = Files.readSymbolicLink(dirs.deepRoot().resolve(NavDeepOverlay.CURRENT)).getFileName().toString();
        } catch (IOException | UnsupportedOperationException e) {
            gen = current.getFileName().toString();
        }
        try (Stream<Path> files = Files.list(current)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (name.endsWith(".json") && KEY.matcher(name.substring(0, name.length() - 5)).matches()
                        && readJson(f) instanceof Map<?, ?> m && m.get("edges") instanceof List<?> list) {
                    byKey.put(name.substring(0, name.length() - 5), list.size());
                }
            }
        } catch (IOException e) {
            // an unreadable generation counts as none
        }
        return new Deep(gen, byKey);
    }

    /** Player-learned jumps per map, for the current graph version. */
    public static Map<Integer, Integer> playerEdgesByMap(Dirs dirs) {
        Map<Integer, Integer> out = new TreeMap<>();
        if (!Files.isDirectory(dirs.observed())) {
            return out;
        }
        try (Stream<Path> files = Files.list(dirs.observed())) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (name.matches("\\d+\\.json") && readJson(f) instanceof Map<?, ?> m
                        && m.get("version") instanceof Number v && v.intValue() == graphVersion()
                        && m.get("edges") instanceof List<?> list) {
                    out.put(Integer.parseInt(name.substring(0, name.length() - 5)), list.size());
                }
            }
        } catch (IOException e) {
            // none
        }
        return out;
    }

    /**
     * What the server made of real players' moves ({@link NavPlayerLearning}'s {@code players/stats.json} next to the
     * observed folder): totals by outcome since the first start, the last 14 days, the busiest maps, foothold-id
     * mismatches, moves still being counted, and the most used portal routes. Empty when nothing was recorded yet.
     */
    public static Map<String, Object> playerLearning(Dirs dirs) {
        Path players = dirs.observed().resolveSibling("players");
        Map<String, Object> out = new LinkedHashMap<>();
        if (readJson(players.resolve("stats.json")) instanceof Map<?, ?> stats) {
            for (String k : new String[]{"since", "updated", "moves", "totals", "fhMismatch", "dropped", "sightings"}) {
                if (stats.get(k) != null) {
                    out.put(k, stats.get(k));
                }
            }
            if (stats.get("days") instanceof Map<?, ?> days) {
                List<String> keys = new ArrayList<>();
                days.keySet().forEach(d -> keys.add(String.valueOf(d)));
                keys.sort(Comparator.naturalOrder());
                Map<String, Object> recent = new LinkedHashMap<>();
                for (String d : keys.subList(Math.max(0, keys.size() - 14), keys.size())) {
                    recent.put(d, days.get(d));
                }
                out.put("days", recent);
            }
            if (stats.get("maps") instanceof Map<?, ?> maps) {
                Map<String, Object> top = new LinkedHashMap<>();
                maps.entrySet().stream().limit(15).forEach(e -> top.put(String.valueOf(e.getKey()), e.getValue()));
                out.put("maps", top);
            }
        }
        if (readJson(players.resolve("portals.json")) instanceof Map<?, ?> portals && portals.get("routes") instanceof Map<?, ?> routes) {
            List<Object[]> rows = new ArrayList<>();
            routes.forEach((route, byPortal) -> {
                if (byPortal instanceof Map<?, ?> m) {
                    m.forEach((name, n) -> {
                        if (n instanceof Number count) {
                            rows.add(new Object[]{String.valueOf(route), String.valueOf(name), count.intValue()});
                        }
                    });
                }
            });
            rows.sort(Comparator.comparingInt((Object[] r) -> -(Integer) r[2]));
            List<Object> top = new ArrayList<>();
            rows.stream().limit(15).forEach(r -> top.add(List.of(r[0], r[1], r[2])));
            out.put("portalRoutes", routes.size());
            out.put("portals", top);
        }
        return out;
    }

    /** Nav learning per graph key: edges recorded, edges penalised now, faded failures and successes. */
    public record Learned(int edges, int penalised, double failures, double successes) {
    }

    public static Map<String, Learned> learnedByKey(Dirs dirs, long now) {
        Map<String, Learned> out = new TreeMap<>();
        if (!Files.isDirectory(dirs.learned())) {
            return out;
        }
        try (Stream<Path> files = Files.list(dirs.learned())) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                String key = name.substring(0, name.length() - 5);
                Map<String, double[]> book = learned(f, graphVersion(), now);
                if (book.isEmpty()) {
                    continue;
                }
                int penalised = 0;
                double fails = 0;
                double wins = 0;
                for (double[] fs : book.values()) {
                    fails += fs[0];
                    wins += fs[1];
                    if (NavEdgeLearning.factorFor(fs[0], fs[1]) > 1.0) {
                        penalised++;
                    }
                }
                out.put(key, new Learned(book.size(), penalised, round(fails), round(wins)));
            }
        } catch (IOException e) {
            // none
        }
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private static Object readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 100) / 100.0 : 0;
    }
}
