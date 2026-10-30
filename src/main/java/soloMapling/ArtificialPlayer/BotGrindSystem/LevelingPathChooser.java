package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import server.maps.MiniDungeonInfo;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;
import soloMapling.ArtificialPlayer.BotYieldCaps;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

// Ticket 31: picks a training bot's next map from the leveling path (LevelingPath.yaml) by its level band and job
// group. TrainingMapChooser asks first on most decisions; a null answer means "nothing on the path fits right now"
// and the chooser carries on exactly as before (nearby maps by level, TrainingRegions relocation).
//
// An entry is a candidate for this bot only when the live world agrees: the map has mobs in this WZ, is not a
// mini-dungeon, is not in a region TrainingRegions.yaml disables, BotMapGates lets the bot's rebirths in, its
// mobs are not far above the bot, it is not on the bot's crowd cooldown, it has free spot capacity, and it is
// either walkable from the bot's home or a route (cabs, boats, trains...) reaches it that the bot can pay for.
// The draw is weighted: entry weight x the bot's favourite continents (a stable per-bot bias, so a cohort spreads
// instead of copying each other) x free room on the map x its ticket-15 yield headroom, with maps around the
// bot's home favoured over a trip abroad. A trip abroad also needs one of the world-wide relocation permits and
// moves the bot's home to the hub (or the map's return town) on arrival.
//
// Tuned with -D system properties (config.yaml is strict):
//   solomapling.levelPath=false        switch the path off (every bot uses the old choice)
//   solomapling.levelPath.follow       chance per decision to follow the path while home still fits (0.75)
//   solomapling.levelPath.generalMix   weight factor for a band's general list next to a job's own list (0.35)
//   solomapling.levelPath.maxAbove     skip maps whose median mob level is more than this above the bot (15)
//   solomapling.levelPath.homeBias     weight factor for maps walkable from home over trips abroad (3.0)
//   solomapling.levelPath.belowChance  chance to draw from the band below instead (0.08)
//   solomapling.levelPath.aheadChance  chance to draw from the next band when within 3 levels of it (0.10)
//   solomapling.levelPath.partyWeight  weight factor for party spots, bots train solo (0.5)
// Ours (SoloMapling).
public final class LevelingPathChooser {

    private LevelingPathChooser() {
    }

    public record Tuning(boolean enabled, double follow, double generalMix, int maxAbove, double homeBias,
                         double belowChance, double aheadChance, double partyWeight) {
        static Tuning fromSystemProperties() {
            return new Tuning(
                    !"false".equalsIgnoreCase(System.getProperty("solomapling.levelPath", "true").trim()),
                    clamp01(dbl("solomapling.levelPath.follow", 0.75)),
                    Math.max(0, dbl("solomapling.levelPath.generalMix", 0.35)),
                    (int) Math.max(0, dbl("solomapling.levelPath.maxAbove", 15)),
                    Math.max(0, dbl("solomapling.levelPath.homeBias", 3.0)),
                    clamp01(dbl("solomapling.levelPath.belowChance", 0.08)),
                    clamp01(dbl("solomapling.levelPath.aheadChance", 0.10)),
                    Math.max(0, dbl("solomapling.levelPath.partyWeight", 0.5)));
        }
    }

    static final int AHEAD_WINDOW = 3;     // levels before the next band where a bot may skip ahead
    static final int MIN_MOB_COUNT = 2;    // as TrainingMapFinder: no one-off quest-mob maps

    // What the chooser knows about the bot. hardMinMob: a downward-only deep-hub floor (1 = none).
    public record Request(int botId, int level, int rebirths, int jobId, int meso, int homeMapId,
                          Set<Integer> excluded, int hardMinMob) {
    }

    // The live world, faked in tests.
    interface World {
        int mobLevel(int mapId);                    // median mob level, -1 = no mobs / no such map

        int mobCount(int mapId);

        boolean isDungeon(int mapId);

        boolean regionDisabled(int mapId);          // inside a TrainingRegions region with enabled: false

        Map<Integer, Integer> walkReach(int homeMapId); // maps walkable from home -> hops

