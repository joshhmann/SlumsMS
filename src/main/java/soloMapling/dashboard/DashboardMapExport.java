package soloMapling.dashboard;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavDashboardData;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The dashboard's map and nav-learning export (ticket 35): a standalone job, like the deep bake, that reads the
 * WZ files and the nav cache and writes, under {@code <out>/nav/}:
 * <ul>
 *   <li>{@code names.json}: every map's name from String.wz, for the search box;</li>
 *   <li>{@code index.json}: every map with a cached graph, with its profiles and edge, learning and stuck totals;</li>
 *   <li>{@code maps/<id>.json}: the map's footholds, ropes, ladders, portals and stuck spots;</li>
 *   <li>{@code graphs/<key>.json}: one profile's nav graph with deep-bake, player-learned and learned-cost marks;</li>
 *   <li>{@code learning.json} and {@code learning/<day>.json}: learning totals over time, best and worst maps.</li>
 * </ul>
 * It is incremental: a map or graph whose inputs did not change since the last run is not rewritten. Nothing it
 * does touches the game server; run it beside the server at low priority (see deploy/dashboard).
 * <pre>
 *   java -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav -cp target/Cosmic.jar soloMapling.dashboard.DashboardMapExport
 *       [--out=reports/dashboard] [--nav-reports=reports/nav] [--budget-minutes=30] [--stuck-days=7] [--force]
 *       [map id ...]
 * </pre>
 */
public final class DashboardMapExport {

    static final int HISTORY_DAYS = 30;
    static final int MAX_SPOTS_PER_MAP = 2_000;
    /** Learned costs fade (12 h half-life): graphs with learning are refreshed at least this often. */
    static final long LEARNED_REFRESH_MS = TimeUnit.HOURS.toMillis(6);

    public record Options(Path out, Path navReports, long budgetMinutes, int stuckDays, boolean force, List<Integer> maps) {
        public static Options parse(String[] args) {
            Path out = Path.of("reports/dashboard");
            Path reports = Path.of("reports/nav");
            long budget = 30;
            int stuckDays = 7;
            boolean force = false;
            List<Integer> maps = new ArrayList<>();
            for (String a : args) {
                if (a.startsWith("--out=")) {
                    out = Path.of(a.substring(6));
                } else if (a.startsWith("--nav-reports=")) {
                    reports = Path.of(a.substring(14));
                } else if (a.startsWith("--budget-minutes=")) {
                    budget = Long.parseLong(a.substring(17));
                } else if (a.startsWith("--stuck-days=")) {
                    stuckDays = Math.max(1, Integer.parseInt(a.substring(13)));
                } else if (a.equals("--force")) {
                    force = true;
                } else if (a.matches("\\d+")) {
                    maps.add(Integer.parseInt(a));
                } else {
                    throw new IllegalArgumentException("Unknown argument " + a);
                }
            }
            return new Options(out, reports, budget, stuckDays, force, maps);
        }
    }

    /** What one run did. */
    public record Summary(int maps, int mapsWritten, int graphsWritten, int failed, int skippedForBudget) {
    }

    private DashboardMapExport() {
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        long t0 = System.currentTimeMillis();
        Summary s = run(o, NavDashboardData.Dirs.fromSystemProperties(), DashboardMapExport::wzGeometry, wzNames(), t0);
        System.out.println("dashboard map export: " + s.maps() + " maps, " + s.mapsWritten() + " map files and "
                + s.graphsWritten() + " graph files written, " + s.failed() + " failed, " + s.skippedForBudget()
                + " left for the next run, " + (System.currentTimeMillis() - t0) / 1000 + "s");
        System.exit(0); // WZ loading leaves non-daemon threads behind
    }

    /** The map file's layout; a change rewrites every map once (157: portals gained their target portal, p[5]). */
    static final String MAP_FORMAT = "f2";

    /** Where a map's geometry comes from; tests substitute one. */
    public interface Geometry {
        Map<String, Object> of(int mapId);
    }

    private static Map<String, Object> wzGeometry(int mapId) {
        return NavDashboardData.geometry(mapId);
    }

