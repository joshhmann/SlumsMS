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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Edges from the nightly deep bake (ticket 04b). The live builder samples each platform about once per walk step
 * so a cold map builds in a second; {@link NavDeepBake} rebuilds every cached graph off the game server at a much
 * finer density and keeps only the moves the live graph lacks. Those land here, one file per graph key, and the
 * planner reads them next to the graph's own edges ({@link NavObservedEdges#outgoing}) - never written into the
 * cached graph.
 *
 * <p>Layout: {@code <cache>/deep/v<GRAPH_VERSION>/<generation>/<key>.json}, with {@code current} a symlink to
 * the generation in use. The bake writes a whole new generation and swaps the link in one rename, so a reader sees
 * either the old set or the new one; the previous generations stay for rollback. A file carries the graph's
 * signature (its region layout) and is ignored for a graph whose regions differ, so an overlay can never point at
 * the wrong platforms. A running server notices a new generation within a minute.
 */
public final class NavDeepOverlay {

    private static final Logger log = LoggerFactory.getLogger(NavDeepOverlay.class);

    /** Bumped when the deep search or the file format changes, so older generations are re-baked. */
    static final int FORMAT = 1;
    static final String CURRENT = "current";
    private static final long RECHECK_MS = 60_000;

    /**
     * One graph's baked edges: for each region they leave from, the region's whole outgoing list (the graph's own
     * edges, then the baked ones), merged once at load so the planner's inner loop never allocates.
     */
    record Loaded(String generation, Map<Integer, List<BotNavigationGraph.Edge>> byRegion, int edgeCount) {
    }

    private static final Loaded NONE = new Loaded("", Map.of(), 0);

    private static volatile Path root = defaultRoot();
    private static volatile String generation;
    private static volatile long nextCheckAt;

    private NavDeepOverlay() {
    }

