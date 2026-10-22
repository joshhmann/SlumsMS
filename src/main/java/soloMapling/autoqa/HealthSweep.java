package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * One auto-QA health sweep over a sample of the live bots: the invariants (a) to (f) of docs/ops/qa-suite.md
 * "Auto-QA". Holds the state that spans sweeps (where each bot stood still, the error-rate history), so one instance
 * lives for the server's lifetime. Pure: the game side ({@link GameHealthSource}) hands it {@link BotSample}s and the
 * footholds, so the whole sweep is unit-tested ({@code HealthSweepTest}).
 */
public final class HealthSweep {

    /** One bot as the sweep saw it. {@code moving}: the movement driver says it is going somewhere. */
    public record BotSample(int id, String name, int channel, int mapId, int x, int y, FootholdCheck.Pose pose,
                            boolean moving, boolean driven, String mapProblem) {
    }

    /** Everything a sweep needs from the game, taken at one moment. */
    public record Snapshot(long nowMs, List<BotSample> bots, int botsSeen, int mapsSeen, int[] botsPerChannel,
                           double[] expectedShares, int errorLines, String errorSource) {
    }

    /** Thresholds; {@link #defaults()} matches config.yaml's AUTOQA_* defaults. */
    public record Limits(int footholdTolerancePx, long stuckMs, int clumpRadiusPx, int clumpMinBots,
                         double channelTolerance, int channelMinTotal, int errorMinPerWindow, double errorSpikeFactor,
                         int maxListed) {
        public static Limits defaults() {
            return new Limits(FootholdCheck.TOLERANCE_PX, 3 * 60_000L, 30, 5, 0.15, 40, 20, 3.0, 25);
        }
    }

    public enum Status { OK, WARN, FAIL }

    private final Limits limits;
    private final SweepMath.StuckTracker stuck = new SweepMath.StuckTracker();
    private final SweepMath.ErrorRate errors;

    public HealthSweep(Limits limits) {
        this.limits = limits;
        this.errors = new SweepMath.ErrorRate(12, limits.errorMinPerWindow(), limits.errorSpikeFactor());
    }