    public static Summary run(Options o, NavDashboardData.Dirs dirs, Geometry geometry, Map<Integer, String[]> names, long now)
            throws IOException {
        Path nav = o.out().resolve("nav");
        Files.createDirectories(nav.resolve("maps"));
        Files.createDirectories(nav.resolve("graphs"));
        long deadline = now + TimeUnit.MINUTES.toMillis(o.budgetMinutes());
        LocalDate today = NavDashboardData.todayUtc();

        Map<Integer, List<String>> keys = NavDashboardData.cachedKeys(dirs);
        NavDashboardData.Stuck stuck = NavDashboardData.stuck(dirs, today, Math.max(o.stuckDays(), HISTORY_DAYS));
        Set<String> recentDays = new TreeSet<>();
        for (int i = 0; i < o.stuckDays(); i++) {
            recentDays.add(today.minusDays(i).toString());
        }
        NavDashboardData.Stuck recent = NavDashboardData.stuck(dirs, today, o.stuckDays());
        Map<Integer, List<NavDashboardData.StuckSpot>> spotsByMap = new HashMap<>();
        for (NavDashboardData.StuckSpot s : recent.spots()) {
            spotsByMap.computeIfAbsent(s.mapId(), k -> new ArrayList<>()).add(s);
        }
        NavDashboardData.Deep deep = NavDashboardData.deep(dirs);
        Map<Integer, Integer> players = NavDashboardData.playerEdgesByMap(dirs);
        Map<String, NavDashboardData.Learned> learned = NavDashboardData.learnedByKey(dirs, now);

        AtomicFiles.writeJson(nav.resolve("names.json"), namesJson(names));

        // Maps with stuck events first (the ones someone will look at), then by id.
        Map<Integer, Integer> stuckTotals = new HashMap<>();
        recent.spots().forEach(s -> stuckTotals.merge(s.mapId(), s.count(), Integer::sum));
        List<Integer> order = new ArrayList<>(o.maps().isEmpty() ? keys.keySet() : o.maps());
        order.removeIf(id -> !keys.containsKey(id));
        order.sort(Comparator.comparingInt((Integer id) -> -stuckTotals.getOrDefault(id, 0)).thenComparingInt(id -> id));

        Map<String, Object> state = readState(nav.resolve("state.json"));
        // Ticket 157 follow-up: every map's pit regions (NavPits), from the last time its map file was written
        Map<Integer, List<Integer>> pitsByMap = new TreeMap<>();
        for (int mapId : keys.keySet()) {
            List<Integer> known = statePits(state, mapId);
            if (known != null && !known.isEmpty()) {
                pitsByMap.put(mapId, known);
            }
        }
        long learnedBucket = now / LEARNED_REFRESH_MS;
        int mapsWritten = 0;
        int graphsWritten = 0;
        int failed = 0;
        int skipped = 0;
        for (int mapId : order) {
            if (System.currentTimeMillis() >= deadline) {
                skipped++;
                continue;
            }
            List<NavDashboardData.StuckSpot> spots = spotsByMap.getOrDefault(mapId, List.of());
            List<Object> keyRows = new ArrayList<>();
            Map<String, Map<String, Object>> built = new HashMap<>();
            StringBuilder mapSig = new StringBuilder("v" + NavDashboardData.graphVersion() + MAP_FORMAT);
            try {
                for (String key : keys.get(mapId)) {
                    String sig = keySignature(dirs, key, deep.generation(), learned.containsKey(key) ? learnedBucket : 0);
                    Path out = nav.resolve("graphs").resolve(key + ".json");
                    Object summary = state.get("k:" + key) instanceof Map<?, ?> st && sig.equals(st.get("sig"))
                            && Files.exists(out) && !o.force() ? st.get("summary") : null;
                    if (summary == null) {
                        Map<String, Object> g = NavDashboardData.graph(key, dirs, now);
                        if (g == null) {
                            continue;
                        }
                        AtomicFiles.writeJson(out, g);
                        built.put(key, g);
                        graphsWritten++;
                        summary = keySummary(g);
                        state.put("k:" + key, Map.of("sig", sig, "summary", summary));
                    }
                    keyRows.add(summary);
                    mapSig.append('|').append(sig);
                }
                mapSig.append("|s").append(spotHash(spots));
                Path mapFile = nav.resolve("maps").resolve(mapId + ".json");
                boolean fresh = state.get("m:" + mapId) instanceof Map<?, ?> st && mapSig.toString().equals(st.get("sig"))
                        && st.get("pits") instanceof List<?>;
                if (!fresh || o.force() || !Files.exists(mapFile)) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("id", mapId);
                    String[] n = names.getOrDefault(mapId, new String[]{"", ""});
                    doc.put("street", n[0]);
                    doc.put("name", n[1]);
                    Map<String, Object> geo = geometry.of(mapId);
                    doc.putAll(geo);
                    doc.put("profiles", keyRows);
                    doc.put("stuckDays", o.stuckDays());
                    doc.put("stuck", spotRows(spots));
                    Map<String, Integer> perDay = new TreeMap<>();
                    stuck.byDay().forEach((day, perMap) -> perDay.put(day, perMap.getOrDefault(mapId, 0)));
                    doc.put("stuckByDay", perDay);
                    AtomicFiles.writeJson(mapFile, doc);
                    mapsWritten++;
                    List<Integer> pits = pits(nav, keys.get(mapId), built, geo.get("bounds"));
                    state.put("m:" + mapId, Map.of("sig", mapSig.toString(), "pits", pits));
                    if (pits.isEmpty()) {
                        pitsByMap.remove(mapId);
                    } else {
                        pitsByMap.put(mapId, pits);
                    }
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                System.out.println("map " + mapId + " failed: " + e);
            }
        }

        writeIndex(nav, keys, names, state, stuckTotals, players, deep, now);
        writePits(nav, pitsByMap);
        writeLearning(nav, dirs, keys, stuck, recent, deep, players, learned, today, now);
        copyReports(o.navReports(), nav);
        AtomicFiles.writeJson(nav.resolve("state.json"), state);
        return new Summary(order.size(), mapsWritten, graphsWritten, failed, skipped);
    }

