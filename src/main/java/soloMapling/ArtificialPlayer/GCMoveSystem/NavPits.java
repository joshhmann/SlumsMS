package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pits: platforms the planner never routes a bot into from outside, because a bot that gets there cannot walk back
 * out. Two rules, one set:
 *
 * <ul>
 * <li><b>Below the view</b> (ticket 157 follow-up; the Website lane's dead-end check, TEST 2026-10-30: 20 "pits" over
 * 960 maps, e.g. 100040001 r33 at y 632 under a view bottom of 450): some tree and dungeon maps keep a hidden catch
 * floor below the bottom of their view bounds. A ground region whose top lies below the view bottom is a pit. A bot
 * that is already in one (it fell) may still use any edge out.</li>
 * <li><b>Traps</b> (b109, the same check's "Pit" rows on 12 maps): a region the map's exit area can reach, but from
 * which no walk, jump, drop, climb or in-map portal leads back to a portal to another map: the hollow inside a block
 * a drop falls into (Ludibrium ticketing place 220000100 r3, the Lith Harbor crates 104000000 r48/r50), a sealed
 * room under a floor (222020100 r18), the one-way lower halves of Unbalanced / Twisted Time (220060201, 220060301).
 * Movement skills do not count: a walker could not get out. Worked out like the dead-end check
 * ({@code tools/dashboard/extras/slums-dashboard-extras}, {@code check_map}): the exits are the portals to another
 * map plus the script-run portals, placed with the same {@link #regionAt} rule, and only on maps that have a portal
 * to another map and are not swim maps (swimmers do not use the ground graph). A bot standing in a trap is put back
 * on the nearest platform with a way out ({@link #rescuePoint}, GCMovementDriver's pit rescue).</li>
 * </ul>
 *
 * <p>Applied when planning, not in the graph builder, so the cached graphs and the nav learning books stay as they
 * are. The sets are worked out once per graph instance and kept beside it (with the deep bake and player-learned
 * edges known at that time); a map without view bounds has no below-view pits.
 */
final class NavPits {

    private NavPits() {
    }

    /** The dead-end check's "no map" target. */
    static final int NO_MAP = 999_999_999;
    /** Script-run portal types (their target is not in the WZ data), as the dead-end check counts them. */
    private static final Set<Integer> SCRIPT_PORTALS = Set.of(7, 8, 9, 11, 12, 13, 14);

    /** A graph's pits: {@code all} (both rules) and {@code traps} (no way back out). */
    record Sets(BitSet all, BitSet traps) {
    }

    private static final Sets NONE = new Sets(new BitSet(0), new BitSet(0));

    /** Whether this edge drops a bot from outside a pit into one. */
    static boolean into(BotNavigationGraph graph, MapleMap map, BotNavigationGraph.Edge edge) {
        BitSet pits = sets(graph, map).all();
        return !pits.isEmpty() && edge.toRegionId >= 0 && pits.get(edge.toRegionId)
                && !(edge.fromRegionId >= 0 && pits.get(edge.fromRegionId));
    }

    /** Whether the region is a pit on this map. */
    static boolean isPit(BotNavigationGraph graph, MapleMap map, int regionId) {
        return regionId >= 0 && sets(graph, map).all().get(regionId);
    }

    /** Whether the region is a trap: no way back to the map's exits without a movement skill. */
    static boolean isTrap(BotNavigationGraph graph, MapleMap map, int regionId) {
        return regionId >= 0 && sets(graph, map).traps().get(regionId);
    }

    static BitSet pits(BotNavigationGraph graph, MapleMap map) {
        return sets(graph, map).all();
    }

    static Sets sets(BotNavigationGraph graph, MapleMap map) {
        Sets sets = graph.pits;
        if (sets == null) {
            if (map == null) {
                return NONE; // nothing to work it out from yet; not cached
            }
            sets = compute(graph, map);
            graph.pits = sets;
        }
        return sets;
    }

    static Sets compute(BotNavigationGraph graph, MapleMap map) {
        BitSet all = belowView(graph, map.getMapArea());
        BitSet traps = new BitSet();
        if (!map.isSwim()) {
            Map<Integer, Set<Integer>> fwd = new HashMap<>();
            for (BotNavigationGraph.Region region : graph.regions) {
                for (BotNavigationGraph.Edge e : NavObservedEdges.outgoing(graph, region.id)) { // with baked and learned edges
                    if (!e.isMovementSkill()) {
                        fwd.computeIfAbsent(e.fromRegionId, k -> new HashSet<>()).add(e.toRegionId);
                    }
                }
            }
            List<PortalRow> portals = new ArrayList<>();
            for (Portal p : map.getPortals()) {
                if (p.getPosition() != null) {
                    portals.add(new PortalRow(p.getType(), p.getPosition().x, p.getPosition().y, p.getTargetMapId()));
                }
            }
            traps(rows(graph), fwd, portals, map.getId()).forEach(traps::set);
        }
        if (all.isEmpty() && traps.isEmpty()) {
            return NONE;
        }
        BitSet union = (BitSet) all.clone();
        union.or(traps);
        return new Sets(union, traps);
    }

    private static List<Row> rows(BotNavigationGraph graph) {
        List<Row> rows = new ArrayList<>(graph.regions.size());
        for (BotNavigationGraph.Region r : graph.regions) {
            rows.add(new Row(r.id, r.isRopeRegion, r.minX, r.minY, r.maxX, r.maxY));
        }
        rows.sort(Comparator.comparingInt(Row::id));
        return rows;
    }

    static BitSet belowView(BotNavigationGraph graph, Rectangle view) {
        BitSet out = new BitSet();
        if (view == null || view.width <= 0 || view.height <= 0) {
            return out;
        }
        int bottom = view.y + view.height;
        for (BotNavigationGraph.Region region : graph.regions) {
            if (isPitRegion(region.isRopeRegion, region.id, region.minY, bottom)) {
                out.set(region.id);
            }
        }
        return out;
    }

    /** The below-view rule for one region (shared with the dashboard's {@code nav/pits.json}): ground whose top is below the view. */
    static boolean isPitRegion(boolean rope, int regionId, int minY, int viewBottom) {
        return !rope && regionId >= 0 && minY > viewBottom;
    }

    // ── traps: one rule for the planner and nav/pits.json ─────────────────────────────────────

    /** A region as the dashboard exports it ({@code [id, rope, minX, minY, maxX, maxY]}). */
    record Row(int id, boolean rope, int minX, int minY, int maxX, int maxY) {
    }

    /** A portal: its type, position and target map. */
    record PortalRow(int type, int x, int y, int toMap) {
    }

    /**
     * The trap regions of one map's graph, sorted: reachable from the regions that lead to an exit, but leading to
     * none. {@code fwd}: each region's successors over every edge but movement skills. Empty when the map has no
     * portal to another map (the dead-end check reports those as no_exit / script_exit and looks no further).
     */
    static Set<Integer> traps(List<Row> rows, Map<Integer, ? extends Collection<Integer>> fwd, List<PortalRow> portals,
                              int mapId) {
        boolean anyExit = false;
        Set<Integer> exitRegions = new HashSet<>();
        for (PortalRow p : portals) {
            boolean exit = p.toMap() != NO_MAP && p.toMap() != mapId;
            anyExit |= exit;
            if (exit || SCRIPT_PORTALS.contains(p.type())) {
                Integer r = regionAt(rows, p.x(), p.y());
                if (r != null) {
                    exitRegions.add(r);
                }
            }
        }
        if (!anyExit || exitRegions.isEmpty()) {
            return Set.of();
        }
        Map<Integer, Set<Integer>> back = new HashMap<>();
        fwd.forEach((from, tos) -> {
            for (int to : tos) {
                back.computeIfAbsent(to, k -> new HashSet<>()).add(from);
            }
        });
        Set<Integer> canExit = reach(exitRegions, back);
        Set<Integer> out = new TreeSet<>(reach(canExit, fwd));
        out.removeAll(canExit);
        return out;
    }

    private static Set<Integer> reach(Set<Integer> starts, Map<Integer, ? extends Collection<Integer>> adj) {
        Set<Integer> seen = new HashSet<>(starts);
        Deque<Integer> todo = new ArrayDeque<>(starts);
        while (!todo.isEmpty()) {
            Collection<Integer> next = adj.get(todo.pop());
            if (next == null) {
                continue;
            }
            for (int n : next) {
                if (seen.add(n)) {
                    todo.push(n);
                }
            }
        }
        return seen;
    }

    /**
     * The platform a point stands on, as the dead-end check finds it ({@code region_at}): the ground region under (or
     * up to 40 px above) it whose span, widened by 20 px, covers x; else the region nearest to it. Null with no rows.
     */
    static Integer regionAt(List<Row> rows, int x, int y) {
        Integer best = null;
        double bestD = 0;
        for (Row r : rows) {
            if (r.rope()) {
                continue;
            }
            int x1 = r.minX(), y1 = r.minY(), x2 = r.maxX(), y2 = r.maxY();
            if (x < x1 - 20 || x > x2 + 20) {
                continue;
            }
            double ry = x2 == x1 ? y1 : y1 + (double) (y2 - y1) * (Math.min(Math.max(x, x1), x2) - x1) / (x2 - x1);
            double d = ry - y;
            if (d < -40) {
                continue;
            }
            if (best == null || d < bestD) {
                best = r.id();
                bestD = d;
            }
        }
        if (best == null) {
            long far = 0;
            for (Row r : rows) {
                long cx = Math.min(Math.max(x, r.minX()), r.maxX());
                long cy = Math.min(Math.max(y, Math.min(r.minY(), r.maxY())), Math.max(r.minY(), r.maxY()));
                long d = (cx - x) * (cx - x) + (cy - y) * (cy - y);
                if (best == null || d < far) {
                    best = r.id();
                    far = d;
                }
            }
        }
        return best;
    }

    // ── a bot already in a trap ─────────────────────────────────────────────────────────────────

    /** Where a bot standing in a trap is put back: the nearest point on a ground region that is no pit; null if none. */
    static Point rescuePoint(BotNavigationGraph graph, MapleMap map, Point pos) {
        BitSet pits = sets(graph, map).all();
        Point best = null;
        long bestD = Long.MAX_VALUE;
        for (BotNavigationGraph.Region r : graph.regions) {
            if (r.isRopeRegion || pits.get(r.id)) {
                continue;
            }
            Point p = r.pointAt(Math.min(Math.max(pos.x, r.minX), r.maxX));
            if (p == null) {
                continue;
            }
            long dx = p.x - pos.x;
            long dy = p.y - pos.y;
            long d = dx * dx + dy * dy;
            // a point the ground lookup puts on another platform (the underside of a block 6 px under the hollow's
            // floor, 220000100 r4) would land the bot right back in the trap
            if (d < bestD && graph.findRegionId(map, p) == r.id) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
