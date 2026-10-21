package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 48: the nav graph cache is a bounded LRU that never drops a graph of an occupied map. */
class NavGraphCacheTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Set<Integer> pinned = new HashSet<>();
    private final Set<Integer> notOnDisk = new HashSet<>();

    @TempDir
    Path learnDir;

    @AfterEach
    void restore() {
        NavEdgeLearning.useDirForTest(null);
    }

    private NavGraphCache<Integer> cache(int maxEntries, long maxBytes, long minIdleMs) {
        NavGraphCache<Integer> cache = new NavGraphCache<>(k -> k, k -> !notOnDisk.contains(k), () -> pinned,
                maxEntries, maxBytes, minIdleMs);
        cache.setClock(now::get);
        return cache;
    }

    private static BotNavigationGraph.Edge jump(int from, int to, int cost) {
        return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.JUMP, new Point(from * 100, 50),
                new Point(to * 100, 50), from * 100, from * 100, 0, 0, 0, 0, 0, cost);
    }

    /** Three rope regions, 0 -> 2 directly or 0 -> 1 -> 2, plus {@code extraEdges} self-loops to fatten it. */
    static BotNavigationGraph graph(int mapId, int extraEdges) {
        List<BotNavigationGraph.Region> regions = List.of(new BotNavigationGraph.Region(0, 0, 0, 100, false),
                new BotNavigationGraph.Region(1, 100, 0, 100, false), new BotNavigationGraph.Region(2, 200, 0, 100, false));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        Map<Integer, List<BotNavigationGraph.Edge>> out = new HashMap<>();
        java.util.ArrayList<BotNavigationGraph.Edge> zero = new java.util.ArrayList<>(List.of(jump(0, 2, 100), jump(0, 1, 60)));
        for (int i = 0; i < extraEdges; i++) {
            zero.add(jump(0, 0, 10 + i));
        }
        out.put(0, zero);
        out.put(1, List.of(jump(1, 2, 60)));
        out.put(2, List.of());
        return new BotNavigationGraph(mapId, BotNavigationGraphProvider.GRAPH_VERSION, BotMovementProfile.base(),
                regions, byId, Map.of(), out, Set.of());
    }

    @Test
    void theLeastRecentlyUsedGraphGoesFirst() {
        NavGraphCache<Integer> cache = cache(3, Long.MAX_VALUE, 0);
        for (int map = 1; map <= 3; map++) {
            cache.put(map, graph(map, 0));
            now.addAndGet(10);
        }
        cache.get(1); // 2 is now the coldest
        now.addAndGet(10);
        cache.put(4, graph(4, 0));
        assertEquals(3, cache.size());
        assertNull(cache.get(2));
        assertNotNull(cache.get(1));
        assertNotNull(cache.get(3));
        assertNotNull(cache.get(4));
        assertEquals(1, cache.evictions());
    }

    @Test
    void aGraphOfAMapSomeoneIsOnIsNeverEvicted() {
        NavGraphCache<Integer> cache = cache(2, Long.MAX_VALUE, 0);
        pinned.add(1);
        pinned.add(2);
        cache.put(1, graph(1, 0));
        now.addAndGet(10);
        cache.put(2, graph(2, 0));
        now.addAndGet(10);
        cache.put(3, graph(3, 0));
        // 1 and 2 are older but occupied: the only unpinned graph (the newest) goes.
        assertNotNull(cache.get(1));
        assertNotNull(cache.get(2));
        assertNull(cache.get(3));

        pinned.add(3);
        pinned.add(4);
        now.addAndGet(10);
        cache.put(3, graph(3, 0));
        now.addAndGet(10);
        cache.put(4, graph(4, 0));
        assertEquals(4, cache.size(), "all occupied: over the bound rather than drop a live map's graph");

        pinned.clear(); // everyone left: the next pass trims back to the bound, coldest first
        now.addAndGet(10);
        cache.evictIfOver();
        assertEquals(2, cache.size());
        assertNotNull(cache.get(3));
        assertNotNull(cache.get(4));
    }

    @Test
    void recentlyUsedAndNotOnDiskGraphsStay() {
        NavGraphCache<Integer> cache = cache(1, Long.MAX_VALUE, 60_000);
        cache.put(1, graph(1, 0));
        now.addAndGet(1_000);
        cache.put(2, graph(2, 0));
        assertEquals(2, cache.size(), "a route prewarm used a second ago is not evicted");

        now.addAndGet(120_000);
        notOnDisk.add(1);
        cache.evictIfOver();
        assertNotNull(cache.get(1), "no cache file to reload it from: kept");
        assertNull(cache.get(2));
    }

    @Test
    void theByteBoundCountsEdges() {
        long small = NavGraphCache.estimateBytes(graph(1, 0));
        long big = NavGraphCache.estimateBytes(graph(2, 1000));
        assertTrue(big - small >= 1000L * 128, "each edge costs its Edge and two Points: " + (big - small));

        NavGraphCache<Integer> cache = cache(100, big + small * 2, 0);
        cache.put(1, graph(1, 0));
        now.addAndGet(10);
        cache.put(2, graph(2, 1000));
        now.addAndGet(10);
        cache.put(3, graph(3, 0));
        now.addAndGet(10);
        cache.put(4, graph(4, 0));
        assertNull(cache.get(1), "over the byte bound: the coldest goes");
        assertTrue(cache.estimatedBytes() <= big + small * 2);
        assertEquals(cache.estimatedBytes(), small * 2 + big);
    }

    @Test
    void forMapListsEveryProfileOfOneMap() {
        NavGraphCache<Integer> cache = new NavGraphCache<>(k -> k / 10, k -> true, Set::of, 10, Long.MAX_VALUE, 0);
        cache.put(10, graph(1, 0));
        cache.put(11, graph(1, 0));
        cache.put(20, graph(2, 0));
        assertEquals(2, cache.forMap(1).size());
        assertEquals(1, cache.forMap(2).size());
        assertEquals(0, cache.forMap(3).size());
    }

    /** End to end through the provider: evict, reload from the disk cache, and what was learned still applies. */
    @Test
    void anEvictedGraphReloadsFromDiskWithItsLearning() throws Exception {
        NavEdgeLearning.useDirForTest(learnDir);
        int mapId = 990004801;
        BotNavigationGraph original = graph(mapId, 0);
        BotNavigationGraphProvider.GraphCacheKey key = BotNavigationGraphProvider.GraphCacheKey.from(mapId, BotMovementProfile.base());
        BotNavigationGraphProvider.saveGraph(original);
        assertTrue(Files.isRegularFile(BotNavigationGraphProvider.graphFile(key)));

        NavGraphCache<BotNavigationGraphProvider.GraphCacheKey> live = BotNavigationGraphProvider.GRAPHS;
        int maxEntries = live.maxEntries;
        long minIdle = live.minIdleMs;
        try {
            live.setPinnedMaps(Set::of);
            live.minIdleMs = 0;
            live.put(key, original);
            MapleMap map = mock(MapleMap.class);
            when(map.getId()).thenReturn(mapId);
            assertNotNull(BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base()));

            BotNavigationGraph.Edge direct = original.getOutgoing(0).get(0);
            for (int i = 0; i < 6; i++) {
                NavEdgeLearning.record(original, direct, true, System.currentTimeMillis());
            }
            double learned = NavEdgeLearning.factor(original, direct);
            assertTrue(learned > 1.5);
            List<BotNavigationGraph.Edge> plannerView = NavObservedEdges.outgoing(original, 0);

            live.maxEntries = 0;
            live.evictIfOver();
            assertNull(BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base()), "evicted");
            live.maxEntries = maxEntries;

            BotNavigationGraph reloaded = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
            assertNotNull(reloaded);
            assertNotSame(original, reloaded);
            assertEquals(original.regions.size(), reloaded.regions.size());
            assertEquals(original.getOutgoing(0).size(), reloaded.getOutgoing(0).size());
            BotNavigationGraph.Edge reloadedDirect = reloaded.getOutgoing(0).get(0);
            assertEquals(learned, NavEdgeLearning.factor(reloaded, reloadedDirect), 1e-9,
                    "the learning book is keyed by map/profile, not the graph object");
            assertEquals(plannerView.size(), NavObservedEdges.outgoing(reloaded, 0).size());
            assertNotNull(BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base()), "cached again");
        } finally {
            live.maxEntries = maxEntries;
            live.minIdleMs = minIdle;
            live.setPinnedMaps(NavGraphCache::occupiedMapIds);
            Files.deleteIfExists(BotNavigationGraphProvider.graphFile(key));
        }
    }
}