    // ── pits ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Ticket 157 follow-up (the Website lane's dead-end check): {@code nav/pits.json},
     * {@code {"version":1, "maps": {"<mapId>": [regionId, ...]}}}, every region {@code NavPits} treats as a pit (the
     * planner never routes into one, but the exported graphs still carry those edges). Only maps with a pit are
     * listed. Rewritten only when its content changes.
     */
    static boolean writePits(Path nav, Map<Integer, List<Integer>> pitsByMap) throws IOException {
        Map<String, Object> maps = new LinkedHashMap<>();
        pitsByMap.forEach((id, regions) -> maps.put(String.valueOf(id), regions));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("maps", maps);
        String text = Json.write(doc);
        Path file = nav.resolve("pits.json");
        try {
            if (Files.isRegularFile(file) && text.equals(Files.readString(file, StandardCharsets.UTF_8))) {
                return false;
            }
        } catch (IOException e) {
            // rewrite it
        }
        AtomicFiles.write(file, text);
        return true;
    }

    /** The union of a map's pit regions over its profiles' graphs (just built, else read back from the export). */
    private static List<Integer> pits(Path nav, List<String> keys, Map<String, Map<String, Object>> built, Object bounds) {
        if (bounds == null) {
            return List.of();
        }
        TreeSet<Integer> out = new TreeSet<>();
        for (String key : keys) {
            Object regions = null;
            Map<String, Object> g = built.get(key);
            if (g != null) {
                regions = g.get("regions");
            } else {
                Path f = nav.resolve("graphs").resolve(key + ".json");
                try {
                    if (Files.isRegularFile(f) && Json.parse(Files.readString(f, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                        regions = m.get("regions");
                    }
                } catch (IOException | RuntimeException e) {
                    // no regions for this profile
                }
            }
            out.addAll(NavDashboardData.pitRegions(regions, bounds));
        }
        return List.copyOf(out);
    }

    /** The pits a map's state entry remembers, or null when it has none recorded. */
    private static List<Integer> statePits(Map<String, Object> state, int mapId) {
        if (!(state.get("m:" + mapId) instanceof Map<?, ?> st) || !(st.get("pits") instanceof List<?> list)) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (Object v : list) {
            if (v instanceof Number n) {
                out.add(n.intValue());
            }
        }
        return out;
    }

    // ── index ───────────────────────────────────────────────────────────────────────────────────

    /** A profile's line in the map file and the index: key, speed, jump, snowshoes, edges, deep, players, penalised. */
    private static List<Object> keySummary(Map<String, Object> g) {
        return List.of(g.get("key"), g.get("speed"), g.get("jump"), g.get("snowShoes"),
                ((List<?>) g.get("edges")).size(), g.get("deepEdges"), g.get("playerEdges"), g.get("penalised"));
    }

    private static void writeIndex(Path nav, Map<Integer, List<String>> keys, Map<Integer, String[]> names,
                                   Map<String, Object> state, Map<Integer, Integer> stuck, Map<Integer, Integer> players,
                                   NavDashboardData.Deep deep, long now) throws IOException {
        List<Object> maps = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> e : keys.entrySet()) {
            int mapId = e.getKey();
            if (!Files.exists(nav.resolve("maps").resolve(mapId + ".json"))) {
                continue;
            }
            int edges = 0;
            int deepEdges = 0;
            int penalised = 0;
            for (String key : e.getValue()) {
                if (state.get("k:" + key) instanceof Map<?, ?> st && st.get("summary") instanceof List<?> s && s.size() == 8) {
                    edges = Math.max(edges, ((Number) s.get(4)).intValue());
                    deepEdges += ((Number) s.get(5)).intValue();
                    penalised += ((Number) s.get(7)).intValue();
                }
            }
            String[] n = names.getOrDefault(mapId, new String[]{"", ""});
            maps.add(List.of(mapId, n[0], n[1], e.getValue().size(), edges, deepEdges, players.getOrDefault(mapId, 0),
                    penalised, stuck.getOrDefault(mapId, 0)));
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("generated", now);
        index.put("graphVersion", NavDashboardData.graphVersion());
        index.put("deepGeneration", deep.generation());
        index.put("columns", List.of("id", "street", "name", "profiles", "edges", "deepEdges", "playerEdges",
                "penalised", "stuck"));
        index.put("maps", maps);
        AtomicFiles.writeJson(nav.resolve("index.json"), index);
    }

    // ── learning over time ──────────────────────────────────────────────────────────────────────

    private static void writeLearning(Path nav, NavDashboardData.Dirs dirs, Map<Integer, List<String>> keys, NavDashboardData.Stuck stuck,
                                      NavDashboardData.Stuck recent, NavDashboardData.Deep deep,
                                      Map<Integer, Integer> players, Map<String, NavDashboardData.Learned> learned,
                                      LocalDate today, long now) throws IOException {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("day", today.toString());
        totals.put("t", now);
        totals.put("graphKeys", keys.values().stream().mapToInt(List::size).sum());
        totals.put("maps", keys.size());
        totals.put("deepGeneration", deep.generation());
        totals.put("deepKeys", deep.edgesByKey().size());
        totals.put("deepEdges", deep.edgesByKey().values().stream().mapToInt(Integer::intValue).sum());
        totals.put("playerEdges", players.values().stream().mapToInt(Integer::intValue).sum());
        totals.put("playerMaps", players.size());
        totals.put("learnedEdges", learned.values().stream().mapToInt(NavDashboardData.Learned::edges).sum());
        totals.put("penalised", learned.values().stream().mapToInt(NavDashboardData.Learned::penalised).sum());
        totals.put("failures", LiveSnapshot.round1(learned.values().stream().mapToDouble(NavDashboardData.Learned::failures).sum()));
        totals.put("successes", LiveSnapshot.round1(learned.values().stream().mapToDouble(NavDashboardData.Learned::successes).sum()));
        Map<String, Integer> stuckByKind = new TreeMap<>();
        for (NavDashboardData.StuckSpot s : NavDashboardData.stuck(dirs, today, 1).spots()) {
            stuckByKind.merge(s.kind(), s.count(), Integer::sum);
        }
        totals.put("stuckToday", stuck.byDay().getOrDefault(today.toString(), Map.of()).values().stream()
                .mapToInt(Integer::intValue).sum());
        AtomicFiles.writeJson(nav.resolve("learning").resolve(today + ".json"), totals);

        List<Object> history = new ArrayList<>();
        Path dir = nav.resolve("learning");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> days = files.filter(f -> f.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .sorted().toList();
            for (Path f : days.subList(Math.max(0, days.size() - 365), days.size())) {
                try {
                    history.add(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException e) {
                    // skip a damaged day
                }
            }
        }

        // Stuck events per day (all maps, by day), and per map: the last 3 days against the 4 before.
        Map<String, Integer> stuckPerDay = new TreeMap<>();
        stuck.byDay().forEach((day, perMap) -> stuckPerDay.put(day, perMap.values().stream().mapToInt(Integer::intValue).sum()));
        List<String> days = new ArrayList<>(stuck.byDay().keySet());
        Map<Integer, double[]> trend = new HashMap<>();
        for (int i = 0; i < days.size(); i++) {
            int age = days.size() - 1 - i;
            if (age >= 7) {
                continue;
            }
            int slot = age < 3 ? 0 : 1;
            stuck.byDay().get(days.get(i)).forEach((mapId, n) -> trend.computeIfAbsent(mapId, k -> new double[2])[slot] += n);
        }
        List<Object> improving = new ArrayList<>();
        List<Map.Entry<Integer, double[]>> rows = new ArrayList<>(trend.entrySet());
        rows.sort(Comparator.comparingDouble((Map.Entry<Integer, double[]> e) -> e.getValue()[0] / 3 - e.getValue()[1] / 4));
        for (Map.Entry<Integer, double[]> e : rows) {
            double before = e.getValue()[1] / 4;
            double after = e.getValue()[0] / 3;
            if (before > after && improving.size() < 15) {
                improving.add(List.of(e.getKey(), LiveSnapshot.round1(before), LiveSnapshot.round1(after)));
            }
        }
        Map<Integer, Integer> week = new HashMap<>();
        recent.spots().forEach(s -> week.merge(s.mapId(), s.count(), Integer::sum));
        Map<Integer, Integer> penalisedByMap = new HashMap<>();
        learned.forEach((key, l) -> penalisedByMap.merge(Integer.parseInt(key.split("-")[0]), l.penalised(), Integer::sum));
        List<Object> worst = new ArrayList<>();
        Set<Integer> worstMaps = new TreeSet<>(week.keySet());
        worstMaps.addAll(penalisedByMap.keySet());
        List<Integer> worstOrder = new ArrayList<>(worstMaps);
        worstOrder.sort(Comparator.comparingInt((Integer id) -> -(week.getOrDefault(id, 0) + 10 * penalisedByMap.getOrDefault(id, 0)))
                .thenComparingInt(id -> id));
        for (int mapId : worstOrder.subList(0, Math.min(15, worstOrder.size()))) {
            worst.add(List.of(mapId, week.getOrDefault(mapId, 0), penalisedByMap.getOrDefault(mapId, 0)));
        }
        Map<String, Object> deepTop = new LinkedHashMap<>();
        deep.edgesByKey().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
                .forEach(e -> deepTop.put(e.getKey(), e.getValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", now);
        out.put("today", totals);
        out.put("stuckTodayByKind", stuckByKind);
        out.put("history", history);
        out.put("stuckPerDay", stuckPerDay);
        out.put("improving", improving);
        out.put("worst", worst);
        out.put("deepTopKeys", deepTop);
        out.put("players", NavDashboardData.playerLearning(dirs));
        AtomicFiles.writeJson(nav.resolve("learning.json"), out);
    }

    private static void copyReports(Path from, Path nav) {
        for (String name : List.of("bake-latest.md", "stuck-latest.md")) {
            Path f = from.resolve(name);
            try {
                if (Files.isRegularFile(f) && Files.size(f) < 4_000_000) {
                    AtomicFiles.write(nav.resolve(name), Files.readString(f, StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException e) {
                // reports are optional
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private static String keySignature(NavDashboardData.Dirs dirs, String key, String generation, long learnedBucket) {
        String mapId = key.split("-")[0];
        return mtime(dirs.graphs().resolve(key + ".bin")) + ":" + generation + ":"
                + mtime(dirs.observed().resolve(mapId + ".json")) + ":" + mtime(dirs.learned().resolve(key + ".json"))
                + ":" + learnedBucket;
    }

    private static long mtime(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static int spotHash(List<NavDashboardData.StuckSpot> spots) {
        int h = 0;
        for (NavDashboardData.StuckSpot s : spots) {
            h += s.hashCode(); // order-free
        }
        return h;
    }

    private static List<Object> spotRows(List<NavDashboardData.StuckSpot> spots) {
        List<NavDashboardData.StuckSpot> sorted = new ArrayList<>(spots);
        sorted.sort(Comparator.comparingInt((NavDashboardData.StuckSpot s) -> -s.count()));
        List<Object> rows = new ArrayList<>();
        for (NavDashboardData.StuckSpot s : sorted.subList(0, Math.min(MAX_SPOTS_PER_MAP, sorted.size()))) {
            rows.add(List.of(s.x(), s.y(), s.kind(), s.count(), s.edge(), s.last()));
        }
        return rows;
    }

    private static Map<String, Object> namesJson(Map<Integer, String[]> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        new TreeMap<>(names).forEach((id, n) -> out.put(String.valueOf(id), List.of(n[0], n[1])));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readState(Path file) {
        try {
            if (Files.isRegularFile(file) && Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                return new HashMap<>((Map<String, Object>) m);
            }
        } catch (IOException | RuntimeException e) {
            // start over
        }
        return new HashMap<>();
    }

    /** Every map's street and place name from String.wz (empty without WZ files). */
    static Map<Integer, String[]> wzNames() {
        Map<Integer, String[]> out = new HashMap<>();
        try {
            Data root = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Map.img");
            if (root == null) {
                return out;
            }
            for (Data group : root) {
                for (Data map : group) {
                    if (map.getName() != null && map.getName().matches("\\d+")) {
                        out.put(Integer.parseInt(map.getName()), new String[]{DataTool.getString("streetName", map, ""),
                                DataTool.getString("mapName", map, "")});
                    }
                }
            }
        } catch (RuntimeException e) {
            System.out.println("map names unavailable: " + e);
        }
        return out;
    }
}
