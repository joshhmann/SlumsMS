package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import net.server.Server;
import net.server.world.World;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.BotShutdown;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Observability tracker for the GCMoveSystem LOD layer. A single ~1s poll over the online real
 * players (id ≤ 20000) maintains two sets of map ids:
 * 
 *   fullMaps — a real player is on the map (FULL tier: physics + broadcast).
 *   haloMaps — a portal-adjacent map a real player is standing near the entrance to (HALO: pre-warm
 *              physics on the map they're about to walk into).
 *
 *
 * The driver/scheduler reads .isActiveMap(int) to decide whether a bot's map is worth full
 * fidelity. Polling (rather than hooking MapleMap.addPlayer/removePlayer) keeps this entirely
 * additive — no Cosmic base file is touched. Halo is proximity-gated: a neighbour is warmed only while
 * a player is within HALO_PORTAL_RADIUS_PX of the portal leading to it — this bounds hub fan-out (a
 * player mid-town near no portal warms nothing) and pre-warms the map being approached. It uses
 * GCWorldGraph's raw portal adjacency to filter (not route(), which adds non-adjacent taxi edges) and
 * never forces the world graph to build — halo stays empty until that graph exists via some other path.
 * Anything not pre-warmed in time still snaps to FULL instantly on actual entry (BotMapEntryResponder).
 */
final class ObserverTracker {
    private ObserverTracker() {
    }

    private static final long POLL_MS = 1000;
    // Hysteresis (M3): a map keeps full fidelity for this long after losing its last real player, so a
    // player pacing across a portal can't flap bots between physics and coarse.
    // Promotion is immediate; only demotion waits out the dwell ("err toward FULL — over-render is safe").
    private static final long DWELL_MS = 4000;

    // HALO proximity gate: a neighbour map is pre-warmed only while a real player is within this many
    // pixels of the portal that leads to it — about the lead distance for the ~1s poll + bot promotion
    // to finish before the player crosses. A player mid-town (near no portal) warms nothing. Tune live.
    private static final int HALO_PORTAL_RADIUS_PX = 350;
    private static final double HALO_PORTAL_RADIUS_SQ =
            (double) HALO_PORTAL_RADIUS_PX * HALO_PORTAL_RADIUS_PX;

    private static volatile Set<Integer> fullMaps = Set.of();   // raw: a real player is on the map
    private static volatile Set<Integer> haloMaps = Set.of();   // proximity: a player is near the portal to it
    // 1.6.2 audit #3 (b112): the map INSTANCES a real player is on. "Watched" (real quest scripts, real rewards, real
    // combat) is per instance: the same map id on another channel, or another event instance, is not watched.
    // Map ids above stay for the LOD physics tiers, where over-rendering is safe.
    private static volatile Set<MapleMap> fullInstances = Set.of();

    // The sticky "active" set (full ∪ halo, held for DWELL_MS after observation) — the dwell hysteresis.
    private static final TierDwell DWELL = new TierDwell(DWELL_MS);

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    // Ticket 09: the poll's own pool, kept so shutdown can cancel it.
    private static volatile ScheduledExecutorService pollPool;

    // Transient promotion override: when a bot ENTERS a map that already has a real player, we mark
    // that map observed immediately so FULL behaviour (visible spawn, broadcast, combat) starts the
    // same tick instead of waiting up to one poll. Short TTL (> POLL_MS) so the poll takes back over.
    private static final long FORCE_TTL_MS = 2_000;
    private static final Map<Integer, Long> forcedFull = new ConcurrentHashMap<>();
    // Audit #3: the same override for one map instance (BotMapEntryResponder, a bot arriving next to a player).
    // MapleMap keeps identity equality, so this is keyed by the instance.
    private static final Map<MapleMap, Long> forcedInstances = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> forcedLod = new ConcurrentHashMap<>();

    private static boolean forcedUntil(Map<Integer, Long> m, int mapId) {
        Long until = m.get(mapId);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            m.remove(mapId);
            return false;
        }
        return true;
    }

    // Ticket 157 (idea from nutnnut/Cosmic /api/lod, 77e0c5bc9): maps a GM pinned to full simulation with
    // !env lod pin (TEST only, at most MAX_PINS, gone on restart), so auto-QA and BotWatch see real behaviour there
    // with nobody standing on them. Empty Set.of() when none: the hot-path check is one contains on an empty set.
    static final int MAX_PINS = 5;
    private static volatile Set<Integer> pinned = Set.of();

    /* Pins a map to FULL. Returns null when pinned, else why not. */
    static synchronized String pin(int mapId) {
        if (mapId <= 0) {
            return "not a map id: " + mapId;
        }
        if (pinned.contains(mapId)) {
            return null;
        }
        if (pinned.size() >= MAX_PINS) {
            return "already " + MAX_PINS + " maps pinned (the cap); unpin one first";
        }
        Set<Integer> next = new HashSet<>(pinned);
        next.add(mapId);
        pinned = Set.copyOf(next);
        return null;
    }

    /* Releases a pinned map; false when it was not pinned. */
    static synchronized boolean unpin(int mapId) {
        if (!pinned.contains(mapId)) {
            return false;
        }
        Set<Integer> next = new HashSet<>(pinned);
        next.remove(mapId);
        pinned = next.isEmpty() ? Set.of() : Set.copyOf(next);
        return true;
    }

    static Set<Integer> pinned() {
        return pinned;
    }

    static boolean isPinned(int mapId) {
        return pinned.contains(mapId);
    }

    /* Test seam: these map instances (and their ids) have a real player on them. */
    static void observeForTest(Set<MapleMap> maps) {
        Set<Integer> ids = new HashSet<>();
        for (MapleMap m : maps) {
            ids.add(m.getId());
        }
        fullInstances = Set.copyOf(maps);
        fullMaps = Set.copyOf(ids);
    }

    /* Test seam: no pins. */
    static synchronized void clearPinsForTest() {
        pinned = Set.of();
    }

    // A real (non-bot) player is on this map right now — a live check, not the cached poll.
    static boolean hasRealPlayerNow(MapleMap map) {
        if (map == null) {
            return false;
        }
        for (Character c : map.getAllPlayers()) {
            if (c != null && !BotHelpers.isBot(c)) {
                return true;
            }
        }
        return false;
    }

    // Treat mapId as observed (FULL) immediately for a short window, regardless of the 1s poll.
    static void markObservedNow(int mapId) {
        forcedFull.put(mapId, System.currentTimeMillis() + FORCE_TTL_MS);
    }

    // Treat this map instance as observed (FULL) for a short window; its id is held at full LOD physics too.
    static void markObservedNow(MapleMap map) {
        if (map == null) {
            return;
        }
        long until = System.currentTimeMillis() + FORCE_TTL_MS;
        forcedInstances.put(map, until);
        forcedLod.put(map.getId(), until); // the id's LOD physics (isActiveMap); not "watched" on other instances
    }

    private static boolean forced(MapleMap map) {
        Long until = forcedInstances.get(map);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            forcedInstances.remove(map);
            return false;
        }
        return true;
    }

    private static boolean forced(int mapId) {
        Long until = forcedFull.get(mapId);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            forcedFull.remove(mapId);
            return false;
        }
        return true;
    }

    /* Start the poll once (idempotent). Called from GCMovement.enable(Character). */
    static void ensureStarted() {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: no observer poll into a shutdown
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gcmove-observer");
            t.setDaemon(true);
            return t;
        });
        pollPool = pool;
        pool.scheduleAtFixedRate(ObserverTracker::safeRefresh, 0, POLL_MS, TimeUnit.MILLISECONDS);
        BotShutdown.onQuiesce(ObserverTracker::stopPoll);
    }

    /* Ticket 09: cancel the observer poll. The pool is ours, so shutting it down cancels the task. */
    private static void stopPoll() {
        ScheduledExecutorService pool = pollPool;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /* Whether the observer poll is running (started by the first GCMovement.enable). */
    static boolean started() {
        return STARTED.get();
    }

    /* A real player is on this map (FULL tier). */
    static boolean isFull(int mapId) {
        return fullMaps.contains(mapId) || forced(mapId) || forcedUntil(forcedLod, mapId) || pinned.contains(mapId);
    }

    /*
     * Audit #3: a real player is on THIS map instance (same channel, same event instance), or a short entry override
     * marked it, or a GM pinned its id (TEST, ticket 157: a pin holds the id on every channel), or a test/tool forced
     * the id with markObservedNow(int). The "watched" gate for real quests, rewards and combat.
     */
    static boolean isFull(MapleMap map) {
        if (map == null) {
            return false;
        }
        return fullInstances.contains(map) || forced(map) || pinned.contains(map.getId()) || forcedById(map.getId());
    }

    // markObservedNow(int) without an instance (a tool or test forcing an id): every instance of it
    private static boolean forcedById(int mapId) {
        return forcedUntil(forcedFull, mapId);
    }

    /* A real player is standing near the portal leading to this map (HALO tier). */
    static boolean isHalo(int mapId) {
        return haloMaps.contains(mapId);
    }

    /*
     * The map is worth full-fidelity physics (and is exempt from LOD throttling/coarse): FULL or HALO
     * now, OR within the post-observation dwell window. This is the set the driver gates on.
     */
    static boolean isActiveMap(int mapId) {
        return DWELL.isActive(mapId) || forced(mapId) || forcedUntil(forcedLod, mapId) || pinned.contains(mapId);
    }

    static int fullCount() {
        return fullMaps.size();
    }

    static int haloCount() {
        return haloMaps.size();
    }

    /* Maps held at full fidelity right now (FULL ∪ HALO ∪ dwelling). */
    static int activeCount() {
        return DWELL.size();
    }

    /* Snapshot of the maps currently FULL (a real player present) — diagnostics/inspection only. */
    static Set<Integer> fullMaps() {
        return fullMaps;
    }

    /* Snapshot of the maps currently HALO (portal-adjacent to a real player) — diagnostics/inspection only. */
    static Set<Integer> haloMaps() {
        return haloMaps;
    }

    private static void safeRefresh() {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: keep the last-known sets and stop walking worlds that are going away
        }
        try {
            refresh();
        } catch (Throwable ignored) {
            // a thrown poll would cancel the periodic task — keep the last-known sets
        }
    }

    private static void refresh() {
        long startedAt = System.nanoTime();
        try {
            refresh0();
        } finally {
            BotPerformanceMonitor.recordObserverPoll(System.nanoTime() - startedAt);
        }
    }

    private static void refresh0() {
        Server server = Server.getInstance();
        if (server == null) {
            return;
        }
        Set<Integer> full = new HashSet<>();
        Set<MapleMap> instances = new HashSet<>();
        List<Character> realPlayers = new ArrayList<>();
        for (World world : server.getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (chr != null && !BotHelpers.isBot(chr)) {
                    full.add(chr.getMapId());
                    MapleMap on = chr.getMap();
                    if (on != null) {
                        instances.add(on);
                    }
                    realPlayers.add(chr);
                }
            }
        }

        // HALO is proximity-gated: warm a neighbour only while a player is standing near the portal that
        // leads to it, instead of warming every neighbour of an occupied map (which warms a whole region
        // from a hub town). Only fill from an already-built world graph — never force the ~5k-map scan.
        Set<Integer> halo = new HashSet<>();
        if (GCWorldGraph.isReady()) {
            for (Character chr : realPlayers) {
                addNearbyPortalMaps(chr, full, halo);
            }
        }

        // Hysteresis: feed the observed (full ∪ halo) maps into the dwell machine, which keeps each
        // active for DWELL_MS past its last observation — so demotion lags but promotion is immediate.
        Set<Integer> observed = new HashSet<>(full);
        observed.addAll(halo);
        Set<Integer> activeBefore = DWELL.active();
        DWELL.observe(observed, System.currentTimeMillis());
        Set<Integer> activeAfter = DWELL.active();
        int promoted = 0;
        for (int mapId : activeAfter) {
            if (!activeBefore.contains(mapId)) {
                promoted++;
            }
        }
        int demoted = 0;
        for (int mapId : activeBefore) {
            if (!activeAfter.contains(mapId)) {
                demoted++;
            }
        }
        BotPerformanceMonitor.recordTierFlips(promoted, demoted);

        fullMaps = full.isEmpty() ? Set.of() : Set.copyOf(full);
        fullInstances = instances.isEmpty() ? Set.of() : Set.copyOf(instances);
        long nowMs = System.currentTimeMillis();
        forcedInstances.values().removeIf(until -> until < nowMs); // never hold a closed event instance's map
        haloMaps = halo.isEmpty() ? Set.of() : Set.copyOf(halo);
    }

    // Add to `halo` the walkable target maps of any portal on the player's map within
    // HALO_PORTAL_RADIUS of the player. Intersecting each portal's target with GCWorldGraph's portal
    // neighbours keeps the filtering (doors / scripted / version-gated / no-target portals excluded)
    // consistent with the rest of the LOD layer, and gives the portal positions the graph doesn't carry.
    private static void addNearbyPortalMaps(Character chr, Set<Integer> full, Set<Integer> halo) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return;
        }
        int[] walkable = GCWorldGraph.portalNeighbors(chr.getMapId());
        if (walkable.length == 0) {
            return;
        }
        Point pos = chr.getPosition();
        if (pos == null) {
            return;
        }
        for (Portal portal : map.getPortals()) {
            if (portal == null) {
                continue;
            }
            int target = portal.getTargetMapId();
            if (target <= 0 || full.contains(target) || !contains(walkable, target)) {
                continue;
            }
            Point pp = portal.getPosition();
            if (pp != null && pos.distanceSq(pp) <= HALO_PORTAL_RADIUS_SQ) {
                halo.add(target);
            }
        }
    }

    private static boolean contains(int[] arr, int value) {
        for (int x : arr) {
            if (x == value) {
                return true;
            }
        }
        return false;
    }
}
