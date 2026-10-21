package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The in-memory nav graphs, bounded (ticket 48: the 24 h soak's old gen crept 25-40 MB/h, led by
 * BotNavigationGraph$Edge, because the provider's map of graphs only ever grew - every map and every movement
 * profile any bot or player had passed through stayed resident).
 *
 * <p>Least recently used graphs go first once the cache holds more than {@code bot.nav.graphCache.maxEntries}
 * graphs or more than {@code bot.nav.graphCache.maxMB} of estimated heap. Never evicted: a graph of a map someone
 * (bot or player, any channel - graphs are per map id, shared by all channels) is standing on now, a graph used in
 * the last {@code bot.nav.graphCache.minIdleSec} seconds (route prewarms for the next hops of a trip), and a graph
 * with no cache file to come back from. An evicted graph is read back from cache/bot-nav on its next use.
 *
 * <p>Nothing learned lives in a graph object: player-adopted edges (NavObservedEdges) are keyed by map id, the nightly
 * deep overlay and the NavEdgeLearning book are transient fields re-resolved from their own stores on first use, so a
 * reloaded graph sees exactly what the evicted one did.
 *
 * <p>Reads are lock-free (a ConcurrentHashMap get plus a volatile stamp); eviction runs after a put, under a try-lock,
 * so planners never wait on it.
 */
final class NavGraphCache<K> {
    private static final Logger log = LoggerFactory.getLogger(NavGraphCache.class);

    static final int DEFAULT_MAX_ENTRIES = Integer.getInteger("bot.nav.graphCache.maxEntries", 3000);
    static final long DEFAULT_MAX_BYTES = Long.getLong("bot.nav.graphCache.maxMB", 256L) * 1024 * 1024;
    static final long DEFAULT_MIN_IDLE_MS = Long.getLong("bot.nav.graphCache.minIdleSec", 120L) * 1000;

    static final class Slot {
        final BotNavigationGraph graph;
        final long bytes;
        volatile long lastUsedMs;

        Slot(BotNavigationGraph graph, long bytes, long now) {
            this.graph = graph;
            this.bytes = bytes;
            this.lastUsedMs = now;
        }
    }

    private final Map<K, Slot> slots = new ConcurrentHashMap<>();
    private final Map<Integer, Set<K>> keysByMap = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final ReentrantLock evictLock = new ReentrantLock();
    private final java.util.function.ToIntFunction<K> mapIdOf;
    private final Predicate<K> reloadable;
    private volatile Supplier<Set<Integer>> pinnedMaps;
    private volatile LongSupplier clock = System::currentTimeMillis;
    volatile int maxEntries;
    volatile long maxBytes;
    volatile long minIdleMs;
    private final AtomicLong evictions = new AtomicLong();

    NavGraphCache(java.util.function.ToIntFunction<K> mapIdOf, Predicate<K> reloadable, Supplier<Set<Integer>> pinnedMaps,
                  int maxEntries, long maxBytes, long minIdleMs) {
        this.mapIdOf = mapIdOf;
        this.reloadable = reloadable;
        this.pinnedMaps = pinnedMaps;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.minIdleMs = minIdleMs;
    }

    BotNavigationGraph get(K key) {
        Slot slot = slots.get(key);
        if (slot == null) {
            return null;
        }
        slot.lastUsedMs = clock.getAsLong();
        return slot.graph;
    }

    boolean containsKey(K key) {
        return slots.containsKey(key);
    }

    void put(K key, BotNavigationGraph graph) {
        Slot fresh = new Slot(graph, estimateBytes(graph), clock.getAsLong());
        keysByMap.computeIfAbsent(mapIdOf.applyAsInt(key), id -> ConcurrentHashMap.newKeySet()).add(key);
        Slot old = slots.put(key, fresh);
        totalBytes.addAndGet(fresh.bytes - (old != null ? old.bytes : 0));
        evictIfOver();
    }

