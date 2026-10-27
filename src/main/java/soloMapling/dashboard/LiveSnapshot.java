package soloMapling.dashboard;

import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Turns what the exporter read from the server into the dashboard's {@code live.json} and one point of the day's
 * series. Pure apart from the previous reading it keeps for rates (tick times, GC), so tests drive it with
 * made-up samples.
 */
final class LiveSnapshot {

    /** One online bot. {@code roster}: a director (roster) bot; false = a boot environment resident (1.6.0 towns). */
    record BotSample(int id, String name, int level, String job, int mapId, String type, String activity, long meso,
                     boolean roster) {
        BotSample(int id, String name, int level, String job, int mapId, String type, String activity, long meso) {
            this(id, name, level, job, mapId, type, activity, meso, true);
        }
    }

    /** Population numbers. {@code lod} is full maps, halo maps, active maps, bots on dynamic movement. */
    record Population(int roster, int target, int peak, String director, List<Integer> targetsByHour, int players,
                      int[] lod) {
    }

    /** Server health. {@code pool} is DatabaseConnection.poolStats(), {@code gate} BotDbGate.stats(); either may be null. */
    record Health(long uptimeMs, long heapUsed, long heapMax, long nonHeapUsed, double processCpu, double systemLoad,
                  int threads, long gcCount, long gcMs, int[] pool, int[] gate,
                  List<BotPerformanceMonitor.PhaseTotal> phases) {
    }

    static final int TOP_MAPS = 40;
    static final int TOP_ECONOMY = 30;
    private static final long[] WEALTH_EDGES = {1, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};
    private static final String[] WEALTH_LABELS = {"0", "1-999", "1k-10k", "10k-100k", "100k-1m", "1m-10m", "10m-100m", "100m+"};

    private Map<String, BotPerformanceMonitor.PhaseTotal> prevPhases = Map.of();
    private long prevAt;
    private long prevGcCount = -1;
    private long prevGcMs;

    /** The live document and the series point taken from it. */
    record Result(Map<String, Object> live, Map<String, Object> point) {
    }

    Result build(long now, List<BotSample> bots, Population pop, Health health, EconomyFeed economy,
                 IntFunction<String> mapName) {
        double secs = prevAt == 0 ? 0 : Math.max(0.001, (now - prevAt) / 1000.0);
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("t", now);
        live.put("intervalS", round1(secs));
        live.put("bots", bots(bots, pop, mapName));
        live.put("economy", economy(bots, economy, mapName));
        Map<String, Object> h = health(health, secs);
        live.put("health", h);
        prevAt = now;

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("t", now);
        point.put("on", bots.size());
        point.put("tgt", pop.target());
        point.put("pl", pop.players());
        point.put("act", activities(bots));
        point.put("towns", towns(bots));
        point.put("heap", h.get("heapUsedMb"));
        point.put("cpu", h.get("cpuPct"));
        point.put("tick", h.get("tickMsPerS"));
        point.put("pool", h.get("poolActive"));
        point.put("gate", h.get("gateUsed"));
        Object meso = ((Map<?, ?>) live.get("economy")).get("mesosToday");
        point.put("meso", meso);
        return new Result(live, point);
    }

