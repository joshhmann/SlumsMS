package soloMapling.ArtificialPlayer.BotGrindSystem;

import server.maps.MiniDungeonInfo;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Deterministic training-map discovery: BFS the nearby walkable maps from the bot's map, keep the
// ones whose representative mob level is inside the caller's two-sided band [minMob, maxMob], else the
// closest-level mob-bearing maps (the bot's own map among them, so nothing in-band never means nowhere). Reads only WZ (via GCMovement.mapsWithinHops + MapMobIndex); the
// walkable-portal BFS naturally excludes towns (no mobs) and PQ/instance maps (not portal-reachable).
// Mini-dungeon instances are explicitly rejected (MiniDungeonInfo.isDungeonMap), and maps outside the
// allowed original-content regions are rejected (TrainingRegions) so a bot never grinds/warps into a
// single-person instance or into Leafre / Aqua Road / new-school content even if a portal reaches it.
//
// Our own creation. Replaces TrainingBot's old REGIONS table.
public final class TrainingMapFinder {

    private TrainingMapFinder() {
    }

    private static final int MIN_MOB_COUNT = 2; // skip one-off quest-mob maps
    // When nothing is in-band, return this many closest-level maps (not one): a single-map fallback made
    // every out-of-band bot in a cohort converge on the identical map (single candidates skip the
    // weighted pick entirely), guaranteeing crowding on exactly the maps with the least to offer.
    private static final int FALLBACK_K = 3;

    // Reachable, region-allowed, mob-bearing maps whose median mob level sits in the two-sided band
    // [minMob, maxMob] (the caller bands both ends so a high bot won't admit trivial far-out maps).
    // `level` is used only for the fallback: when nothing is in-band, return the closest-level allowed
    // maps so the bot always has somewhere to go. Maps in `excluded` (e.g. one the caller just left
    // because it was overcrowded) are skipped, including for the fallback.
    public static List<TrainingMap> findTrainingMaps(int fromMapId, int level, int minMob, int maxMob,
                                                     int maxHops, Set<Integer> excluded) {
        return findTrainingMaps(fromMapId, level, minMob, maxMob, maxHops, excluded, false, 1);
    }

    // As above, but with includeOrigin: the BFS uses fromMapId as its search root and never emits it, so a
    // cohort that spawns on a mob-bearing field — a "deep hub" like Sharp Cliff I or Ant Tunnel Park — could
    // only ever transit through its own map, never grind it. includeOrigin folds fromMapId back in as a
    // hops=0 candidate, subject to the identical filters (region, mini-dungeon, excluded, mob count, band).
    public static List<TrainingMap> findTrainingMaps(int fromMapId, int level, int minMob, int maxMob,
                                                     int maxHops, Set<Integer> excluded, boolean includeOrigin) {
        return findTrainingMaps(fromMapId, level, minMob, maxMob, maxHops, excluded, includeOrigin, 1);
    }

    // hardMinMob is an absolute floor the FALLBACK also respects: a downward-only deep-hub pro must never
    // be offered an easy up-map even when nothing is in-band (the band's minMob only gates eligibility;
    // the old single-map fallback ignored it). 1 = no hard floor.
    public static List<TrainingMap> findTrainingMaps(int fromMapId, int level, int minMob, int maxMob,
                                                     int maxHops, Set<Integer> excluded, boolean includeOrigin,
                                                     int hardMinMob) {
        Map<Integer, Integer> nearby = GCMovement.mapsWithinHopsByDepth(fromMapId, maxHops);
        List<TrainingMap> reachable = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : nearby.entrySet()) {
            TrainingMap m = grindable(e.getKey(), e.getValue(), excluded);
            if (m != null) {
                reachable.add(m);
            }
        }
        // The origin (hops=0) is always scored: as a normal candidate with includeOrigin, else only for the
        // fallback, so a bot standing on a mob field with nothing in-band anywhere can fight what's around it.
        TrainingMap origin = nearby.containsKey(fromMapId) ? null : grindable(fromMapId, 0, excluded);
        return select(reachable, origin, includeOrigin, level, minMob, maxMob, hardMinMob);
    }

    // The map as a training candidate at `hops`, or null when it can't be one: excluded by the caller,
    // a mini-dungeon, outside the allowed regions, or without enough mobs (towns, quest-mob maps).
    private static TrainingMap grindable(int mapId, int hops, Set<Integer> excluded) {
        if (excluded != null && excluded.contains(mapId)) {
            return null; // caller is steering away from this map (crowd cooldown)
        }
        if (MiniDungeonInfo.isDungeonMap(mapId)) {
            return null; // single-person mini-dungeon instance — bots must never grind/warp here
        }
        if (!TrainingRegions.isAllowed(mapId)) {
            return null; // outside original-content regions (Leafre / Aqua Road / new-school etc.)
        }
        MapMobIndex.MapMobInfo info = MapMobIndex.info(mapId);
        if (info.medianLevel() < 1 || info.mobCount() < MIN_MOB_COUNT) {
            return null; // no mobs (town) or too few to be a grind map
        }
        return new TrainingMap(mapId, info.medianLevel(), hops);
    }

    // Pure selection over already-admitted candidates: the in-band maps when there are any, else the K
    // closest-level maps. The fallback ranks maps at/above hardMinMob first and only reaches below the floor
    // when nothing clears it — a level-125 pro whose floor (115) sits above every allowed map in reach must
    // still go somewhere, and the closest-level maps are then the hardest ones, i.e. still the "deepest".
    // Closeness is |mobLevel - level|, so a bot of any level (rebirths go to 255) gets the hardest maps it
    // can reach rather than nothing. Ticket 31's level path replaces this fallback with a curated route.
    static List<TrainingMap> select(List<TrainingMap> reachable, TrainingMap origin, boolean includeOrigin,
                                    int level, int minMob, int maxMob, int hardMinMob) {
        List<TrainingMap> eligible = new ArrayList<>();
        List<TrainingMap> outOfBand = new ArrayList<>(); // fallback candidates at/above hardMinMob
        List<TrainingMap> belowFloor = new ArrayList<>(); // last-resort fallback candidates under hardMinMob
        List<TrainingMap> all = new ArrayList<>(reachable);
        if (origin != null) {
            all.add(origin);
        }
        for (TrainingMap m : all) {
            int lvl = m.mobLevel();
            boolean bandable = m != origin || includeOrigin;
            if (bandable && lvl >= minMob && lvl <= maxMob) {
                eligible.add(m);
            } else if (lvl >= hardMinMob) {
                outOfBand.add(m);
            } else {
                belowFloor.add(m);
            }
        }
        if (!eligible.isEmpty()) {
            return eligible;
        }
        // Nothing in-band: the K closest-level allowed maps, so the caller's weighted pick still has
        // options to spread over (the level-fit gap-decay then fades the worst of them).
        List<TrainingMap> fallback = outOfBand.isEmpty() ? belowFloor : outOfBand;
        fallback.sort((a, b) -> Integer.compare(Math.abs(a.mobLevel() - level), Math.abs(b.mobLevel() - level)));
        return List.copyOf(fallback.subList(0, Math.min(FALLBACK_K, fallback.size())));
    }
}
