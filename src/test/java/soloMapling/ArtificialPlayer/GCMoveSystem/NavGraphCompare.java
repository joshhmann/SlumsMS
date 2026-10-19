package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/*
 * Dev tool, not a unit test: builds the nav graph of each given map from real WZ data and prints one line per map
 * - graph version, build time, region count, edges per type, and the footholds reachable from the spawn portal by
 * walk-only edges (and with skill edges). Run against two builds (e.g. graph v61 and v68) and diff the output to
 * check a graph change for regressions. Point -Dwz-path at the WZ directory and -Dbot.nav.cacheDir at a scratch
 * directory. Only uses API present in both versions so the same source compiles against either.
 *
 *   java -cp <jar>:<test-classes> -Dwz-path=wz -Dbot.nav.cacheDir=cache-nav \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.NavGraphCompare 100040000 101000000 ...
 */
public final class NavGraphCompare {
    private NavGraphCompare() {
    }

    public static void main(String[] args) {
        for (String arg : args) {
            int mapId = Integer.parseInt(arg.trim());
            try {
                System.out.println(describe(mapId));
            } catch (RuntimeException e) {
                System.out.println("map=" + mapId + " ERROR " + e);
            }
        }
    }

    private static String describe(int mapId) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        long started = System.nanoTime();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        long ms = (System.nanoTime() - started) / 1_000_000L;

        Map<String, Integer> byType = new TreeMap<>();
        int total = 0;
        for (List<BotNavigationGraph.Edge> edges : graph.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge edge : edges) {
                byType.merge(edge.type.name(), 1, Integer::sum);
                total++;
            }
        }

        int spawnRegion = spawnRegion(map, graph);
        Set<Integer> walkFootholds = reachableFootholds(graph, spawnRegion, false);
        Set<Integer> allFootholds = reachableFootholds(graph, spawnRegion, true);
        return "map=" + mapId + " v=" + graph.version + " ms=" + ms + " regions=" + graph.regions.size()
                + " edges=" + total + " " + byType + " spawnRegion=" + spawnRegion
                + " walkReachFh=" + walkFootholds.size() + " skillReachFh=" + allFootholds.size()
                + " walkReachIds=" + new java.util.TreeSet<>(walkFootholds);
    }

    private static int spawnRegion(MapleMap map, BotNavigationGraph graph) {
        for (Portal portal : map.getPortals()) {
            if (portal.getType() == 0) { // start point
                Point p = portal.getPosition();
                Point ground = BotPhysicsEngine.findGroundPoint(map, p);
                int region = graph.findRegionId(map, ground != null ? ground : p);
                if (region >= 0) {
                    return region;
                }
            }
        }
        return graph.regions.isEmpty() ? -1 : graph.regions.get(0).id;
    }

    /* Footholds of every region reachable from start; skill edges (by type name, so this compiles pre-v60 too) only
     * when includeSkills. Footholds, not region ids, so two graph versions compare even if region ids shift. */
    private static Set<Integer> reachableFootholds(BotNavigationGraph graph, int start, boolean includeSkills) {
        Set<Integer> seen = new HashSet<>();
        if (start < 0) {
            return Set.of();
        }
        Deque<Integer> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            int region = queue.poll();
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region)) {
                String type = edge.type.name();
                boolean skill = type.equals("TELEPORT") || type.equals("FLASH_JUMP");
                if (skill && !includeSkills) {
                    continue;
                }
                if (seen.add(edge.toRegionId)) {
                    queue.add(edge.toRegionId);
                }
            }
        }
        Set<Integer> footholds = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : graph.regionIdByFootholdId.entrySet()) {
            if (seen.contains(e.getValue())) {
                footholds.add(e.getKey());
            }
        }
        return footholds;
    }
}
