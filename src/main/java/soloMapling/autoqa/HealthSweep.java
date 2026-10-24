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
 * One auto-QA health sweep over a sample of the live bots: the invariants (a) to (g) of docs/ops/qa-suite.md
 * "Auto-QA". Holds the state that spans sweeps (where each bot stood still, the error-rate history), so one instance
 * lives for the server's lifetime. Pure: the game side ({@link GameHealthSource}) hands it {@link BotSample}s and the
 * footholds, so the whole sweep is unit-tested ({@code HealthSweepTest}).
 */
public final class HealthSweep {

    /** One bot as the sweep saw it. {@code moving}: the movement driver says it is going somewhere. */
    /*
     * rc6 personal space: {@code idle} = not walking by any engine (a recorded-path walk counts too); {@code pairKey}
     * = the deliberate pair it is in (couple, talking with a player, a scene, a shop), or null; {@code town} = a town
     * or hangout map; {@code player} = a real player (the overlap check counts players; the other checks never see
     * them).
     */
    public record BotSample(int id, String name, int channel, int mapId, int x, int y, FootholdCheck.Pose pose,
                            boolean moving, boolean driven, String mapProblem, boolean synthetic, boolean idle,
                            String pairKey, boolean town, boolean player) {
        public BotSample(int id, String name, int channel, int mapId, int x, int y, FootholdCheck.Pose pose,
                         boolean moving, boolean driven, String mapProblem, boolean synthetic) {
            this(id, name, channel, mapId, x, y, pose, moving, driven, mapProblem, synthetic, !moving, null, false, false);
        }

        public BotSample(int id, String name, int channel, int mapId, int x, int y, FootholdCheck.Pose pose,
                         boolean moving, boolean driven, String mapProblem) {
            this(id, name, channel, mapId, x, y, pose, moving, driven, mapProblem, false);
        }
    }

    /**
     * rc6: a position the server last SENT to clients (spawn or move packet) for a character on a map a real or
     * synthetic player is on: what a viewer's client draws. {@code kind} is "bot", "synthetic" or "player".
     */
    public record SentSample(int id, String name, int channel, int mapId, int x, int y, FootholdCheck.Pose pose,
                             String kind, long ageMs, String how) {
    }

    /** Everything a sweep needs from the game, taken at one moment. */
    public record Snapshot(long nowMs, List<BotSample> bots, int botsSeen, int mapsSeen, int[] botsPerChannel,
                           double[] expectedShares, int errorLines, String errorSource,
                           List<soloMapling.ArtificialPlayer.BotMapCap.Load> mapLoads, List<SentSample> sent,
                           List<BotSample> players) {
        /** rc6: without the real players (the overlap check then looks at bots only). */
        public Snapshot(long nowMs, List<BotSample> bots, int botsSeen, int mapsSeen, int[] botsPerChannel,
                        double[] expectedShares, int errorLines, String errorSource,
                        List<soloMapling.ArtificialPlayer.BotMapCap.Load> mapLoads, List<SentSample> sent) {
            this(nowMs, bots, botsSeen, mapsSeen, botsPerChannel, expectedShares, errorLines, errorSource, mapLoads, sent,
                    null);
        }

        public Snapshot(long nowMs, List<BotSample> bots, int botsSeen, int mapsSeen, int[] botsPerChannel,
                        double[] expectedShares, int errorLines, String errorSource,
                        List<soloMapling.ArtificialPlayer.BotMapCap.Load> mapLoads) {
            this(nowMs, bots, botsSeen, mapsSeen, botsPerChannel, expectedShares, errorLines, errorSource, mapLoads, null);
        }

        /** Without map loads (the map-cap check then has nothing to look at). */
        public Snapshot(long nowMs, List<BotSample> bots, int botsSeen, int mapsSeen, int[] botsPerChannel,
                        double[] expectedShares, int errorLines, String errorSource) {
            this(nowMs, bots, botsSeen, mapsSeen, botsPerChannel, expectedShares, errorLines, errorSource, List.of());
        }
    }

