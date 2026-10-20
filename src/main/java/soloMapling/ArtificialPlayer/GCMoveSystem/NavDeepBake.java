package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.MapleMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The nightly deep bake (ticket 04b): a standalone job, run beside the game server at low priority, that rebuilds
 * every cached nav graph at {@link BotNavigationGraphProvider.SampleDensity#DEEP} and writes the jumps, drops,
 * rope moves, teleports and flash jumps the live graph missed as a new {@link NavDeepOverlay} generation. It also
 * builds the live graph of any key that is not cached yet, so the server finds it ready.
 *
 * <p>It is incremental and bounded: a key the current generation already covers is carried over (hard link), keys
 * nobody baked yet go first - maps where bots got stuck most ({@link NavStuckLog}) before the rest - and it stops
 * taking new maps when the time budget runs out; the next night continues. Whatever was baked is published by
 * swapping the {@code current} link in one rename, keeping the previous generations for {@code --rollback}. It
 * never touches the game server: the only shared files are graph cache files, which both sides write atomically.
 *
 * <pre>
 *   java -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav -cp target/Cosmic.jar \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.NavDeepBake [--threads=2] [--budget-minutes=150] [--all-maps]
 *       [--grace-minutes=30] [--full] [--keep=3] [--report-dir=reports/nav] [--stuck-dir=cache/bot-nav/stuck]
 *       [map or key ...]
 *   ... NavDeepBake --rollback
 * </pre>
 * Arguments that are map ids or cache keys bake just those (into a new generation that still carries the rest).
 */
public final class NavDeepBake {

    private static final Pattern KEY = Pattern.compile("(\\d+)-s(\\d+)-j(\\d+)(-ss)?(?:\\.bin|\\.json)?");
    private static final Pattern MAP_IMG = Pattern.compile("(\\d{9})\\.img(?:\\.xml)?");
    private static final DateTimeFormatter GENERATION = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Launch windows closer than this to a live edge of the same move are the same move. */
    static final int SAME_MOVE_PX = 16;
    /** At most this many launch spots per move (regions, type, direction), counting the live graph's. */
    static final int MAX_PER_MOVE = 3;

    /** What one graph key's bake found. */
    record KeyResult(String key, int mapId, int added, int newConnections, Map<String, Integer> byType,
                     boolean liveBuilt, long ms, String error) {
    }

    private NavDeepBake() {
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        Path deepRoot = NavDeepOverlay.defaultRoot();
        if (o.rollback) {
            String back = rollback(deepRoot);
            System.out.println(back == null ? "rollback: no older generation" : "rollback: current -> " + back);
            System.exit(back == null ? 1 : 0);
            return;
        }
        int code = run(o, deepRoot);
        // The graph warm-up executors are daemon threads; nothing else keeps the JVM up.
        System.exit(code);
    }

    static int run(Options o, Path deepRoot) throws IOException, InterruptedException {
        long started = System.currentTimeMillis();
        long deadline = started + TimeUnit.MINUTES.toMillis(o.budgetMinutes);
        Path graphDir = BotNavigationGraphProvider.graphFile(new BotNavigationGraphProvider.GraphCacheKey(0, 0, 0, false))
                .getParent();

        Map<Integer, List<BotMovementProfile>> profilesByMap = new TreeMap<>();
        if (o.targets.isEmpty()) {
            addKeysFrom(graphDir, profilesByMap);
            o.usedMaps.addAll(profilesByMap.keySet());
            if (o.allMaps) {
                for (int mapId : wzMapIds()) {
                    addProfile(profilesByMap, mapId, BotMovementProfile.base());
                }
            }
        } else {
            for (String target : o.targets) {
                if (target.matches("\\d+")) {
                    addProfile(profilesByMap, Integer.parseInt(target), BotMovementProfile.base());
                    try (Stream<Path> files = Files.isDirectory(graphDir) ? Files.list(graphDir) : Stream.empty()) {
                        files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith(target + "-"))
                                .forEach(n -> addKey(profilesByMap, n));
                    }
                } else {
                    addKey(profilesByMap, target);
                }
            }
        }

        Files.createDirectories(deepRoot);
        Path current = currentDir(deepRoot);
        String genName = GENERATION.format(Instant.ofEpochMilli(started));
        Path gen = deepRoot.resolve(genName + ".partial");
        Files.createDirectories(gen);

        // Carry over what the current generation already baked (all of it when only some targets are re-baked).
        int carried = 0;
        Map<Integer, List<BotMovementProfile>> todo = new TreeMap<>();
        if (current != null) {
            try (Stream<Path> files = Files.list(current)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.getFileName().toString();
                    Matcher m = KEY.matcher(name);
                    if (!m.matches() || !name.endsWith(".json") || !isCurrentFormat(file)) {
                        continue;
                    }
                    boolean rebake = o.full || (!o.targets.isEmpty() && contains(profilesByMap, name));
                    if (!rebake) {
                        link(file, gen.resolve(name));
                        carried++;
                    }
                }
            }
        }
        for (Map.Entry<Integer, List<BotMovementProfile>> entry : profilesByMap.entrySet()) {
            for (BotMovementProfile profile : entry.getValue()) {
                String key = keyName(entry.getKey(), profile);
                if (!Files.exists(gen.resolve(key + ".json"))) {
                    todo.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(profile);
                }
            }
        }

        // Maps where bots got stuck lately first, then maps bots have graphs for, then the rest, by id.
        Map<Integer, Integer> stuck = NavStuckLog.countsByMap(o.stuckDir, 7);
        List<Integer> order = new ArrayList<>(todo.keySet());
        order.sort(Comparator.comparingInt((Integer id) -> -stuck.getOrDefault(id, 0))
                .thenComparing(id -> !o.usedMaps.contains(id)).thenComparingInt(id -> id));
        int keysTodo = todo.values().stream().mapToInt(List::size).sum();
        System.out.println("deep bake v" + BotNavigationGraphProvider.GRAPH_VERSION + ": " + keysTodo + " keys on "
                + order.size() + " maps to bake, " + carried + " carried over, " + o.threads + " threads, budget "
                + o.budgetMinutes + " min");

        long hardStop = deadline + TimeUnit.MINUTES.toMillis(o.graceMinutes);
        List<KeyResult> results = new ArrayList<>();
        Map<Integer, Boolean> unfinished = new ConcurrentHashMap<>();
        AtomicInteger keysDone = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(o.threads, r -> {
            Thread t = new Thread(r, "nav-deep-bake");
            t.setDaemon(true);
            return t;
        });
        for (int mapId : order) {
            pool.execute(() -> {
                if (System.currentTimeMillis() >= deadline) {
                    unfinished.put(mapId, true);
                    return;
                }
                List<KeyResult> mapResults = bakeMap(mapId, todo.get(mapId), gen, hardStop);
                synchronized (results) {
                    results.addAll(mapResults);
                }
                int done = keysDone.addAndGet(mapResults.size());
                if (done % 50 < mapResults.size()) {
                    System.out.println("baked " + done + " of " + keysTodo + " keys in "
                            + (System.currentTimeMillis() - started) / 1000 + "s");
                }
            });
        }
        pool.shutdown();
        // A map still building this long after the budget is abandoned (its key is retried next run): what was baked
        // gets published rather than lost to the unit's timeout.
        if (!pool.awaitTermination(Math.max(1, hardStop - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) {
            System.out.println("grace period over: publishing without the maps still building");
        }

        List<KeyResult> finished;
        synchronized (results) {
            finished = new ArrayList<>(results);
        }
        return publish(o, deepRoot, gen, genName, finished, carried, keysTodo, unfinished.size(), started);
    }

    private static int publish(Options o, Path deepRoot, Path gen, String genName, List<KeyResult> results,
                               int carried, int keysTodo, int unfinishedMaps, long started) throws IOException {
        int baked = results.size();
        if (baked == 0) { // nothing new: keep the current generation, no churn for the server
            deleteTree(gen);
            System.out.println("done: nothing to bake, generation unchanged");
            return 0;
        }
        int failed = (int) results.stream().filter(r -> r.error() != null).count();
        String report = report(genName, results, carried, keysTodo, unfinishedMaps, started, o);
        Files.writeString(gen.resolve("report.md"), report, StandardCharsets.UTF_8);
        Path published = deepRoot.resolve(genName);
        Files.move(gen, published, StandardCopyOption.ATOMIC_MOVE);
        swapCurrent(deepRoot, genName);
        prune(deepRoot, o.keep);
        if (o.reportDir != null) {
            Files.createDirectories(o.reportDir);
            String day = genName.substring(0, 4) + "-" + genName.substring(4, 6) + "-" + genName.substring(6, 8);
            writeAtomically(o.reportDir.resolve("bake-" + day + ".md"), report);
            writeAtomically(o.reportDir.resolve("bake-latest.md"), report);
        }
        int added = results.stream().mapToInt(KeyResult::added).sum();
        int connections = results.stream().mapToInt(KeyResult::newConnections).sum();
        System.out.println("done: generation " + genName + ", baked " + baked + " keys (" + failed + " failed), "
                + carried + " carried over, " + (keysTodo - baked) + " left for the next run; " + added
                + " edges added, " + connections + " new region connections, in "
                + (System.currentTimeMillis() - started) / 1000 + "s");
        return 0;
    }

    // ── one map ─────────────────────────────────────────────────────────────────────────────────

    static List<KeyResult> bakeMap(int mapId, List<BotMovementProfile> profiles, Path gen, long hardStopAt) {
        List<KeyResult> results = new ArrayList<>();
        MapleMap map;
        try {
            map = BotNavigationMapLoader.loadMapGeometry(mapId);
        } catch (RuntimeException e) {
            for (BotMovementProfile profile : profiles) {
                results.add(failed(mapId, profile, 0, e));
                writeFailureMarker(gen, keyName(mapId, profile), e);
            }
            return results;
        }
        for (BotMovementProfile profile : profiles) {
            long t0 = System.nanoTime();
            if (System.currentTimeMillis() >= hardStopAt) {
                break; // the run is publishing without this map
            }
            try {
                BotNavigationGraphProvider.GraphCacheKey key = BotNavigationGraphProvider.GraphCacheKey.from(mapId, profile);
                BotNavigationGraph live = BotNavigationGraphProvider.loadGraph(key);
                boolean liveBuilt = live == null;
                if (live == null) {
                    live = BotNavigationGraphProvider.buildGraphWithDensity(map, profile,
                            BotNavigationGraphProvider.SampleDensity.LIVE);
                    BotNavigationGraphProvider.saveGraph(live);
                }
                BotNavigationGraph deep = BotNavigationGraphProvider.buildGraphWithDensity(map, profile,
                        BotNavigationGraphProvider.SampleDensity.DEEP);
                String keyName = keyName(mapId, profile);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (!NavDeepOverlay.signature(live).equals(NavDeepOverlay.signature(deep))) {
                    writeAtomically(gen.resolve(keyName + ".json"), NavDeepOverlay.fileContent(live, List.of()));
                    results.add(new KeyResult(keyName, mapId, 0, 0, Map.of(), liveBuilt, ms,
                            "regions differ between the cached and the deep graph"));
                    continue;
                }
                List<BotNavigationGraph.Edge> added = missing(live, deep);
                writeAtomically(gen.resolve(keyName + ".json"), NavDeepOverlay.fileContent(live, added));
                Map<String, Integer> byType = new TreeMap<>();
                int connections = 0;
                for (BotNavigationGraph.Edge edge : added) {
                    byType.merge(edge.type.name(), 1, Integer::sum);
                    if (!joinedByAny(live, edge.fromRegionId, edge.toRegionId)) {
                        connections++;
                    }
                }
                results.add(new KeyResult(keyName, mapId, added.size(), connections, byType, liveBuilt, ms, null));
            } catch (RuntimeException | IOException | StackOverflowError e) {
                results.add(failed(mapId, profile, (System.nanoTime() - t0) / 1_000_000L, e));
                writeFailureMarker(gen, keyName(mapId, profile), e);
            }
        }
        return results;
    }

    /* A key that cannot be baked is marked done (no edges) so later runs skip it until --full or a new format. */
    private static void writeFailureMarker(Path gen, String keyName, Throwable e) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", BotNavigationGraphProvider.GRAPH_VERSION);
        json.put("format", NavDeepOverlay.FORMAT);
        json.put("error", String.valueOf(e));
        json.put("edges", List.of());
        try {
            writeAtomically(gen.resolve(keyName + ".json"), soloMapling.server.Json.write(json));
        } catch (IOException ignored) {
            // the run is publishing already
        }
    }

    private static KeyResult failed(int mapId, BotMovementProfile profile, long ms, Throwable e) {
        return new KeyResult(keyName(mapId, profile), mapId, 0, 0, Map.of(), false, ms, String.valueOf(e));
    }

    /**
     * The deep graph's moves the live graph lacks: an edge is new unless the live graph has the same move (regions,
     * type, launch direction) launching within {@link #SAME_MOVE_PX} of it. Walks and portals are left to the live
     * builder. Each move keeps at most {@link #MAX_PER_MOVE} launch spots, live ones included, cheapest first, so a
     * densely sampled platform does not flood the planner with near-copies.
     */
    static List<BotNavigationGraph.Edge> missing(BotNavigationGraph live, BotNavigationGraph deep) {
        Map<String, List<BotNavigationGraph.Edge>> byMove = new LinkedHashMap<>();
        List<Integer> regionIds = new ArrayList<>(deep.outgoingByRegionId.keySet());
        regionIds.sort(Integer::compare);
        for (int regionId : regionIds) {
            for (BotNavigationGraph.Edge edge : deep.getOutgoing(regionId)) {
                if (edge.type == BotNavigationGraph.EdgeType.WALK || edge.type == BotNavigationGraph.EdgeType.PORTAL
                        || edge.fromRegionId == edge.toRegionId) {
                    continue;
                }
                byMove.computeIfAbsent(moveKey(edge), k -> new ArrayList<>()).add(edge);
            }
        }
        List<BotNavigationGraph.Edge> added = new ArrayList<>();
        for (List<BotNavigationGraph.Edge> candidates : byMove.values()) {
            List<BotNavigationGraph.Edge> kept = new ArrayList<>();
            for (BotNavigationGraph.Edge edge : live.getOutgoing(candidates.getFirst().fromRegionId)) {
                if (moveKey(edge).equals(moveKey(candidates.getFirst()))) {
                    kept.add(edge);
                }
            }
            candidates.sort(Comparator.comparingInt((BotNavigationGraph.Edge e) -> e.cost)
                    .thenComparingInt(e -> e.launchMinX).thenComparingInt(e -> e.launchMinY));
            for (BotNavigationGraph.Edge edge : candidates) {
                if (kept.size() >= MAX_PER_MOVE) {
                    break;
                }
                if (kept.stream().noneMatch(k -> sameLaunch(k, edge))) {
                    kept.add(edge);
                    added.add(edge);
                }
            }
        }
        return added;
    }

    private static String moveKey(BotNavigationGraph.Edge edge) {
        return edge.fromRegionId + ">" + edge.toRegionId + ":" + edge.type + ":" + Integer.signum(edge.launchStepX);
    }

    private static boolean sameLaunch(BotNavigationGraph.Edge a, BotNavigationGraph.Edge b) {
        return a.launchMinX - SAME_MOVE_PX <= b.launchMaxX && b.launchMinX <= a.launchMaxX + SAME_MOVE_PX
                && a.launchMinY - SAME_MOVE_PX <= b.launchMaxY && b.launchMinY <= a.launchMaxY + SAME_MOVE_PX;
    }

    private static boolean joinedByAny(BotNavigationGraph graph, int from, int to) {
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(from)) {
            if (edge.toRegionId == to) {
                return true;
            }
        }
        return false;
    }

    // ── generations ─────────────────────────────────────────────────────────────────────────────

    static Path currentDir(Path deepRoot) {
        Path link = deepRoot.resolve(NavDeepOverlay.CURRENT);
        try {
            return Files.isSymbolicLink(link) && Files.isDirectory(link) ? deepRoot.resolve(Files.readSymbolicLink(link)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Points {@code current} at the generation in one rename (a reader sees the old link or the new one). */
    static void swapCurrent(Path deepRoot, String genName) throws IOException {
        Path tmp = deepRoot.resolve(NavDeepOverlay.CURRENT + ".next");
        Files.deleteIfExists(tmp);
        Files.createSymbolicLink(tmp, Path.of(genName));
        Files.move(tmp, deepRoot.resolve(NavDeepOverlay.CURRENT), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** Points {@code current} at the newest generation older than it; that generation's name, or null. */
    static String rollback(Path deepRoot) throws IOException {
        Path current = currentDir(deepRoot);
        String currentName = current == null ? null : current.getFileName().toString();
        String back = null;
        for (String name : generations(deepRoot)) {
            if (currentName == null || name.compareTo(currentName) < 0) {
                back = name; // ascending: the last one below current is the newest older one
            }
        }
        if (back != null) {
            swapCurrent(deepRoot, back);
        }
        return back;
    }

    /** Keeps the newest {@code keep} generations (and whatever {@code current} points at); drops the rest. */
    static void prune(Path deepRoot, int keep) throws IOException {
        Path current = currentDir(deepRoot);
        List<String> gens = generations(deepRoot);
        for (int i = 0; i < gens.size() - keep; i++) {
            Path dir = deepRoot.resolve(gens.get(i));
            if (current != null && dir.getFileName().equals(current.getFileName())) {
                continue;
            }
            deleteTree(dir);
        }
        try (Stream<Path> files = Files.list(deepRoot)) { // a run that died before publishing
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().endsWith(".partial")
                        && Files.getLastModifiedTime(p).toMillis() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)) {
                    deleteTree(p);
                }
            }
        }
    }

    static List<String> generations(Path deepRoot) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(deepRoot)) {
            files.filter(p -> Files.isDirectory(p) && !Files.isSymbolicLink(p))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("\\d{8}-\\d{6}"))
                    .forEach(names::add);
        }
        names.sort(String::compareTo);
        return names;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean isCurrentFormat(Path file) {
        try {
            return soloMapling.server.Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> json
                    && json.get("format") instanceof Number f && f.intValue() == NavDeepOverlay.FORMAT
                    && json.get("version") instanceof Number v && v.intValue() == BotNavigationGraphProvider.GRAPH_VERSION;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void link(Path from, Path to) throws IOException {
        try {
            Files.createLink(to, from);
        } catch (FileAlreadyExistsException e) {
            // already carried
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── keys ────────────────────────────────────────────────────────────────────────────────────

    static String keyName(int mapId, BotMovementProfile p) {
        return mapId + "-s" + p.totalSpeedStat() + "-j" + p.totalJumpStat() + (p.snowShoes() ? "-ss" : "");
    }

    private static void addKeysFrom(Path dir, Map<Integer, List<BotMovementProfile>> profilesByMap) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".bin"))
                    .forEach(n -> addKey(profilesByMap, n));
        }
    }

    private static void addKey(Map<Integer, List<BotMovementProfile>> profilesByMap, String name) {
        Matcher m = KEY.matcher(name);
        if (m.matches()) {
            addProfile(profilesByMap, Integer.parseInt(m.group(1)), new BotMovementProfile(Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), m.group(4) != null));
        }
    }

    private static void addProfile(Map<Integer, List<BotMovementProfile>> profilesByMap, int mapId, BotMovementProfile profile) {
        List<BotMovementProfile> profiles = profilesByMap.computeIfAbsent(mapId, id -> new ArrayList<>());
        if (!profiles.contains(profile)) {
            profiles.add(profile);
        }
    }

    private static boolean contains(Map<Integer, List<BotMovementProfile>> profilesByMap, String fileName) {
        Matcher m = KEY.matcher(fileName);
        if (!m.matches()) {
            return false;
        }
        List<BotMovementProfile> profiles = profilesByMap.get(Integer.parseInt(m.group(1)));
        return profiles != null && profiles.contains(new BotMovementProfile(Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), m.group(4) != null));
    }

    /** Every map id in the WZ Map tree (-Dwz-path), for --all-maps. */
    private static List<Integer> wzMapIds() throws IOException {
        Path maps = Path.of(System.getProperty("wz-path", "wz"), "Map.wz", "Map");
        List<Integer> ids = new ArrayList<>();
        if (!Files.isDirectory(maps)) {
            return ids;
        }
        try (Stream<Path> walk = Files.walk(maps, 2)) {
            walk.forEach(p -> {
                Matcher m = MAP_IMG.matcher(p.getFileName().toString());
                if (m.matches()) {
                    ids.add(Integer.parseInt(m.group(1)));
                }
            });
        }
        return ids;
    }

    // ── report ──────────────────────────────────────────────────────────────────────────────────

    static String report(String genName, List<KeyResult> results, int carried, int keysTodo, int unfinishedMaps,
                         long started, Options o) {
        StringBuilder out = new StringBuilder();
        int added = results.stream().mapToInt(KeyResult::added).sum();
        int connections = results.stream().mapToInt(KeyResult::newConnections).sum();
        long liveBuilt = results.stream().filter(KeyResult::liveBuilt).count();
        List<KeyResult> failed = results.stream().filter(r -> r.error() != null).toList();
        Map<String, Integer> byType = new TreeMap<>();
        results.forEach(r -> r.byType().forEach((t, n) -> byType.merge(t, n, Integer::sum)));

        out.append("# Nav deep bake ").append(genName).append(" (graph v").append(BotNavigationGraphProvider.GRAPH_VERSION)
                .append(")\n\n");
        out.append("- Baked ").append(results.size()).append(" of ").append(keysTodo).append(" graph keys due")
                .append(" (").append(failed.size()).append(" failed); ").append(carried)
                .append(" carried over from the previous generation.\n");
        if (unfinishedMaps > 0) {
            out.append("- Budget of ").append(o.budgetMinutes).append(" min ran out with ").append(unfinishedMaps)
                    .append(" maps left; the next run continues with them.\n");
        }
        out.append("- Edges added: ").append(added).append(' ').append(byType).append("; ").append(connections)
                .append(" join two regions the live graph did not join directly.\n");
        out.append("- Live graphs built because they were not cached: ").append(liveBuilt).append(".\n");
        out.append("- Took ").append((System.currentTimeMillis() - started) / 1000).append(" s on ").append(o.threads)
                .append(" threads.\n\n");

        Map<Integer, int[]> byMap = new HashMap<>();
        for (KeyResult r : results) {
            int[] sums = byMap.computeIfAbsent(r.mapId(), k -> new int[3]);
            sums[0] += r.added();
            sums[1] += r.newConnections();
            sums[2]++;
        }
        List<Map.Entry<Integer, int[]>> top = new ArrayList<>(byMap.entrySet());
        top.removeIf(e -> e.getValue()[0] == 0);
        top.sort(Comparator.comparingInt((Map.Entry<Integer, int[]> e) -> -e.getValue()[1])
                .thenComparingInt(e -> -e.getValue()[0]));
        out.append("## Edges added per map\n\n");
        if (top.isEmpty()) {
            out.append("None.\n\n");
        } else {
            out.append("| map | edges added | new connections | graph keys |\n| --- | ---: | ---: | ---: |\n");
            for (Map.Entry<Integer, int[]> e : top.subList(0, Math.min(top.size(), 50))) {
                out.append("| ").append(e.getKey()).append(" | ").append(e.getValue()[0]).append(" | ")
                        .append(e.getValue()[1]).append(" | ").append(e.getValue()[2]).append(" |\n");
            }
            if (top.size() > 50) {
                out.append("\n... and ").append(top.size() - 50).append(" more maps (per-key detail below).\n");
            }
            out.append('\n');
        }
        if (!failed.isEmpty()) {
            out.append("## Failed\n\n");
            for (KeyResult r : failed.subList(0, Math.min(failed.size(), 50))) {
                out.append("- ").append(r.key()).append(": ").append(r.error()).append('\n');
            }
            out.append('\n');
        }
        out.append("## Per key\n\n```\nkey\tadded\tnew_connections\tlive_built\tms\ttypes\n");
        results.stream().sorted(Comparator.comparing(KeyResult::key)).filter(r -> r.added() > 0 || r.error() != null)
                .forEach(r -> out.append(r.key()).append('\t').append(r.added()).append('\t').append(r.newConnections())
                        .append('\t').append(r.liveBuilt()).append('\t').append(r.ms()).append('\t')
                        .append(r.error() != null ? "ERROR" : r.byType().toString()).append('\n'));
        out.append("```\n");
        return out.toString();
    }

    // ── options ─────────────────────────────────────────────────────────────────────────────────

    static final class Options {
        int threads = 2;
        long budgetMinutes = 150;
        long graceMinutes = 30;
        final java.util.Set<Integer> usedMaps = new java.util.HashSet<>();
        boolean allMaps;
        boolean full;
        boolean rollback;
        int keep = 3;
        Path reportDir = Path.of("reports", "nav");
        Path stuckDir = NavStuckLog.defaultDir();
        final List<String> targets = new ArrayList<>();

        static Options parse(String[] args) {
            Options o = new Options();
            for (String arg : args) {
                String value = arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : "";
                if (arg.startsWith("--threads=")) {
                    o.threads = Math.max(1, Integer.parseInt(value));
                } else if (arg.startsWith("--budget-minutes=")) {
                    o.budgetMinutes = Math.max(1, Long.parseLong(value));
                } else if (arg.startsWith("--grace-minutes=")) {
                    o.graceMinutes = Math.max(0, Long.parseLong(value));
                } else if (arg.equals("--all-maps")) {
                    o.allMaps = true;
                } else if (arg.equals("--full")) {
                    o.full = true;
                } else if (arg.equals("--rollback")) {
                    o.rollback = true;
                } else if (arg.startsWith("--keep=")) {
                    o.keep = Math.max(2, Integer.parseInt(value));
                } else if (arg.startsWith("--report-dir=")) {
                    o.reportDir = value.isEmpty() ? null : Path.of(value);
                } else if (arg.startsWith("--stuck-dir=")) {
                    o.stuckDir = Path.of(value);
                } else if (arg.startsWith("--")) {
                    throw new IllegalArgumentException("unknown option " + arg);
                } else {
                    o.targets.add(arg);
                }
            }
            return o;
        }
    }
}
