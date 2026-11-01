package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Rope;
import soloMapling.server.BotShutdown;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;

import static soloMapling.DebugUtilities.debugprint;

// Ported from GreenCatMS. Credit: NutNNut.
final class BotNavigationGraphProvider {
    private static final Logger log = LoggerFactory.getLogger(BotNavigationGraphProvider.class);

    // 47: forbidFallDown footholds stay solid during down-jump grace (landing predictions
    //     change on maps with stacked platforms) — caches must rebuild.
    // 48: WZ info/fs reinterpreted as slipperiness (was a bogus ground-speed scale): walk
    //     step on fs maps jumps back to full speed, runways stretch by 1/fs.
    // 49: down-jumps capped at DOWN_JUMP_MAX_DROP_PX (client probes a bounded range below;
    //     Orbis-tower-style 860px down-jump edges must disappear).
    // 50: indexed ground lookups (findBelowIndexed) - tie-breaks between overlapping footholds
    //     can differ from the tree's traversal order (Ellinia: 2 of 5151 edges).
    // 54: client-true landings — touchdown halves carried momentum (packet fit), so slippery
    //     post-landing brake sims stop in ~1/4 the distance and previously-rejected icy hop
    //     edges (El Nath 267->277->278->279) become stable.
    // 55: disasm-true air control (CVecCtrl::CalcFloat @ 0x9b2c3c) — input only nudges vx
    //     inside an 8.93 x fs px/s band (no walkSpeed air cap; counter-strafe pins at the
    //     band edge) and no-input flight drags 1 x fs (100 x fs at terminal fall). Committed
    //     arcs still fly the launch key held, so constant-stepX arc sims stay exact.
    static final int GRAPH_VERSION = 70; // 51: kinetic slippery model + snowshoes; 52: brake-to-stop landings; 53: glide-unless-edge stop policy (slipperyStopDir); 56: uncap straight-drop launch windows (full droppable span, no +/-20 fragmentation); 57: remove the (empirically wrong) 300px down-jump drop cap - down-jumps fall until landing; 58: rope-grab reach counts descent below the ledge (mid-rope jump-grabs from adjacent platforms); 59: fall-sim caps to map height not 1500ms - long single-fall descents (tall shafts: Ellinia tree, Perion) now generate DROP/JUMP/ROPE edges; 60: re-cap drops for organic descent - walk-offs capped at MAX_DROP_PX, down-jumps at the tighter DOWN_JUMP_MAX_DROP_PX, and down-jumps carry DOWN_JUMP_COST_PENALTY_MS so the pathfinder prefers ropes/walk-offs over plummeting an entire vertical map; 61: widened rope top-exit probe (BotPhysicsEngine.findTopExitLanding) - accept a step-off foothold slightly above/below the rope top and a few px off-axis, so uneven/slanted ladder heads mint a clean CLIMB step-off edge instead of only ballistic top jump-offs; 68: nutnnut/Cosmic graph v60-v67 merged onto ours v60-v61 - their 66 (ground walk follows the standing foothold's prev/next chain across a joined fork), 67 (skip phantom cross-region JUMP/FLASH_JUMP edges landing on the source region's own ground), 62 (rope-exit/transfer CLIMB edges carry a Y launch window with per-endpoint cost), 60/61/64/65 (TELEPORT + FLASH_JUMP skill edges with X launch windows; teleport landing SSOT BotPhysicsEngine.teleportLanding, down-teleport to the furthest platform, y-snap 75), keeping our 60 drop caps and 61 widened rope top-exit; their 63 (baked portal route buckets) is not ported; 69: nutnnut/Cosmic v68+v69+v71 walk-off DROP fixes (credit NutNNut) - a directional walk-off's landing is authored with the executor's walk-off sim from the runway anchor on the source region's own foothold (not a lip-pixel fall), and only when every live launch state (sub-pixel phase, carry, arrival speed) lands the same region (stableWalkOffLanding); their v70 zMass walls are not ported; 70 (b109, dead-end pits): ledges narrower than two edge insets get an anchor at every pixel (wall-bound notches get their jump out), regions narrower than the walk-off runway walk off from their far end, and a portal with no ground under it serves the floor up to 100 px above it

    // Drop caps for organic descent (re-added; v57 had removed the old single cap). A bot must
    // never plummet down a whole vertical map. Two distinct downward moves, treated differently:
    //   - walk-off-a-cliff (directional drop, launchStepX != 0): organic, capped at MAX_DROP_PX.
    //   - down-jump-through-platform (straight drop, launchStepX == 0): the "new MapleStory" crouch
    //     drop that real players rarely chain. Kept only as a SHORT fallback for one-way platforms
    //     with no other way down - capped tighter (DOWN_JUMP_MAX_DROP_PX) and cost-penalized
    //     (DOWN_JUMP_COST_PENALTY_MS) so Dijkstra takes a rope or walk-off whenever one reaches the
    //     same place.
    static final int MAX_DROP_PX = 300;
    static final int DOWN_JUMP_MAX_DROP_PX = 200;
    static final int DOWN_JUMP_COST_PENALTY_MS = 2000;
    private static final int ENDPOINT_ANCHOR_SPACING_PX = 10;
    private static final int SAME_SOLID_NEST_GAP_PX = 8;
    private static final int ROPE_ANCHOR_INTERVAL_PX = 30;
    private static final int JUMP_POST_LANDING_STABILITY_TICKS = 3;
    private static final int MAX_PROFILED_JUMP_REGIONS = 5;
    private static final int FAST_WARMUP_MAX_FOOTHOLDS = 200;
    // Teleport skill edges (v60/v65, nutnnut/Cosmic. Credit: NutNNut). Bots are modelled at max teleport range
    // (150px, skill L20) regardless of their actual level - the graph isn't keyed by teleport level. Package-
    // visible: SSOT for teleport reach, shared with BotNavigationManager's execution.
    static final int TELEPORT_RANGE_PX = 150;
    static final int TELEPORT_Y_SNAP_PX = 75;  // horizontal teleport: vertical snap band to a platform
    static final int TELEPORT_COST_MS = 150;   // near-instant cast+recovery (tunable)
    // Base dir is overridable so tests never persist their (often trimmed) graphs into the live cache - a
    // trimmed map at base profile would otherwise overwrite the real graph and strand bots with region=-1.
    // Surefire points -Dbot.nav.cacheDir at cache/bot-nav-test; prod uses the default. (After nutnnut/Cosmic
    // 6c8896f98, AGPL-3.0. Credit: NutNNut.)
    private static final Path CACHE_BASE_DIR = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"));

    /** The graph cache folder name: the graph version plus the graph-affecting NN-011 switches that are on
     *  (NavPortSwitches.graphTag), so a graph built with one of them on is never served with it off. */
    static String cacheFolderName() {
        return "v" + GRAPH_VERSION + NavPortSwitches.graphTag();
    }