    /** Thresholds; {@link #defaults()} matches config.yaml's AUTOQA_* defaults. */
    public record Limits(int footholdTolerancePx, long stuckMs, int clumpRadiusPx, int clumpMinBots,
                         double channelTolerance, int channelMinTotal, int errorMinPerWindow, double errorSpikeFactor,
                         int maxListed, int townClumpRadiusPx, int townClumpMinBots, int overlapDxPx, int overlapDyPx,
                         int overlapFailAt) {
        /** rc6: town clumps of 3 within 20 px; overlap = |dx| < 12 and |dy| < 5, FAIL at 5 pairs. */
        public Limits(int footholdTolerancePx, long stuckMs, int clumpRadiusPx, int clumpMinBots,
                      double channelTolerance, int channelMinTotal, int errorMinPerWindow, double errorSpikeFactor,
                      int maxListed) {
            this(footholdTolerancePx, stuckMs, clumpRadiusPx, clumpMinBots, channelTolerance, channelMinTotal,
                    errorMinPerWindow, errorSpikeFactor, maxListed, TOWN_CLUMP_RADIUS, TOWN_CLUMP_MIN, OVERLAP_DX,
                    OVERLAP_DY, OVERLAP_FAIL_AT);
        }

        public static Limits defaults() {
            return new Limits(FootholdCheck.TOLERANCE_PX, 3 * 60_000L, 30, 5, 0.15, 40, 20, 3.0, 25);
        }
    }

    /*
     * rc6 personal space. Town bots keep 25 px from strangers and 15 px from a partner, so 3 bots inside a 20 px
     * radius on a town map is already a stack (the field threshold of 5 within 30 px never saw pairs or trios).
     */
    static final int TOWN_CLUMP_RADIUS = 20, TOWN_CLUMP_MIN = 3;
    static final int OVERLAP_DX = 12, OVERLAP_DY = 5, OVERLAP_FAIL_AT = 5;

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
        checks.put(SENT_CHECK, footholdSentCheck(s, footholds));
        checks.put("stuck", stuckCheck(s));
        checks.put("clumps", clumpCheck(s));
        checks.put("overlap", overlapCheck(s));
        checks.put("channelSplit", channelCheck(s));
        checks.put("invalidMaps", invalidMapCheck(s));
        checks.put("errors", errorCheck(s));
        checks.put("mapCap", mapCapCheck(s));

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

    /** The report name of the client-side grounding check (next to "foothold", the server's own positions). */
    public static final String SENT_CHECK = "foothold (sent)";

