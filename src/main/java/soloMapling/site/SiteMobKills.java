package soloMapling.site;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * 1.6.0: the site's "most hunted monsters". An in-memory counter bumped when a mob dies ({@code MapleMap.killMonster}),
 * bucketed per hour in a ring of 7 days; world.json reads the last 24 h and the last 7 days. Players' and bots' kills
 * count together; GM, auto-QA and world-event mobs are skipped by the caller. Nothing is recorded until the exporter is
 * switched on ({@link #setRecording}). Kills are real ones in a map; bots' offscreen grind (no real mob dies) is counted by
 * {@link #offscreen} from its modelled kill pace, spread over the map's spawn table. With {@link Store} (migration 047) the hour buckets are flushed every few minutes, read back
 * at start and pruned after {@link #KEEP_DAYS} days; without it the counts start from zero after a restart.
 */
public final class SiteMobKills {

    public static final int TOP = 25;
    public static final int HOURS = 24 * 7;
    private static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);
    public static final int KEEP_DAYS = 30;

    /** Where the hour buckets are kept between restarts. {@code hour} is epoch hours (UTC). */
    public interface Store {
        /** Sets (not adds) each (hour, mob) count. */
        void write(Map<Long, Map<Integer, Integer>> counts) throws Exception;

        /** Every (hour, mob) count with hour >= {@code fromHour}. */
        Map<Long, Map<Integer, Integer>> read(long fromHour) throws Exception;

        void prune(long beforeHour) throws Exception;
    }

    /** One row of the file: the mob id, its String.wz name and the kills in the window. */
    public record Row(int mob, String name, int kills) {
    }

    private static final class Bucket {
        final long hour;
        final ConcurrentHashMap<Integer, AtomicInteger> kills = new ConcurrentHashMap<>();

        Bucket(long hour) {
            this.hour = hour;
        }
    }

    private static final Bucket[] ring = new Bucket[HOURS];
    private static volatile boolean recording;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private SiteMobKills() {
    }

    public static void setRecording(boolean on) {
        recording = on;
    }

    public static boolean recording() {
        return recording;
    }

    /** One kill of mob {@code mobId}. Cheap, lock-free except when a new hour starts; never throws. */
    public static void kill(int mobId) {
        if (!recording) {
            return;
        }
        try {
            long hour = clock.getAsLong() / HOUR_MS;
            Bucket b = bucket(hour);
            b.kills.computeIfAbsent(mobId, k -> new AtomicInteger()).incrementAndGet();
        } catch (RuntimeException e) {
            // a counter must never hurt a kill
        }
    }

    /**
     * Offscreen kills: a bot grinding where nobody looks never fights a real mob (OffscreenCombat models it), so it
     * calls this with the kills its modelled pace owes. They are spread over the map's own spawn table ({@code mobIds},
     * one entry per spawn point, repeats weight the pick). Returns the fractional kills carried to the next call.
     */
    public static double offscreen(List<Integer> mobIds, double kills) {
        if (!recording || mobIds == null || mobIds.isEmpty() || kills < 1) {
            return recording ? Math.max(0, kills) : 0;
        }
        int n = (int) Math.min(kills, 200);
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            kill(mobIds.get(rnd.nextInt(mobIds.size())));
        }
        return kills - n;
    }

    /** The {@link #TOP} most killed mobs over the last {@code hours} hours (this hour included), most first. */
    public static List<Row> top(int hours, long now, IntFunction<String> names) {
        long hourNow = now / HOUR_MS;
        Map<Integer, Integer> sum = new ConcurrentHashMap<>();
        for (Bucket b : ring.clone()) {
            if (b != null && b.hour <= hourNow && b.hour > hourNow - hours) {
                b.kills.forEach((id, n) -> sum.merge(id, n.get(), Integer::sum));
            }
        }
        List<Map.Entry<Integer, Integer>> all = new ArrayList<>(sum.entrySet());
        all.sort(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        List<Row> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : all.subList(0, Math.min(TOP, all.size()))) {
            String name;
            try {
                name = names.apply(e.getKey());
            } catch (RuntimeException ex) {
                name = "";
            }
            out.add(new Row(e.getKey(), name == null ? "" : name, e.getValue()));
        }
        return out;
    }

    /** Reads the last 7 days back from {@code store} into the ring (adds to what is already counted). Never throws. */
    public static void restore(Store store, long now) {
        try {
            long hourNow = now / HOUR_MS;
            Map<Long, Map<Integer, Integer>> rows = store.read(hourNow - HOURS + 1);
            rows.forEach((hour, mobs) -> {
                if (hour > hourNow || hour <= hourNow - HOURS) {
                    return;
                }
                Bucket b = bucket(hour);
                mobs.forEach((id, n) -> b.kills.computeIfAbsent(id, k -> new AtomicInteger()).addAndGet(n));
            });
        } catch (Exception e) {
            // no table yet (migration 047 not applied): start from zero
        }
    }

    /** Writes this and the previous hour (their running totals) to {@code store} and prunes old rows. Never throws. */
    public static boolean flush(Store store, long now) {
        try {
            long hourNow = now / HOUR_MS;
            Map<Long, Map<Integer, Integer>> out = new java.util.HashMap<>();
            for (Bucket b : ring.clone()) {
                if (b != null && (b.hour == hourNow || b.hour == hourNow - 1) && !b.kills.isEmpty()) {
                    Map<Integer, Integer> m = new java.util.HashMap<>();
                    b.kills.forEach((id, n) -> m.put(id, n.get()));
                    out.put(b.hour, m);
                }
            }
            if (!out.isEmpty()) {
                store.write(out);
            }
            store.prune(hourNow - KEEP_DAYS * 24L);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Bucket bucket(long hour) {
        int slot = (int) (hour % HOURS);
        Bucket b = ring[slot];
        if (b == null || b.hour != hour) {
            synchronized (ring) {
                b = ring[slot];
                if (b == null || b.hour != hour) {
                    b = new Bucket(hour);
                    ring[slot] = b;
                }
            }
        }
        return b;
    }

    /** Tests. */
    static void reset(LongSupplier c) {
        synchronized (ring) {
            java.util.Arrays.fill(ring, null);
        }
        clock = c == null ? System::currentTimeMillis : c;
    }
}