    private static Path cacheDir() {
        return CACHE_BASE_DIR.resolve(cacheFolderName());
    }
    // Bounded LRU (ticket 48): never drops a graph of a map someone is on; evicted graphs reload from cacheDir().
    static final NavGraphCache<GraphCacheKey> GRAPHS = new NavGraphCache<>(GraphCacheKey::mapId,
            key -> Files.isRegularFile(graphFile(key)), NavGraphCache::occupiedMapIds,
            NavGraphCache.DEFAULT_MAX_ENTRIES, NavGraphCache.DEFAULT_MAX_BYTES, NavGraphCache.DEFAULT_MIN_IDLE_MS);
    private static final Map<GraphCacheKey, CompletableFuture<BotNavigationGraph>> PENDING_GRAPHS = new ConcurrentHashMap<>();
    private static final Map<GraphCacheKey, GraphBuildReport> LAST_BUILD_REPORTS = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<Integer>> COLLIDABLE_WALL_IDS_BY_MAP_ID = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<Integer>> COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID = new ConcurrentHashMap<>();
    private static final ThreadLocal<BuildProfileBuilder> ACTIVE_BUILD_PROFILE = new ThreadLocal<>();
    private static final ThreadLocal<SampleDensity> ACTIVE_DENSITY = new ThreadLocal<>();
    private static final ExecutorService GRAPH_WARMUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bot-nav-graph-warmup");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService FAST_GRAPH_WARMUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bot-nav-graph-warmup-fast");
        thread.setDaemon(true);
        return thread;
    });

    record GraphCacheKey(int mapId, int totalSpeedStat, int totalJumpStat, boolean snowShoes) {
        static GraphCacheKey from(int mapId, BotMovementProfile profile) {
            BotMovementProfile effective = profile == null ? BotMovementProfile.base() : profile;
            return new GraphCacheKey(mapId, effective.totalSpeedStat(), effective.totalJumpStat(),
                    effective.snowShoes());
        }
    }

    /*
     * How densely the builder samples launch points. LIVE is what every boot and warm-up builds - sized so a
     * cold map builds in about a second. DEEP is the nightly deep bake (NavDeepBake): it runs off the game
     * server, samples platforms every couple of pixels and ropes every 10px, and keeps only the edges the LIVE
     * graph missed (NavDeepOverlay). Region ids do not depend on the density, so DEEP edges fit the LIVE graph.
     * interiorSpacingPx 0 means one walk step.
     */
    record SampleDensity(int interiorSpacingPx, int endpointSpacingPx, int ropeAnchorIntervalPx) {
        static final SampleDensity LIVE = new SampleDensity(0, ENDPOINT_ANCHOR_SPACING_PX, ROPE_ANCHOR_INTERVAL_PX);
        static final SampleDensity DEEP = new SampleDensity(2, 2, 10);
    }

    private static SampleDensity density() {
        SampleDensity density = ACTIVE_DENSITY.get();
        return density != null ? density : SampleDensity.LIVE;
    }

    /* Snowshoes only change physics on slippery ground (fs < 1): strip the flag everywhere
     *  else so a snowshoe-wearing bot shares the normal map graphs instead of duplicating them. */
    private static BotMovementProfile canonicalProfile(MapleMap map, BotMovementProfile profile) {
        if (profile == null || !profile.snowShoes() || BotPhysicsEngine.slipperyGround(map)) {
            return profile;
        }
        return new BotMovementProfile(profile.totalSpeedStat(), profile.totalJumpStat(), false);
    }

    static final class GraphBuildReport {
        final long buildAnchorPointsNs;
        final int mapId;
        final int totalSpeedStat;
        final int totalJumpStat;
        final int footholdCount;
        final int walkableFootholdCount;
        final int ropeCount;
        final int regionCount;
        final int totalEdgeCount;
        final int walkEdgeCount;
        final int jumpEdgeCount;
        final int dropEdgeCount;
        final int climbEdgeCount;
        final int portalEdgeCount;
        final long collectFootholdsNs;
        final long buildRegionsNs;
        final long addRopeRegionsNs;
        final long buildFeatureXsNs;
        final long buildWalkEdgesNs;
        final long buildDropEdgesNs;
        final long buildJumpEdgesNs;
        final long buildRopeEntryEdgesNs;
        final long buildRopeExitEdgesNs;
        final long buildPortalEdgesNs;
        final long totalBuildNs;
        final long jumpSampleCount;
        final long jumpCacheHitCount;
        final long jumpCacheMissCount;
        final long jumpBoundaryRefineProbeCount;
        final List<JumpRegionProfile> slowestJumpRegions;

        GraphBuildReport(int mapId,
                         int totalSpeedStat,
                         int totalJumpStat,
                         int footholdCount,
                         int walkableFootholdCount,
                         int ropeCount,
                         int regionCount,
                         int totalEdgeCount,
                         int walkEdgeCount,
                         int jumpEdgeCount,
                         int dropEdgeCount,
                         int climbEdgeCount,
                         int portalEdgeCount,
                         long buildAnchorPointsNs,
                         long collectFootholdsNs,
                         long buildRegionsNs,
                         long addRopeRegionsNs,
                         long buildFeatureXsNs,
                         long buildWalkEdgesNs,
                         long buildDropEdgesNs,
                         long buildJumpEdgesNs,
                         long buildRopeEntryEdgesNs,
                         long buildRopeExitEdgesNs,
                         long buildPortalEdgesNs,
                         long totalBuildNs,
                         long jumpSampleCount,
                         long jumpCacheHitCount,
                         long jumpCacheMissCount,
                         long jumpBoundaryRefineProbeCount,
                         List<JumpRegionProfile> slowestJumpRegions) {
            this.buildAnchorPointsNs = buildAnchorPointsNs;
            this.mapId = mapId;
            this.totalSpeedStat = totalSpeedStat;
            this.totalJumpStat = totalJumpStat;
            this.footholdCount = footholdCount;
            this.walkableFootholdCount = walkableFootholdCount;
            this.ropeCount = ropeCount;
            this.regionCount = regionCount;
            this.totalEdgeCount = totalEdgeCount;
            this.walkEdgeCount = walkEdgeCount;
            this.jumpEdgeCount = jumpEdgeCount;
            this.dropEdgeCount = dropEdgeCount;
            this.climbEdgeCount = climbEdgeCount;
            this.portalEdgeCount = portalEdgeCount;
            this.collectFootholdsNs = collectFootholdsNs;
            this.buildRegionsNs = buildRegionsNs;
            this.addRopeRegionsNs = addRopeRegionsNs;
            this.buildFeatureXsNs = buildFeatureXsNs;
            this.buildWalkEdgesNs = buildWalkEdgesNs;
            this.buildDropEdgesNs = buildDropEdgesNs;
            this.buildJumpEdgesNs = buildJumpEdgesNs;
            this.buildRopeEntryEdgesNs = buildRopeEntryEdgesNs;
            this.buildRopeExitEdgesNs = buildRopeExitEdgesNs;
            this.buildPortalEdgesNs = buildPortalEdgesNs;
            this.totalBuildNs = totalBuildNs;
            this.jumpSampleCount = jumpSampleCount;
            this.jumpCacheHitCount = jumpCacheHitCount;
            this.jumpCacheMissCount = jumpCacheMissCount;
            this.jumpBoundaryRefineProbeCount = jumpBoundaryRefineProbeCount;
            this.slowestJumpRegions = new ArrayList<>(slowestJumpRegions);
        }
    }

    record JumpRegionProfile(int regionId,
                             int width,
                             int sampleCount,
                             int edgeCount,
                             int cacheHits,
                             int cacheMisses,
                             long elapsedNs) {
    }

    private static final class BuildProfileBuilder {
        private long buildAnchorPointsNs;
        private final int mapId;
        private final int totalSpeedStat;
        private final int totalJumpStat;
        private final long buildStartedAtNs = System.nanoTime();
        private int footholdCount;
        private int walkableFootholdCount;
        private int ropeCount;
        private int regionCount;
        private int totalEdgeCount;
        private int walkEdgeCount;
        private int jumpEdgeCount;
        private int dropEdgeCount;
        private int climbEdgeCount;
        private int portalEdgeCount;
        private long collectFootholdsNs;
        private long buildRegionsNs;
        private long addRopeRegionsNs;
        private long buildFeatureXsNs;
        private long buildWalkEdgesNs;
        private long buildDropEdgesNs;
        private long buildJumpEdgesNs;
        private long buildRopeEntryEdgesNs;
        private long buildRopeExitEdgesNs;
        private long buildPortalEdgesNs;
        private long jumpSampleCount;
        private long jumpCacheHitCount;
        private long jumpCacheMissCount;
        private long jumpBoundaryRefineProbeCount;
        private final List<JumpRegionProfile> slowestJumpRegions = new ArrayList<>();

        private BuildProfileBuilder(int mapId, BotMovementProfile movementProfile) {
            this.mapId = mapId;
            this.totalSpeedStat = movementProfile.totalSpeedStat();
            this.totalJumpStat = movementProfile.totalJumpStat();
        }

        private void recordEdge(BotNavigationGraph.EdgeType type) {
            totalEdgeCount++;
            switch (type) {
                case WALK -> walkEdgeCount++;
                case JUMP -> jumpEdgeCount++;
                case DROP -> dropEdgeCount++;
                case CLIMB -> climbEdgeCount++;
                case PORTAL -> portalEdgeCount++;
                case TELEPORT, FLASH_JUMP -> { } // counted in totalEdgeCount only
            }
        }

        private void recordJumpSample(boolean cacheHit) {
            jumpSampleCount++;
            if (cacheHit) {
                jumpCacheHitCount++;
            } else {
                jumpCacheMissCount++;
            }
        }

        private void recordJumpBoundaryRefineProbe() {
            jumpBoundaryRefineProbeCount++;
        }

        private void recordJumpRegion(JumpRegionProfile profile) {
            slowestJumpRegions.add(profile);
            slowestJumpRegions.sort(Comparator.comparingLong(JumpRegionProfile::elapsedNs).reversed());
            if (slowestJumpRegions.size() > MAX_PROFILED_JUMP_REGIONS) {
                slowestJumpRegions.removeLast();
            }
        }

        private GraphBuildReport finish() {
            return new GraphBuildReport(
                    mapId,
                    totalSpeedStat,
                    totalJumpStat,
                    footholdCount,
                    walkableFootholdCount,
                    ropeCount,
                    regionCount,
                    totalEdgeCount,
                    walkEdgeCount,
                    jumpEdgeCount,
                    dropEdgeCount,
                    climbEdgeCount,
                    portalEdgeCount,
                    buildAnchorPointsNs,
                    collectFootholdsNs,
                    buildRegionsNs,
                    addRopeRegionsNs,
                    buildFeatureXsNs,
                    buildWalkEdgesNs,
                    buildDropEdgesNs,
                    buildJumpEdgesNs,
                    buildRopeEntryEdgesNs,
                    buildRopeExitEdgesNs,
                    buildPortalEdgesNs,
                    System.nanoTime() - buildStartedAtNs,
                    jumpSampleCount,
                    jumpCacheHitCount,
                    jumpCacheMissCount,
                    jumpBoundaryRefineProbeCount,
                    slowestJumpRegions);
        }
    }

    private record JumpLaunchWindow(int minX, int maxX, Point startPoint, Point endPoint, int landingTimeMs) {
    }

    private static final class JumpBuildStats {
        int sampleCount;
        int edgeCount;
        int cacheHits;
        int cacheMisses;
    }

    private record JumpLandingKey(int x, int y, int launchStepX) {
    }

    private static final class JumpLandingCache {
        private final Map<JumpLandingKey, BotPhysicsEngine.PostLandingJump> hits = new HashMap<>();
        private final Set<JumpLandingKey> misses = new HashSet<>();
    }

    private static final class FlashJumpLandingCache {
        private final Map<JumpLandingKey, BotPhysicsEngine.JumpLanding> hits = new HashMap<>();
        private final Set<JumpLandingKey> misses = new HashSet<>();
    }

    private record RopeGrabKey(int x, int y, int launchStepX, int ropeX, int ropeTopY, int ropeBottomY) {
    }

    private static final class RopeGrabCache {
        private final Map<RopeGrabKey, Point> hits = new HashMap<>();
        private final Set<RopeGrabKey> misses = new HashSet<>();
    }

    static BotNavigationGraph getGraph(MapleMap map) {
        return getGraph(map, BotMovementProfile.base());
    }

    static BotNavigationGraph getGraph(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return null;
        }
        movementProfile = canonicalProfile(map, movementProfile);
        GraphCacheKey key = GraphCacheKey.from(map.getId(), movementProfile);
        BotNavigationGraph cached = GRAPHS.get(key);
        if (cached != null) {
            return cached;
        }
        return getOrStartGraphLoad(map, movementProfile, key, false).join();
    }

    /* Returns the cached graph without triggering a build. */
    static BotNavigationGraph peekGraph(MapleMap map) {
        if (map == null) {
            return null;
        }
        List<Map.Entry<GraphCacheKey, BotNavigationGraph>> cached = GRAPHS.forMap(map.getId());
        return cached.isEmpty() ? null : cached.get(0).getValue();
    }

    /* Returns the cached graph for the requested profile without triggering a build. */
    static BotNavigationGraph peekGraph(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return null;
        }
        return GRAPHS.get(GraphCacheKey.from(map.getId(), canonicalProfile(map, movementProfile)));
    }

    /* Returns the closest cached graph for this map when the exact profile graph is unavailable. */
    static BotNavigationGraph peekClosestGraph(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return null;
        }

        GraphCacheKey requested = GraphCacheKey.from(map.getId(), canonicalProfile(map, movementProfile));
        BotNavigationGraph bestGraph = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<GraphCacheKey, BotNavigationGraph> entry : GRAPHS.forMap(requested.mapId())) {
            GraphCacheKey key = entry.getKey();
            if (key.mapId() != requested.mapId()) {
                continue;
            }

            // A slip/no-slip mismatch is structurally wrong (different runway/glide physics) —
            // dominate any speed/jump distance, but stay a usable last-resort fallback.
            int distance = Math.abs(key.totalSpeedStat() - requested.totalSpeedStat())
                    + Math.abs(key.totalJumpStat() - requested.totalJumpStat())
                    + (key.snowShoes() != requested.snowShoes() ? 1_000 : 0);
            if (bestGraph == null || distance < bestDistance) {
                bestGraph = entry.getValue();
                bestDistance = distance;
            }
        }
        return bestGraph;
    }

    /*
     * Best cached graph for the requested profile: the exact-profile graph if cached, otherwise the
     * closest cached profile for this map (by speed/jump distance), or null if none cached.
     * Single source of truth for "which cached graph should a bot of this profile navigate against",
     * replacing the bare .peekGraph(MapleMap) arbitrary-first-entry pick at profile-aware
     * call sites and the open-coded exact-then-closest fallback duplicated across callers.
     */
    static BotNavigationGraph peekBestGraph(MapleMap map, BotMovementProfile movementProfile) {
        BotNavigationGraph exact = peekGraph(map, movementProfile);
        return exact != null ? exact : peekClosestGraph(map, movementProfile);
    }

    /*
     * Ticket 77: no warm-up work once shutdown starts. The gate is the same one the other GCMoveSystem
     * classes check; the maps are torn down right after it closes and null their footholds
     * (MapleMap.dispose), so a build started then would only log an NPE.
     */
    static boolean warmUpAllowed() {
        return !BotShutdown.isQuiesced();
    }

    /*
     * The footholds a warm-up may scan right now, or null when the map is gone: shutdown has started, or
     * MapleMap.dispose already nulled them. Callers take this snapshot once and work from it, so a dispose
     * that lands between a null check and the scan cannot NPE them.
     */
    static FootholdTree footholdsForWarmUp(MapleMap map) {
        if (map == null || !warmUpAllowed()) {
            return null;
        }
        return map.getFootholds();
    }

    static void warmGraphAsync(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null || !warmUpAllowed()) {
            return;
        }
        movementProfile = canonicalProfile(map, movementProfile);
        GraphCacheKey key = GraphCacheKey.from(map.getId(), movementProfile);
        if (GRAPHS.containsKey(key)) {
            return;
        }
        getOrStartGraphLoad(map, movementProfile, key, true);
    }

    /*
     * Route-ahead prewarm for multi-hop travel: load each map and queue its graph warmup, so
     * the bot doesn't land in graph-warmup fallback at every hop. MapManager.getMap
     * itself loads the map from WZ on first touch — heavy, hence the whole loop runs on the
     * warmup executor, never on a bot tick thread.
     */
    static void warmGraphsForRouteAsync(MapManager mapFactory, List<Integer> mapIds,
                                        BotMovementProfile movementProfile) {
        if (!warmUpAllowed()) {
            return;
        }
        GRAPH_WARMUP_EXECUTOR.execute(() -> {
            for (int mapId : mapIds) {
                if (!warmUpAllowed()) {
                    return; // shutdown started: stop loading maps for a route nobody will walk
                }
                try {
                    warmGraphAsync(mapFactory.getMap(mapId), movementProfile);
                } catch (RuntimeException e) {
                    log.warn("Route prewarm failed for map {}", mapId, e);
                }
            }
        });
    }

    /*
     * Loads the map's cached graph for this profile off the caller's thread, from memory or the disk cache only:
     * never builds one (a player wandering into an unbaked map must not cost a graph build). Completes with null
     * when no cached file exists. For NavObservedEdges, which needs the base graph of maps no bot is on.
     */
    static CompletableFuture<BotNavigationGraph> loadCachedGraphAsync(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return CompletableFuture.completedFuture(null);
        }
        BotMovementProfile profile = canonicalProfile(map, movementProfile);
        GraphCacheKey key = GraphCacheKey.from(map.getId(), profile);
        BotNavigationGraph cached = GRAPHS.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        if (!Files.isRegularFile(graphFile(key))) {
            return CompletableFuture.completedFuture(null);
        }
        return getOrStartGraphLoad(map, profile, key, true);
    }

    /* Builds a graph in memory only - not cached, not saved (the offline replay on a map with no baked graph). */
    static BotNavigationGraph buildUncachedGraph(MapleMap map, BotMovementProfile movementProfile) {
        return buildGraph(map, movementProfile);
    }

    static BotNavigationGraph rebuildGraph(MapleMap map) {
        return rebuildGraph(map, BotMovementProfile.base());
    }

    static BotNavigationGraph rebuildGraph(MapleMap map, BotMovementProfile movementProfile) {
        GraphCacheKey key = GraphCacheKey.from(map.getId(), movementProfile);
        BotNavigationGraph rebuilt = buildGraph(map, movementProfile);
        if (rebuilt == null) {
            return null; // shutdown started or the map unloaded: leave the cached graph alone
        }
        GRAPHS.put(key, rebuilt);
        CompletableFuture<BotNavigationGraph> pending = PENDING_GRAPHS.remove(key);
        if (pending != null) {
            pending.complete(rebuilt);
        }
        saveGraph(rebuilt);
        return rebuilt;
    }

    /*
     * Builds the map's graph at another sample density, for the nightly deep bake. Nothing is cached or saved:
     * the caller keeps only what the live graph lacks.
     */
    static BotNavigationGraph buildGraphWithDensity(MapleMap map, BotMovementProfile movementProfile,
                                                    SampleDensity density) {
        ACTIVE_DENSITY.set(density);
        try {
            return buildGraph(map, movementProfile);
        } finally {
            ACTIVE_DENSITY.remove();
        }
    }

    private static CompletableFuture<BotNavigationGraph> getOrStartGraphLoad(MapleMap map,
                                                                             BotMovementProfile movementProfile,
                                                                             GraphCacheKey key,
                                                                             boolean async) {
        BotNavigationGraph cached = GRAPHS.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<BotNavigationGraph> existing = PENDING_GRAPHS.get(key);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<BotNavigationGraph> future = new CompletableFuture<>();
        CompletableFuture<BotNavigationGraph> race = PENDING_GRAPHS.putIfAbsent(key, future);
        if (race != null) {
            return race;
        }

        Runnable task = () -> {
            try {
                if (!warmUpAllowed()) {
                    future.complete(null); // shutdown closed the gate after this task was queued
                    return;
                }
                BotNavigationGraph graph = loadOrBuildGraph(map, movementProfile, key);
                if (graph == null) {
                    future.complete(null); // the map unloaded mid-warm-up: abandoned, not a failure
                    return;
                }
                GRAPHS.put(key, graph);
                future.complete(graph);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                log.warn("Failed to warm bot nav graph for map {} speed={} jump={}",
                        key.mapId(), key.totalSpeedStat(), key.totalJumpStat(), t);
            } finally {
                PENDING_GRAPHS.remove(key, future);
            }
        };

        if (async) {
            selectWarmupExecutor(map).execute(task);
        } else {
            task.run();
        }
        return future;
    }

    private static ExecutorService selectWarmupExecutor(MapleMap map) {
        return isFastWarmupCandidate(map) ? FAST_GRAPH_WARMUP_EXECUTOR : GRAPH_WARMUP_EXECUTOR;
    }

    private static boolean isFastWarmupCandidate(MapleMap map) {
        FootholdTree footholds = footholdsForWarmUp(map);
        return footholds != null
                && footholds.getAllFootholds().size() <= FAST_WARMUP_MAX_FOOTHOLDS;
    }

    private static BotNavigationGraph loadOrBuildGraph(MapleMap map,
                                                       BotMovementProfile movementProfile,
                                                       GraphCacheKey key) {
        BotNavigationGraph cached = loadGraph(key);
        if (cached != null) {
            return cached;
        }

        BotNavigationGraph built = buildGraph(map, movementProfile);
        if (built == null) {
            return null; // the map unloaded (or shutdown started) mid-warm-up: nothing to save
        }
        saveGraph(built);
        return built;
    }

    static BotNavigationGraph loadGraph(GraphCacheKey key) {
        Path file = graphFile(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            Object loaded = in.readObject();
            if (!(loaded instanceof BotNavigationGraph graph)) {
                return null;
            }
            if (!isGraphFor(graph, key)) {
                return null;
            }
            seedCachedFootholdCollisionIds(graph);
            return graph;
        } catch (IOException | ClassNotFoundException e) {
            log.debug("Failed to load bot nav graph cache for map {} speed={} jump={}",
                    key.mapId(), key.totalSpeedStat(), key.totalJumpStat(), e);
            return null;
        }
    }

    /*
     * Written to a temporary file and moved into place, so a server loading the graph while the nightly bake (or
     * another server thread) saves it never reads half a file.
     */
    static void saveGraph(BotNavigationGraph graph) {
        try {
            Path cacheDir = cacheDir();
            Files.createDirectories(cacheDir);
            Path file = graphFile(GraphCacheKey.from(graph.mapId, graph.movementProfile));
            Path tmp = Files.createTempFile(cacheDir, file.getFileName().toString(), ".tmp");
            try {
                try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                    out.writeObject(graph);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            log.debug("Failed to save bot nav graph cache for map {}", graph.mapId, e);
        }
    }

    /**
     * The cache file of one key. Snowshoes get their own suffix: a snowshoe graph only survives
     * {@link #canonicalProfile} on slippery maps, where it differs from the plain graph, and the two used to
     * share one file - whichever was saved last was loaded for both. The plain name is unchanged, so existing
     * plain files stay valid; a snowshoe graph left under a plain name is refused by {@link #isGraphFor}.
     */
    static Path graphFile(GraphCacheKey key) {
        return cacheDir().resolve(key.mapId() + "-s" + key.totalSpeedStat() + "-j" + key.totalJumpStat()
                + (key.snowShoes() ? "-ss" : "") + ".bin");
    }

    /** Whether a loaded graph was built for exactly this key, under the current graph version. */
    static boolean isGraphFor(BotNavigationGraph graph, GraphCacheKey key) {
        return graph.version == GRAPH_VERSION
                && graph.mapId == key.mapId()
                && graph.movementProfile.totalSpeedStat() == key.totalSpeedStat()
                && graph.movementProfile.totalJumpStat() == key.totalJumpStat()
                && graph.movementProfile.snowShoes() == key.snowShoes();
    }

    private static BotNavigationGraph buildGraph(MapleMap map, BotMovementProfile movementProfile) {
        FootholdTree footholdsTree = footholdsForWarmUp(map);
        if (footholdsTree == null) {
            log.debug("Bot nav graph build skipped for map {}: shutdown started or the map unloaded",
                    map == null ? -1 : map.getId());
            return null;
        }
        movementProfile = movementProfile == null ? BotMovementProfile.base() : movementProfile;
        BuildProfileBuilder buildProfile = new BuildProfileBuilder(map.getId(), movementProfile);
        ACTIVE_BUILD_PROFILE.set(buildProfile);
        try {
            List<Foothold> footholds = footholdsTree.getAllFootholds();
            Map<Integer, Foothold> footholdsById = new HashMap<>();
            List<Foothold> walkableFootholds = new ArrayList<>();
            long phaseStartedAt = System.nanoTime();
            Set<Integer> collidableWallIds = new HashSet<>();
            Set<Integer> collidableFromBelowIds;
            for (Foothold foothold : footholds) {
                footholdsById.put(foothold.getId(), foothold);
                if (!foothold.isWall()) {
                    walkableFootholds.add(foothold);
                }
            }
            collidableFromBelowIds = classifyCollidableFromBelowFootholds(footholdsById);
            for (Foothold foothold : footholds) {
                if (Foothold.isCollidableWall(foothold, footholdsById)) {
                    collidableWallIds.add(foothold.getId());
                }
            }
            buildProfile.collectFootholdsNs = System.nanoTime() - phaseStartedAt;
            buildProfile.footholdCount = footholds.size();
            buildProfile.walkableFootholdCount = walkableFootholds.size();
            buildProfile.ropeCount = map.getRopes().size();
            COLLIDABLE_WALL_IDS_BY_MAP_ID.put(map.getId(), new HashSet<>(collidableWallIds));
            COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.put(map.getId(), new HashSet<>(collidableFromBelowIds));

            List<BotNavigationGraph.Region> regions = new ArrayList<>();
            Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
            Map<Integer, Integer> regionIdByFootholdId = new HashMap<>();
            phaseStartedAt = System.nanoTime();
            buildRegions(walkableFootholds, footholdsById, collidableFromBelowIds, regions, regionsById, regionIdByFootholdId);
            buildProfile.buildRegionsNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            int nextRegionId = regions.stream().mapToInt(r -> r.id).max().orElse(0) + 1;
            for (Rope rope : map.getRopes()) {
                BotNavigationGraph.Region ropeRegion = new BotNavigationGraph.Region(
                        nextRegionId++, rope.x(), rope.topY(), rope.bottomY(), rope.isLadder());
                regions.add(ropeRegion);
                regionsById.put(ropeRegion.id, ropeRegion);
            }
            buildProfile.addRopeRegionsNs = System.nanoTime() - phaseStartedAt;
            buildProfile.regionCount = regions.size();

            phaseStartedAt = System.nanoTime();
            Map<Integer, List<Integer>> featureXsByRegionId = buildFeatureXsByRegionId(map, regions, regionIdByFootholdId);
            buildProfile.buildFeatureXsNs = System.nanoTime() - phaseStartedAt;
            phaseStartedAt = System.nanoTime();
            Map<Integer, List<Point>> anchorsByRegionId = buildAnchorsByRegionId(map, regions, featureXsByRegionId, movementProfile);
            buildProfile.buildAnchorPointsNs = System.nanoTime() - phaseStartedAt;
            List<BotNavigationGraph.Region> groundRegions = new ArrayList<>();
            List<BotNavigationGraph.Region> ropeRegions = new ArrayList<>();
            for (BotNavigationGraph.Region region : regions) {
                if (region.isRopeRegion) {
                    ropeRegions.add(region);
                } else {
                    groundRegions.add(region);
                }
            }
            Map<Integer, Rope> ropeByRegionId = buildRopeByRegionId(map, ropeRegions);
            Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
            Set<String> edgeKeys = new HashSet<>();
            JumpLandingCache jumpLandingCache = new JumpLandingCache();
            RopeGrabCache ropeGrabCache = new RopeGrabCache();

            BotPhysicsEngine.setBuildWalkRegionLookup(map, regionsById, regionIdByFootholdId, footholdsById);

            phaseStartedAt = System.nanoTime();
            for (Foothold foothold : walkableFootholds) {
                addWalkEdges(foothold, footholdsById, regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
            }
            buildProfile.buildWalkEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : groundRegions) {
                addDropEdges(region, map, regionsById, regionIdByFootholdId,
                        anchorsByRegionId.getOrDefault(region.id, List.of()), outgoing, edgeKeys, movementProfile);
            }
            buildProfile.buildDropEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : groundRegions) {
                addJumpEdges(region, map, regionsById, regionIdByFootholdId,
                        anchorsByRegionId.getOrDefault(region.id, List.of()), outgoing, edgeKeys, jumpLandingCache, movementProfile);
            }
            // Skill edges (teleport / flash jump): baked into the shared graph, filtered per bot at plan time
            // (BotNavigationManager / NavMovementSkills). Cross-region only. Counted in the jump phase.
            FlashJumpLandingCache flashJumpLandingCache = new FlashJumpLandingCache();
            for (BotNavigationGraph.Region region : groundRegions) {
                List<Point> regionAnchors = anchorsByRegionId.getOrDefault(region.id, List.of());
                addTeleportEdges(region, map, regionsById, regionIdByFootholdId, regionAnchors, outgoing, edgeKeys);
                addFlashJumpEdges(region, map, regionsById, regionIdByFootholdId, regionAnchors, outgoing, edgeKeys,
                        jumpLandingCache, flashJumpLandingCache, movementProfile);
            }
            buildProfile.buildJumpEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : ropeRegions) {
                addRopeEntryEdges(region, groundRegions, ropeByRegionId, map, anchorsByRegionId,
                        outgoing, edgeKeys, ropeGrabCache, movementProfile);
            }
            buildProfile.buildRopeEntryEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : ropeRegions) {
                addRopeExitEdges(region, ropeRegions, ropeByRegionId, map, regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
            }
            buildProfile.buildRopeExitEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (Portal portal : map.getPortals()) {
                addPortalEdges(portal, map, regionsById, regionIdByFootholdId, outgoing, edgeKeys);
            }
            buildProfile.buildPortalEdgesNs = System.nanoTime() - phaseStartedAt;

            BotNavigationGraph graph = new BotNavigationGraph(
                    map.getId(), GRAPH_VERSION, movementProfile, regions, regionsById, regionIdByFootholdId, outgoing,
                    collidableWallIds, collidableFromBelowIds);
            GraphBuildReport report = buildProfile.finish();
            LAST_BUILD_REPORTS.put(GraphCacheKey.from(map.getId(), movementProfile), report);
            debugprint("Built bot nav graph map {} speed={} jump={} in {} ms (regions={}, edges={}, drop={} ms, jump={} ms, jumpSamples={}, cacheHits={})",
                    map.getId(),
                    movementProfile.totalSpeedStat(),
                    movementProfile.totalJumpStat(),
                    String.format("%.2f", report.totalBuildNs / 1_000_000.0),
                    report.regionCount,
                    report.totalEdgeCount,
                    String.format("%.2f", report.buildDropEdgesNs / 1_000_000.0),
                    String.format("%.2f", report.buildJumpEdgesNs / 1_000_000.0),
                    report.jumpSampleCount,
                    report.jumpCacheHitCount);
            return graph;
        } finally {
            BotPhysicsEngine.clearBuildWalkRegionLookup();
            ACTIVE_BUILD_PROFILE.remove();
        }
    }

    static GraphBuildReport getLastBuildReport(int mapId) {
        return getLastBuildReport(mapId, BotMovementProfile.base());
    }

    static GraphBuildReport getLastBuildReport(int mapId, BotMovementProfile movementProfile) {
        return LAST_BUILD_REPORTS.get(GraphCacheKey.from(mapId, movementProfile));
    }

    static Set<Integer> getCachedCollidableWallIds(int mapId) {
        return COLLIDABLE_WALL_IDS_BY_MAP_ID.get(mapId);
    }

    static Set<Integer> getCachedCollidableFromBelowIds(int mapId) {
        return COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.get(mapId);
    }

    static Set<Integer> computeCollidableFromBelowIds(MapleMap map) {
        if (map == null || map.getFootholds() == null) {
            return Set.of();
        }
        Set<Integer> cached = COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.get(map.getId());
        if (cached != null) {
            return cached;
        }
        Map<Integer, Foothold> footholdsById = new HashMap<>();
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            footholdsById.put(foothold.getId(), foothold);
        }
        Set<Integer> computed = classifyCollidableFromBelowFootholds(footholdsById);
        COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.put(map.getId(), new HashSet<>(computed));
        return computed;
    }

    private static void seedCachedFootholdCollisionIds(BotNavigationGraph graph) {
        COLLIDABLE_WALL_IDS_BY_MAP_ID.put(graph.mapId, new HashSet<>(graph.collidableWallIds));
        COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.put(graph.mapId, new HashSet<>(graph.collidableFromBelowIds));
    }

    static Set<Integer> classifyCollidableFromBelowFootholds(Map<Integer, Foothold> footholdsById) {
        List<ClassifiedLoop> loops = classifyClosedLoops(buildClosedLoops(footholdsById));
        if (loops.isEmpty()) {
            return Set.of();
        }

        Set<Integer> result = new HashSet<>();
        for (ClassifiedLoop classifiedLoop : loops) {
            if (!classifiedLoop.solid()) {
                continue;
            }

            ClosedLoop loop = classifiedLoop.loop();
            for (int footholdId : loop.footholdIds()) {
                Foothold foothold = footholdsById.get(footholdId);
                if (foothold == null || foothold.isWall() || foothold.getY1() != foothold.getY2()) {
                    continue;
                }

                double midX = (foothold.getX1() + foothold.getX2()) / 2.0;
                double y = foothold.getY1();
                boolean insideAbove = isPointInLoop(loop, midX, y - 1.0);
                boolean insideBelow = isPointInLoop(loop, midX, y + 1.0);
                if (insideAbove && !insideBelow) {
                    result.add(foothold.getId());
                }
            }
        }
        return result;
    }

    private record ClosedLoop(int[] footholdIds, double[] xs, double[] ys, double minX, double maxX, double minY, double maxY) {}

    private record ClassifiedLoop(ClosedLoop loop, int depth, boolean solid) {}

    private static List<ClosedLoop> buildClosedLoops(Map<Integer, Foothold> footholdsById) {
        List<ClosedLoop> loops = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        for (Foothold start : footholdsById.values()) {
            if (visited.contains(start.getId())) {
                continue;
            }

            List<Foothold> chain = new ArrayList<>();
            Set<Integer> seenInChain = new HashSet<>();
            Foothold current = start;
            boolean closed = false;
            while (current != null && seenInChain.add(current.getId()) && chain.size() <= footholdsById.size()) {
                chain.add(current);
                Foothold next = footholdsById.get(current.getNext());
                if (next != null && next.getId() == start.getId()) {
                    closed = true;
                    break;
                }
                current = next;
            }

            if (!closed || chain.size() < 3 || !isEndpointConnectedLoop(chain)) {
                continue;
            }

            visited.addAll(seenInChain);
            loops.add(toClosedLoop(chain));
        }
        return loops;
    }

    private static boolean isEndpointConnectedLoop(List<Foothold> chain) {
        for (int i = 0; i < chain.size(); i++) {
            Foothold current = chain.get(i);
            Foothold next = chain.get((i + 1) % chain.size());
            if (current.getX2() != next.getX1() || current.getY2() != next.getY1()) {
                return false;
            }
        }
        return true;
    }

    private static ClosedLoop toClosedLoop(List<Foothold> chain) {
        int size = chain.size();
        int[] footholdIds = new int[size];
        double[] xs = new double[size];
        double[] ys = new double[size];
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            Foothold foothold = chain.get(i);
            footholdIds[i] = foothold.getId();
            xs[i] = foothold.getX1();
            ys[i] = foothold.getY1();
            minX = Math.min(minX, Math.min(foothold.getX1(), foothold.getX2()));
            maxX = Math.max(maxX, Math.max(foothold.getX1(), foothold.getX2()));
            minY = Math.min(minY, Math.min(foothold.getY1(), foothold.getY2()));
            maxY = Math.max(maxY, Math.max(foothold.getY1(), foothold.getY2()));
        }
        return new ClosedLoop(footholdIds, xs, ys, minX, maxX, minY, maxY);
    }

    private static List<ClassifiedLoop> classifyClosedLoops(List<ClosedLoop> loops) {
        List<ClosedLoop> ordered = new ArrayList<>(loops);
        ordered.sort(Comparator.comparingDouble(BotNavigationGraphProvider::loopBoundingArea).reversed());

        List<ClassifiedLoop> classified = new ArrayList<>(ordered.size());
        for (ClosedLoop loop : ordered) {
            ClassifiedLoop parent = null;
            double parentArea = Double.POSITIVE_INFINITY;
            for (ClassifiedLoop candidate : classified) {
                if (loopContainsLoop(candidate.loop(), loop)) {
                    double area = loopBoundingArea(candidate.loop());
                    if (area < parentArea) {
                        parent = candidate;
                        parentArea = area;
                    }
                }
            }

            int depth = parent == null ? 0 : parent.depth() + 1;
            boolean solid = parent == null
                    || isThinNestedShell(parent.loop(), loop)
                    ? parent == null || parent.solid()
                    : !parent.solid();
            ClassifiedLoop classifiedLoop = new ClassifiedLoop(loop, depth, solid);
            classified.add(classifiedLoop);
        }
        return classified;
    }

    private static double loopBoundingArea(ClosedLoop loop) {
        return (loop.maxX() - loop.minX()) * (loop.maxY() - loop.minY());
    }

    private static boolean loopContainsLoop(ClosedLoop outer, ClosedLoop inner) {
        if (outer.minX() > inner.minX() || outer.maxX() < inner.maxX()
                || outer.minY() > inner.minY() || outer.maxY() < inner.maxY()) {
            return false;
        }
        return isPointInLoop(outer, inner.xs()[0], inner.ys()[0]);
    }

    private static boolean isThinNestedShell(ClosedLoop outer, ClosedLoop inner) {
        return inner.minX() - outer.minX() <= SAME_SOLID_NEST_GAP_PX
                && outer.maxX() - inner.maxX() <= SAME_SOLID_NEST_GAP_PX
                && inner.minY() - outer.minY() <= SAME_SOLID_NEST_GAP_PX
                && outer.maxY() - inner.maxY() <= SAME_SOLID_NEST_GAP_PX;
    }

    private static boolean isPointInLoop(ClosedLoop loop, double x, double y) {
        boolean inside = false;
        double[] xs = loop.xs();
        double[] ys = loop.ys();
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean intersects = ((ys[i] > y) != (ys[j] > y))
                    && (x < (xs[j] - xs[i]) * (y - ys[i]) / ((ys[j] - ys[i]) + 0.0) + xs[i]);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void buildRegions(List<Foothold> footholds,
                                     Map<Integer, Foothold> footholdsById,
                                     Set<Integer> collidableFromBelowIds,
                                     List<BotNavigationGraph.Region> regions,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId) {
        UnionFind unionFind = new UnionFind();
        for (Foothold foothold : footholds) {
            unionFind.add(foothold.getId());
        }

        for (Foothold foothold : footholds) {
            unionWalkableFootholds(unionFind, foothold, footholdsById.get(foothold.getPrev()));
            unionWalkableFootholds(unionFind, foothold, footholdsById.get(foothold.getNext()));
        }

        Map<Integer, List<Foothold>> groupedFootholds = new HashMap<>();
        for (Foothold foothold : footholds) {
            groupedFootholds.computeIfAbsent(unionFind.find(foothold.getId()), ignored -> new ArrayList<>()).add(foothold);
        }

        List<List<Foothold>> groups = new ArrayList<>(groupedFootholds.values());
        groups.sort(Comparator
                .comparingInt(BotNavigationGraphProvider::groupMinY)
                .thenComparingInt(BotNavigationGraphProvider::groupMinX));

        int nextRegionId = 1;
        for (List<Foothold> group : groups) {
            group.sort(Comparator
                    .comparingInt(BotNavigationGraphProvider::footholdMinX)
                    .thenComparingInt(foothold -> Math.min(foothold.getY1(), foothold.getY2()))
                    .thenComparingInt(Foothold::getId));

            List<BotNavigationGraph.Segment> segments = new ArrayList<>(group.size());
            for (Foothold foothold : group) {
                segments.add(new BotNavigationGraph.Segment(
                        foothold,
                        collidableFromBelowIds.contains(foothold.getId())));
            }

            BotNavigationGraph.Region region = new BotNavigationGraph.Region(nextRegionId++, segments);
            regions.add(region);
            regionsById.put(region.id, region);
            for (Foothold foothold : group) {
                regionIdByFootholdId.put(foothold.getId(), region.id);
            }
        }
    }

    private static void unionWalkableFootholds(UnionFind unionFind, Foothold first, Foothold second) {
        if (!BotPhysicsEngine.canWalkAcrossFootholds(first, second)) {
            return;
        }
        unionFind.union(first.getId(), second.getId());
    }

    private static void addWalkEdges(Foothold foothold,
                                     Map<Integer, Foothold> footholdsById,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys,
                                     BotMovementProfile movementProfile) {
        addWalkEdge(foothold, footholdsById.get(foothold.getPrev()), regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
        addWalkEdge(foothold, footholdsById.get(foothold.getNext()), regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
    }

    private static void addWalkEdge(Foothold fromFoothold,
                                    Foothold targetFoothold,
                                    Map<Integer, BotNavigationGraph.Region> regionsById,
                                    Map<Integer, Integer> regionIdByFootholdId,
                                    Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                    Set<String> edgeKeys,
                                    BotMovementProfile movementProfile) {
        if (fromFoothold == null || targetFoothold == null || targetFoothold.isWall()) {
            return;
        }

        int fromRegionId = regionIdByFootholdId.getOrDefault(fromFoothold.getId(), -1);
        int toRegionId = regionIdByFootholdId.getOrDefault(targetFoothold.getId(), -1);
        if (fromRegionId < 0 || toRegionId < 0 || fromRegionId == toRegionId) {
            return;
        }

        EndpointConnection connection = closestEndpointConnection(fromFoothold, targetFoothold);
        if (connection == null || !isWalkConnection(connection)) {
            return;
        }

        BotNavigationGraph.Region from = regionsById.get(fromRegionId);
        BotNavigationGraph.Region to = regionsById.get(toRegionId);
        if (from == null || to == null) {
            return;
        }

        Point start = from.pointAt(connection.from.x);
        Point end = to.pointAt(connection.to.x);
        int cost = estimateWalkCost(start, end, movementProfile);
        addEdge(from.id, to.id, BotNavigationGraph.EdgeType.WALK, start, end, 0, 0, cost, outgoing, edgeKeys);
        addEdge(to.id, from.id, BotNavigationGraph.EdgeType.WALK, end, start, 0, 0, cost, outgoing, edgeKeys);
    }

    private static void addDropEdges(BotNavigationGraph.Region from,
                                     MapleMap map,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId,
                                     List<Point> anchors,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys,
                                     BotMovementProfile movementProfile) {
        addDirectionalDropEdge(from, map, regionsById, regionIdByFootholdId, -1, outgoing, edgeKeys, movementProfile);
        addDirectionalDropEdge(from, map, regionsById, regionIdByFootholdId, 1, outgoing, edgeKeys, movementProfile);

        for (Point anchor : anchors) {
            if (dropLaunchStep(from, map, anchor, movementProfile) != 0) {
                continue;
            }

            JumpLaunchWindow launchWindow = expandDownJumpLaunchWindow(
                    from, map, regionIdByFootholdId, anchor.x, movementProfile);
            if (launchWindow == null) {
                continue;
            }

            int toRegionId = findRegionIdBelow(map, regionIdByFootholdId, launchWindow.endPoint());
            BotNavigationGraph.Region below = regionsById.get(toRegionId);
            if (below == null || below.id == from.id) {
                continue;
            }

            // Penalize the crouch-drop so ropes/walk-offs win whenever they reach the same region;
            // the down-jump is only chosen when it's effectively the only way down.
            addEdge(from.id, below.id, BotNavigationGraph.EdgeType.DROP,
                    launchWindow.startPoint(), launchWindow.endPoint(),
                    launchWindow.minX(), launchWindow.maxX(),
                    0, 0, launchWindow.landingTimeMs() + DOWN_JUMP_COST_PENALTY_MS, outgoing, edgeKeys);
        }
    }

    private static void addDirectionalDropEdge(BotNavigationGraph.Region from,
                                               MapleMap map,
                                               Map<Integer, BotNavigationGraph.Region> regionsById,
                                               Map<Integer, Integer> regionIdByFootholdId,
                                               int direction,
                                               Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                               Set<String> edgeKeys,
                                               BotMovementProfile movementProfile) {
        if (direction == 0) {
            return;
        }
        Point endpoint = direction < 0 ? from.leftPoint() : from.rightPoint();
        if (from.isForbidFallDownAt(endpoint.x)) {
            return;
        }

        // O(1) runway: place the edge startPoint so the bot has room to accelerate
        // to (near) max walk speed before walking off. No candidate iteration, no
        // runway simulation — just constants from config.
        int runwayPx = BotPhysicsEngine.launchRunwayPx(map, movementProfile);
        int launchX;
        if (direction < 0) {
            launchX = Math.min(from.maxX, endpoint.x + runwayPx);
        } else {
            launchX = Math.max(from.minX, endpoint.x - runwayPx);
        }
        // v70 (b109): a region narrower than the runway walks off from its far end instead of having no walk-off
        // at all. A 2 px near-vertical foothold a jump lands on (610020006 r1, x 1182..1184 over 570 px) is left
        // only by walking off its low end; without the edge every bot that landed there stayed. The walk-off sim
        // below (every live launch state lands the same region) still decides whether the drop is real.
        int actualRunway = Math.abs(launchX - endpoint.x);
        if (actualRunway <= 0) {
            return;
        }
        Point startPoint = from.pointAt(launchX);
        if (BotPhysicsEngine.isGroundRunwayBlockedByWall(map, startPoint, endpoint)) {
            return;
        }

        int stepX = BotPhysicsEngine.walkStep(map, movementProfile) * direction;
        BotPhysicsEngine.WalkOffLanding walkOff = stableWalkOffLanding(map, from, startPoint, direction, null,
                regionIdByFootholdId, movementProfile);
        if (walkOff == null) {
            return;
        }
        BotNavigationGraph.Region below = regionsById.get(
                regionIdByFootholdId.getOrDefault(walkOff.landing().foothold().getId(), -1));
        if (below == null) {
            return;
        }

        addEdge(from.id, below.id, BotNavigationGraph.EdgeType.DROP,
                startPoint,
                walkOff.landing().point(),
                stepX,
                0,
                walkOff.travelTimeMs(),
                outgoing,
                edgeKeys);
    }

    /*
     * Graph v69 (ported from nutnnut/Cosmic 8a7e7b3a5 + 362926fdf + 26b372e7c, AGPL-3.0. Credit: NutNNut): the
     * landing of a directional walk-off from startPoint, or null when there is no drop to author. Shared by the
     * builder and the player-learned edges (NavObservedEdges) so both author the same drops.
     *  - Execution SSOT: the landing comes from the same walk-off sim the executor gate runs
     *    (BotNavigationManager.selectDropWaypoint). A real dismount leaves the ground up to a walk step PAST the lip,
     *    which can land on another platform than a fall from the exact lip pixel (Henesys department store
     *    100000102: four of its walk-offs really land elsewhere), and the bot then parked at the runway anchor.
     *  - The walk starts on the source region's own foothold, never a coordinate lookup that can pick a shelf up to
     *    MAX_SLOPE_UP overhead and walk off THAT lip.
     *  - Knife-edge guard: every live launch state (BotPhysicsEngine.walkOffLandingVariants) must land the same
     *    region, or the planner keeps replanning through an edge the bot cannot reproduce (FM 910000000).
     *  - Ours kept: the walk-off must descend more than 4 px and at most MAX_DROP_PX.
     * graph: the finished graph (runtime callers); null inside a build (uses the build's walk-region lookup).
     */
    static BotPhysicsEngine.WalkOffLanding stableWalkOffLanding(MapleMap map,
                                                               BotNavigationGraph.Region from,
                                                               Point startPoint,
                                                               int direction,
                                                               BotNavigationGraph graph,
                                                               Map<Integer, Integer> regionIdByFootholdId,
                                                               BotMovementProfile movementProfile) {
        Foothold standing = BotPhysicsEngine.findWalkRegionGroundFoothold(map, graph, from.id, startPoint.x,
                startPoint.y);
        if (standing == null) {
            return null;
        }
        List<BotPhysicsEngine.WalkOffLanding> variants = BotPhysicsEngine.walkOffLandingVariants(
                map, startPoint, direction, standing, movementProfile);
        BotPhysicsEngine.WalkOffLanding walkOff = variants.isEmpty() ? null : variants.get(0);
        if (walkOff == null || walkOff.landing() == null || walkOff.landing().foothold() == null) {
            return null;
        }
        int toRegionId = regionIdByFootholdId.getOrDefault(walkOff.landing().foothold().getId(), -1);
        if (toRegionId < 0 || toRegionId == from.id) {
            return null;
        }
        int dropPx = walkOff.landing().point().y - walkOff.launchPoint().y;
        if (dropPx <= 4 || dropPx > MAX_DROP_PX) {
            return null;
        }
        for (BotPhysicsEngine.WalkOffLanding variant : variants) {
            if (variant == null || variant.landing() == null || variant.landing().foothold() == null
                    || regionIdByFootholdId.getOrDefault(variant.landing().foothold().getId(), -1) != toRegionId) {
                return null;
            }
        }
        return walkOff;
    }

    /*
     * Jump-edge launch state: the three launchStepX classes {-walkStep, 0, +walkStep} are
     * COMPLETE even on slippery ground — no extra launch-speed classes or momentum chaining
     * state are needed. Packet-verified client physics (see Config.AIR_CONTROL_ACCEL_PXSS):
     * a jump with a direction held snaps to ±walkSpeed at takeoff regardless of ground speed
     * (so a directional hop needs no runway and loses nothing to a slow icy start or a
     * halved post-landing slide), and a no-input jump carries the current hspeed (bounded by
     * ±walkSpeed, and the executor brakes vertical jumps to ~0 first — "jump-slide" gate).
     * The simulated constant-stepX ballistic arc is exact because the arc flies with the
     * launch direction key HELD (CVecCtrl::CalcFloat @ 0x9b2c3c): held input is a no-op above
     * the 8.93 x fs px/s input band — it neither accelerates nor clamps a walkSpeed launch —
     * and the no-input drag only applies when no key is held, so vx stays constant.
     */
    private static void addJumpEdges(BotNavigationGraph.Region from,
                                     MapleMap map,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId,
                                     List<Point> anchors,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys,
                                     JumpLandingCache jumpLandingCache,
                                     BotMovementProfile movementProfile) {
        long startedAt = System.nanoTime();
        int jumpStep = BotPhysicsEngine.walkStep(map, movementProfile);
        JumpBuildStats stats = new JumpBuildStats();
        for (Point anchor : anchors) {
            for (int launchStepX : new int[]{-jumpStep, 0, jumpStep}) {
                BotPhysicsEngine.PostLandingJump simulatedLanding = simulateJumpLandingCached(
                        map, anchor, launchStepX, jumpLandingCache, stats, movementProfile);
                if (simulatedLanding == null || simulatedLanding.lostGround()) {
                    continue;
                }
                BotPhysicsEngine.JumpLanding landing = simulatedLanding.landing();

                int toRegionId = regionIdByFootholdId.getOrDefault(simulatedLanding.finalFoothold().getId(), -1);
                BotNavigationGraph.Region to = regionsById.get(toRegionId);
                if (to == null || to.id == from.id) {
                    continue;
                }
                if (Math.abs(landing.point().x - anchor.x) < 6 && Math.abs(landing.point().y - anchor.y) < 6) {
                    continue;
                }

                JumpLaunchWindow launchWindow = expandJumpLaunchWindow(from, map, regionIdByFootholdId,
                        anchor.x, launchStepX, to.id, stats, jumpLandingCache, movementProfile);
                if (launchWindow == null) {
                    continue;
                }
                // v67 phantom cross-region jump: the authored landing is on ground the SOURCE region
                // already covers (a ramp foot overlapping a flat platform, or coincident chains at the
                // same height). The client never switches chains on shared ground, so this "jump" cannot
                // change region - A* would commit it and the bot could never execute it (map 600020100
                // r97->r73: stuck/oscillating). Guard the window's representative endpoint.
                if (isPhantomCrossRegionLanding(from, launchWindow.endPoint())) {
                    continue;
                }

                addEdge(from.id, to.id, BotNavigationGraph.EdgeType.JUMP,
                        launchWindow.startPoint(), launchWindow.endPoint(),
                        launchWindow.minX(), launchWindow.maxX(),
                        launchStepX, 0, launchWindow.landingTimeMs(),
                        outgoing, edgeKeys);
                stats.edgeCount++;
            }
        }

        BuildProfileBuilder profile = ACTIVE_BUILD_PROFILE.get();
        if (profile != null) {
            profile.recordJumpRegion(new JumpRegionProfile(
                    from.id,
                    from.width(),
                    stats.sampleCount,
                    stats.edgeCount,
                    stats.cacheHits,
                    stats.cacheMisses,
                    System.nanoTime() - startedAt));
        }
    }

    /* v67: a cross-region ballistic edge whose landing sits on the source region's own surface (ported from
     * nutnnut/Cosmic 59c528899, AGPL-3.0. Credit: NutNNut). */
    static boolean isPhantomCrossRegionLanding(BotNavigationGraph.Region from, Point landing) {
        return from.surfaceCoversPoint(landing.x, landing.y, BotNavigationGraph.SHARED_GROUND_Y_PX);
    }

    // ── Movement-skill edges (teleport / flash jump) ─────────────────────────────────────────────
    // Graph v60/v61/v64/v65/v67, ported from nutnnut/Cosmic (GreenCatMS) BotNavigationGraphProvider at 59c528899,
    // AGPL-3.0. Credit: NutNNut. Baked into every graph; planned only for bots whose job has the skill.

    /**
     * Teleport edges: per intent, a horizontal blink (±range, snap to a platform within the vertical
     * band) and vertical blinks up/down (up/down = furthest platform within range). Given the SAME
     * X-launch-window treatment as JUMP/FLASH_JUMP: per (anchor, intent) we expand the contiguous span of
     * launch X that all blink into the same target region and emit ONE windowed edge (adjacent anchors
     * dedup via edgeKeys) instead of one point-edge per anchor. Execution computes the blink dest LIVE
     * from the bot's actual position (BotNavigationManager.tryExecuteTeleport) so any X in the window is a
     * legal full-range hop. Only cross-region landings become edges. Bots are modelled at max range, so no
     * per-edge distance is stored — usability is just "has teleport".
     */
    private static void addTeleportEdges(BotNavigationGraph.Region from,
                                         MapleMap map,
                                         Map<Integer, BotNavigationGraph.Region> regionsById,
                                         Map<Integer, Integer> regionIdByFootholdId,
                                         List<Point> anchors,
                                         Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                         Set<String> edgeKeys) {
        // Intent-based: ask the physics SSOT (BotPhysicsEngine.teleportLanding) where a left/right/up/down
        // teleport from this anchor legally lands. Generation never invents a position — physics resolves
        // it (horizontal = closest platform vertically / same-level priority; up/down = furthest within
        // range; null = blocked, no edge).
        for (Point anchor : anchors) {
            for (int[] intent : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                addTeleportEdgeForIntent(from, map, regionsById, regionIdByFootholdId, anchor,
                        intent[0], intent[1], outgoing, edgeKeys);
            }
        }
    }

    private static void addTeleportEdgeForIntent(BotNavigationGraph.Region from,
                                                 MapleMap map,
                                                 Map<Integer, BotNavigationGraph.Region> regionsById,
                                                 Map<Integer, Integer> regionIdByFootholdId,
                                                 Point anchor,
                                                 int dirX,
                                                 int dirY,
                                                 Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                                 Set<String> edgeKeys) {
        Point dest = BotPhysicsEngine.teleportLanding(map, anchor, dirX, dirY, TELEPORT_RANGE_PX, TELEPORT_Y_SNAP_PX);
        if (dest == null || dest.equals(anchor)) {
            return; // blocked — no platform to snap to in that direction
        }
        BotNavigationGraph.Region to = findRegionBelow(map, regionsById, regionIdByFootholdId, new Point(dest.x, dest.y - 1));
        if (to == null || to.id == from.id) {
            return; // cross-region only (same-platform express is a runtime decision)
        }

        JumpLaunchWindow launchWindow = expandTeleportLaunchWindow(from, map, regionsById, regionIdByFootholdId,
                anchor.x, dirX, dirY, to.id);
        if (launchWindow == null) {
            return;
        }
        // NN-011 teleportPhantomGuard (nutnnut/Cosmic 43b9f06c8, credit NutNNut): the v67 phantom guard for teleports.
        // A blink that lands on ground the SOURCE region also covers (overlapping chains at the same height) cannot be
        // trusted to switch chains, so the edge may never change region and the bot parks on tele-pos (600020100
        // TELEPORT r97->r73 onto the shared foot).
        if (NavPortSwitches.teleportPhantomGuard && isPhantomCrossRegionLanding(from, launchWindow.endPoint())) {
            return;
        }

        addEdge(from.id, to.id, BotNavigationGraph.EdgeType.TELEPORT,
                launchWindow.startPoint(), launchWindow.endPoint(),
                launchWindow.minX(), launchWindow.maxX(),
                0, 0, TELEPORT_COST_MS, outgoing, edgeKeys);
    }

    private static JumpLaunchWindow expandTeleportLaunchWindow(BotNavigationGraph.Region from,
                                                               MapleMap map,
                                                               Map<Integer, BotNavigationGraph.Region> regionsById,
                                                               Map<Integer, Integer> regionIdByFootholdId,
                                                               int anchorX,
                                                               int dirX,
                                                               int dirY,
                                                               int targetRegionId) {
        if (!isValidTeleportLaunchX(from, map, regionsById, regionIdByFootholdId, anchorX, dirX, dirY, targetRegionId)) {
            return null;
        }

        int minX = findTeleportBoundary(from, map, regionsById, regionIdByFootholdId, anchorX, dirX, dirY,
                targetRegionId, true);
        int maxX = findTeleportBoundary(from, map, regionsById, regionIdByFootholdId, anchorX, dirX, dirY,
                targetRegionId, false);

        int representativeX = (minX + maxX) / 2;
        Point representativeStart = from.pointAt(representativeX);
        Point representativeDest = BotPhysicsEngine.teleportLanding(
                map, representativeStart, dirX, dirY, TELEPORT_RANGE_PX, TELEPORT_Y_SNAP_PX);
        if (representativeDest == null) {
            return null;
        }
        BotNavigationGraph.Region to = findRegionBelow(map, regionsById, regionIdByFootholdId,
                new Point(representativeDest.x, representativeDest.y - 1));
        if (to == null || to.id != targetRegionId) {
            return null;
        }

        return new JumpLaunchWindow(minX, maxX, representativeStart, representativeDest, TELEPORT_COST_MS);
    }

    private static int findTeleportBoundary(BotNavigationGraph.Region from,
                                            MapleMap map,
                                            Map<Integer, BotNavigationGraph.Region> regionsById,
                                            Map<Integer, Integer> regionIdByFootholdId,
                                            int startX,
                                            int dirX,
                                            int dirY,
                                            int targetRegionId,
                                            boolean searchLeft) {
        int limitX = searchLeft ? from.minX : from.maxX;
        int validX = startX;
        int invalidX = startX;
        int step = 1;

        while (true) {
            int probeX = searchLeft
                    ? Math.max(limitX, startX - step)
                    : Math.min(limitX, startX + step);
            if (probeX == validX) {
                break;
            }

            if (!isValidTeleportLaunchX(from, map, regionsById, regionIdByFootholdId, probeX,
                    dirX, dirY, targetRegionId)) {
                invalidX = probeX;
                break;
            }

            validX = probeX;
            if (probeX == limitX) {
                return probeX;
            }
            step *= 2;
        }

        while (Math.abs(validX - invalidX) > 1) {
            int probeX = (validX + invalidX) / 2;
            if (isValidTeleportLaunchX(from, map, regionsById, regionIdByFootholdId, probeX,
                    dirX, dirY, targetRegionId)) {
                validX = probeX;
            } else {
                invalidX = probeX;
            }
        }
        return validX;
    }

    private static boolean isValidTeleportLaunchX(BotNavigationGraph.Region from,
                                                  MapleMap map,
                                                  Map<Integer, BotNavigationGraph.Region> regionsById,
                                                  Map<Integer, Integer> regionIdByFootholdId,
                                                  int launchX,
                                                  int dirX,
                                                  int dirY,
                                                  int targetRegionId) {
        if (!isApproachableJumpLaunchX(from, map, launchX)) {
            return false;
        }
        Point origin = from.pointAt(launchX);
        Point dest = BotPhysicsEngine.teleportLanding(map, origin, dirX, dirY, TELEPORT_RANGE_PX, TELEPORT_Y_SNAP_PX);
        if (dest == null || dest.equals(origin)) {
            return false;
        }
        BotNavigationGraph.Region to = findRegionBelow(map, regionsById, regionIdByFootholdId,
                new Point(dest.x, dest.y - 1));
        return to != null && to.id == targetRegionId;
    }

    /**
     * Flash-jump edges: a directional jump with the mid-air dash injected at apex (see
     * BotPhysicsEngine.simulateFlashJumpLanding). Given the SAME X-launch-window treatment as ground
     * JUMP (expandFlashJumpLaunchWindow / findFlashJumpBoundary): per (anchor, dir) we expand the
     * contiguous span of launch X that all flash-jump into the same target region AND that a plain jump
     * canNOT reach — FJ earns an edge only where it adds reach, position-aware. Emitting one windowed
     * edge collapses the ~per-anchor point-edges this used to produce into a few windowed edges
     * (adjacent anchors dedup via edgeKeys), mirroring JUMP/DROP/rope windows.
     */
    private static void addFlashJumpEdges(BotNavigationGraph.Region from,
                                          MapleMap map,
                                          Map<Integer, BotNavigationGraph.Region> regionsById,
                                          Map<Integer, Integer> regionIdByFootholdId,
                                          List<Point> anchors,
                                          Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                          Set<String> edgeKeys,
                                          JumpLandingCache jumpLandingCache,
                                          FlashJumpLandingCache flashJumpLandingCache,
                                          BotMovementProfile movementProfile) {
        int jumpStep = BotPhysicsEngine.walkStep(map, movementProfile);
        JumpBuildStats stats = new JumpBuildStats();
        for (Point anchor : anchors) {
            for (int dir : new int[]{-1, 1}) {
                int launchStepX = dir * jumpStep;
                BotPhysicsEngine.JumpLanding fj = simulateFlashJumpLandingCached(
                        map, anchor, launchStepX, flashJumpLandingCache, stats, movementProfile);
                if (fj == null) {
                    continue;
                }
                int toRegionId = regionIdByFootholdId.getOrDefault(fj.foothold().getId(), -1);
                BotNavigationGraph.Region to = regionsById.get(toRegionId);
                if (to == null || to.id == from.id) {
                    continue;
                }
                JumpLaunchWindow launchWindow = expandFlashJumpLaunchWindow(from, map, regionIdByFootholdId,
                        anchor.x, launchStepX, to.id, stats, jumpLandingCache, flashJumpLandingCache, movementProfile);
                if (launchWindow == null) {
                    continue;
                }
                // Same phantom-edge guard as ground JUMP: skip a flash-jump whose authored landing is on
                // ground the source region already covers (shared/overlapping chains) — it cannot change
                // region. Guard the window endpoint (the landing the edge carries), not the raw sim.
                if (isPhantomCrossRegionLanding(from, launchWindow.endPoint())) {
                    continue;
                }

                addEdge(from.id, to.id, BotNavigationGraph.EdgeType.FLASH_JUMP,
                        launchWindow.startPoint(), launchWindow.endPoint(),
                        launchWindow.minX(), launchWindow.maxX(),
                        launchStepX, 0, launchWindow.landingTimeMs(), outgoing, edgeKeys);
            }
        }
    }

    private static BotPhysicsEngine.JumpLanding simulateFlashJumpLandingCached(MapleMap map,
                                                                               Point start,
                                                                               int launchStepX,
                                                                               FlashJumpLandingCache flashJumpLandingCache,
                                                                               JumpBuildStats stats,
                                                                               BotMovementProfile movementProfile) {
        JumpLandingKey key = new JumpLandingKey(start.x, start.y, launchStepX);
        BotPhysicsEngine.JumpLanding cached = flashJumpLandingCache.hits.get(key);
        if (cached != null) {
            recordJumpSample(stats, true);
            return cached;
        }
        if (flashJumpLandingCache.misses.contains(key)) {
            recordJumpSample(stats, true);
            return null;
        }

        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateFlashJumpLanding(map, start, launchStepX, movementProfile);
        if (landing == null) {
            flashJumpLandingCache.misses.add(key);
        } else {
            flashJumpLandingCache.hits.put(key, landing);
        }
        recordJumpSample(stats, false);
        return landing;
    }

    private static JumpLaunchWindow expandFlashJumpLaunchWindow(BotNavigationGraph.Region from,
                                                               MapleMap map,
                                                               Map<Integer, Integer> regionIdByFootholdId,
                                                               int anchorX,
                                                               int launchStepX,
                                                               int targetRegionId,
                                                               JumpBuildStats stats,
                                                               JumpLandingCache jumpLandingCache,
                                                               FlashJumpLandingCache flashJumpLandingCache,
                                                               BotMovementProfile movementProfile) {
        if (!isValidFlashJumpLaunchX(from, map, regionIdByFootholdId, anchorX, launchStepX, targetRegionId,
                stats, jumpLandingCache, flashJumpLandingCache, movementProfile)) {
            return null;
        }

        int minX = findFlashJumpBoundary(from, map, regionIdByFootholdId, anchorX, launchStepX, targetRegionId,
                true, stats, jumpLandingCache, flashJumpLandingCache, movementProfile);
        int maxX = findFlashJumpBoundary(from, map, regionIdByFootholdId, anchorX, launchStepX, targetRegionId,
                false, stats, jumpLandingCache, flashJumpLandingCache, movementProfile);

        int representativeX = (minX + maxX) / 2;
        Point representativeStart = from.pointAt(representativeX);
        BotPhysicsEngine.JumpLanding representative = simulateFlashJumpLandingCached(
                map, representativeStart, launchStepX, flashJumpLandingCache, stats, movementProfile);
        if (representative == null
                || regionIdByFootholdId.getOrDefault(representative.foothold().getId(), -1) != targetRegionId) {
            return null;
        }

        return new JumpLaunchWindow(minX, maxX, representativeStart, representative.point(), representative.timeMs());
    }

    private static int findFlashJumpBoundary(BotNavigationGraph.Region from,
                                             MapleMap map,
                                             Map<Integer, Integer> regionIdByFootholdId,
                                             int startX,
                                             int launchStepX,
                                             int targetRegionId,
                                             boolean searchLeft,
                                             JumpBuildStats stats,
                                             JumpLandingCache jumpLandingCache,
                                             FlashJumpLandingCache flashJumpLandingCache,
                                             BotMovementProfile movementProfile) {
        int limitX = searchLeft ? from.minX : from.maxX;
        int validX = startX;
        int invalidX = startX;
        int step = 1;

        while (true) {
            int probeX = searchLeft
                    ? Math.max(limitX, startX - step)
                    : Math.min(limitX, startX + step);
            if (probeX == validX) {
                break;
            }

            if (!isValidFlashJumpLaunchX(from, map, regionIdByFootholdId, probeX,
                    launchStepX, targetRegionId, stats, jumpLandingCache, flashJumpLandingCache, movementProfile)) {
                invalidX = probeX;
                break;
            }

            validX = probeX;
            if (probeX == limitX) {
                return probeX;
            }
            step *= 2;
        }

        while (Math.abs(validX - invalidX) > 1) {
            int probeX = (validX + invalidX) / 2;
            if (isValidFlashJumpLaunchX(from, map, regionIdByFootholdId, probeX,
                    launchStepX, targetRegionId, stats, jumpLandingCache, flashJumpLandingCache, movementProfile)) {
                validX = probeX;
            } else {
                invalidX = probeX;
            }
        }
        return validX;
    }

    /**
     * A launch X is a valid flash-jump launch into {@code targetRegionId} iff it is approachable, the
     * flash-jump from there lands in the target region, AND a plain jump from the same X does NOT reach
     * the same region (where a plain jump suffices the cheaper JUMP edge covers it — so the FJ window
     * spans only the X-range FJ uniquely adds; their union covers every reachable launch X with no gap).
     */
    private static boolean isValidFlashJumpLaunchX(BotNavigationGraph.Region from,
                                                   MapleMap map,
                                                   Map<Integer, Integer> regionIdByFootholdId,
                                                   int launchX,
                                                   int launchStepX,
                                                   int targetRegionId,
                                                   JumpBuildStats stats,
                                                   JumpLandingCache jumpLandingCache,
                                                   FlashJumpLandingCache flashJumpLandingCache,
                                                   BotMovementProfile movementProfile) {
        if (!isApproachableJumpLaunchX(from, map, launchX)) {
            return false;
        }
        BotPhysicsEngine.JumpLanding fj = simulateFlashJumpLandingCached(
                map, from.pointAt(launchX), launchStepX, flashJumpLandingCache, stats, movementProfile);
        if (fj == null || regionIdByFootholdId.getOrDefault(fj.foothold().getId(), -1) != targetRegionId) {
            return false;
        }
        BotPhysicsEngine.PostLandingJump normal = simulateJumpLandingCached(
                map, from.pointAt(launchX), launchStepX, jumpLandingCache, stats, movementProfile);
        int normalRegion = normal == null || normal.lostGround()
                ? -1
                : regionIdByFootholdId.getOrDefault(normal.finalFoothold().getId(), -1);
        return normalRegion != targetRegionId;
    }


    private static BotPhysicsEngine.PostLandingJump simulateJumpLandingCached(MapleMap map,
                                                                              Point start,
                                                                              int launchStepX,
                                                                              JumpLandingCache jumpLandingCache,
                                                                              JumpBuildStats stats,
                                                                              BotMovementProfile movementProfile) {
        JumpLandingKey key = new JumpLandingKey(start.x, start.y, launchStepX);
        BotPhysicsEngine.PostLandingJump cachedLanding = jumpLandingCache.hits.get(key);
        if (cachedLanding != null) {
            recordJumpSample(stats, true);
            return cachedLanding;
        }
        if (jumpLandingCache.misses.contains(key)) {
            recordJumpSample(stats, true);
            return null;
        }

        BotPhysicsEngine.PostLandingJump landing = BotPhysicsEngine.simulateJumpLandingWithPostLandingTicks(
                map, start, launchStepX, movementProfile, JUMP_POST_LANDING_STABILITY_TICKS);
        if (landing == null) {
            jumpLandingCache.misses.add(key);
        } else {
            jumpLandingCache.hits.put(key, landing);
        }
        recordJumpSample(stats, false);
        return landing;
    }

    private static Point simulateGroundJumpRopeGrabCached(MapleMap map,
                                                          Point start,
                                                          int launchStepX,
                                                          Rope rope,
                                                          RopeGrabCache ropeGrabCache,
                                                          JumpBuildStats stats,
                                                          BotMovementProfile movementProfile) {
        RopeGrabKey key = new RopeGrabKey(start.x, start.y, launchStepX,
                rope.x(), rope.topY(), rope.bottomY());
        Point cachedGrab = ropeGrabCache.hits.get(key);
        if (cachedGrab != null) {
            recordJumpSample(stats, true);
            return new Point(cachedGrab);
        }
        if (ropeGrabCache.misses.contains(key)) {
            recordJumpSample(stats, true);
            return null;
        }

        Point grab = BotPhysicsEngine.simulateGroundJumpRopeGrab(map, start, launchStepX, rope, movementProfile);
        if (grab == null) {
            ropeGrabCache.misses.add(key);
        } else {
            ropeGrabCache.hits.put(key, new Point(grab));
        }
        recordJumpSample(stats, false);
        return grab;
    }

    private static void recordJumpSample(JumpBuildStats stats, boolean cacheHit) {
        BuildProfileBuilder profile = ACTIVE_BUILD_PROFILE.get();
        if (profile != null) {
            profile.recordJumpSample(cacheHit);
        }
        stats.sampleCount++;
        if (cacheHit) {
            stats.cacheHits++;
        } else {
            stats.cacheMisses++;
        }
    }

    private static Map<Integer, List<Point>> buildAnchorsByRegionId(MapleMap map,
                                                                    List<BotNavigationGraph.Region> regions,
                                                                    Map<Integer, List<Integer>> featureXsByRegionId,
                                                                    BotMovementProfile movementProfile) {
        Map<Integer, List<Point>> anchorsByRegionId = new HashMap<>();
        for (BotNavigationGraph.Region region : regions) {
            if (region.isRopeRegion) {
                continue;
            }
            anchorsByRegionId.put(region.id, anchorPoints(map, region, featureXsByRegionId.getOrDefault(region.id, List.of()), movementProfile));
        }
        return anchorsByRegionId;
    }

    private static JumpLaunchWindow expandJumpLaunchWindow(BotNavigationGraph.Region from,
                                                           MapleMap map,
                                                           Map<Integer, Integer> regionIdByFootholdId,
                                                           int anchorX,
                                                           int launchStepX,
                                                           int targetRegionId,
                                                           JumpBuildStats stats,
                                                           JumpLandingCache jumpLandingCache,
                                                           BotMovementProfile movementProfile) {
        if (!isValidJumpLaunchX(from, map, regionIdByFootholdId, anchorX, launchStepX, targetRegionId,
                stats, jumpLandingCache, movementProfile)) {
            return null;
        }

        int minX = findJumpBoundary(from, map, regionIdByFootholdId, anchorX, launchStepX, targetRegionId,
                true, stats, jumpLandingCache, movementProfile);
        int maxX = findJumpBoundary(from, map, regionIdByFootholdId, anchorX, launchStepX, targetRegionId,
                false, stats, jumpLandingCache, movementProfile);

        int representativeX = (minX + maxX) / 2;
        Point representativeStart = from.pointAt(representativeX);
        BotPhysicsEngine.PostLandingJump representativeSimulation =
                simulateJumpLandingCached(map, representativeStart, launchStepX, jumpLandingCache, stats, movementProfile);
        if (representativeSimulation == null || representativeSimulation.lostGround()) {
            return null;
        }

        int landingRegionId = regionIdByFootholdId.getOrDefault(representativeSimulation.finalFoothold().getId(), -1);
        if (landingRegionId != targetRegionId) {
            return null;
        }

        return new JumpLaunchWindow(minX, maxX, representativeStart, representativeSimulation.landing().point(),
                representativeSimulation.landing().timeMs());
    }

    private static JumpLaunchWindow expandDownJumpLaunchWindow(BotNavigationGraph.Region from,
                                                               MapleMap map,
                                                               Map<Integer, Integer> regionIdByFootholdId,
                                                               int anchorX,
                                                               BotMovementProfile movementProfile) {
        BotPhysicsEngine.JumpLanding anchorLanding = validateDownJumpLaunchX(
                from, map, regionIdByFootholdId, anchorX, movementProfile);
        if (anchorLanding == null) {
            return null;
        }

        int targetRegionId = regionIdByFootholdId.getOrDefault(anchorLanding.foothold().getId(), -1);
        if (targetRegionId < 0) {
            return null;
        }

        int minX = findDownJumpBoundary(from, map, regionIdByFootholdId, anchorX, targetRegionId, true, movementProfile);
        int maxX = findDownJumpBoundary(from, map, regionIdByFootholdId, anchorX, targetRegionId, false, movementProfile);

        int representativeX = (minX + maxX) / 2;
        Point representativeStart = from.pointAt(representativeX);
        BotPhysicsEngine.JumpLanding representativeLanding = validateDownJumpLaunchX(
                from, map, regionIdByFootholdId, representativeX, movementProfile, targetRegionId);
        if (representativeLanding == null) {
            return null;
        }

        return new JumpLaunchWindow(minX, maxX, representativeStart,
                representativeLanding.point(), representativeLanding.timeMs());
    }

    private static int findJumpBoundary(BotNavigationGraph.Region from,
                                        MapleMap map,
                                        Map<Integer, Integer> regionIdByFootholdId,
                                        int startX,
                                        int launchStepX,
                                        int targetRegionId,
                                        boolean searchLeft,
                                        JumpBuildStats stats,
                                        JumpLandingCache jumpLandingCache,
                                        BotMovementProfile movementProfile) {
        int limitX = searchLeft ? from.minX : from.maxX;
        int validX = startX;
        int invalidX = startX;
        int step = 1;

        while (true) {
            int probeX = searchLeft
                    ? Math.max(limitX, startX - step)
                    : Math.min(limitX, startX + step);
            if (probeX == validX) {
                break;
            }

            if (!isValidJumpLaunchX(from, map, regionIdByFootholdId, probeX,
                    launchStepX, targetRegionId, stats, jumpLandingCache, movementProfile)) {
                invalidX = probeX;
                break;
            }

            validX = probeX;
            if (probeX == limitX) {
                return probeX;
            }
            step *= 2;
        }

        while (Math.abs(validX - invalidX) > 1) {
            int probeX = (validX + invalidX) / 2;
            if (isValidJumpLaunchX(from, map, regionIdByFootholdId, probeX,
                    launchStepX, targetRegionId, stats, jumpLandingCache, movementProfile)) {
                validX = probeX;
            } else {
                invalidX = probeX;
            }
        }
        return validX;
    }

    private static int findDownJumpBoundary(BotNavigationGraph.Region from,
                                            MapleMap map,
                                            Map<Integer, Integer> regionIdByFootholdId,
                                            int startX,
                                            int targetRegionId,
                                            boolean searchLeft,
                                            BotMovementProfile movementProfile) {
        // A straight down-jump has NO horizontal launch precision (you press down+jump and fall
        // in place), so the window is the whole contiguous span of the source region that drops
        // into the same target - bounded only by the region edges, exactly like findJumpBoundary.
        // Capping it at +/-20px fragmented one droppable platform into many partial 40px edges
        // whose union didn't even cover the platform, stranding bots just outside a chosen edge
        // (El Nath r54->r56, pathlog-Leroy-2026-06-12T141517: 14 edges, none covering x=1287).
        int limitX = searchLeft ? from.minX : from.maxX;
        int validX = startX;
        int invalidX = startX;
        int step = 1;

        while (true) {
            int probeX = searchLeft
                    ? Math.max(limitX, startX - step)
                    : Math.min(limitX, startX + step);
            if (probeX == validX) {
                break;
            }

            if (!isValidDownJumpLaunchX(from, map, regionIdByFootholdId, probeX, movementProfile, targetRegionId)) {
                invalidX = probeX;
                break;
            }

            validX = probeX;
            if (probeX == limitX) {
                return probeX;
            }
            step *= 2;
        }

        while (Math.abs(validX - invalidX) > 1) {
            int probeX = (validX + invalidX) / 2;
            if (isValidDownJumpLaunchX(from, map, regionIdByFootholdId, probeX, movementProfile, targetRegionId)) {
                validX = probeX;
            } else {
                invalidX = probeX;
            }
        }
        return validX;
    }

    private static boolean isValidJumpLaunchX(BotNavigationGraph.Region from,
                                              MapleMap map,
                                              Map<Integer, Integer> regionIdByFootholdId,
                                              int launchX,
                                              int launchStepX,
                                              int targetRegionId,
                                              JumpBuildStats stats,
                                              JumpLandingCache jumpLandingCache,
                                              BotMovementProfile movementProfile) {
        if (!isApproachableJumpLaunchX(from, map, launchX)) {
            return false;
        }
        BotPhysicsEngine.PostLandingJump landing = simulateJumpLandingCached(
                map, from.pointAt(launchX), launchStepX, jumpLandingCache, stats, movementProfile);
        return landing != null
                && !landing.lostGround()
                && regionIdByFootholdId.getOrDefault(landing.finalFoothold().getId(), -1) == targetRegionId;
    }

    private static BotPhysicsEngine.JumpLanding validateDownJumpLaunchX(BotNavigationGraph.Region from,
                                                                         MapleMap map,
                                                                         Map<Integer, Integer> regionIdByFootholdId,
                                                                         int launchX,
                                                                         BotMovementProfile movementProfile) {
        return validateDownJumpLaunchX(from, map, regionIdByFootholdId, launchX, movementProfile, Integer.MIN_VALUE);
    }

    private static boolean isValidDownJumpLaunchX(BotNavigationGraph.Region from,
                                                  MapleMap map,
                                                  Map<Integer, Integer> regionIdByFootholdId,
                                                  int launchX,
                                                  BotMovementProfile movementProfile,
                                                  int targetRegionId) {
        return validateDownJumpLaunchX(from, map, regionIdByFootholdId, launchX, movementProfile, targetRegionId) != null;
    }

    private static BotPhysicsEngine.JumpLanding validateDownJumpLaunchX(BotNavigationGraph.Region from,
                                                                         MapleMap map,
                                                                         Map<Integer, Integer> regionIdByFootholdId,
                                                                         int launchX,
                                                                         BotMovementProfile movementProfile,
                                                                         int requiredTargetRegionId) {
        if (from == null || from.isRopeRegion || map == null) {
            return null;
        }
        Point launchPoint = from.pointAt(launchX);
        if (isBlockedWallBoundaryLaunch(from, map, launchPoint)) {
            return null;
        }
        if (!BotPhysicsEngine.canStartDownJump(map, launchPoint)
                || from.isForbidFallDownAt(launchX)
                || dropLaunchStep(from, map, launchPoint, movementProfile) != 0) {
            return null;
        }

        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateDownJumpLanding(map, launchPoint);
        if (landing == null || landing.point().y <= launchPoint.y + 4) {
            return null;
        }
        // Short-fallback cap: don't crouch-drop down a tall shaft - that's what ropes are for.
        if (landing.point().y - launchPoint.y > DOWN_JUMP_MAX_DROP_PX) {
            return null;
        }

        int landingRegionId = regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
        if (landingRegionId < 0 || landingRegionId == from.id) {
            return null;
        }
        if (requiredTargetRegionId != Integer.MIN_VALUE && landingRegionId != requiredTargetRegionId) {
            return null;
        }
        return landing;
    }

    private static boolean isApproachableJumpLaunchX(BotNavigationGraph.Region from, MapleMap map, int launchX) {
        if (from == null || from.isRopeRegion || map == null) {
            return false;
        }
        Point launchPoint = from.pointAt(launchX);
        if (isBlockedWallBoundaryLaunch(from, map, launchPoint)) {
            return false;
        }
        if (launchX > from.minX && canWalkToLaunchX(from, map, launchX - 1, launchX)) {
            return true;
        }
        return launchX < from.maxX && canWalkToLaunchX(from, map, launchX + 1, launchX);
    }

    private static JumpLaunchWindow expandRopeGrabLaunchWindow(BotNavigationGraph.Region from,
                                                               MapleMap map,
                                                               int anchorX,
                                                               int launchStepX,
                                                               Rope rope,
                                                               JumpBuildStats stats,
                                                               RopeGrabCache ropeGrabCache,
                                                               BotMovementProfile movementProfile) {
        if (!isValidRopeGrabLaunchX(from, map, anchorX, launchStepX, rope, stats, ropeGrabCache, movementProfile)) {
            return null;
        }

        int minX = findRopeGrabBoundary(from, map, anchorX, launchStepX, rope,
                true, stats, ropeGrabCache, movementProfile);
        int maxX = findRopeGrabBoundary(from, map, anchorX, launchStepX, rope,
                false, stats, ropeGrabCache, movementProfile);

        int representativeX = (minX + maxX) / 2;
        Point representativeStart = from.pointAt(representativeX);
        Point representativeGrab = simulateGroundJumpRopeGrabCached(
                map, representativeStart, launchStepX, rope, ropeGrabCache, stats, movementProfile);
        if (representativeGrab == null) {
            return null;
        }

        int travelMs = BotPhysicsEngine.estimateGroundJumpRopeGrabTimeMs(
                map, representativeStart, launchStepX, rope, movementProfile);
        return new JumpLaunchWindow(minX, maxX, representativeStart, representativeGrab, travelMs);
    }

    private static int findRopeGrabBoundary(BotNavigationGraph.Region from,
                                            MapleMap map,
                                            int startX,
                                            int launchStepX,
                                            Rope rope,
                                            boolean searchLeft,
                                            JumpBuildStats stats,
                                            RopeGrabCache ropeGrabCache,
                                            BotMovementProfile movementProfile) {
        int limitX = searchLeft ? from.minX : from.maxX;
        int validX = startX;
        int invalidX = startX;
        int step = 1;

        while (true) {
            int probeX = searchLeft
                    ? Math.max(limitX, startX - step)
                    : Math.min(limitX, startX + step);
            if (probeX == validX) {
                break;
            }

            if (!isValidRopeGrabLaunchX(from, map, probeX, launchStepX, rope, stats, ropeGrabCache, movementProfile)) {
                invalidX = probeX;
                break;
            }

            validX = probeX;
            if (probeX == limitX) {
                return probeX;
            }
            step *= 2;
        }

        while (Math.abs(validX - invalidX) > 1) {
            int probeX = (validX + invalidX) / 2;
            if (isValidRopeGrabLaunchX(from, map, probeX, launchStepX, rope, stats, ropeGrabCache, movementProfile)) {
                validX = probeX;
            } else {
                invalidX = probeX;
            }
        }
        return validX;
    }

    private static boolean isValidRopeGrabLaunchX(BotNavigationGraph.Region from,
                                                  MapleMap map,
                                                  int launchX,
                                                  int launchStepX,
                                                  Rope rope,
                                                  JumpBuildStats stats,
                                                  RopeGrabCache ropeGrabCache,
                                                  BotMovementProfile movementProfile) {
        if (!isApproachableJumpLaunchX(from, map, launchX)) {
            return false;
        }
        Point grab = simulateGroundJumpRopeGrabCached(
                map, from.pointAt(launchX), launchStepX, rope, ropeGrabCache, stats, movementProfile);
        return grab != null;
    }

    private static boolean isBlockedWallBoundaryLaunch(BotNavigationGraph.Region from, MapleMap map, Point launchPoint) {
        if (map == null || map.getFootholds() == null || launchPoint == null) {
            return false;
        }
        if (NavPortSwitches.zmassWalls) {
            // NN-011 zmassWalls (nutnnut/Cosmic 43b9f06c8): only a wall that really collides for a mover launching
            // from this region pins the launch; walls of unrelated structures are invisible to it.
            for (Foothold foothold : map.getFootholds().getAllFootholds()) {
                if (!foothold.isWall() || foothold.getX1() != launchPoint.x
                        || !BotPhysicsEngine.wallCollidesForLaunch(map, foothold, from)) {
                    continue;
                }
                int minY = Math.min(foothold.getY1(), foothold.getY2());
                int maxY = Math.max(foothold.getY1(), foothold.getY2());
                if (launchPoint.y > minY && launchPoint.y <= maxY) {
                    return true;
                }
            }
            return false;
        }

        Set<Integer> collidableWallIds = getCachedCollidableWallIds(map.getId());
        if (collidableWallIds == null || collidableWallIds.isEmpty()) {
            return false;
        }

        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            if (!foothold.isWall()
                    || !collidableWallIds.contains(foothold.getId())
                    || foothold.getX1() != launchPoint.x) {
                continue;
            }

            int minY = Math.min(foothold.getY1(), foothold.getY2());
            int maxY = Math.max(foothold.getY1(), foothold.getY2());
            if (launchPoint.y > minY && launchPoint.y <= maxY) {
                return true;
            }
        }
        return false;
    }

    private static boolean canWalkToLaunchX(BotNavigationGraph.Region from, MapleMap map, int fromX, int launchX) {
        Point fromPoint = from.pointAt(fromX);
        Point launchPoint = from.pointAt(launchX);
        if (fromPoint == null || launchPoint == null || fromPoint.equals(launchPoint)) {
            return false;
        }
        return BotPhysicsEngine.canWalkGroundStep(map, fromPoint, launchPoint.x - fromPoint.x);
    }

    // --- Rope entry edges: ground/rope region → rope region ---

    private static void addRopeEntryEdges(BotNavigationGraph.Region ropeRegion,
                                          List<BotNavigationGraph.Region> groundRegions,
                                          Map<Integer, Rope> ropeByRegionId,
                                          MapleMap map,
                                          Map<Integer, List<Point>> anchorsByRegionId,
                                          Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                          Set<String> edgeKeys,
                                          RopeGrabCache ropeGrabCache,
                                          BotMovementProfile movementProfile) {
        Rope rope = ropeByRegionId.get(ropeRegion.id);
        if (rope == null) {
            return;
        }

        int ropeX = rope.x();
        int walkStep = BotMovementManager.walkStep(map, movementProfile);
        JumpBuildStats stats = new JumpBuildStats();
        for (BotNavigationGraph.Region ground : groundRegions) {
            for (Point anchor : anchorsByRegionId.getOrDefault(ground.id, List.of())) {
                int firstClimbableY = BotPhysicsEngine.firstClimbableY(rope);
                boolean canGrab = Math.abs(anchor.x - ropeX) <= BotMovementManager.cfg.ROPE_GRAB_X
                        && anchor.y >= firstClimbableY && anchor.y <= rope.bottomY();
                boolean canTopGrab = Math.abs(anchor.x - ropeX) <= BotMovementManager.cfg.ROPE_GRAB_X
                        && anchor.y < rope.topY()
                        && rope.topY() - anchor.y <= BotPhysicsEngine.cfg.MAX_SNAP_DROP;
                boolean canJumpGrab = BotMovementManager.canReachRopeFromGround(map, anchor, rope, movementProfile);
                boolean canTopStep = anchor.y <= rope.topY() + BotMovementManager.cfg.JUMP_Y_THRESH
                        && Math.abs(anchor.x - ropeX) <= BotMovementManager.cfg.ROPE_GRAB_X;

                if (canGrab) {
                    Point ropePoint = new Point(ropeX, Math.max(firstClimbableY, Math.min(anchor.y, rope.bottomY())));
                    addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                            anchor, ropePoint, 0, 0, BotPhysicsEngine.cfg.TICK_MS, outgoing, edgeKeys);
                    continue;
                }

                if (canTopGrab) {
                    addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                            anchor, new Point(ropeX, firstClimbableY), 0, 0, BotPhysicsEngine.cfg.TICK_MS, outgoing, edgeKeys);
                    continue;
                }

                if (canTopStep) {
                    Point ropeGrab = BotPhysicsEngine.simulateDownJumpRopeGrab(map, anchor, rope);
                    if (ropeGrab != null) {
                        int cost = BotPhysicsEngine.estimateDownJumpRopeGrabTimeMs(map, anchor, rope);
                        addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                                anchor, ropeGrab, 0, 0, cost, outgoing, edgeKeys);
                    }
                }

                if (canJumpGrab) {
                    for (int jumpStep : new int[]{-walkStep, 0, walkStep}) {
                        JumpLaunchWindow launchWindow = expandRopeGrabLaunchWindow(
                                ground, map, anchor.x, jumpStep, rope, stats, ropeGrabCache, movementProfile);
                        if (launchWindow == null) {
                            continue;
                        }
                        addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                                launchWindow.startPoint(), launchWindow.endPoint(),
                                launchWindow.minX(), launchWindow.maxX(),
                                jumpStep, 0, launchWindow.landingTimeMs(), outgoing, edgeKeys);
                    }
                }
            }
        }
    }

    // --- Rope exit edges: rope region → ground/rope region ---

    private static void addRopeExitEdges(BotNavigationGraph.Region ropeRegion,
                                         List<BotNavigationGraph.Region> ropeRegions,
                                         Map<Integer, Rope> ropeByRegionId,
                                         MapleMap map,
                                         Map<Integer, BotNavigationGraph.Region> regionsById,
                                         Map<Integer, Integer> regionIdByFootholdId,
                                         Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                         Set<String> edgeKeys,
                                         BotMovementProfile movementProfile) {
        Rope rope = ropeByRegionId.get(ropeRegion.id);
        if (rope == null) {
            return;
        }

        int ropeX = rope.x();
        int jumpStep = BotMovementManager.walkStep(map, movementProfile);
        int maxRopeJumpDx = BotPhysicsEngine.maxRopeGrabSimulationHorizontalTravel(map, movementProfile);

        // Direct step-off at the top of the rope
        addTopStepOffEdge(ropeRegion, rope, map, regionsById, regionIdByFootholdId, outgoing, edgeKeys);

        // v62 (ported from nutnnut/Cosmic 23975ce86, AGPL-3.0. Credit: NutNNut.)
        // Jump-off / step-off to ground at various heights along the rope. The launch axis here is the
        // climb height Y (the rope analogue of a ground jump's launch X). Probing every anchorY x 3 dirs
        // and emitting one edge each produced ~anchorYs x 3 near-duplicate CLIMB edges all landing in the
        // same region. Instead, for each (anchorY, stepX) expand the contiguous Y-window that lands in the
        // SAME region and emit ONE edge carrying [minY, maxY]. Adjacent anchorYs collapse to the same window
        // via the edge-key dedup. Execution climbs to any height inside the window (selectClimbWaypoint).
        int loY = BotPhysicsEngine.firstClimbableY(rope);
        int hiY = rope.bottomY();
        for (int anchorY : ropeAnchorYs(rope)) {
            for (int stepX : new int[]{-jumpStep, 0, jumpStep}) {
                BotMovementManager.JumpLanding anchorLanding = BotMovementManager.simulateRopeJumpLanding(
                        map, new Point(ropeX, anchorY), stepX, movementProfile);
                if (anchorLanding == null) {
                    continue;
                }

                int toRegionId = regionIdByFootholdId.getOrDefault(anchorLanding.foothold().getId(), -1);
                BotNavigationGraph.Region toRegion = regionsById.get(toRegionId);
                if (toRegion == null || toRegion.isRopeRegion) {
                    continue;
                }
                IntPredicate sameTarget = y -> {
                    BotMovementManager.JumpLanding l = BotMovementManager.simulateRopeJumpLanding(
                            map, new Point(ropeX, y), stepX, movementProfile);
                    return l != null
                            && regionIdByFootholdId.getOrDefault(l.foothold().getId(), -1) == toRegionId;
                };
                int minY = findRopeLaunchBoundary(anchorY, loY, hiY, true, sameTarget);
                int maxY = findRopeLaunchBoundary(anchorY, loY, hiY, false, sameTarget);
                Point ropePoint = new Point(ropeX, (minY + maxY) / 2);
                BotMovementManager.JumpLanding rep = BotMovementManager.simulateRopeJumpLanding(map, ropePoint, stepX, movementProfile);
                if (rep == null || regionIdByFootholdId.getOrDefault(rep.foothold().getId(), -1) != toRegionId) {
                    continue;
                }
                // Fall cost varies with launch height, so cost the two window endpoints (higher launch =
                // longer fall = pricier); the search interpolates per the bot's actual climb height.
                int cost = BotPhysicsEngine.estimateRopeJumpLandingTimeMs(map, ropePoint, stepX, movementProfile);
                int costAtMinY = BotPhysicsEngine.estimateRopeJumpLandingTimeMs(map, new Point(ropeX, minY), stepX, movementProfile);
                int costAtMaxY = BotPhysicsEngine.estimateRopeJumpLandingTimeMs(map, new Point(ropeX, maxY), stepX, movementProfile);
                addRopeWindowEdge(ropeRegion.id, toRegionId, ropePoint, rep.point(), minY, maxY, stepX,
                        costAtMinY, costAtMaxY, cost, outgoing, edgeKeys);
            }
        }

        // Rope-to-rope transfers need a tighter vertical sweep than generic rope exits. Same Y-window
        // treatment: for each target rope, expand the contiguous climb-height range that grabs it.
        for (int anchorY : ropeTransferAnchorYs(rope)) {
            for (BotNavigationGraph.Region otherRope : ropeRegions) {
                if (otherRope.id == ropeRegion.id) {
                    continue;
                }

                Rope targetRope = ropeByRegionId.get(otherRope.id);
                if (targetRope == null) {
                    continue;
                }

                int dx = Math.abs(ropeX - targetRope.x());
                if (dx > maxRopeJumpDx) {
                    continue;
                }

                int launchDir = targetRope.x() > ropeX ? jumpStep : -jumpStep;
                IntPredicate grabsTarget = y -> BotPhysicsEngine.simulateRopeJumpGrab(
                        map, new Point(ropeX, y), launchDir, targetRope, movementProfile) != null;
                if (!grabsTarget.test(anchorY)) {
                    continue;
                }
                int minY = findRopeLaunchBoundary(anchorY, loY, hiY, true, grabsTarget);
                int maxY = findRopeLaunchBoundary(anchorY, loY, hiY, false, grabsTarget);
                Point ropePoint = new Point(ropeX, (minY + maxY) / 2);
                Point ropeGrab = BotPhysicsEngine.simulateRopeJumpGrab(map, ropePoint, launchDir, targetRope, movementProfile);
                if (ropeGrab == null) {
                    continue;
                }
                int cost = BotPhysicsEngine.estimateRopeJumpGrabTimeMs(map, ropePoint, launchDir, targetRope, movementProfile);
                int costAtMinY = BotPhysicsEngine.estimateRopeJumpGrabTimeMs(map, new Point(ropeX, minY), launchDir, targetRope, movementProfile);
                int costAtMaxY = BotPhysicsEngine.estimateRopeJumpGrabTimeMs(map, new Point(ropeX, maxY), launchDir, targetRope, movementProfile);
                addRopeWindowEdge(ropeRegion.id, otherRope.id, ropePoint, ropeGrab, minY, maxY, launchDir,
                        costAtMinY, costAtMaxY, cost, outgoing, edgeKeys);
            }
        }
    }

    /*
     * Contiguous-window boundary search on the rope climb-height axis - the Y twin of findJumpBoundary.
     * From startY it doubles outward then binary-searches up (smaller Y, bounded by loY) or down (hiY) for
     * the last height where validY still holds. (nutnnut/Cosmic graph v62. Credit: NutNNut.)
     */
    static int findRopeLaunchBoundary(int startY, int loY, int hiY, boolean searchUp, IntPredicate validY) {
        int limitY = searchUp ? loY : hiY;
        int validYv = startY;
        int invalidY = startY;
        int step = 1;

        while (true) {
            int probeY = searchUp ? Math.max(limitY, startY - step) : Math.min(limitY, startY + step);
            if (probeY == validYv) {
                break;
            }
            if (!validY.test(probeY)) {
                invalidY = probeY;
                break;
            }
            validYv = probeY;
            if (probeY == limitY) {
                return probeY;
            }
            step *= 2;
        }

        while (Math.abs(validYv - invalidY) > 1) {
            int probeY = (validYv + invalidY) / 2;
            if (validY.test(probeY)) {
                validYv = probeY;
            } else {
                invalidY = probeY;
            }
        }
        return validYv;
    }

    private static void addTopStepOffEdge(BotNavigationGraph.Region ropeRegion,
                                          Rope rope,
                                          MapleMap map,
                                          Map<Integer, BotNavigationGraph.Region> regionsById,
                                          Map<Integer, Integer> regionIdByFootholdId,
                                          Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                          Set<String> edgeKeys) {
        // Same widened top-exit probe the runtime uses (BotPhysicsEngine.findTopExitLanding), so a
        // clean CLIMB step-off edge exists wherever physics would actually land the climb — otherwise
        // pathing falls back to ballistic top jump-offs that read as janky on uneven ground.
        Point landPoint = BotPhysicsEngine.findTopExitLanding(map, rope);
        if (landPoint == null) {
            return;
        }

        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, landPoint);
        if (foothold == null) {
            return;
        }

        BotNavigationGraph.Region ground = regionsById.get(regionIdByFootholdId.getOrDefault(foothold.getId(), -1));
        if (ground == null || ground.isRopeRegion) {
            return;
        }

        Point ropePoint = new Point(rope.x(), rope.topY());
        addEdge(ropeRegion.id, ground.id, BotNavigationGraph.EdgeType.CLIMB,
                ropePoint, landPoint, 0, 0, BotPhysicsEngine.cfg.TICK_MS, outgoing, edgeKeys);
    }
    private static List<Integer> ropeAnchorYs(Rope rope) {
        List<Integer> ys = new ArrayList<>();
        int firstClimbableY = BotPhysicsEngine.firstClimbableY(rope);
        ys.add(firstClimbableY);
        int interval = density().ropeAnchorIntervalPx();
        for (int y = rope.topY() + interval; y < rope.bottomY(); y += interval) {
            if (y > firstClimbableY) {
                ys.add(y);
            }
        }
        if (ys.getLast() != rope.bottomY()) {
            ys.add(rope.bottomY());
        }
        return ys;
    }

    private static Map<Integer, Rope> buildRopeByRegionId(MapleMap map, List<BotNavigationGraph.Region> ropeRegions) {
        Map<Integer, Rope> ropeByRegionId = new HashMap<>();
        for (BotNavigationGraph.Region ropeRegion : ropeRegions) {
            Rope rope = findRopeFromRegion(map, ropeRegion);
            if (rope != null) {
                ropeByRegionId.put(ropeRegion.id, rope);
            }
        }
        return ropeByRegionId;
    }

    private static List<Integer> ropeTransferAnchorYs(Rope rope) {
        List<Integer> ys = new ArrayList<>();
        int step = Math.max(1, BotPhysicsEngine.climbStepPerTick());
        int firstClimbableY = BotPhysicsEngine.firstClimbableY(rope);
        for (int y = firstClimbableY; y <= rope.bottomY(); y += step) {
            ys.add(y);
        }
        if (ys.isEmpty() || ys.getLast() != rope.bottomY()) {
            ys.add(rope.bottomY());
        }
        return ys;
    }

    static Rope findRopeFromRegion(MapleMap map, BotNavigationGraph.Region ropeRegion) {
        if (ropeRegion == null || !ropeRegion.isRopeRegion) {
            return null;
        }
        for (Rope rope : map.getRopes()) {
            if (rope.x() == ropeRegion.minX && rope.topY() == ropeRegion.minY && rope.bottomY() == ropeRegion.maxY) {
                return rope;
            }
        }
        return null;
    }

    /** v70 (b109): how far above a portal with no ground under it the floor it serves may lie (a vRange). */
    static final int PORTAL_UNDER_FLOOR_SNAP_PX = 100;

    private static void addPortalEdges(Portal portal,
                                       MapleMap map,
                                       Map<Integer, BotNavigationGraph.Region> regionsById,
                                       Map<Integer, Integer> regionIdByFootholdId,
                                       Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                       Set<String> edgeKeys) {
        if (portal.getTargetMapId() != map.getId()) {
            return;
        }

        Portal targetPortal = map.getPortal(portal.getTarget());
        if (targetPortal == null || targetPortal.getId() == portal.getId()) {
            return;
        }

        // Portal WZ positions can sit a few pixels below the foothold surface they belong to,
        // causing findBelow to skip the correct foothold and land on the next lower platform.
        // Probe MAX_SNAP_DROP above the portal position so findBelow always finds the right foothold.
        int snapUp = BotPhysicsEngine.cfg.MAX_SNAP_DROP;
        BotNavigationGraph.Region from = findRegionBelow(map, regionsById, regionIdByFootholdId,
                new Point(portal.getPosition().x, portal.getPosition().y - snapUp));
        if (from == null) {
            // v70 (b109): a touch portal set under the floor it serves (410000350 up00 at y 379, 44 px under the
            // floor at 335, hRange 2500 / vRange 100: walking onto that floor warps you up to inv00) has no ground
            // below it. Take the floor just above it instead, so the bottom floor keeps its only way out.
            from = findRegionBelow(map, regionsById, regionIdByFootholdId,
                    new Point(portal.getPosition().x, portal.getPosition().y - PORTAL_UNDER_FLOOR_SNAP_PX));
        }
        BotNavigationGraph.Region to = findRegionBelow(map, regionsById, regionIdByFootholdId,
                new Point(targetPortal.getPosition().x, targetPortal.getPosition().y - snapUp));
        if (from == null || to == null) {
            return;
        }

        Point start = from.pointAt(portal.getPosition().x);
        Point end = to.pointAt(targetPortal.getPosition().x);
        // A single portal is instantaneous, so its base edge cost is 0. The only real time a
        // portal costs is the post-use cooldown the bot pays when it chains straight into another
        // portal; that is modelled path-dependently in BotNavigationManager's A* (the viaPortal
        // state flag), not as a flat per-edge cost. See PORTAL_USE_COOLDOWN_MS.
        addEdge(from.id, to.id, BotNavigationGraph.EdgeType.PORTAL, start, end, 0, portal.getId(), 0, outgoing, edgeKeys);
    }

    private static Map<Integer, List<Integer>> buildFeatureXsByRegionId(MapleMap map,
                                                                        List<BotNavigationGraph.Region> regions,
                                                                        Map<Integer, Integer> regionIdByFootholdId) {
        Map<Integer, Set<Integer>> featureXs = new HashMap<>();

        for (Rope rope : map.getRopes()) {
            addFeatureX(featureXs, findRegionIdBelow(map, regionIdByFootholdId, new Point(rope.x(), rope.bottomY() - 1)), rope.x());
            addFeatureX(featureXs, findRegionIdBelow(map, regionIdByFootholdId, new Point(rope.x(), rope.topY() - 1)), rope.x());
            addFeatureX(featureXs, findRegionIdBelow(map, regionIdByFootholdId,
                    new Point(rope.x(), rope.topY() - BotMovementManager.cfg.JUMP_Y_THRESH * 2)), rope.x());
        }

        for (Portal portal : map.getPortals()) {
            int snapUp = BotPhysicsEngine.cfg.MAX_SNAP_DROP;
            Point portalProbe = new Point(portal.getPosition().x, portal.getPosition().y - snapUp);
            addFeatureX(featureXs, findRegionIdBelow(map, regionIdByFootholdId, portalProbe), portal.getPosition().x);
            if (portal.getTargetMapId() != map.getId()) {
                continue;
            }

            Portal targetPortal = map.getPortal(portal.getTarget());
            if (targetPortal != null) {
                Point targetProbe = new Point(targetPortal.getPosition().x, targetPortal.getPosition().y - snapUp);
                addFeatureX(featureXs, findRegionIdBelow(map, regionIdByFootholdId, targetProbe), targetPortal.getPosition().x);
            }
        }

        for (BotNavigationGraph.Region region : regions) {
            if (region.isRopeRegion) {
                continue;
            }
            // Left/right endpoints of any platform are X positions where a jump trajectory
            // from the region below qualitatively changes (clears the platform vs. lands on it).
            // Without these as feature anchors, narrow launch windows for "jump-over" or
            // "land-on-intermediate" edges can have zero seeded samples and be missed.
            projectRegionXsToRegionBelow(featureXs, map, regionIdByFootholdId, region.leftPoint());
            projectRegionXsToRegionBelow(featureXs, map, regionIdByFootholdId, region.rightPoint());
            if (region.width() <= 64) {
                projectRegionXsToRegionBelow(featureXs, map, regionIdByFootholdId, region.centerPoint());
            }
        }

        Map<Integer, List<Integer>> featuresByRegionId = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : featureXs.entrySet()) {
            List<Integer> xs = new ArrayList<>(entry.getValue());
            xs.sort(Integer::compareTo);
            featuresByRegionId.put(entry.getKey(), xs);
        }
        return featuresByRegionId;
    }

    private static void projectRegionXsToRegionBelow(Map<Integer, Set<Integer>> featureXs,
                                                     MapleMap map,
                                                     Map<Integer, Integer> regionIdByFootholdId,
                                                     Point point) {
        if (point == null) {
            return;
        }
        addFeatureX(featureXs, findRegionIdBelow(map, regionIdByFootholdId, new Point(point.x, point.y + 1)), point.x);
    }

    private static void addFeatureX(Map<Integer, Set<Integer>> featureXs, int regionId, int x) {
        if (regionId < 0) {
            return;
        }
        featureXs.computeIfAbsent(regionId, ignored -> new HashSet<>()).add(x);
    }

    private static int findRegionIdBelow(MapleMap map,
                                         Map<Integer, Integer> regionIdByFootholdId,
                                         Point point) {
        if (map.getFootholds() == null) {
            return -1;
        }

        Foothold foothold = BotPhysicsEngine.findBelowIndexed(map, point);
        if (foothold == null) {
            return -1;
        }

        return regionIdByFootholdId.getOrDefault(foothold.getId(), -1);
    }

    private static BotNavigationGraph.Region findRegionBelow(MapleMap map,
                                                             Map<Integer, BotNavigationGraph.Region> regionsById,
                                                             Map<Integer, Integer> regionIdByFootholdId,
                                                             Point point) {
        int regionId = findRegionIdBelow(map, regionIdByFootholdId, point);
        if (regionId < 0) {
            return null;
        }
        return regionsById.get(regionId);
    }

    private static List<Point> anchorPoints(MapleMap map,
                                            BotNavigationGraph.Region region,
                                            List<Integer> featureXs,
                                            BotMovementProfile movementProfile) {
        List<Point> points = new ArrayList<>();
        SampleDensity density = density();
        int endpointSpacing = density.endpointSpacingPx();
        addAnchor(points, region.leftPoint());
        // Near-edge anchors for better jump/drop accuracy at platform boundaries
        int edgeInset = Math.max(8, (int) Math.round(movementProfile.walkVelocityPxs() * BotPhysicsEngine.cfg.TICK_MS / 1000.0));
        if (region.width() > edgeInset * 2) {
            addAnchor(points, region.pointAt(region.minX + edgeInset), endpointSpacing);
            addAnchor(points, region.pointAt(region.maxX - edgeInset), endpointSpacing);
        }
        int ticksToApex = Math.max(1, (int) Math.ceil(
                BotPhysicsEngine.jumpForcePerTick(movementProfile) / Math.max(0.001f, BotPhysicsEngine.gravityPerTick())));
        int jumpInset = Math.max(edgeInset * 3, BotPhysicsEngine.walkStep(map, movementProfile) * ticksToApex);
        if (region.width() > jumpInset * 2) {
            addAnchor(points, region.pointAt(region.minX + jumpInset), endpointSpacing);
            addAnchor(points, region.pointAt(region.maxX - jumpInset), endpointSpacing);
        }
        // Interior densification: narrow launch windows for jumps over or onto platforms above
        // can sit in the middle of a wide region with no naturally-derived feature X. Without
        // dense interior sampling, findJumpBoundary has no seed and the edge is missed entirely.
        // Spacing matches walkStep so a launch window as narrow as a single pre-launch tick
        // (≈walkStep px) is guaranteed at least one seed sample. Repeated sims are absorbed by
        // jumpLandingCache.
        int walkStep = BotPhysicsEngine.walkStep(map, movementProfile);
        int interiorSpacing = Math.max(density.interiorSpacingPx() > 0 ? density.interiorSpacingPx() : walkStep, 1);
        if (region.width() > edgeInset * 2) {
            for (int x = region.minX + edgeInset;
                 x <= region.maxX - edgeInset;
                 x += interiorSpacing) {
                addAnchor(points, region.pointAt(x), 0);
            }
        } else {
            // v70 (b109): a ledge no wider than two edge insets got anchors only at its two ends. Where both ends
            // stand against a wall (a 5 px notch under a 12 px step, 105070200 r11; 105090000 r21) both launches are
            // wall-blocked, so the notch had no way out and every bot that stepped into it stayed. Seed every pixel
            // between the ends: at most 2 x edgeInset anchors, on few regions.
            for (int x = region.minX + 1; x < region.maxX; x++) {
                addAnchor(points, region.pointAt(x), 0);
            }
        }
        for (BotNavigationGraph.Segment segment : region.segments) {
            addAnchor(points, new Point(segment.x1, segment.y1), endpointSpacing);
            addAnchor(points, new Point(segment.x2, segment.y2), endpointSpacing);
        }
        if (region.width() >= Math.max(BotMovementManager.cfg.FOLLOW_DIST * 2, 140)) {
            addAnchor(points, region.centerPoint());
        }
        if (region.width() >= Math.max(BotMovementManager.cfg.FOLLOW_DIST * 4, 260)) {
            addAnchor(points, region.pointAt(region.minX + region.width() / 3));
            addAnchor(points, region.pointAt(region.maxX - region.width() / 3));
        }
        addAnchor(points, region.rightPoint());
        for (int featureX : featureXs) {
            if (featureX >= region.minX && featureX <= region.maxX) {
                addAnchor(points, region.pointAt(featureX));
            }
        }
        points.sort(Comparator.comparingInt((Point point) -> point.x).thenComparingInt(point -> point.y));
        return points;
    }

    private static void addAnchor(List<Point> points, Point point) {
        addAnchor(points, point, 0);
    }

    private static void addAnchor(List<Point> points, Point point, int minSpacingPx) {
        for (Point existing : points) {
            if (existing.equals(point)) {
                return;
            }
            if (minSpacingPx > 0
                    && Math.abs(existing.x - point.x) <= minSpacingPx
                    && Math.abs(existing.y - point.y) <= 8) {
                return;
            }
        }
        points.add(point);
    }

    private static boolean isWalkConnection(EndpointConnection connection) {
        int dx = Math.abs(connection.to.x - connection.from.x);
        int dy = connection.to.y - connection.from.y;
        return BotPhysicsEngine.isWalkableEndpointStep(dx, dy);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchMinX,
                                int launchMaxX,
                                int launchStepX,
                                int portalId,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, type, startPoint, endPoint, launchMinX, launchMaxX, launchStepX, portalId,
                0, 0, 0, cost, outgoing, edgeKeys);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchStepX,
                                int portalId,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, type, startPoint, endPoint, startPoint.x, startPoint.x,
                startPoint.y, startPoint.y, launchStepX, portalId, 0, 0, 0, cost, cost, cost, outgoing, edgeKeys);
    }

    /* Rope-exit / rope-transfer CLIMB edge carrying a Y launch window [launchMinY, launchMaxY] at a fixed
     * rope x (startPoint.x). Mirror of the windowed JUMP/DROP addEdge, but the launch axis is the climb Y. */
    private static void addRopeWindowEdge(int fromRegionId,
                                          int toRegionId,
                                          Point startPoint,
                                          Point endPoint,
                                          int launchMinY,
                                          int launchMaxY,
                                          int launchStepX,
                                          int costAtMinY,
                                          int costAtMaxY,
                                          int cost,
                                          Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                          Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, BotNavigationGraph.EdgeType.CLIMB, startPoint, endPoint,
                startPoint.x, startPoint.x, launchMinY, launchMaxY, launchStepX, 0, 0, 0, 0,
                costAtMinY, costAtMaxY, cost, outgoing, edgeKeys);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchMinX,
                                int launchMaxX,
                                int launchStepX,
                                int portalId,
                                int ropeX,
                                int ropeTopY,
                                int ropeBottomY,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, type, startPoint, endPoint, launchMinX, launchMaxX,
                startPoint.y, startPoint.y, launchStepX, portalId, ropeX, ropeTopY, ropeBottomY, cost, cost, cost,
                outgoing, edgeKeys);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchMinX,
                                int launchMaxX,
                                int launchMinY,
                                int launchMaxY,
                                int launchStepX,
                                int portalId,
                                int ropeX,
                                int ropeTopY,
                                int ropeBottomY,
                                int launchCostAtMinY,
                                int launchCostAtMaxY,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        String key = fromRegionId + ":" + toRegionId + ":" + type + ":" + startPoint.x + ":" + startPoint.y + ":"
                + endPoint.x + ":" + endPoint.y + ":" + launchStepX + ":" + portalId + ":"
                + ropeX + ":" + ropeTopY + ":" + ropeBottomY + ":" + launchMinX + ":" + launchMaxX
                + ":" + launchMinY + ":" + launchMaxY;
        if (!edgeKeys.add(key)) {
            return;
        }

        outgoing.computeIfAbsent(fromRegionId, ignored -> new ArrayList<>())
                .add(new BotNavigationGraph.Edge(fromRegionId, toRegionId, type, startPoint, endPoint,
                        launchMinX, launchMaxX, launchMinY, launchMaxY, launchStepX, portalId, ropeX, ropeTopY,
                        ropeBottomY, launchCostAtMinY, launchCostAtMaxY, cost));
        BuildProfileBuilder profile = ACTIVE_BUILD_PROFILE.get();
        if (profile != null) {
            profile.recordEdge(type);
        }
    }

    private static EndpointConnection closestEndpointConnection(Foothold first, Foothold second) {
        Point[] firstEndpoints = new Point[]{
                new Point(first.getX1(), first.getY1()),
                new Point(first.getX2(), first.getY2())
        };
        Point[] secondEndpoints = new Point[]{
                new Point(second.getX1(), second.getY1()),
                new Point(second.getX2(), second.getY2())
        };

        EndpointConnection best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Point from : firstEndpoints) {
            for (Point to : secondEndpoints) {
                int distance = Math.abs(to.x - from.x) + Math.abs(to.y - from.y);
                if (distance < bestDistance) {
                    best = new EndpointConnection(from, to);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static EndpointConnection sharedEndpointConnection(Foothold first, Foothold second) {
        Point[] firstEndpoints = new Point[]{
                new Point(first.getX1(), first.getY1()),
                new Point(first.getX2(), first.getY2())
        };
        Point[] secondEndpoints = new Point[]{
                new Point(second.getX1(), second.getY1()),
                new Point(second.getX2(), second.getY2())
        };

        for (Point from : firstEndpoints) {
            for (Point to : secondEndpoints) {
                if (from.equals(to)) {
                    return new EndpointConnection(from, to);
                }
            }
        }
        return null;
    }

    private static int footholdMinX(Foothold foothold) {
        return Math.min(foothold.getX1(), foothold.getX2());
    }

    private static int groupMinX(List<Foothold> footholds) {
        int minX = Integer.MAX_VALUE;
        for (Foothold foothold : footholds) {
            minX = Math.min(minX, footholdMinX(foothold));
        }
        return minX;
    }

    private static int groupMinY(List<Foothold> footholds) {
        int minY = Integer.MAX_VALUE;
        for (Foothold foothold : footholds) {
            minY = Math.min(minY, Math.min(foothold.getY1(), foothold.getY2()));
        }
        return minY;
    }

    private static int estimateWalkCost(Point start, Point end) {
        return estimateWalkCost(start, end, BotMovementProfile.base());
    }

    private static int estimateWalkCost(Point start, Point end, BotMovementProfile movementProfile) {
        return estimateHorizontalTravelTimeMs(Math.abs(end.x - start.x), movementProfile);
    }

    private static int estimateHorizontalTravelTimeMs(int dx, BotMovementProfile movementProfile) {
        return Math.max(0, (int) Math.round((dx * 1000.0) / Math.max(1.0, movementProfile.walkVelocityPxs())));
    }

    private static int dropLaunchStep(BotNavigationGraph.Region region, MapleMap map, Point anchor, BotMovementProfile movementProfile) {
        Point left = region.leftPoint();
        if (Math.abs(anchor.x - left.x) <= ENDPOINT_ANCHOR_SPACING_PX && Math.abs(anchor.y - left.y) <= 12) {
            return -BotMovementManager.walkStep(map, movementProfile);
        }

        Point right = region.rightPoint();
        if (Math.abs(anchor.x - right.x) <= ENDPOINT_ANCHOR_SPACING_PX && Math.abs(anchor.y - right.y) <= 12) {
            return BotMovementManager.walkStep(map, movementProfile);
        }

        return 0;
    }

    private record EndpointConnection(Point from, Point to) {
    }



    private static final class UnionFind {
        private final Map<Integer, Integer> parent = new HashMap<>();
        private final Map<Integer, Integer> rank = new HashMap<>();

        void add(int value) {
            parent.putIfAbsent(value, value);
            rank.putIfAbsent(value, 0);
        }

        int find(int value) {
            Integer parentValue = parent.get(value);
            if (parentValue == null) {
                add(value);
                return value;
            }
            if (parentValue == value) {
                return value;
            }

            int root = find(parentValue);
            parent.put(value, root);
            return root;
        }

        void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot == secondRoot) {
                return;
            }

            int firstRank = rank.getOrDefault(firstRoot, 0);
            int secondRank = rank.getOrDefault(secondRoot, 0);
            if (firstRank < secondRank) {
                parent.put(firstRoot, secondRoot);
                return;
            }
            if (firstRank > secondRank) {
                parent.put(secondRoot, firstRoot);
                return;
            }

            parent.put(secondRoot, firstRoot);
            rank.put(firstRoot, firstRank + 1);
        }
    }
}