        int routeFare(int fromMapId, int toMapId, int rebirths, int jobId, int level); // -1 = unreachable

        int capacity(int mapId);

        int botsOn(int mapId);

        double yieldSaturation(int mapId);          // 0 = untouched .. 1 = the map's bot yield cap is spent

        int homeFor(int mapId);                     // the town a bot training there lives in, or -1
    }

    // Uniform [0, 1) draws, so tests can script them.
    interface Rolls {
        double next();
    }

    private static volatile Tuning tuning = Tuning.fromSystemProperties();
    private static final World LIVE = new LiveWorld();
    private static final Permits PERMITS = new Permits(System::currentTimeMillis);

    public static Tuning tuning() {
        return tuning;
    }

    static void setTuning(Tuning t) {
        tuning = t == null ? Tuning.fromSystemProperties() : t;
    }

    // Re-read the YAML and the -D tunables, and forget the cached walks (`!env path reload`).
    public static int reload() {
        tuning = Tuning.fromSystemProperties();
        LiveWorld.clearCaches();
        return LevelingPath.reload().entryCount();
    }

    public static boolean enabled() {
        return tuning.enabled() && !LevelingPath.path().isEmpty();
    }

    // Should this decision consult the path? Always when home has nothing in the bot's band, else `follow`.
    public static boolean wantsPath(boolean localInBand, double roll) {
        return enabled() && (!localInBand || roll < tuning.follow());
    }

    // The live entry point: a path map for this bot (NOT reserved - the chooser reserves), or null.
    public static TrainingMap choose(Character chr, int homeMapId, int rebirths, int hardMinMob, Set<Integer> excluded) {
        Request r = new Request(chr.getId(), chr.getLevel(), rebirths, chr.getJob().getId(), chr.getMeso(),
                homeMapId, excluded, hardMinMob);
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        return choose(LevelingPath.path(), tuning, LIVE, PERMITS, r, rng::nextDouble);
    }

    // ── The pick (pure over World) ──

    static TrainingMap choose(LevelingPath.Path path, Tuning t, World w, Permits permits, Request r, Rolls rolls) {
        int band = path.bandIndex(r.level());
        if (band < 0) {
            return null;
        }
        // Variety: now and then a bot trains a band below, or one ahead when it is about to reach it.
        double v = rolls.next();
        if (v < t.belowChance() && band > 0) {
            band--;
        } else if (v < t.belowChance() + t.aheadChance() && band + 1 < path.bands().size()
                && r.level() >= path.bands().get(band + 1).lo() - AHEAD_WINDOW) {
            band++;
        }
        List<Candidate> local = new ArrayList<>();
        List<Candidate> abroad = new ArrayList<>();
        candidates(path, t, w, r, band, local, abroad);
        List<Candidate> all = new ArrayList<>(local);
        all.addAll(abroad);
        Candidate pick = draw(all, rolls.next());
        if (pick == null) {
            return null;
        }
        if (abroad.contains(pick) && !permits.take(TrainingRegions.tuning().relocationsPerMinute())) {
            pick = draw(local, rolls.next()); // the world-wide trip budget is spent this minute: stay around home
        }
        return pick == null ? null : pick.map();
    }

