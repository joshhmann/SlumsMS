package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.MiniDungeonInfo;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;

import java.util.ArrayList;
import java.util.List;

/*
 * Dev tool, not a unit test (ticket 31 path upkeep): lists maps a bot could grind at a level - median mob level in
 * [lo - 10, hi + 5], 10+ spawns, not a mini-dungeon - with the fewest rebirths at which a route from Henesys reaches
 * them for a bot of level lo. Used to swap path entries no player can reach for ones that are reachable.
 * Mob levels come from Mob.wz (see LevelPathSweepCheck).
 *
 *   java -cp <jar>:<test-classes> -Dwz-path=wz soloMapling.ArtificialPlayer.GCMoveSystem.LevelPathCandidates lo hi
 */
public final class LevelPathCandidates {

    private LevelPathCandidates() {
    }

    public static void main(String[] args) throws Exception {
        int lo = Integer.parseInt(args[0]);
        int hi = Integer.parseInt(args[1]);
        java.lang.reflect.Method preload = Class.forName(
                "soloMapling.ArtificialPlayer.BotGrindSystem.LevelPathSweepCheck").getDeclaredMethod("preloadMobLevels");
        preload.setAccessible(true);
        preload.invoke(null);
        List<String> out = new ArrayList<>();
        for (int map : new java.util.TreeSet<>(GCWorldGraph.get().keySet())) {
            int mob = MapMobIndex.level(map);
            if (mob < lo - 10 || mob > hi + 5 || MapMobIndex.mobCount(map) < Integer.getInteger("minSpawns", 10) || MiniDungeonInfo.isDungeonMap(map)) {
                continue;
            }
            int need = -1;
            for (int r = BotMapGates.requiredRebirths(map); r <= 3; r++) {
                if (GCMovement.route(constants.id.MapId.HENESYS, map, r, lo) != null) {
                    need = r;
                    break;
                }
            }
            if (need >= 0) {
                out.add(String.format("%d mob=%d spawns=%d r=%d", map, mob, MapMobIndex.mobCount(map), need));
            }
        }
        out.forEach(System.out::println);
    }
}
