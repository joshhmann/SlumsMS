package soloMapling.qa;

import client.BotClient;
import client.Character;
import client.Client;
import net.opcodes.SendOpcode;
import net.packet.Packet;
import server.TimerManager;
import server.maps.MapEntryPlacement;
import server.maps.MapObject;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * {@code !qa run pe-1}: measures a GM's map entries on the server. From the warp packet on, it counts every packet
 * the GM's client is sent ({@link Client.PacketMeter}) for {@link #WINDOW_MS} ms, and logs one MAP_ENTRY line per
 * entry: how long the server took from the warp packet to the end of the placement, the packets and bytes in that
 * synchronous part, what the placement deferred and when the last deferred spawn went out, the packets and bytes
 * in the whole window with the top opcodes, and the map's objects by type.
 * <p>
 * Off unless a running scenario listens to {@link QaLog.Topic#MAP_ENTRY}: {@link #begin} is then one volatile read.
 */
public final class MapEntryMeter {

    /** How long after the warp packet the packets are counted. */
    static final long WINDOW_MS = 3000;

    private static final Map<Integer, Window> open = new ConcurrentHashMap<>();
    private static final Map<Integer, List<Result>> results = new ConcurrentHashMap<>();
    private static volatile Map<Integer, String> opcodeNames;

    /** Schedules the end of a window; the tests run it by hand. */
    interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    static volatile Scheduler scheduler = (task, delayMs) -> TimerManager.getInstance().schedule(task, delayMs);

    static {
        MapEntryPlacement.setListener(new MapEntryPlacement.Listener() {
            @Override
            public void entered(Character entrant, int mapId, int sentNow, int deferred) {
                Window w = open.get(entrant.getId());
                if (w != null) {
                    w.placedNow = sentNow;
                    w.deferred = deferred;
                }
            }

            @Override
            public void trickled(Character entrant, int mapId, int deferred, int sent) {
                Window w = open.get(entrant.getId());
                if (w != null) {
                    w.trickleSent = sent;
                    w.trickleDoneNanos = System.nanoTime();
                }
            }
        });
    }

    private MapEntryMeter() {
    }

    /** One entry's numbers, kept for the run's summary. */
    record Result(int from, int to, double syncMs, int syncPackets, long syncBytes, int deferred, double trickleMs,
                  int windowPackets, long windowBytes) {
    }

    static final class Window implements Client.PacketMeter {
        final int from, to;
        final long t0 = System.nanoTime();
        final AtomicInteger packets = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();
        final Map<Integer, AtomicInteger> byOpcode = new ConcurrentHashMap<>();
        volatile int syncPackets = -1;
        volatile long syncBytes;
        volatile double syncMs;
        volatile int placedNow = -1, deferred, trickleSent;
        volatile long trickleDoneNanos;
        volatile String census = "";

        Window(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public void sent(Packet packet) {
            byte[] b = packet.getBytes();
            packets.incrementAndGet();
            bytes.addAndGet(b.length + 4L); // + the 4-byte header on the wire
            if (b.length >= 2) {
                int op = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
                byOpcode.computeIfAbsent(op, k -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    /** Character.changeMapInternal, before the warp packet. */
    public static void begin(Character chr, MapleMap from, MapleMap to) {
        if (!QaLog.on(QaLog.Topic.MAP_ENTRY) || chr == null || !chr.isGM()) {
            return;
        }
        Client c = chr.getClient();
        if (c == null || c instanceof BotClient) {
            return;
        }
        Window w = new Window(from != null ? from.getId() : 0, to != null ? to.getId() : 0);
        w.census = census(to);
        open.put(chr.getId(), w);
        c.setPacketMeter(w);
        scheduler.schedule(() -> finish(chr, w), WINDOW_MS);
    }

    /** Character.changeMapInternal, right after MapleMap.addPlayer: the synchronous part is over. */
    public static void placed(Character chr) {
        if (open.isEmpty()) {
            return;
        }
        Window w = open.get(chr.getId());
        if (w != null && w.syncPackets < 0) {
            w.syncMs = (System.nanoTime() - w.t0) / 1e6;
            w.syncBytes = w.bytes.get();
            w.syncPackets = w.packets.get();
        }
    }

    static void finish(Character chr, Window w) {
        if (!open.remove(chr.getId(), w)) {
            return;
        }
        Client c = chr.getClient();
        if (c != null && c.getPacketMeter() == w) {
            c.setPacketMeter(null);
        }
        double trickleMs = w.trickleDoneNanos > 0 ? (w.trickleDoneNanos - w.t0) / 1e6 : 0;
        Result r = new Result(w.from, w.to, w.syncMs, Math.max(0, w.syncPackets), w.syncBytes, w.deferred, trickleMs,
                w.packets.get(), w.bytes.get());
        results.computeIfAbsent(chr.getId(), k -> Collections.synchronizedList(new ArrayList<>())).add(r);
        QaLog.owner(QaLog.Topic.MAP_ENTRY, chr.getId(), line(r, w));
    }

    static String line(Result r, Window w) {
        return String.format("entry %d->%d sync_ms=%.1f sync_packets=%d sync_bytes=%d placed_now=%d deferred=%d"
                        + " trickled=%d last_deferred_ms=%.0f window_%ds_packets=%d window_bytes=%d top=[%s] objects=[%s]",
                r.from(), r.to(), r.syncMs(), r.syncPackets(), r.syncBytes(), w.placedNow, r.deferred(),
                w.trickleSent, r.trickleMs(), WINDOW_MS / 1000, r.windowPackets(), r.windowBytes(),
                topOpcodes(w.byOpcode, 6), w.census);
    }

    /** "SPAWN_PLAYER 54, DROP_ITEM_FROM_MAPOBJECT 300" for the most frequent opcodes. */
    static String topOpcodes(Map<Integer, AtomicInteger> byOpcode, int n) {
        return byOpcode.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .limit(n)
                .map(e -> opcodeName(e.getKey()) + " " + e.getValue().get())
                .collect(Collectors.joining(", "));
    }

    static String opcodeName(int op) {
        Map<Integer, String> names = opcodeNames;
        if (names == null) {
            names = new HashMap<>();
            for (SendOpcode o : SendOpcode.values()) {
                names.putIfAbsent(o.getValue(), o.name());
            }
            opcodeNames = names;
        }
        return names.getOrDefault(op, String.format("0x%X", op));
    }

    /** The map's objects by type before the entry, bots counted apart: "PLAYER 52 (bots 50), REACTOR 3". */
    static String census(MapleMap map) {
        if (map == null) {
            return "";
        }
        Map<server.maps.MapObjectType, Integer> byType = new EnumMap<>(server.maps.MapObjectType.class);
        int bots = 0;
        for (MapObject o : map.getMapObjects()) {
            byType.merge(o.getType(), 1, Integer::sum);
            if (o instanceof Character c && BotHelpers.isBot(c)) {
                bots++;
            }
        }
        final int botCount = bots;
        return byType.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()
                        + (e.getKey() == server.maps.MapObjectType.PLAYER ? " (bots " + botCount + ")" : ""))
                .collect(Collectors.joining(", "));
    }

    /** The entries measured for this GM since the last {@link #clear}. */
    static List<Result> results(int gmId) {
        List<Result> list = results.get(gmId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    static void clear(int gmId) {
        results.remove(gmId);
    }

    /** "n=20 sync_ms p50/p95 1.2/3.4 ..." over a GM's entries. */
    static String summary(List<Result> rs) {
        if (rs.isEmpty()) {
            return "no entries measured";
        }
        return String.format("n=%d sync_ms p50/p95 %.1f/%.1f, sync_packets p50/p95 %d/%d, deferred p50/p95 %d/%d,"
                        + " last_deferred_ms p95 %.0f, window_packets p50/p95 %d/%d, window_KB p95 %.1f",
                rs.size(),
                pct(rs.stream().map(Result::syncMs).toList(), 50), pct(rs.stream().map(Result::syncMs).toList(), 95),
                pct(rs.stream().map(Result::syncPackets).toList(), 50), pct(rs.stream().map(Result::syncPackets).toList(), 95),
                pct(rs.stream().map(Result::deferred).toList(), 50), pct(rs.stream().map(Result::deferred).toList(), 95),
                pct(rs.stream().map(Result::trickleMs).toList(), 95),
                pct(rs.stream().map(Result::windowPackets).toList(), 50), pct(rs.stream().map(Result::windowPackets).toList(), 95),
                pct(rs.stream().map(r -> r.windowBytes() / 1024.0).toList(), 95));
    }

    static <T extends Comparable<T>> T pct(List<T> values, int p) {
        List<T> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }
}