    static Path defaultRoot() {
        return Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "deep",
                BotNavigationGraphProvider.cacheFolderName());
    }

    /** A region's outgoing edges with the baked ones: the graph's own list itself when nothing was baked there. */
    static List<BotNavigationGraph.Edge> outgoing(BotNavigationGraph graph, int regionId) {
        Loaded loaded = loaded(graph);
        if (loaded.edgeCount() == 0) {
            return graph.getOutgoing(regionId);
        }
        List<BotNavigationGraph.Edge> merged = loaded.byRegion().get(regionId);
        return merged != null ? merged : graph.getOutgoing(regionId);
    }

    static Loaded loaded(BotNavigationGraph graph) {
        String current = currentGeneration();
        Loaded loaded = graph.deepOverlay;
        if (loaded != null && loaded.generation().equals(current)) {
            return loaded;
        }
        loaded = current.isEmpty() ? NONE : load(graph, current);
        graph.deepOverlay = loaded;
        return loaded;
    }

    /** The generation {@code current} points at ("" when there is none), re-read at most once a minute. */
    private static String currentGeneration() {
        long now = System.currentTimeMillis();
        String known = generation;
        if (known != null && now < nextCheckAt) {
            return known;
        }
        String resolved = "";
        try {
            Path link = root.resolve(CURRENT);
            if (Files.isSymbolicLink(link)) {
                resolved = Files.readSymbolicLink(link).getFileName().toString();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot read the deep bake generation under {}", root, e);
        }
        generation = resolved;
        nextCheckAt = now + RECHECK_MS;
        return resolved;
    }

    private static Loaded load(BotNavigationGraph graph, String gen) {
        Path file = root.resolve(gen).resolve(keyOf(graph) + ".json");
        if (!Files.isRegularFile(file)) {
            return new Loaded(gen, Map.of(), 0);
        }
        try {
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> json)
                    || !(json.get("version") instanceof Number v) || v.intValue() != graph.version
                    || !signature(graph).equals(json.get("signature"))
                    || !(json.get("edges") instanceof List<?> list)) {
                return new Loaded(gen, Map.of(), 0); // another graph version or region layout: not ours
            }
            List<BotNavigationGraph.Edge> edges = new ArrayList<>();
            for (Object o : list) {
                BotNavigationGraph.Edge edge = fromJson(o);
                if (edge != null && graph.getRegion(edge.fromRegionId) != null
                        && graph.getRegion(edge.toRegionId) != null) {
                    edges.add(edge);
                }
            }
            return index(gen, graph, edges);
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable deep bake overlay {}", file, e);
            return new Loaded(gen, Map.of(), 0);
        }
    }

    static Loaded index(String gen, BotNavigationGraph graph, List<BotNavigationGraph.Edge> edges) {
        Map<Integer, List<BotNavigationGraph.Edge>> byRegion = new HashMap<>();
        for (BotNavigationGraph.Edge edge : edges) {
            byRegion.computeIfAbsent(edge.fromRegionId, k -> new ArrayList<>(graph.getOutgoing(k))).add(edge);
        }
        byRegion.replaceAll((k, v) -> List.copyOf(v));
        return new Loaded(gen, Map.copyOf(byRegion), edges.size());
    }

    /** The cache key's file name, as the graph cache spells it. */
    static String keyOf(BotNavigationGraph graph) {
        return NavEdgeLearning.bookKey(graph);
    }

    /**
     * The graph's region layout: every region's id, kind and extent, and which region each foothold belongs to.
     * Two builds of the same map and profile agree on it whatever their sample density.
     */
    static String signature(BotNavigationGraph graph) {
        long h = 1125899906842597L;
        List<BotNavigationGraph.Region> regions = new ArrayList<>(graph.regions);
        regions.sort((a, b) -> Integer.compare(a.id, b.id));
        for (BotNavigationGraph.Region r : regions) {
            h = mix(h, r.id);
            h = mix(h, r.isRopeRegion ? 1 : 0);
            h = mix(h, r.minX);
            h = mix(h, r.maxX);
            h = mix(h, r.minY);
            h = mix(h, r.maxY);
        }
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(graph.regionIdByFootholdId).entrySet()) {
            h = mix(h, e.getKey());
            h = mix(h, e.getValue());
        }
        return Long.toHexString(h) + "-" + regions.size();
    }

    private static long mix(long h, int v) {
        return h * 31 + v;
    }

    // ── file format ─────────────────────────────────────────────────────────────────────────────

    static List<Object> toJson(BotNavigationGraph.Edge e) {
        return List.of(e.fromRegionId, e.toRegionId, e.type.name(), e.startPoint.x, e.startPoint.y,
                e.endPoint.x, e.endPoint.y, e.launchMinX, e.launchMaxX, e.launchMinY, e.launchMaxY, e.launchStepX,
                e.portalId, e.ropeX, e.ropeTopY, e.ropeBottomY, e.launchCostMinY, e.launchCostMaxY, e.cost);
    }

    static BotNavigationGraph.Edge fromJson(Object o) {
        if (!(o instanceof List<?> f) || f.size() != 19 || !(f.get(2) instanceof String type)) {
            return null;
        }
        int[] n = new int[19];
        for (int i = 0; i < 19; i++) {
            if (i != 2) {
                if (!(f.get(i) instanceof Number number)) {
                    return null;
                }
                n[i] = number.intValue();
            }
        }
        BotNavigationGraph.EdgeType edgeType;
        try {
            edgeType = BotNavigationGraph.EdgeType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new BotNavigationGraph.Edge(n[0], n[1], edgeType, new Point(n[3], n[4]), new Point(n[5], n[6]),
                n[7], n[8], n[9], n[10], n[11], n[12], n[13], n[14], n[15], n[16], n[17], n[18]);
    }

    static String fileContent(BotNavigationGraph graph, List<BotNavigationGraph.Edge> edges) {
        List<Object> list = new ArrayList<>();
        for (BotNavigationGraph.Edge edge : edges) {
            list.add(toJson(edge));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", graph.version);
        json.put("format", FORMAT);
        json.put("signature", signature(graph));
        json.put("edges", list);
        return Json.write(json);
    }

    /** For the GM read-out. */
    public static String describe() {
        String gen = currentGeneration();
        return gen.isEmpty() ? "deep bake: none yet" : "deep bake: generation " + gen;
    }

    /** Test seam: another root (null restores the default) and a fresh generation check. */
    static void useRootForTest(Path testRoot) {
        root = testRoot == null ? defaultRoot() : testRoot;
        generation = null;
        nextCheckAt = 0;
    }
}
