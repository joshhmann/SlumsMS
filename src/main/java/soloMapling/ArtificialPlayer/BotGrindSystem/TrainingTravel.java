package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

// Ticket 03: when a training bot leaves its region for another one. TrainingMapChooser asks consider() once
// per decision; a Destination means "travel to this hub's area, train there, and make the hub your home town",
// and the trip itself rides whatever cabs, boats, trains or cranes connect the two (GCTravel/BotTransit).
//
// The policy is a hook: the default (RegionTravel) is the coarse, data-driven widening from
// TrainingRegions.yaml - move on rarely while the home region still fits, usually once it's outgrown, to a
// region whose maps fit the bot's level band, that it can reach, and whose fares it can pay. Ticket 31's
// leveling path replaces it with install(policy) and keeps everything else (the trip, the fares, the gates,
// the in-band pick and the crowd spreading inside the region). Ours (SoloMapling).
public final class TrainingTravel {

    private TrainingTravel() {
    }

    // What the chooser knows when it asks. localInBand = some map near the bot fits its band already.
    public record Request(Character chr, int level, int rebirths, int jobId, int meso, int currentMapId,
                          int homeMapId, boolean localInBand, int minMob, int maxMob) {
    }

    // Move to hubMapId (a town in `region`) and train around it; the hub becomes the bot's home.
    public record Destination(String region, int hubMapId) {
    }

    // The ticket 31 hook: pick where a bot should go train next, or null to keep training where it is.
    @FunctionalInterface
    public interface Policy {
        Destination choose(Request request);
    }

    private static final RegionTravel DEFAULT = new RegionTravel(
            TrainingTravel::hubMaps,
            (from, to, rebirths, jobId, level) -> {
                List<Integer> route = GCMovement.route(from, to, rebirths, level);
                return route == null ? -1 : GCMovement.routeFare(from, route, jobId);
            },
            System::currentTimeMillis);

    private static volatile Policy policy = DEFAULT;

    public static void install(Policy p) {
        policy = p == null ? DEFAULT : p;
    }

    public static Destination consider(Request request) {
        try {
            return policy.choose(request);
        } catch (RuntimeException e) {
            return null; // a broken policy keeps the bot training locally
        }
    }

    // ── The data-driven default ──

    // Fare of the cheapest route between two maps for this bot, or -1 when nothing connects them. The level keeps
    // out hops whose script turns the bot away (Jeff lets nobody under 50 past Ice Valley).
    @FunctionalInterface
    interface RouteFare {
        int fare(int fromMapId, int toMapId, int rebirths, int jobId, int level);
    }

    static final class RegionTravel implements Policy {
        private final IntFunction<List<TrainingMap>> hubMaps;
        private final RouteFare routeFare;
        private final LongSupplier clock;
        private long windowStartMs;
        private int startedInWindow;

        RegionTravel(IntFunction<List<TrainingMap>> hubMaps, RouteFare routeFare, LongSupplier clock) {
            this.hubMaps = hubMaps;
            this.routeFare = routeFare;
            this.clock = clock;
        }

        @Override
        public Destination choose(Request r) {
            return choose(r, ThreadLocalRandom.current().nextDouble(), ThreadLocalRandom.current().nextDouble());
        }

        // roll decides whether the bot moves on at all; pick chooses among the fitting regions. Both in [0, 1).
        Destination choose(Request r, double roll, double pick) {
            TrainingRegions.Tuning tuning = TrainingRegions.tuning();
            double chance = r.localInBand() ? tuning.wanderChance() : tuning.outgrownChance();
            if (roll >= chance) {
                return null;
            }
            List<TrainingRegions.Region> fits = new ArrayList<>();
            List<Integer> hubs = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            double total = fits(r, fits, hubs, weights);
            if (fits.isEmpty() || !takePermit(tuning.relocationsPerMinute())) {
                return null;
            }
            double at = pick * total;
            for (int i = 0; i < fits.size(); i++) {
                at -= weights.get(i);
                if (at < 0 || i == fits.size() - 1) {
                    return new Destination(fits.get(i).name(), hubs.get(i));
                }
            }
            return null; // not reached
        }