    /** The maps of this band a bot may pick: around its home (local) and elsewhere (abroad), with their weights. */
    static void candidates(LevelingPath.Path path, Tuning t, World w, Request r, int band, List<Candidate> local,
                           List<Candidate> abroad) {
        List<LevelingPath.Entry> entries = path.candidates(band, r.jobId(), t.generalMix());
        Map<Integer, Integer> home = r.homeMapId() > 0 ? w.walkReach(r.homeMapId()) : Map.of();
        for (LevelingPath.Entry e : entries) {
            int map = e.mapId();
            if (r.excluded() != null && r.excluded().contains(map)) {
                continue;
            }
            if (!BotMapGates.mayEnter(map, r.rebirths(), r.level()) || w.isDungeon(map) || w.regionDisabled(map)) {
                continue;
            }
            int mob = w.mobLevel(map);
            if (mob < 1 || w.mobCount(map) < MIN_MOB_COUNT || mob > r.level() + t.maxAbove()
                    || mob < r.hardMinMob()) {
                continue;
            }
            int cap = Math.max(1, w.capacity(map));
            int n = w.botsOn(map);
            if (n >= cap) {
                continue; // full: the next decision may find room, the fallback has other maps meanwhile
            }
            double weight = e.weight() * favourite(r.botId(), map) * ((cap - n) / (double) cap)
                    * TrainingMapChooser.crowdFactor(w.yieldSaturation(map)) * (e.party() ? t.partyWeight() : 1.0);
            if (weight <= 0) {
                continue;
            }
            Integer hops = map == r.homeMapId() ? Integer.valueOf(0) : home.get(map);
            if (hops != null) {
                local.add(new Candidate(new TrainingMap(map, mob, Math.max(1, hops)), weight * t.homeBias()));
                continue;
            }
            int fare = r.homeMapId() > 0 ? w.routeFare(r.homeMapId(), map, r.rebirths(), r.jobId(), r.level()) : -1;
            if (fare < 0 || fare > r.meso()) {
                continue; // no way there from home, or the bot can't pay the fares
            }
            int newHome = w.homeFor(map);
            if (newHome > 0 && !BotMapGates.mayEnter(newHome, r.rebirths(), r.level())) {
                newHome = -1; // 1.6.2 b041: never make a town the bot may not enter its home (a rebirth-gated hub)
            }
            int newHops = newHome > 0 ? w.walkReach(newHome).getOrDefault(map, 1) : 1;
            TrainingMap m = new TrainingMap(map, mob, Math.max(1, newHops));
            abroad.add(new Candidate(newHome > 0 && newHome != r.homeMapId() ? m.relocatingTo(newHome) : m, weight));
        }
    }

    /**
     * 1.6.2 b041 (auto-QA t-ld-1): every map and new home the leveling path would offer a bot of this level and
     * rebirth count from homeMapId, in its band and the bands either side of it (the variety rolls), on the live
     * world data and ignoring crowding: {map, new home or -1}.
     */
    public static List<int[]> offeredForProbe(int level, int rebirths, int jobId, int homeMapId) {
        LevelingPath.Path path = LevelingPath.path();
        int band = path.bandIndex(level);
        List<int[]> out = new ArrayList<>();
        if (band < 0) {
            return out;
        }
        Request r = new Request(0, level, rebirths, jobId, Integer.MAX_VALUE, homeMapId, null, 1);
        World uncrowded = new World() {
            public Map<Integer, Integer> walkReach(int hub) { return LIVE.walkReach(hub); }
            public int routeFare(int f, int to, int rb, int job, int lv) { return LIVE.routeFare(f, to, rb, job, lv); }
            public boolean isDungeon(int map) { return LIVE.isDungeon(map); }
            public boolean regionDisabled(int map) { return LIVE.regionDisabled(map); }
            public int mobLevel(int map) { return LIVE.mobLevel(map); }
            public int mobCount(int map) { return LIVE.mobCount(map); }
            public int capacity(int map) { return 1_000; }
            public int botsOn(int map) { return 0; }
            public double yieldSaturation(int map) { return 0; }
            public int homeFor(int map) { return LIVE.homeFor(map); }
        };
        for (int b = Math.max(0, band - 1); b <= Math.min(path.bands().size() - 1, band + 1); b++) {
            List<Candidate> local = new ArrayList<>();
            List<Candidate> abroad = new ArrayList<>();
            candidates(path, tuning, uncrowded, r, b, local, abroad);
            for (List<Candidate> cs : List.of(local, abroad)) {
                for (Candidate c : cs) {
                    out.add(new int[]{c.map().mapId(), c.map().relocates() ? c.map().relocateHomeMapId() : -1});
                }
            }
        }
        return out;
    }

    record Candidate(TrainingMap map, double weight) {
    }

    private static Candidate draw(List<Candidate> cs, double roll) {
        double total = 0;
        for (Candidate c : cs) {
            total += c.weight();
        }
        if (total <= 0) {
            return null;
        }
        double at = roll * total;
        for (Candidate c : cs) {
            at -= c.weight();
            if (at < 0) {
                return c;
            }
        }
        return cs.get(cs.size() - 1);
    }

