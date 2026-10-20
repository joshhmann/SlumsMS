package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import constants.game.CharacterStance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Ticket 34: what real players' swimming teaches the swim graph, like NavObservedEdges does for jumps (ticket 04).
 *
 * A player's absolute move fragments on a swim map, while swimming (no foothold, swim stance), are followed as a
 * trail. Each time the trail has gone a node's width or more, the two ends are snapped to graph nodes:
 *   - the graph already joins them: a make on that edge (it pays off any failures bots ran up there);
 *   - it does not, but a straight swim between them is clear (centre line only; the graph's own edges also want
 *     room on both sides): a sighting. Two sightings adopt it as a SWIM edge - a gap bots now dare to use because a
 *     player swam it. Adopted edges are kept in cache/bot-nav/observed/<mapId>-swim.json and restored with the graph.
 */
final class SwimNavObserved {

    private static final Logger log = LoggerFactory.getLogger(SwimNavObserved.class);

    static final int SIGHTINGS_TO_ADOPT = 2;
    static final int SNAP_PX = 30;
    static final int MAX_SPAN_PX = SwimNavGraph.SPACING * 3;
    private static volatile Path dir = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "observed");

    private static final class Trail {
        int mapId;
        Point last;
    }

    private static final Map<Integer, Trail> trails = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sightings = new ConcurrentHashMap<>();
    /** Synchronous in tests and the lab (no executor). */
    static volatile boolean synchronous;

    private SwimNavObserved() {
    }

    /** One absolute move fragment of a real player (NavObservedEdges.onPlayerMove forwards swim maps here). */
    static void onPlayerMove(Character player, int x, int y, int fh, int stance) {
        MapleMap map = player.getMap();
        if (map == null || !map.isSwim()) {
            return;
        }
        Trail trail = trails.computeIfAbsent(player.getId(), k -> new Trail());
        Point from;
        Point to = new Point(x, y);
        synchronized (trail) {
            boolean swimming = fh == 0 && (stance < 0 || CharacterStance.isSwimming(stance));
            if (trail.mapId != map.getId() || !swimming) {
                trail.mapId = map.getId();
                trail.last = swimming ? to : null;
                return;
            }
            if (trail.last == null) {
                trail.last = to;
                return;
            }
            if (Math.abs(trail.last.x - x) + Math.abs(trail.last.y - y) < SwimNavGraph.SPACING) {
                return;
            }
            from = trail.last;
            trail.last = to;
        }
        if (synchronous) {
            observe(map, from, to);
        } else {
            ExecutorServiceManager.runAsync(() -> observe(map, from, to));
        }
    }

    static void forget(int playerId) {
        trails.remove(playerId);
    }

    /** A player swam straight from a to b. Returns the edge it confirmed or adopted, else null. */
    static BotNavigationGraph.Edge observe(MapleMap map, Point a, Point b) {
        SwimNavGraph graph = SwimNavGraph.peekOrWarm(map);
        if (graph == null || Math.abs(a.x - b.x) + Math.abs(a.y - b.y) > MAX_SPAN_PX * 2) {
            return null;
        }
        int from = snap(graph, a);
        int to = snap(graph, b);
        if (from < 0 || to < 0 || from == to) {
            return null;
        }
        BotNavigationGraph.Edge known = graph.edgeBetween(from, to);
        if (known != null) {
            NavEdgeLearning.swimSuccess(null, graph, known);
            return known;
        }
        Point pa = graph.point(from);
        Point pb = graph.point(to);
        if (graph.kinds[from] != SwimNavGraph.Kind.WATER || graph.kinds[to] != SwimNavGraph.Kind.WATER
                || Math.abs(pa.x - pb.x) > MAX_SPAN_PX || Math.abs(pa.y - pb.y) > MAX_SPAN_PX
                || BotPhysicsEngine.swimSegment(map, pa, pb) != BotPhysicsEngine.SwimHit.NONE) {
            return null;
        }
        String key = map.getId() + ":" + from + ">" + to;
        if (sightings.merge(key, 1, Integer::sum) < SIGHTINGS_TO_ADOPT) {
            return null;
        }
        sightings.remove(key);
        BotNavigationGraph.Edge edge = SwimNavGraph.edge(from, to, pa, pb, BotNavigationGraph.EdgeType.SWIM,
                SwimNavGraph.swimMs(graph.speeds, pa.x, pa.y, pb.x, pb.y));
        if (graph.adopt(edge)) {
            save(map.getId(), graph, edge);
            return edge;
        }
        return null;
    }

    private static int snap(SwimNavGraph graph, Point p) {
        int best = -1;
        long bestD = (long) SNAP_PX * SNAP_PX + 1;
        int r = SNAP_PX;
        for (int node = 0; node < graph.size(); node++) {
            int dx = graph.xs[node] - p.x;
            int dy = graph.ys[node] - p.y;
            if (Math.abs(dx) > r || Math.abs(dy) > r) {
                continue;
            }
            long d = (long) dx * dx + (long) dy * dy;
            if (d < bestD) {
                bestD = d;
                best = node;
            }
        }
        return best;
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    private static Path file(int mapId) {
        return dir.resolve(mapId + "-swim.json");
    }

    private static synchronized void save(int mapId, SwimNavGraph graph, BotNavigationGraph.Edge added) {
        try {
            List<Object> edges = new ArrayList<>(read(mapId, graph.version));
            edges.add(List.of(added.startPoint.x, added.startPoint.y, added.endPoint.x, added.endPoint.y));
            Files.createDirectories(dir);
            Files.writeString(file(mapId), Json.write(Map.of("version", graph.version, "edges", edges)),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not save observed swim edges for {}", mapId, e);
        }
    }

    private static List<?> read(int mapId, int version) {
        Path f = file(mapId);
        try {
            if (Files.exists(f) && Json.parse(Files.readString(f, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("version") instanceof Number v && v.intValue() == version
                    && root.get("edges") instanceof List<?> edges) {
                return edges;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable observed swim edges {}", f, e);
        }
        return List.of();
    }

    /** Puts the adopted edges of an earlier run back on a freshly built graph. */
    static void restore(MapleMap map, SwimNavGraph graph) {
        for (Object o : read(map.getId(), graph.version)) {
            if (!(o instanceof List<?> l) || l.size() != 4) {
                continue;
            }
            Point a = new Point(((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue());
            Point b = new Point(((Number) l.get(2)).intValue(), ((Number) l.get(3)).intValue());
            int from = snap(graph, a);
            int to = snap(graph, b);
            if (from >= 0 && to >= 0 && from != to && graph.point(from).equals(a) && graph.point(to).equals(b)) {
                graph.adopt(SwimNavGraph.edge(from, to, a, b, BotNavigationGraph.EdgeType.SWIM,
                        SwimNavGraph.swimMs(graph.speeds, a.x, a.y, b.x, b.y)));
            }
        }
    }

    /** Test seam: a scratch directory and a clean slate. */
    static void useDirForTest(Path testDir) {
        dir = testDir;
        trails.clear();
        sightings.clear();
        synchronous = true;
    }
}