    // ── bots ────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> bots(List<BotSample> bots, Population pop, IntFunction<String> mapName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", bots.size());
        out.put("roster", pop.roster());
        out.put("target", pop.target());
        out.put("peak", pop.peak());
        out.put("director", pop.director());
        out.put("players", pop.players());
        out.put("targetsByHour", pop.targetsByHour());
        if (pop.lod() != null && pop.lod().length == 4) {
            out.put("lod", Map.of("fullMaps", pop.lod()[0], "haloMaps", pop.lod()[1], "activeMaps", pop.lod()[2],
                    "dynamicBots", pop.lod()[3]));
        }
        out.put("activity", activities(bots));
        Map<String, Integer> types = new TreeMap<>();
        Map<String, Integer> jobs = new TreeMap<>();
        Map<Integer, Integer> bands = new TreeMap<>();
        Map<Integer, int[]> maps = new HashMap<>(); // count, level sum, residents, roster
        for (BotSample b : bots) {
            types.merge(b.type(), 1, Integer::sum);
            jobs.merge(b.job(), 1, Integer::sum);
            bands.merge(b.level() / 10 * 10, 1, Integer::sum);
            int[] m = maps.computeIfAbsent(b.mapId(), k -> new int[4]);
            m[0]++;
            m[1] += b.level();
            m[b.roster() ? 3 : 2]++;
        }
        out.put("types", types);
        out.put("jobs", jobs);
        List<Object> levels = new ArrayList<>();
        bands.forEach((band, n) -> levels.add(List.of(band, n)));
        out.put("levels", levels);
        List<Map.Entry<Integer, int[]>> top = new ArrayList<>(maps.entrySet());
        top.sort(Comparator.comparingInt((Map.Entry<Integer, int[]> e) -> -e.getValue()[0]).thenComparing(Map.Entry::getKey));
        List<Object> mapRows = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : top.subList(0, Math.min(TOP_MAPS, top.size()))) {
            // 1.6.0 towns: [map, name, bots, avg level, residents (boot environment), roster (director)]
            mapRows.add(List.of(e.getKey(), name(mapName, e.getKey()), e.getValue()[0], e.getValue()[1] / e.getValue()[0],
                    e.getValue()[2], e.getValue()[3]));
        }
        out.put("maps", mapRows);
        out.put("mapsWithBots", maps.size());
        return out;
    }

    /**
     * 1.6.0 towns: per town (its whole map family, every channel; the Free Market with its rooms), residents (boot
     * environment bots) and roster (director) bots: {@code {"Henesys": [residents, roster], ...}}.
     */
    static Map<String, List<Integer>> towns(List<BotSample> bots) {
        Map<String, int[]> counts = new TreeMap<>();
        for (BotSample b : bots) {
            String town = java.util.Optional.ofNullable(soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns.townOf(b.mapId()))
                    .map(soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns.Town::name).orElse(null);
            if (town != null) counts.computeIfAbsent(town, k -> new int[2])[b.roster() ? 1 : 0]++;
        }
        Map<String, List<Integer>> out = new TreeMap<>();
        counts.forEach((town, n) -> out.put(town, List.of(n[0], n[1])));
        return out;
    }

    static Map<String, Integer> activities(List<BotSample> bots) {
        Map<String, Integer> out = new TreeMap<>();
        for (BotSample b : bots) {
            out.merge(b.activity(), 1, Integer::sum);
        }
        return out;
    }

    // ── economy ─────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> economy(List<BotSample> bots, EconomyFeed feed, IntFunction<String> mapName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", feed.source());
        out.put("caps", feed.caps());
        out.put("wealth", wealth(bots));

        List<EconomyFeed.MapIntake> maps = new ArrayList<>(feed.mapsToday());
        long mesos = 0;
        long items = 0;
        boolean itemsCounted = false;
        for (EconomyFeed.MapIntake m : maps) {
            mesos += m.mesos();
            if (m.items() >= 0) {
                items += m.items();
                itemsCounted = true;
            }
        }
        maps.sort(Comparator.comparingLong((EconomyFeed.MapIntake m) -> -m.mesos()).thenComparingInt(EconomyFeed.MapIntake::mapId));
        List<Object> mapRows = new ArrayList<>();
        for (EconomyFeed.MapIntake m : maps.subList(0, Math.min(TOP_ECONOMY, maps.size()))) {
            mapRows.add(List.of(m.mapId(), name(mapName, m.mapId()), m.mesos(), m.items(), m.mesoCap(), m.itemCap(), m.refused()));
        }
        List<EconomyFeed.BotIntake> earners = new ArrayList<>(feed.botsToday());
        earners.sort(Comparator.comparingLong((EconomyFeed.BotIntake b) -> -b.mesos()).thenComparingInt(EconomyFeed.BotIntake::charId));
        List<Object> botRows = new ArrayList<>();
        for (EconomyFeed.BotIntake b : earners.subList(0, Math.min(TOP_ECONOMY, earners.size()))) {
            botRows.add(List.of(b.charId(), String.valueOf(b.name()), b.level(), b.mesos(), b.cap(), b.refused()));
        }
        out.put("mesosToday", mesos);
        out.put("itemsToday", itemsCounted ? items : -1);
        out.put("maps", mapRows);
        out.put("bots", botRows);
        if (feed instanceof DerivedEconomy derived) {
            out.put("spentToday", derived.spentToday().values().stream().mapToLong(Long::longValue).sum());
        }
        return out;
    }

    static Map<String, Object> wealth(List<BotSample> bots) {
        long[] mesos = bots.stream().mapToLong(BotSample::meso).sorted().toArray();
        int[] hist = new int[WEALTH_LABELS.length];
        long total = 0;
        for (long m : mesos) {
            total += m;
            int bucket = 0;
            while (bucket < WEALTH_EDGES.length && m >= WEALTH_EDGES[bucket]) {
                bucket++;
            }
            hist[bucket]++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("p10", quantile(mesos, 0.10));
        out.put("p50", quantile(mesos, 0.50));
        out.put("p90", quantile(mesos, 0.90));
        out.put("max", mesos.length == 0 ? 0 : mesos[mesos.length - 1]);
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < hist.length; i++) {
            rows.add(List.of(WEALTH_LABELS[i], hist[i]));
        }
        out.put("hist", rows);
        // Median wealth by level band: does wealth follow level?
        Map<Integer, List<Long>> byBand = new TreeMap<>();
        for (BotSample b : bots) {
            byBand.computeIfAbsent(b.level() / 10 * 10, k -> new ArrayList<>()).add(b.meso());
        }
        List<Object> bands = new ArrayList<>();
        byBand.forEach((band, list) -> bands.add(List.of(band, quantile(list.stream().mapToLong(Long::longValue).sorted().toArray(), 0.5),
                list.size())));
        out.put("medianByLevel", bands);
        return out;
    }

    static long quantile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int i = (int) Math.min(sorted.length - 1, Math.max(0, Math.round(q * (sorted.length - 1))));
        return sorted[i];
    }

    // ── health ──────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> health(Health h, double secs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uptimeS", h.uptimeMs() / 1000);
        out.put("heapUsedMb", h.heapUsed() >> 20);
        out.put("heapMaxMb", h.heapMax() >> 20);
        out.put("nonHeapMb", h.nonHeapUsed() >> 20);
        out.put("cpuPct", h.processCpu() < 0 ? null : round1(h.processCpu() * 100));
        out.put("load", h.systemLoad() < 0 ? null : round1(h.systemLoad()));
        out.put("threads", h.threads());
        if (prevGcCount >= 0 && secs > 0) {
            out.put("gcPerMin", round1(Math.max(0, h.gcCount() - prevGcCount) * 60 / secs));
            out.put("gcMsPerMin", round1(Math.max(0, h.gcMs() - prevGcMs) * 60 / secs));
        }
        prevGcCount = h.gcCount();
        prevGcMs = h.gcMs();
        if (h.pool() != null) {
            out.put("pool", Map.of("active", h.pool()[0], "idle", h.pool()[1], "total", h.pool()[2], "waiting", h.pool()[3]));
            out.put("poolActive", h.pool()[0]);
        }
        if (h.gate() != null) {
            out.put("gate", Map.of("permits", h.gate()[0], "free", h.gate()[1], "overruns", h.gate()[2]));
            out.put("gateUsed", h.gate()[0] - h.gate()[1]);
        }

        // Tick time: each phase's share of the window since the last snapshot, as BotPerformanceMonitor reports it.
        Map<String, BotPerformanceMonitor.PhaseTotal> now = new LinkedHashMap<>();
        for (BotPerformanceMonitor.PhaseTotal p : h.phases()) {
            now.put(p.name(), p);
        }
        if (secs > 0) {
            Map<String, Double> buckets = new LinkedHashMap<>();
            List<Object> phases = new ArrayList<>();
            double tick = 0;
            for (BotPerformanceMonitor.PhaseTotal p : now.values()) {
                BotPerformanceMonitor.PhaseTotal before = prevPhases.get(p.name());
                long nanos = p.nanos() - (before == null ? 0 : before.nanos());
                long calls = p.calls() - (before == null ? 0 : before.calls());
                double msPerS = nanos / 1e6 / secs;
                if (!p.nested()) {
                    buckets.merge(p.bucket().name(), msPerS, Double::sum);
                    tick += msPerS;
                }
                phases.add(List.of(p.name(), p.bucket().name(), p.nested(), round2(msPerS), round1(calls / secs),
                        calls == 0 ? 0 : round1(nanos / 1e3 / calls)));
            }
            buckets.replaceAll((k, v) -> round2(v));
            out.put("buckets", buckets);
            out.put("tickMsPerS", round2(tick));
            out.put("phases", phases);
        }
        prevPhases = now;
        return out;
    }

    private static String name(IntFunction<String> mapName, int mapId) {
        try {
            String n = mapName.apply(mapId);
            return n == null ? "" : n;
        } catch (RuntimeException e) {
            return "";
        }
    }

    static double round1(double v) {
        return Double.isFinite(v) ? Math.round(v * 10) / 10.0 : 0;
    }

    static double round2(double v) {
        return Double.isFinite(v) ? Math.round(v * 100) / 100.0 : 0;
    }
}
