package soloMapling.ArtificialPlayer.GCMoveSystem;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotScriptedWarp;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;

import static soloMapling.server.MapleVersionManager.isPortalinCurrentVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/*
 * Portals-only world connectivity graph for GCTravel: which map walkably connects to
 * which. Scanned once from Map.wz — non-door, unscripted portals with a real target map,
 * following info/link exactly like MapFactory so the edge set matches the runtime map.
 *
 * Routing (ticket 03) adds the curated scripted warps and BotTransit's paid/scheduled hops (cabs,
 * boats, trains, ferries) on top, and picks the cheapest route by time. A route nothing connects
 * returns null from .route and GCTravel warps instead. Built lazily in-memory on first use
 * (~5k XMLs in a few seconds on a worker pool); not disk-cached.
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCWorldGraph {
    private GCWorldGraph() {
    }

    private static final int NO_TARGET_MAPID = 999999999; // tm of spawn points / doors
    private static final int[] EMPTY = new int[0];
    private static volatile Map<Integer, int[]> edges;

    static Map<Integer, int[]> get() {
        Map<Integer, int[]> cached = edges;
        if (cached != null) {
            return cached;
        }
        synchronized (GCWorldGraph.class) {
            if (edges == null) {
                edges = build();
            }
            return edges;
        }
    }

    static boolean isReady() {
        return edges != null;
    }

    /*
     * Raw walkable-portal neighbours of mapId (the visually-adjacent maps) — for the LOD halo.
     * Unlike .neighbors this excludes transit destinations, and unlike .get it never
     * triggers the world-graph build: returns empty until the graph already exists.
     */
    static int[] portalNeighbors(int mapId) {
        Map<Integer, int[]> g = edges; // read the volatile directly — do NOT force a build
        return g == null ? EMPTY : g.getOrDefault(mapId, EMPTY);
    }

    static int mapCount() {
        return get().size();
    }

    /*
     * Cheapest route from fromMapId to toMapId: the ordered map ids to enter, ending with toMapId. Empty
     * list when already there; null when not reachable within maxHops (caller warps instead). Rebirth gates
     * are all open except the never-enter maps, and paid transit is allowed - see the full overload.
     */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops) {
        return route(fromMapId, toMapId, maxHops, 3, true, NO_SAVED_RETURN);
    }

    /* The World Tour's way home when a bot has no saved town: the script's own default (Lith Harbor). */
    static final int NO_SAVED_RETURN = constants.id.MapId.LITH_HARBOUR;

    /*
     * Ticket 03: cheapest route by time over walkable portals, curated scripted warps and BotTransit edges
     * (cabs, boats, trains, ferries...). A walked hop costs WALK_SEC; a transit hop costs its `sec`, so a
     * boat's wait and voyage weigh what they really take. Maps the bot's rebirths don't open are never passed
     * through (the destination itself is the caller's call). allowFares=false keeps to free hops - a bot
     * that can't pay replans on foot. A "return to saved" edge (the World Tour home) only leads to
     * savedReturn, the town the bot really left from. Routes are cached: the graph and the transit table
     * are static.
     */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops, int rebirths, boolean allowFares,
                               int savedReturn) {
        return route(fromMapId, toMapId, maxHops, rebirths, allowFares, savedReturn, ANY_LEVEL);
    }

    /* A level no script's level check turns away: for callers that plan for no particular bot. */
    static final int ANY_LEVEL = Integer.MAX_VALUE;

    /* As above for a bot of this level: an edge whose script wants a higher level (Jeff's 50) is not offered. */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops, int rebirths, boolean allowFares,
                               int savedReturn, int level) {
        if (fromMapId == toMapId) {
            return List.of();
        }
        if (maxHops <= 0) {
            return null;
        }
        int tier = Math.max(0, Math.min(3, rebirths));
        // the highest level check this bot passes: few distinct keys. ANY_LEVEL stays itself: it passes every script's
        // level check but opens no level gate (ticket 81), so a plan for no particular bot keeps the rebirth rule
        int levelTier = level == ANY_LEVEL ? ANY_LEVEL : BotTransit.levelTier(level);
        RouteKey key = new RouteKey(fromMapId, toMapId, maxHops, tier, allowFares, savedReturn, levelTier);
        List<Integer> cached = ROUTES.get(key);
        if (cached != null) {
            return cached == NO_ROUTE ? null : cached;
        }
        List<Integer> found = dijkstra(get(), fromMapId, toMapId, maxHops, tier, allowFares, savedReturn, levelTier);
        if (ROUTES.size() >= ROUTE_CACHE_MAX) {
            ROUTES.clear(); // crude bound - a refill costs one search per pair
        }
        ROUTES.put(key, found == null ? NO_ROUTE : found);
        return found;
    }

    static final int WALK_SEC = 20; // planner cost of one walked portal hop

    /* Route-search depth cap for every caller (GCTravel, GCMovement). Henesys to the far end of Arcane River or
     * Black Heaven is ~100 hops counting docks and decks; Omega Sector alone was 60, just past the old cap. */
    static final int MAX_ROUTE_HOPS = 160;

    /* Virtual planner nodes for the Hyper Teleport Rock (BotTransit.HyperRock): every map steps to HYPER_IN at the
     * rock's cooldown, HYPER_IN steps to each `into` map for free; maps in an `out_of` range step to HYPER_OUT,
     * which steps to each `home` town. Never part of a returned route. */
    static final int HYPER_IN = -2;
    static final int HYPER_OUT = -3;

    /* Forget cached routes (after the transit table is reloaded). */
    static void clearRouteCache() {
        ROUTES.clear();
    }

    private static final int ROUTE_CACHE_MAX = 50_000;
    private static final List<Integer> NO_ROUTE = List.of(-1);
    private static final Map<RouteKey, List<Integer>> ROUTES = new ConcurrentHashMap<>();

    private record RouteKey(int from, int to, int maxHops, int tier, boolean fares, int savedReturn, int levelTier) {
    }

    static List<Integer> dijkstra(Map<Integer, int[]> g, int fromMapId, int toMapId, int maxHops, int rebirths,
                                  boolean allowFares, int savedReturn) {
        return dijkstra(g, fromMapId, toMapId, maxHops, rebirths, allowFares, savedReturn, ANY_LEVEL);
    }

    static List<Integer> dijkstra(Map<Integer, int[]> g, int fromMapId, int toMapId, int maxHops, int rebirths,
                                  boolean allowFares, int savedReturn, int level) {
        Map<Integer, Integer> cameFrom = new HashMap<>();
        Map<Integer, Long> best = new HashMap<>();
        Map<Integer, Integer> hops = new HashMap<>();
        java.util.Set<Integer> toured = new java.util.HashSet<>(); // reached through an outbound World Tour
        java.util.Set<Integer> rocked = new java.util.HashSet<>(); // reached through a hyper-rock jump
        // A bot already inside a gated area (put there by a GM, a party warp...) may walk back out through it.
        // level here is the transit level tier (BotTransit.levelTier): 120 for a bot of 120+, which opens the
        // Future and lower Grandis (ticket 81); ANY_LEVEL (no particular bot) opens no level gate
        int gateLevel = level == ANY_LEVEL ? 0 : level;
        boolean escaping = !BotMapGates.mayEnter(fromMapId, rebirths, gateLevel);
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        best.put(fromMapId, 0L);
        hops.put(fromMapId, 0);
        open.add(new long[]{0L, fromMapId});
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int current = (int) top[1];
            long cost = top[0];
            if (cost > best.getOrDefault(current, Long.MAX_VALUE)) {
                continue; // stale queue entry
            }
            if (current == toMapId) {
                return reconstruct(cameFrom, fromMapId, toMapId);
            }
            int depth = hops.get(current);
            if (depth >= maxHops) {
                continue;
            }
            for (long step : steps(g, current, allowFares, savedReturn, rebirths, level)) {
                int next = (int) (step >>> 32);
                int sec = (int) step & SEC_MASK;
                if (!escaping && next != toMapId && !BotMapGates.mayEnter(next, rebirths, gateLevel)) {
                    continue; // never pass through a map this bot's rebirths don't open
                }
                boolean wasToured = toured.contains(current);
                if ((step & RETURNS) != 0 && wasToured) {
                    continue; // a tour taken on this very route goes home to where it started: no shortcut
                }
                boolean wasRocked = rocked.contains(current);
                if ((step & ROCK) != 0 && wasRocked) {
                    continue; // one jump per route: never in and straight back out as a shortcut past the boats
                }
                long nextCost = cost + sec;
                if (nextCost < best.getOrDefault(next, Long.MAX_VALUE)) {
                    best.put(next, nextCost);
                    hops.put(next, depth + 1);
                    cameFrom.put(next, current);
                    if (wasToured || (step & SAVES) != 0) {
                        toured.add(next);
                    } else {
                        toured.remove(next);
                    }
                    if (wasRocked || (step & ROCK) != 0) {
                        rocked.add(next);
                    } else {
                        rocked.remove(next);
                    }
                    open.add(new long[]{nextCost, next});
                }
            }
        }
        return null;
    }

    // Step packing: next map in the high 32 bits; cost seconds in the low 24; two edge flags above them.
    private static final int SEC_MASK = (1 << 24) - 1;
    private static final long SAVES = 1L << 24;   // saves the bot's town (World Tour out)
    private static final long RETURNS = 1L << 25; // goes back to the saved town (World Tour home)
    private static final long ROCK = 1L << 26;    // a hyper-rock jump (into a virtual node)

    /* Outgoing steps from mapId, each packed as (next << 32 | flags | costSec): walkable portals and curated scripted
     * warps at WALK_SEC, transit edges at their own time (minus those whose rebirth check the bot fails), and the
     * hyper rock's virtual nodes. */
    private static long[] steps(Map<Integer, int[]> g, int mapId, boolean allowFares, int savedReturn, int rebirths,
                                int level) {
        BotTransit.HyperRock rock = BotTransit.hyperRock();
        if (mapId == HYPER_IN || mapId == HYPER_OUT) {
            List<Integer> to = mapId == HYPER_IN ? rock.into() : rock.home();
            long[] out = new long[to.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (long) to.get(i) << 32; // the jump itself is instant; its cost sat on the way in
            }
            return out;
        }
        // Aboard a ride (waiting room, deck, ferry, crane, dragon...) the only way on is the ride itself: the server
        // (or the ride's own timer) moves the bot. The ride maps' WZ portals (200090031's into Ereve and Orbis, the
        // decks' into their cabins, a waiting room's exit) never get used - GCTravel just waits aboard - so offering
        // them let the planner "walk off" a ferry into Orbis and bots looped Ellinia <-> Ereve forever.
        boolean aboard = BotTransit.isRideMap(mapId);
        int[] portals = aboard ? EMPTY : g.getOrDefault(mapId, EMPTY);
        int[] warp = aboard ? EMPTY : BotScriptedWarp.destinations(mapId);
        List<BotTransit.Edge> transit = BotTransit.from(mapId);
        long[] out = new long[portals.length + warp.length + transit.size() + 2];
        int n = 0;
        if (!aboard && !rock.into().isEmpty()) {
            out[n++] = ((long) HYPER_IN << 32) | ROCK | Math.min(rock.cooldownSec(), SEC_MASK);
        }
        if (!aboard && !rock.home().isEmpty() && rock.leavesFrom(mapId)) {
            out[n++] = ((long) HYPER_OUT << 32) | ROCK | Math.min(rock.cooldownSec(), SEC_MASK);
        }
        for (int p : portals) {
            out[n++] = ((long) p << 32) | WALK_SEC;
        }
        for (int w : warp) {
            out[n++] = ((long) w << 32) | WALK_SEC;
        }
        for (BotTransit.Edge e : transit) {
            if (aboard && !e.isRide()) {
                continue; // nothing to board mid-ride
            }
            if (!allowFares && (e.fare() > 0 || e.beginnerFare() > 0)) {
                continue;
            }
            if (e.minRebirths() > rebirths || e.minLevel() > level) {
                continue; // the script turns this bot away (the teleporter's Heliseum / Arcane rows, rebirth 1 / 2; Jeff, level 50)
            }
            if (e.returnToSaved() != null && e.toMapId() != savedReturn) {
                continue; // the World Tour only takes a bot back where it came from
            }
            long flags = (e.saveLocation() != null ? SAVES : 0) | (e.returnToSaved() != null ? RETURNS : 0);
            out[n++] = ((long) e.toMapId() << 32) | flags | Math.min(e.sec(), SEC_MASK);
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /* Every neighbour the planner can step to from mapId (walkable portals, scripted warps, transit). */
    static int[] neighbors(int mapId) {
        long[] steps = steps(get(), mapId, true, NO_SAVED_RETURN, 3, ANY_LEVEL);
        int[] out = new int[steps.length];
        int n = 0;
        for (long step : steps) {
            int next = (int) (step >>> 32);
            if (next > 0) {
                out[n++] = next; // the hyper rock's virtual nodes are not neighbours
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private static List<Integer> reconstruct(Map<Integer, Integer> cameFrom, int from, int to) {
        List<Integer> hops = new ArrayList<>();
        for (int at = to; at != from; at = cameFrom.get(at)) {
            if (at > 0) {
                hops.add(at); // a hyper-rock jump reads as one hop straight to where it lands
            }
        }
        Collections.reverse(hops);
        return List.copyOf(hops);
    }

    private static Map<Integer, int[]> build() {
        Map<Integer, int[]> out = new ConcurrentHashMap<>();
        ThreadLocal<DataProvider> mapSources =
                ThreadLocal.withInitial(() -> DataProviderFactory.getDataProvider(WZFiles.MAP));
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
        Path mapRoot = Path.of(WZFiles.MAP.getFilePath(), "Map");
        for (int area = 0; area <= 9; area++) {
            Path areaDir = mapRoot.resolve("Map" + area);
            if (!Files.isDirectory(areaDir)) {
                continue;
            }
            try (var files = Files.list(areaDir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".img.xml")) {
                        continue;
                    }
                    final int fileArea = area;
                    try {
                        int mapId = Integer.parseInt(name.substring(0, name.length() - ".img.xml".length()));
                        pool.execute(() -> {
                            try {
                                readMap(mapSources.get(), fileArea, mapId, out);
                            } catch (RuntimeException ignored) {
                                // skip an unparseable map file
                            }
                        });
                    } catch (NumberFormatException ignored) {
                        // non-map file (e.g. AreaCode.img.xml)
                    }
                }
            } catch (IOException ignored) {
                // can't list an area dir — skip it
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Collections.unmodifiableMap(out);
    }

    private static void readMap(DataProvider mapSource, int area, int mapId, Map<Integer, int[]> out) {
        Data mapData = mapSource.getData(mapImgPath(area, mapId));
        if (mapData == null) {
            return;
        }
        Data info = mapData.getChildByPath("info");
        String link = info != null ? DataTool.getString("link", info, "") : "";
        if (!link.isEmpty()) {
            try {
                int linkId = Integer.parseInt(link);
                mapData = mapSource.getData(mapImgPath(linkId / 100000000, linkId));
                if (mapData == null) {
                    out.put(mapId, EMPTY);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // malformed link — read the map as-is
            }
        }
        Data portals = mapData.getChildByPath("portal");
        if (portals == null) {
            out.put(mapId, EMPTY);
            return;
        }
        Set<Integer> targets = new TreeSet<>();
        for (Data portal : portals) {
            int targetMapId = DataTool.getInt("tm", portal, NO_TARGET_MAPID);
            if (targetMapId == NO_TARGET_MAPID || targetMapId == mapId) {
                continue;
            }
            if (DataTool.getInt("pt", portal, 0) == Portal.DOOR_PORTAL) {
                continue;
            }
            String script = DataTool.getString("script", portal, "");
            if (!script.isEmpty()) {
                continue; // scripted portals execute their own warp — out of pure-movement scope
            }
            if (!isPortalinCurrentVersion(targetMapId)) {
                continue; // destination is gated out of the current server version (e.g. Nautilus on v55) —
                          // real players are blocked at the portal, so bots must not path there either
            }
            targets.add(targetMapId);
        }
        int[] arr = new int[targets.size()];
        int i = 0;
        for (int t : targets) {
            arr[i++] = t;
        }
        out.put(mapId, arr);
    }

    private static String mapImgPath(int area, int mapId) {
        return "Map/Map" + area + "/" + String.format("%09d", mapId) + ".img";
    }
}
