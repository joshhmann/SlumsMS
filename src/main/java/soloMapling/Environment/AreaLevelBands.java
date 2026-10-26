package soloMapling.Environment;

import server.maps.MiniDungeonInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How the Victoria Island training cohorts' level bands were derived from the maps (owner 2026-10-26: "30ish, but
 * there's still zones in Victoria that are higher level, go by city and the areas around it").
 *
 * <p><b>An area</b> is a town and every grind map whose WZ {@code info/returnMap} is that town - the game's own answer
 * to "which city is this field next to" (Henesys Hunting Grounds and Golem's Temple return to Henesys, the Subway and
 * the Swamp of Despair to Kerning City, Excavation Site and the Remains to Perion, the Ant Tunnel and the Drake caves
 * to Sleepywood). A grind map is one a player would train on: {@link #MIN_SPAWNS}+ mob spawns, a real mob level
 * (event and test maps carry Lv200 dummies), not a job-advancement instance or a mini-dungeon copy.
 *
 * <p><b>Each area has two bands</b>, split at the 2nd-job line ({@link #SECOND_JOB}): the <i>core</i> band from the maps
 * whose median mob is under Lv30 (the fields around town) and the <i>upper</i> band from the rest (the area's
 * dungeons). A band runs from five levels under the area's 10th-percentile map to five over its 90th-percentile map,
 * rounded out to fives - a player trains on mobs a few levels either side of their own, and the percentiles keep one
 * odd map (Remains &lt;Cliff&gt; at Lv73 in Perion) from stretching the whole area. The core band never goes under
 * Lv10 (Lv1-9 bots are their own beginner cohorts) or over 30; the upper band never under 30.
 *
 * <p>The numbers in {@code EnvironmentManager.TRAINING_COHORTS} are these bands as derived from the v83 WZ data
 * ({@code AreaLevelBandsWzTest} re-derives them and fails when a constant drifts from the data). The mix stays in code:
 * nothing here runs at boot.
 */
public final class AreaLevelBands {

    /** The 2nd-job line: maps under it are an area's core, maps at or over it its upper zones. */
    public static final int SECOND_JOB = 30;
    /** A map needs this many mob spawns to count as a place people train (towns, tiny rooms and NPC maps don't). */
    public static final int MIN_SPAWNS = 10;
    /** Event and GM test maps spawn Lv200 dummies; nothing on Victoria a player trains on is this high. */
    public static final int DUMMY_LEVEL = 200;
    /** The lowest level a core band may start at: Lv1-9 are the beginner cohorts. */
    public static final int CORE_FLOOR = 10;
    /** How far under the area's easy maps and over its hard maps a band reaches. */
    public static final int MARGIN = 5;

    private AreaLevelBands() {
    }

    /** A level band, {@code lo}..{@code hi} inclusive. */
    public record Band(int lo, int hi) {
    }

    /**
     * One map's numbers as the derivation reads them: the WZ median mob level, the number of mob spawns, and whether
     * String.wz names it (the unnamed ones are hidden copies - 68 copies of the Ant Tunnel's Cave of Mushrooms, ten of
     * Golem's Castle Ruins - that no player picks from the world map).
     */
    public record GrindMap(int mapId, int medianMobLevel, int spawns, boolean named) {
    }

    /**
     * Whether a map counts towards its area: named, enough spawns, a real mob level, not a job instance (108xxxxxx)
     * and not a mini-dungeon copy (they repeat their entrance map's mobs dozens of times over).
     */
    public static boolean isGrindMap(GrindMap map) {
        return map.named()
                && map.spawns() >= MIN_SPAWNS
                && map.medianMobLevel() > 0
                && map.medianMobLevel() < DUMMY_LEVEL
                && map.mapId() / 1_000_000 != 108
                && !MiniDungeonInfo.isDungeonMap(map.mapId());
    }

    /** The core band of an area (its maps under Lv30), or {@code null} when it has none. */
    public static Band core(List<GrindMap> areaMaps) {
        return derive(grindLevels(areaMaps, 1, SECOND_JOB - 1), CORE_FLOOR, SECOND_JOB);
    }

    /** The upper band of an area (its maps at Lv30+), or {@code null} when it has none. */
    public static Band upper(List<GrindMap> areaMaps) {
        return derive(grindLevels(areaMaps, SECOND_JOB, DUMMY_LEVEL - 1), SECOND_JOB, DUMMY_LEVEL);
    }

    /**
     * The band for a set of map mob levels: {@link #MARGIN} under the 10th percentile to {@link #MARGIN} over the 90th,
     * rounded out to multiples of five and clamped to {@code floor}..{@code ceiling}. {@code null} for no maps.
     */
    public static Band derive(List<Integer> mobLevels, int floor, int ceiling) {
        if (mobLevels.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(mobLevels);
        Collections.sort(sorted);
        int lo = roundDown5(percentile(sorted, 0.1) - MARGIN);
        int hi = roundUp5(percentile(sorted, 0.9) + MARGIN);
        lo = Math.max(floor, Math.min(lo, ceiling));
        hi = Math.min(ceiling, Math.max(hi, lo));
        return new Band(lo, hi);
    }

    /** Nearest-rank-below percentile of a sorted list (index {@code p * (n - 1)}, truncated). */
    static int percentile(List<Integer> sorted, double p) {
        return sorted.get((int) (p * (sorted.size() - 1)));
    }

    public static List<Integer> grindLevels(List<GrindMap> maps, int lo, int hi) {
        List<Integer> out = new ArrayList<>();
        for (GrindMap map : maps) {
            if (isGrindMap(map) && map.medianMobLevel() >= lo && map.medianMobLevel() <= hi) {
                out.add(map.medianMobLevel());
            }
        }
        return out;
    }

    private static int roundDown5(int level) {
        return Math.floorDiv(level, 5) * 5;
    }

    private static int roundUp5(int level) {
        return -Math.floorDiv(-level, 5) * 5;
    }
}