    /*
     * (a2) rc6: the same rule on what clients were TOLD: the y of the last spawn/move packet each character on a map
     * with a real or synthetic player got, against the foothold under that x. Same tolerance, poses and skip rules.
     */
    private Map<String, Object> footholdSentCheck(Snapshot s, Function<Integer, List<FootholdCheck.Seg>> footholds) {
        List<SentSample> sent = s.sent() == null ? List.of() : s.sent();
        int checked = 0, skipped = 0;
        Map<String, Integer> byKind = new TreeMap<>();
        Map<Integer, Integer> byMap = new TreeMap<>();
        Map<String, Integer> skippedByPose = new TreeMap<>();
        Map<String, Integer> checkedByKind = new TreeMap<>();
        List<Map<String, Object>> worst = new ArrayList<>();
        Map<Integer, List<FootholdCheck.Seg>> cache = new HashMap<>();
        for (SentSample b : sent) {
            List<FootholdCheck.Seg> fhs = cache.computeIfAbsent(b.mapId(), footholds);
            if (fhs == null || fhs.isEmpty()) {
                skipped++;
                continue;
            }
            FootholdCheck.Verdict v = FootholdCheck.check(fhs, b.x(), b.y(), b.pose(), limits.footholdTolerancePx());
            if (v.kind() == FootholdCheck.Kind.SKIPPED) {
                skipped++;
                skippedByPose.merge(b.pose().name(), 1, Integer::sum);
                continue;
            }
            checked++;
            checkedByKind.merge(b.kind(), 1, Integer::sum);
            if (v.bad()) {
                byKind.merge(v.kind().name(), 1, Integer::sum);
                byMap.merge(b.mapId(), 1, Integer::sum);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.id());
                m.put("name", b.name());
                m.put("channel", b.channel());
                m.put("map", b.mapId());
                m.put("x", b.x());
                m.put("y", b.y());
                m.put("who", b.kind());
                m.put("kind", v.kind().name());
                m.put("pose", b.pose().name());
                m.put("packet", b.how());
                m.put("ageSeconds", b.ageMs() / 1000);
                m.put("groundY", Double.isNaN(v.groundY()) ? null : round1(v.groundY()));
                m.put("dy", Double.isNaN(v.dy()) ? null : round1(v.dy()));
                worst.add(m);
            }
        }
        worst.sort((a, b) -> Double.compare(absOr((Double) b.get("dy")), absOr((Double) a.get("dy"))));
        int bad = worst.size();
        int sunk = byKind.getOrDefault("SUNK", 0);
        Status st = sunk > 0 ? Status.FAIL : bad > 0 ? Status.WARN : Status.OK;
        String summary = sent.isEmpty() ? "no character on a map a player watches"
                : bad == 0 ? checked + " sent grounded positions on their footholds"
                : bad + " of " + checked + " sent positions off their foothold " + byKind + " on maps " + byMap.keySet();
        Map<String, Object> c = check(st, summary);
        c.put("sampled", sent.size());
        c.put("checked", checked);
        c.put("checkedByWho", checkedByKind);
        c.put("skipped", skipped);
        c.put("skippedByPose", skippedByPose);
        c.put("bad", bad);
        c.put("byKind", byKind);
        c.put("byMap", keyed(byMap));
        c.put("worst", head(worst));
        return c;
    }

    /* (a) grounded bots (and auto-QA's synthetic players, rc6) stand on a foothold, within the tolerance. */
    private Map<String, Object> footholdCheck(Snapshot s, Function<Integer, List<FootholdCheck.Seg>> footholds) {
        int checked = 0, skipped = 0, syntheticChecked = 0;
        Map<String, Integer> byKind = new TreeMap<>();
        Map<Integer, Integer> byMap = new TreeMap<>();
        List<Map<String, Object>> worst = new ArrayList<>();
        Map<Integer, List<FootholdCheck.Seg>> cache = new HashMap<>();
        Map<String, Integer> skippedByPose = new TreeMap<>();
        for (BotSample b : s.bots()) {
            List<FootholdCheck.Seg> fhs = cache.computeIfAbsent(b.mapId(), footholds);
            if (fhs == null || fhs.isEmpty()) {
                skipped++;
                continue;
            }
            FootholdCheck.Verdict v = FootholdCheck.check(fhs, b.x(), b.y(), b.pose(), limits.footholdTolerancePx());
            if (v.kind() == FootholdCheck.Kind.SKIPPED) {
                skipped++;
                skippedByPose.merge(b.pose().name(), 1, Integer::sum);
                continue;
            }
            checked++;
            if (b.synthetic()) {
                syntheticChecked++;
            }
            if (v.bad()) {
                byKind.merge(v.kind().name(), 1, Integer::sum);
                byMap.merge(b.mapId(), 1, Integer::sum);
                Map<String, Object> m = bot(b);
                m.put("kind", v.kind().name());
                m.put("pose", b.pose().name());
                m.put("driven", b.driven());
                if (b.synthetic()) {
                    m.put("synthetic", true);
                }
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
        c.put("syntheticChecked", syntheticChecked);
        c.put("skipped", skipped);
        c.put("skippedByPose", skippedByPose);
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
            if (b.synthetic()) {
                continue; // a pretend player stands where the check put it
            }
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

    /*
     * (c) too many driven bots within a small radius on one map (FM rooms are left out). rc6: on town and hangout maps
     * every grounded idle bot counts (the recorded-path town crowd has no physics, so it was never looked at), and 3
     * within 20 px is a clump there.
     */
    private Map<String, Object> clumpCheck(Snapshot s) {
        Map<String, List<SweepMath.Spot>> byMap = new LinkedHashMap<>();
        Set<String> townMaps = new HashSet<>();
        for (BotSample b : s.bots()) {
            if (isFreeMarket(b.mapId()) || b.player()) {
                continue;
            }
            boolean townIdle = b.town() && b.idle() && b.pose() == FootholdCheck.Pose.GROUNDED;
            if (!b.driven() && !townIdle) {
                continue;
            }
            String key = b.channel() + ":" + b.mapId();
            if (b.town()) {
                townMaps.add(key);
            }
            byMap.computeIfAbsent(key, k -> new ArrayList<>()).add(new SweepMath.Spot(b.id(), b.x(), b.y()));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, List<SweepMath.Spot>> e : byMap.entrySet()) {
            boolean town = townMaps.contains(e.getKey());
            int radius = town ? limits.townClumpRadiusPx() : limits.clumpRadiusPx();
            int min = town ? limits.townClumpMinBots() : limits.clumpMinBots();
            for (SweepMath.Clump cl : SweepMath.clumps(SweepMath.sample(e.getValue(), 200), radius, min)) {
                Map<String, Object> m = new LinkedHashMap<>();
                String[] k = e.getKey().split(":");
                m.put("channel", Integer.parseInt(k[0]));
                m.put("map", Integer.parseInt(k[1]));
                m.put("town", town);
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
                + limits.clumpRadiusPx() + " px (towns: " + limits.townClumpMinBots() + "+ within "
                + limits.townClumpRadiusPx() + " px)" : list.size() + " clump(s), biggest " + list.get(0).get("bots") + " bots on map "
                + list.get(0).get("map") + " ch" + list.get(0).get("channel"));
        c.put("radiusPx", limits.clumpRadiusPx());
        c.put("minBots", limits.clumpMinBots());
        c.put("townRadiusPx", limits.townClumpRadiusPx());
        c.put("townMinBots", limits.townClumpMinBots());
        c.put("clumps", head(list));
        return c;
    }

    /*
     * (h) rc6 personal space: two grounded, idle characters (bots, auto-QA's pretend players and real players) on one
     * map instance with |dx| < 12 px and |dy| < 5 px: their name tags overlap and the one below can't be clicked.
     * Ropes, ladders, jumps and coarse unwatched moves are left out (only GROUNDED idle samples count), and so are
     * deliberate pairs (the same pair key). WARN at any overlap, FAIL at 5; the worst maps are listed.
     */
    private Map<String, Object> overlapCheck(Snapshot s) {
        List<SweepMath.Body> bodies = new ArrayList<>();
        Map<Integer, BotSample> byId = new HashMap<>();
        List<BotSample> all = new ArrayList<>(s.bots());
        if (s.players() != null) {
            all.addAll(s.players());
        }
        for (BotSample b : all) {
            if (!b.idle() || b.pose() != FootholdCheck.Pose.GROUNDED || byId.containsKey(b.id())) {
                continue;
            }
            byId.put(b.id(), b);
            bodies.add(new SweepMath.Body(b.id(), b.channel(), b.mapId(), b.x(), b.y(), b.pairKey()));
        }
        List<SweepMath.Overlap> overlaps = SweepMath.overlaps(bodies, limits.overlapDxPx(), limits.overlapDyPx());
        Map<String, Integer> byMap = new HashMap<>();
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (SweepMath.Overlap o : overlaps) {
            BotSample a = byId.get(o.a()), b = byId.get(o.b());
            byMap.merge(a.channel() + ":" + a.mapId(), 1, Integer::sum);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", a.channel());
            m.put("map", a.mapId());
            m.put("a", a.name() + (a.player() ? " (player)" : ""));
            m.put("b", b.name() + (b.player() ? " (player)" : ""));
            m.put("ids", List.of(a.id(), b.id()));
            m.put("x", a.x());
            m.put("y", a.y());
            m.put("dx", Math.abs(a.x() - b.x()));
            m.put("dy", Math.abs(a.y() - b.y()));
            pairs.add(m);
        }
        pairs.sort((p, q) -> Integer.compare((Integer) p.get("dx") + (Integer) p.get("dy"),
                (Integer) q.get("dx") + (Integer) q.get("dy")));
        List<Map<String, Object>> worstMaps = new ArrayList<>();
        byMap.entrySet().stream()
                .sorted((p, q) -> q.getValue() != p.getValue().intValue() ? Integer.compare(q.getValue(), p.getValue())
                        : p.getKey().compareTo(q.getKey()))
                .forEach(e -> {
                    String[] k = e.getKey().split(":");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("channel", Integer.parseInt(k[0]));
                    m.put("map", Integer.parseInt(k[1]));
                    m.put("pairs", e.getValue());
                    worstMaps.add(m);
                });
        int n = overlaps.size();
        Status st = n == 0 ? Status.OK : n >= limits.overlapFailAt() ? Status.FAIL : Status.WARN;
        String summary = n == 0 ? bodies.size() + " grounded idle characters, none on top of another"
                : n + " overlapping pair(s) (|dx| < " + limits.overlapDxPx() + ", |dy| < " + limits.overlapDyPx()
                + " px), worst map " + worstMaps.get(0).get("map") + " ch" + worstMaps.get(0).get("channel") + " ("
                + worstMaps.get(0).get("pairs") + ")";
        Map<String, Object> c = check(st, summary);
        c.put("checked", bodies.size());
        c.put("pairs", n);
        c.put("failAt", limits.overlapFailAt());
        c.put("worstMaps", head(worstMaps));
        c.put("overlaps", head(pairs));
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
            if (b.mapProblem() != null && !b.synthetic()) {
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

    /*
     * (g) 1.5.0 (3k bots): map instances holding more bots than their per-map cap (BOT_MAP_CAP). The cap only stops
     * additions and some arrivals are exempt (set pieces, followers, story scenes), so a map can go over: WARN, with
     * the instances listed busiest first. Every instance with bots is looked at, not the bot sample.
     */
    private Map<String, Object> mapCapCheck(Snapshot s) {
        List<Map<String, Object>> list = new ArrayList<>();
        int busiest = 0;
        int full = 0;
        List<soloMapling.ArtificialPlayer.BotMapCap.Load> loads = s.mapLoads() == null ? List.of() : s.mapLoads();
        for (soloMapling.ArtificialPlayer.BotMapCap.Load l : loads) {
            busiest = Math.max(busiest, l.bots());
            if (l.full()) {
                full++;
            }
            if (l.over()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("channel", l.channel());
                m.put("map", l.mapId());
                m.put("bots", l.bots());
                m.put("cap", l.cap());
                list.add(m);
            }
        }
        list.sort((a, b) -> Integer.compare((Integer) b.get("bots") - (Integer) b.get("cap"),
                (Integer) a.get("bots") - (Integer) a.get("cap")));
        Status st = list.isEmpty() ? Status.OK : Status.WARN;
        Map<String, Object> c = check(st, list.isEmpty()
                ? loads.size() + " map instance(s) with bots, none over its cap (busiest " + busiest + ")"
                : list.size() + " map instance(s) over the cap, worst map " + list.get(0).get("map") + " ch"
                + list.get(0).get("channel") + " " + list.get(0).get("bots") + "/" + list.get(0).get("cap"));
        c.put("instances", loads.size());
        c.put("full", full);
        c.put("busiest", busiest);
        c.put("over", head(list));
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