    /**
     * Runs the checks on a snapshot. {@code footholds} gives the foothold lines of a map id (an empty list when the
     * map has none loaded). Returns the result as a JSON-ready map ({@code soloMapling.server.Json}).
     */
    public synchronized Map<String, Object> run(Snapshot s, Function<Integer, List<FootholdCheck.Seg>> footholds) {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("foothold", footholdCheck(s, footholds));
        checks.put("stuck", stuckCheck(s));
        checks.put("clumps", clumpCheck(s));
        checks.put("channelSplit", channelCheck(s));
        checks.put("invalidMaps", invalidMapCheck(s));
        checks.put("errors", errorCheck(s));

        Status worst = Status.OK;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> e : checks.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) e.getValue();
            Status st = Status.valueOf((String) c.get("status"));
            if (st.ordinal() > worst.ordinal()) {
                worst = st;
            }
            if (st != Status.OK) {
                problems.add(e.getKey() + " " + st + ": " + c.get("summary"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", worst.name());
        out.put("summary", problems.isEmpty() ? "all invariants hold" : String.join("; ", problems));
        out.put("botsSeen", s.botsSeen());
        out.put("botsSampled", s.bots().size());
        out.put("mapsSeen", s.mapsSeen());
        out.put("checks", checks);
        return out;
    }

    /* (a) grounded bots stand on a foothold, within the tolerance. */
    private Map<String, Object> footholdCheck(Snapshot s, Function<Integer, List<FootholdCheck.Seg>> footholds) {
        int checked = 0, skipped = 0;
        Map<String, Integer> byKind = new TreeMap<>();
        Map<Integer, Integer> byMap = new TreeMap<>();
        List<Map<String, Object>> worst = new ArrayList<>();
        Map<Integer, List<FootholdCheck.Seg>> cache = new HashMap<>();
        for (BotSample b : s.bots()) {
            List<FootholdCheck.Seg> fhs = cache.computeIfAbsent(b.mapId(), footholds);
            if (fhs == null || fhs.isEmpty()) {
                skipped++;
                continue;
            }
            FootholdCheck.Verdict v = FootholdCheck.check(fhs, b.x(), b.y(), b.pose(), limits.footholdTolerancePx());
            if (v.kind() == FootholdCheck.Kind.SKIPPED) {
                skipped++;
                continue;
            }
            checked++;
            if (v.bad()) {
                byKind.merge(v.kind().name(), 1, Integer::sum);
                byMap.merge(b.mapId(), 1, Integer::sum);
                Map<String, Object> m = bot(b);
                m.put("kind", v.kind().name());
                m.put("groundY", Double.isNaN(v.groundY()) ? null : round1(v.groundY()));
                m.put("dy", Double.isNaN(v.dy()) ? null : round1(v.dy()));
                worst.add(m);
            }
        }
        worst.sort((a, b) -> Double.compare(absOr((Double) b.get("dy")), absOr((Double) a.get("dy"))));
        int bad = worst.size();
        int sunk = byKind.getOrDefault("SUNK", 0);
        Status st = sunk > 0 ? Status.FAIL : bad > 0 ? Status.WARN : Status.OK;
        Map<String, Object> c = check(st, bad == 0 ? checked + " grounded bots on their footholds"
                : bad + " of " + checked + " grounded bots off their foothold " + byKind + " on maps " + byMap.keySet());
        c.put("checked", checked);
        c.put("skipped", skipped);
        c.put("bad", bad);
        c.put("byKind", byKind);
        c.put("byMap", keyed(byMap));
        c.put("worst", head(worst));
        return c;
    }

    /* (b) a bot that should be moving has not left its spot for longer than the limit. */
    private Map<String, Object> stuckCheck(Snapshot s) {
        Set<Integer> present = new HashSet<>();
        List<Map<String, Object>> list = new ArrayList<>();
        int moving = 0;
        for (BotSample b : s.bots()) {
            present.add(b.id());
            if (b.moving()) {
                moving++;
            }
            long still = stuck.observe(b.id(), b.mapId(), b.x(), b.y(), b.moving(), s.nowMs());
            if (still > limits.stuckMs()) {
                Map<String, Object> m = bot(b);
                m.put("stillSeconds", still / 1000);
                m.put("pose", b.pose().name());
                list.add(m);
            }
        }
        stuck.retain(present);
        list.sort((a, b) -> Long.compare((Long) b.get("stillSeconds"), (Long) a.get("stillSeconds")));
        Status st = list.isEmpty() ? Status.OK : list.size() >= 10 ? Status.FAIL : Status.WARN;
        Map<String, Object> c = check(st, list.isEmpty() ? moving + " moving bots, none stuck"
                : list.size() + " of " + moving + " moving bots in one spot for over " + limits.stuckMs() / 60_000 + " min");
        c.put("moving", moving);
        c.put("stuck", list.size());
        c.put("bots", head(list));
        return c;
    }

    /* (c) too many driven bots within a small radius on one map (town dressing and FM rooms are left out). */
    private Map<String, Object> clumpCheck(Snapshot s) {
        Map<String, List<SweepMath.Spot>> byMap = new LinkedHashMap<>();
        for (BotSample b : s.bots()) {
            if (!b.driven() || isFreeMarket(b.mapId())) {
                continue;
            }
            byMap.computeIfAbsent(b.channel() + ":" + b.mapId(), k -> new ArrayList<>()).add(new SweepMath.Spot(b.id(), b.x(), b.y()));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, List<SweepMath.Spot>> e : byMap.entrySet()) {
            for (SweepMath.Clump cl : SweepMath.clumps(SweepMath.sample(e.getValue(), 200), limits.clumpRadiusPx(), limits.clumpMinBots())) {
                Map<String, Object> m = new LinkedHashMap<>();
                String[] k = e.getKey().split(":");
                m.put("channel", Integer.parseInt(k[0]));
                m.put("map", Integer.parseInt(k[1]));
                m.put("x", cl.x());
                m.put("y", cl.y());
                m.put("bots", cl.members().size());
                m.put("ids", cl.members());
                list.add(m);
            }
        }
        list.sort((a, b) -> Integer.compare((Integer) b.get("bots"), (Integer) a.get("bots")));
        Status st = list.isEmpty() ? Status.OK : Status.WARN;
        Map<String, Object> c = check(st, list.isEmpty() ? "no clumps of " + limits.clumpMinBots() + "+ within "
                + limits.clumpRadiusPx() + " px" : list.size() + " clump(s), biggest " + list.get(0).get("bots") + " bots on map "
                + list.get(0).get("map") + " ch" + list.get(0).get("channel"));
        c.put("radiusPx", limits.clumpRadiusPx());
        c.put("minBots", limits.clumpMinBots());
        c.put("clumps", head(list));
        return c;
    }

    /* (d) the bots per channel against BOT_CHANNEL_WEIGHTS. */
    private Map<String, Object> channelCheck(Snapshot s) {
        List<SweepMath.ChannelShare> split = SweepMath.channelSplit(s.botsPerChannel(), s.expectedShares(),
                limits.channelTolerance(), limits.channelMinTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (SweepMath.ChannelShare cs : split) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", cs.channel());
            m.put("bots", cs.bots());
            m.put("share", round3(cs.share()));
            m.put("expected", round3(cs.expected()));
            m.put("off", cs.off());
            rows.add(m);
            if (cs.off()) {
                off.add("ch" + cs.channel() + " " + pct(cs.share()) + " (want " + pct(cs.expected()) + ")");
            }
        }
        Map<String, Object> c = check(off.isEmpty() ? Status.OK : Status.WARN,
                off.isEmpty() ? "split matches the weights" : String.join(", ", off));
        c.put("channels", rows);
        return c;
    }

    /* (e) bots on maps no bot should be on, or out of their map's bounds. */
    private Map<String, Object> invalidMapCheck(Snapshot s) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BotSample b : s.bots()) {
            if (b.mapProblem() != null) {
                Map<String, Object> m = bot(b);
                m.put("problem", b.mapProblem());
                list.add(m);
            }
        }
        Map<String, Object> c = check(list.isEmpty() ? Status.OK : Status.FAIL,
                list.isEmpty() ? "every bot on a valid map" : list.size() + " bot(s) on invalid maps");
        c.put("count", list.size());
        c.put("bots", head(list));
        return c;
    }