    // A bot's stable liking for a continent (map id / 10,000,000: Victoria, Ossyria, Leafre/Mu Lung, ...),
    // between 0.5 and 2.0, so two bots of the same level and job don't rank the same maps the same way.
    static double favourite(int botId, int mapId) {
        int area = mapId / 10_000_000;
        long h = (botId * 0x9E3779B97F4A7C15L) ^ (area * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 31);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 29);
        double u = (h >>> 11) / (double) (1L << 53); // [0, 1)
        return Math.pow(2.0, 2.0 * u - 1.0);        // 0.5 .. 2.0, log-uniform
    }

    // World-wide cap on bots setting off abroad because of the path, the same per-minute figure as the
    // TrainingRegions relocation cap (relocations_per_minute) and counted on its own.
    static final class Permits {
        private final LongSupplier clock;
        private long windowStartMs = Long.MIN_VALUE;
        private int taken;

        Permits(LongSupplier clock) {
            this.clock = clock;
        }

        synchronized boolean take(int perMinute) {
            long now = clock.getAsLong();
            if (windowStartMs == Long.MIN_VALUE || now - windowStartMs >= 60_000L) {
                windowStartMs = now;
                taken = 0;
            }
            if (taken >= Math.max(0, perMinute)) {
                return false;
            }
            taken++;
            return true;
        }
    }

    // ── Level sweep (ticket 31 "done when"; `!env path sweep`, or logged with the census) ──

    // Per band and rebirth count: how many distinct path maps a bot could use (mobs, not a mini-dungeon, region
    // not disabled, gate open) and reach by any route from fromMapId, fares aside, at the band's lowest level (level-checked hops like Jeff count only for bots that pass them); then every map no route
    // reaches at rebirth 3. Static WZ + nav data only: no bot is touched.
    public static List<String> sweep(int fromMapId) {
        return sweep(LevelingPath.path(), LIVE, fromMapId);
    }

    static List<String> sweep(LevelingPath.Path path, World w, int fromMapId) {
        List<String> out = new ArrayList<>();
        java.util.Set<Integer> unreachable = new java.util.TreeSet<>();
        java.util.Set<Integer> unusable = new java.util.TreeSet<>();
        for (LevelingPath.Band band : path.bands()) {
            java.util.Set<Integer> maps = new java.util.TreeSet<>();
            for (List<LevelingPath.Entry> es : band.groups().values()) {
                for (LevelingPath.Entry e : es) {
                    maps.add(e.mapId());
                }
            }
            StringBuilder sb = new StringBuilder(String.format("[LevelPathSweep] %03d-%03d maps=%d", band.lo(),
                    band.hi(), maps.size()));
            for (int rebirths = 0; rebirths <= 3; rebirths++) {
                int ok = 0;
                for (int map : maps) {
                    if (w.mobLevel(map) < 1 || w.mobCount(map) < MIN_MOB_COUNT || w.isDungeon(map)
                            || w.regionDisabled(map)) {
                        unusable.add(map);
                        continue;
                    }
                    if (!BotMapGates.mayEnter(map, rebirths, band.lo())) {
                        continue;
                    }
                    if (map != fromMapId && w.routeFare(fromMapId, map, rebirths, 0, band.lo()) < 0) {
                        if (rebirths == 3) {
                            unreachable.add(map);
                        }
                        continue;
                    }
                    ok++;
                }
                sb.append(" r").append(rebirths).append('=').append(ok);
            }
            out.add(sb.toString());
        }
        out.add("[LevelPathSweep] unusable (no mobs / mini-dungeon / disabled region): " + unusable);
        out.add("[LevelPathSweep] no route from " + fromMapId + " at rebirth 3: " + unreachable.size() + " " + unreachable);
        return out;
    }

    // ── The live world ──

    static final class LiveWorld implements World {
        private static final Map<Integer, Map<Integer, Integer>> REACH = new ConcurrentHashMap<>();
        private static final Map<Integer, Integer> HOME_FOR = new ConcurrentHashMap<>();
        static final int NO_RETURN_MAP = 999999999;

        static void clearCaches() {
            REACH.clear();
            HOME_FOR.clear();
        }

        @Override
        public int mobLevel(int mapId) {
            return MapMobIndex.level(mapId);
        }

        @Override
        public int mobCount(int mapId) {
            return MapMobIndex.mobCount(mapId);
        }

        @Override
        public boolean isDungeon(int mapId) {
            return MiniDungeonInfo.isDungeonMap(mapId);
        }

        @Override
        public boolean regionDisabled(int mapId) {
            TrainingRegions.Region region = TrainingRegions.regionOf(mapId);
            return region != null && !region.enabled();
        }

        @Override
        public Map<Integer, Integer> walkReach(int homeMapId) {
            return REACH.computeIfAbsent(homeMapId, h -> Map.copyOf(GCMovement.mapsWithinHopsByDepth(h,
                    TrainingRegions.tuning().hubReachHops())));
        }

        @Override
        public int routeFare(int fromMapId, int toMapId, int rebirths, int jobId, int level) {
            List<Integer> route = GCMovement.route(fromMapId, toMapId, rebirths, level);
            return route == null ? -1 : GCMovement.routeFare(fromMapId, route, jobId);
        }

        @Override
        public int capacity(int mapId) {
            return TrainingMapChooser.mapCapacity(mapId);
        }

        @Override
        public int botsOn(int mapId) {
            return TrainingMapChooser.botsOnMap(mapId);
        }

        @Override
        public double yieldSaturation(int mapId) {
            return BotYieldCaps.mapSaturation(mapId);
        }

        @Override
        public int homeFor(int mapId) {
            return HOME_FOR.computeIfAbsent(mapId, m -> LevelingPathChooser.homeFor(m, TrainingRegions.regions(),
                    this::walkReach, MapMobIndex::returnMapId, MapMobIndex::level));
        }
    }

    // Where a bot training on mapId should live: the nearest (walked hops) hub of the enabled region that holds the
    // map; else the map's WZ return town when that is a mobless town; else the nearest hub of any enabled region
    // that walks to it; else -1 (the bot travels there and keeps its home). A hub of the map's own region comes
    // first so an El Nath field never gets an Aqua Road home just because the Orbis tower makes that walk shorter.
    static int homeFor(int mapId, List<TrainingRegions.Region> regions,
                       java.util.function.IntFunction<Map<Integer, Integer>> walkReach,
                       java.util.function.IntUnaryOperator returnMap, java.util.function.IntUnaryOperator mobLevel) {
        TrainingRegions.Region own = null;
        for (TrainingRegions.Region region : regions) {
            if (region.contains(mapId)) {
                own = region;
                break;
            }
        }
        if (own != null && own.enabled()) {
            int hub = nearestHub(mapId, List.of(own), walkReach);
            if (hub > 0) {
                return hub;
            }
        }
        int ret = returnMap.applyAsInt(mapId);
        if (ret > 0 && ret != LiveWorld.NO_RETURN_MAP && ret != mapId && mobLevel.applyAsInt(ret) < 0) {
            return ret;
        }
        List<TrainingRegions.Region> enabled = new ArrayList<>();
        for (TrainingRegions.Region region : regions) {
            if (region.enabled()) {
                enabled.add(region);
            }
        }
        return nearestHub(mapId, enabled, walkReach);
    }

    private static int nearestHub(int mapId, List<TrainingRegions.Region> regions,
                                  java.util.function.IntFunction<Map<Integer, Integer>> walkReach) {
        int best = -1;
        int bestHops = Integer.MAX_VALUE;
        for (TrainingRegions.Region region : regions) {
            for (int hub : region.hubs()) {
                Integer hops = hub == mapId ? Integer.valueOf(0) : walkReach.apply(hub).get(mapId);
                if (hops != null && hops < bestHops) {
                    best = hub;
                    bestHops = hops;
                }
            }
        }
        return best;
    }

    private static double dbl(String key, double fallback) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp01(double d) {
        return Math.max(0.0, Math.min(1.0, d));
    }
}