        /** Every region and hub this bot could be sent to, with its weight; returns the total weight. */
        double fits(Request r, List<TrainingRegions.Region> fits, List<Integer> hubs, List<Double> weights) {
            TrainingRegions.Region here = TrainingRegions.regionOf(r.currentMapId());
            double total = 0;
            for (TrainingRegions.Region region : TrainingRegions.regions()) {
                if (region == here || !region.attracts(r.level())) {
                    continue;
                }
                for (int hub : region.hubs()) {
                    if (!soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(hub, r.rebirths(), r.level())) {
                        continue; // 1.6.2 b041: never send a bot to a town it may not enter (a level/rebirth gate)
                    }
                    int inBand = inBandCount(region, hub, r);
                    if (inBand == 0) {
                        continue;
                    }
                    int fare = routeFare.fare(r.currentMapId(), hub, r.rebirths(), r.jobId(), r.level());
                    if (fare < 0 || fare > r.meso()) {
                        continue; // unreachable, or the bot can't pay its way there
                    }
                    double w = region.weight() * Math.sqrt(inBand);
                    fits.add(region);
                    hubs.add(hub);
                    weights.add(w);
                    total += w;
                }
            }
            return total;
        }

        private int inBandCount(TrainingRegions.Region region, int hub, Request r) {
            int n = 0;
            for (TrainingMap m : hubMaps.apply(hub)) {
                if (region.contains(m.mapId()) && m.mobLevel() >= r.minMob() && m.mobLevel() <= r.maxMob()
                        && TrainingRegions.isAllowed(m.mapId(), r.rebirths(), r.level())) {
                    n++;
                }
            }
            return n;
        }

        // World-wide cap on bots setting off to a new region, so a level-up wave doesn't empty a continent at once.
        private synchronized boolean takePermit(int perMinute) {
            long now = clock.getAsLong();
            if (now - windowStartMs >= 60_000L) {
                windowStartMs = now;
                startedInWindow = 0;
            }
            if (startedInWindow >= Math.max(0, perMinute)) {
                return false;
            }
            startedInWindow++;
            return true;
        }
    }

    /**
     * 1.6.2 b041 (auto-QA t-ld-1): every hub the region picker could send this bot to from currentMapId, and the
     * in-band maps it would then train on there, as {hub, map}; live world data, no roll and no trip budget.
     */
    public static List<int[]> offeredForProbe(int level, int rebirths, int jobId, int currentMapId, int minMob,
                                              int maxMob) {
        Request r = new Request(null, level, rebirths, jobId, Integer.MAX_VALUE, currentMapId, currentMapId, false,
                minMob, maxMob);
        List<TrainingRegions.Region> fits = new ArrayList<>();
        List<Integer> hubs = new ArrayList<>();
        DEFAULT.fits(r, fits, hubs, new ArrayList<>());
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < hubs.size(); i++) {
            int hub = hubs.get(i);
            out.add(new int[]{hub, hub});
            for (TrainingMap m : hubMaps(hub)) {
                if (fits.get(i).contains(m.mapId()) && m.mobLevel() >= minMob && m.mobLevel() <= maxMob
                        && TrainingRegions.isAllowed(m.mapId(), rebirths, level)) {
                    out.add(new int[]{hub, m.mapId()});
                }
            }
        }
        return out;
    }

    // Grindable maps around a hub (walked hops, any level), cached: static WZ data.
    private static final Map<Integer, List<TrainingMap>> HUB_MAPS = new ConcurrentHashMap<>();

    static List<TrainingMap> hubMaps(int hubMapId) {
        return HUB_MAPS.computeIfAbsent(hubMapId, hub -> TrainingMapFinder.grindableAround(hub,
                TrainingRegions.tuning().hubReachHops()));
    }

    static void clearCaches() {
        HUB_MAPS.clear();
    }
}