    /* (f) error lines since the last sweep against the recent median. */
    private Map<String, Object> errorCheck(Snapshot s) {
        double base = errors.median();
        boolean spike = s.errorLines() >= 0 && errors.add(s.errorLines());
        Map<String, Object> c = check(spike ? Status.FAIL : Status.OK, s.errorLines() < 0 ? "log not readable"
                : s.errorLines() + " error line(s) this window" + (spike ? ", a spike (median " + base + ")" : ""));
        c.put("errorLines", s.errorLines());
        c.put("median", base);
        c.put("source", s.errorSource());
        return c;
    }

    /** True for the Free Market entrance and rooms, where shop bots stand packed on purpose. */
    static boolean isFreeMarket(int mapId) {
        return mapId >= 910000000 && mapId <= 910000099;
    }

    private Map<String, Object> check(Status st, String summary) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("status", st.name());
        c.put("summary", summary);
        return c;
    }

    private static Map<String, Object> bot(BotSample b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("name", b.name());
        m.put("channel", b.channel());
        m.put("map", b.mapId());
        m.put("x", b.x());
        m.put("y", b.y());
        return m;
    }

    private List<Map<String, Object>> head(List<Map<String, Object>> list) {
        return list.size() <= limits.maxListed() ? list : new ArrayList<>(list.subList(0, limits.maxListed()));
    }

    private static Map<String, Object> keyed(Map<Integer, Integer> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static double absOr(Double d) {
        return d == null ? Double.MAX_VALUE : Math.abs(d);
    }

    private static double round1(double d) {
        return Math.round(d * 10) / 10.0;
    }

    private static double round3(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    private static String pct(double d) {
        return Math.round(d * 100) + "%";
    }
}