    /** The cached graphs of one map (every movement profile), touching each as used. */
    List<Map.Entry<K, BotNavigationGraph>> forMap(int mapId) {
        Set<K> keys = keysByMap.get(mapId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<K, BotNavigationGraph>> out = new ArrayList<>(keys.size());
        long now = clock.getAsLong();
        for (K key : keys) {
            Slot slot = slots.get(key);
            if (slot != null) {
                slot.lastUsedMs = now;
                out.add(Map.entry(key, slot.graph));
            }
        }
        return out;
    }

    int size() {
        return slots.size();
    }

    long estimatedBytes() {
        return totalBytes.get();
    }

    long evictions() {
        return evictions.get();
    }

    void clear() {
        slots.clear();
        keysByMap.clear();
        totalBytes.set(0);
    }

    void setPinnedMaps(Supplier<Set<Integer>> pinnedMaps) {
        this.pinnedMaps = pinnedMaps;
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    private boolean over() {
        return slots.size() > maxEntries || totalBytes.get() > maxBytes;
    }

    /** Evicts least recently used, unpinned, reloadable graphs until within bounds (or nothing more may go). */
    int evictIfOver() {
        if (!over() || !evictLock.tryLock()) {
            return 0;
        }
        try {
            Set<Integer> pinned;
            try {
                pinned = pinnedMaps.get();
            } catch (RuntimeException e) {
                log.debug("Nav graph cache: occupied-map lookup failed, skipping eviction", e);
                return 0;
            }
            long cutoff = clock.getAsLong() - minIdleMs;
            List<Map.Entry<K, Slot>> candidates = new ArrayList<>();
            for (Map.Entry<K, Slot> entry : slots.entrySet()) {
                Slot slot = entry.getValue();
                if (slot.lastUsedMs <= cutoff && !pinned.contains(mapIdOf.applyAsInt(entry.getKey()))) {
                    candidates.add(entry);
                }
            }
            candidates.sort(Comparator.comparingLong(e -> e.getValue().lastUsedMs));
            int evicted = 0;
            for (Map.Entry<K, Slot> entry : candidates) {
                if (!over()) {
                    break;
                }
                K key = entry.getKey();
                if (!reloadable.test(key)) {
                    continue;
                }
                if (slots.remove(key, entry.getValue())) {
                    totalBytes.addAndGet(-entry.getValue().bytes);
                    Set<K> keys = keysByMap.get(mapIdOf.applyAsInt(key));
                    if (keys != null) {
                        keys.remove(key);
                        if (slots.containsKey(key)) { // a put raced in after the remove: keep it indexed
                            keys.add(key);
                        }
                    }
                    evicted++;
                }
            }
            if (evicted > 0) {
                evictions.addAndGet(evicted);
                log.debug("Nav graph cache: evicted {} graphs, {} left (~{} MB), {} maps pinned",
                        evicted, slots.size(), totalBytes.get() >> 20, pinned.size());
            }
            return evicted;
        } finally {
            evictLock.unlock();
        }
    }

    /**
     * Rough retained heap of one graph, from the 24 h soak's class histogram: an Edge is 80 bytes plus its two
     * Points (24 each) and a list slot; a Segment 48 plus a slot; a region ~96 plus its list; a foothold-to-region
     * entry a HashMap node plus boxed keys.
     */
    static long estimateBytes(BotNavigationGraph graph) {
        long bytes = 512;
        for (List<BotNavigationGraph.Edge> edges : graph.outgoingByRegionId.values()) {
            bytes += 72 + (long) edges.size() * (80 + 48 + 8);
        }
        for (BotNavigationGraph.Region region : graph.regions) {
            bytes += 96 + 40 + (long) region.segments.size() * (48 + 8);
        }
        bytes += (long) graph.regionsById.size() * 48;
        bytes += (long) graph.regionIdByFootholdId.size() * 64;
        bytes += (long) (graph.collidableWallIds.size() + graph.collidableFromBelowIds.size()) * 48;
        return bytes;
    }

    /** Map ids where any character - bot or player, on any channel of any world - stands now. */
    static Set<Integer> occupiedMapIds() {
        Set<Integer> ids = new HashSet<>();
        for (net.server.world.World world : net.server.Server.getInstance().getWorlds()) {
            for (client.Character chr : world.getPlayerStorage().getAllCharacters()) {
                ids.add(chr.getMapId());
            }
        }
        return ids;
    }
}
