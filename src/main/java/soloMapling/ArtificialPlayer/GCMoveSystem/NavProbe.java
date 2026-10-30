package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 157 (idea from nutnnut/Cosmic {@code /api/navprobe}, 77e0c5bc9; AGPL-3.0, credit NutNNut): can a bot get
 * from where it stands to a point on its map, and by which edges - or why not. Read-only: it asks the same planner
 * the bots use ({@link BotNavigationManager#runSearch}) over the cached graph, with the bot's movement profile and the
 * movement skills it could use, and changes nothing. {@link #probeMap} runs it from the map's spawn point to every
 * portal and NPC on the map and returns the ones nothing reaches: a nightly nav check beside the deep bake.
 *
 * <p>Used by {@code !navprobe} (GM 3, TEST only) and the {@code !qa np-1} scenario (auto-QA {@code t-np-1}).
 */
public final class NavProbe {

    private NavProbe() {
    }

    /** What one probe found. {@code path}: the edges in order, as the stuck report names them. */
    public record Result(boolean reachable, String reason, int fromRegion, int toRegion, List<String> path, int cost) {

        /** For the GM: the verdict, then one line per edge. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add((reachable ? "REACHABLE" : "UNREACHABLE") + ": " + reason + " (r" + fromRegion + " -> r" + toRegion
                    + (reachable && cost > 0 ? ", ~" + cost + " ms" : "") + ")");
            for (int i = 0; i < path.size(); i++) {
                out.add((i + 1) + ". " + path.get(i));
            }
            return out;
        }
    }

    /** A point a player must be able to reach on a map: a portal or an NPC. */
    public record Spot(String what, Point at) {
    }

    /** One probe on a graph. {@code skillMask}: the teleport / flash-jump edges the walker may take (0 = walk only). */
    static Result probe(MapleMap map, BotNavigationGraph graph, int fromRegion, Point from, Point to, int skillMask) {
        if (graph == null) {
            return new Result(false, "no nav graph for this map yet (building, or a swim map)", -1, -1, List.of(), 0);
        }
        int toRegion = BotNavigationManager.resolvePointTargetRegionId(graph, map, to);
        if (fromRegion < 0) {
            return new Result(false, "the start (" + from.x + "," + from.y + ") is off the graph (no ground or rope)",
                    fromRegion, toRegion, List.of(), 0);
        }
        if (toRegion < 0) {
            return new Result(false, "the target (" + to.x + "," + to.y + ") has no ground or rope under it", fromRegion,
                    toRegion, List.of(), 0);
        }
        BotNavigationManager.SearchOutcome outcome = BotNavigationManager.runSearch(graph, map, from, fromRegion,
                toRegion, to, "navprobe", true, false, 0L, skillMask);
        List<String> path = new ArrayList<>(outcome.path().size());
        for (BotNavigationGraph.Edge e : outcome.path()) {
            path.add(NavStuckLog.describe(e));
        }
        if (fromRegion == toRegion) {
            return new Result(true, path.isEmpty() ? "same platform, walk there" : "same platform, via a portal",
                    fromRegion, toRegion, path, outcome.cost());
        }
        if (path.isEmpty() || outcome.path().getLast().toRegionId != toRegion) {
            return new Result(false, "no route from r" + fromRegion + " to r" + toRegion
                    + (skillMask == 0 ? " walking" : " even with movement skills"), fromRegion, toRegion, path, 0);
        }
        return new Result(true, path.size() + (path.size() == 1 ? " edge" : " edges"), fromRegion, toRegion, path,
                outcome.cost());
    }

    /** {@link #probe} from a point (resolved on the graph as the planner would for a standing walker). */
    static Result probe(MapleMap map, BotNavigationGraph graph, Point from, Point to, int skillMask) {
        int fromRegion = graph == null ? -1 : graph.findRegionId(map, from);
        return probe(map, graph, fromRegion, from, to, skillMask);
    }

    /**
     * Can this character reach (x, y) on its map? A bot on dynamic movement probes with its own movement profile,
     * graph and skills, from the region the planner thinks it is in; anyone else (a GM's "me") with its own stats.
     * May build the map's graph if nothing cached it yet, so call it off the game thread.
     */
    public static Result probe(Character who, int x, int y) {
        MapleMap map = who.getMap();
        if (map == null) {
            return new Result(false, "not on a map", -1, -1, List.of(), 0);
        }
        if (map.isSwim()) {
            return new Result(false, "a swim map: the swim navigator routes here, not the ground graph", -1, -1,
                    List.of(), 0);
        }
        BotMovementState entry = GCMovement.stateOf(who);
        BotMovementProfile profile = entry != null ? entry.movementProfile : BotMovementProfile.fromCharacter(who);
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, profile);
        if (graph == null) {
            graph = BotNavigationGraphProvider.getGraph(map, profile);
        }
        Point from = new Point(who.getPosition());
        Point to = new Point(x, y);
        int skillMask = NavMovementSkills.heldMask(who);
        if (graph != null && entry != null) {
            return probe(map, graph, BotNavigationManager.resolveCurrentRegionId(graph, entry, map, from), from, to,
                    skillMask);
        }
        return probe(map, graph, from, to, skillMask);
    }

    /**
     * For {@code !pos}: the nav region at a character's feet on the cached graph of its movement profile (never
     * builds one), and how many edges leave it. One line.
     */
    public static String regionAt(Character who) {
        MapleMap map = who.getMap();
        if (map == null || who.getPosition() == null) {
            return "Nav region: -";
        }
        if (map.isSwim()) {
            return "Nav region: swim map (swim navigator, no ground graph)";
        }
        BotMovementState entry = GCMovement.stateOf(who);
        BotMovementProfile profile = entry != null ? entry.movementProfile : BotMovementProfile.fromCharacter(who);
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, profile);
        if (graph == null) {
            return "Nav region: no graph cached for this map yet";
        }
        return regionLine(map, graph, who.getPosition());
    }

    static String regionLine(MapleMap map, BotNavigationGraph graph, Point at) {
        int id = graph.findRegionId(map, at);
        BotNavigationGraph.Region region = id < 0 ? null : graph.getRegion(id);
        if (region == null) {
            return "Nav region: off the graph at (" + at.x + "," + at.y + ") [" + NavEdgeLearning.bookKey(graph) + "]";
        }
        int out = 0;
        int portals = 0;
        for (BotNavigationGraph.Edge e : graph.getOutgoing(id)) {
            if (e.fromRegionId != e.toRegionId) {
                out++;
            } else if (e.type == BotNavigationGraph.EdgeType.PORTAL) {
                portals++;
            }
        }
        return "Nav region: r" + id + (region.isRopeRegion ? (region.isLadder ? " ladder" : " rope") : " ground")
                + " x" + region.minX + ".." + region.maxX + " y" + region.minY + ".." + region.maxY + ", " + out
                + " edges out" + (portals > 0 ? ", " + portals + " in-map portals" : "") + " ["
                + NavEdgeLearning.bookKey(graph) + "]";
    }

    /** Every portal with a position and every NPC on the map. */
    public static List<Spot> spots(MapleMap map) {
        List<Spot> out = new ArrayList<>();
        for (Portal p : map.getPortals()) {
            if (p != null && p.getPosition() != null && p.getType() != 0) { // type 0: a spawn point, not a door
                out.add(new Spot("portal " + p.getName() + (p.getTargetMapId() != 999999999 && p.getTargetMapId() > 0
                        ? " -> " + p.getTargetMapId() : ""), new Point(p.getPosition())));
            }
        }
        for (MapObject o : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                List.of(MapObjectType.NPC))) {
            if (o instanceof NPC npc && npc.getPosition() != null) {
                out.add(new Spot("npc " + npc.getId(), new Point(npc.getPosition())));
            }
        }
        return out;
    }

    /** Where a player comes into the map: the spawn portal "sp", else the first portal of type 0, else null. */
    static Point spawnPoint(MapleMap map) {
        Point first = null;
        for (Portal p : map.getPortals()) {
            if (p == null || p.getPosition() == null || p.getType() != 0) {
                continue;
            }
            if ("sp".equals(p.getName())) {
                return new Point(p.getPosition());
            }
            if (first == null) {
                first = new Point(p.getPosition());
            }
        }
        return first;
    }

    /**
     * The nightly probe: from the map's spawn point to every portal and NPC on it, walking with the base movement
     * profile. Returns one line per spot nothing reaches (empty = all reachable); a map with no graph or no spawn
     * point is one line saying so. Builds the graph if needed: run it off the game thread.
     */
    public static List<String> probeMap(MapleMap map) {
        if (map == null) {
            return List.of("no map");
        }
        if (map.isSwim()) {
            return List.of(); // the swim navigator's maps are not on the ground graph
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        return probeMap(map, graph, spawnPoint(map), spots(map));
    }

    static List<String> probeMap(MapleMap map, BotNavigationGraph graph, Point start, List<Spot> spots) {
        List<String> failures = new ArrayList<>();
        if (graph == null) {
            failures.add("map " + map.getId() + ": no nav graph");
            return failures;
        }
        if (start == null) {
            failures.add("map " + map.getId() + ": no spawn point to start from");
            return failures;
        }
        int fromRegion = graph.findRegionId(map, start);
        for (Spot spot : spots) {
            Result r = probe(map, graph, fromRegion, start, spot.at(), 0);
            if (!r.reachable()) {
                failures.add("map " + map.getId() + " " + spot.what() + " at (" + spot.at().x + "," + spot.at().y
                        + "): " + r.reason());
            }
        }
        return failures;
    }
}
