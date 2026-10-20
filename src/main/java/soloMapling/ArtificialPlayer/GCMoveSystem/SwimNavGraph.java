package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Ticket 34: the water graph of a swim map (info/swim = 1: Aqua Road, the Aquarium, a few event maps).
 *
 * On a swim map everything off the ground is water, so a ground nav graph (platforms joined by jumps) says nothing
 * useful. This graph samples the water on a grid SPACING px apart and puts a node on every platform every SPACING
 * px, then joins them:
 *   - SWIM water -> water, when a straight swim between them is clear (the swim integrator's own wall / ceiling /
 *     floor sweep, BotPhysicsEngine.swimSegment) with CLEARANCE px to spare on either side;
 *   - SWIM floor -> water (take-off: a jump off the platform into the water above it);
 *   - SWIM water -> floor (landing: sinking straight onto that platform);
 *   - WALK floor -> floor along one foothold chain.
 * Costs are milliseconds from the swim physics (SwimSpeeds): swimming sideways is the 140 px/s cap, sinking is the
 * free or DOWN-held terminal speed, rising is what a burst every SWIM_JUMP_COOLDOWN_MS with UP held achieves.
 * Swimming up through a platform from below is legal (floors are one-way) but looks like swimming through rock, so
 * it costs THROUGH_FLOOR_FACTOR more.
 *
 * Adjacency is kept compact (per node: target ids, costs, types - a few hundred KB for a big map; Edge objects for
 * 50k edges were ~7 MB). An edge a caller needs as an object (a route, learning, the stuck log) is made on demand
 * and cached, as an ordinary BotNavigationGraph.Edge of type SWIM / WALK with node ids in the region fields, so
 * NavEdgeLearning (swim section), NavStuckLog and MovementPlan take it as it is. Node ids are stable for one
 * geometry and VERSION, which is what the learned book is keyed on.
 */
final class SwimNavGraph {

    /** Bump when node placement or edge rules change: learned swim costs are dropped on a new version. */
    static final int VERSION = 1;
    static final int SPACING = 40;
    /** A water node keeps this far from every foothold; a swim edge is also checked this far to each side. */
    static final int CLEARANCE = 12;
    static final int SIDE_CHECK_PX = 8;
    static final double THROUGH_FLOOR_FACTOR = 1.6;
    /*
     * A water node with a platform this close above it is under a slope or a thin ledge - inside the rock in the
     * map's art, and only reachable by swimming up through the floor. A platform node with another platform this close
     * above it is a crawl space no character fits in (a character is ~60 px tall).
     */
    static final int WATER_HEADROOM_PX = 36;
    static final int FLOOR_HEADROOM_PX = 50;
    /*
     * Fixed cost of leaving the ground: the jump wind-up before the swim takes over, plus a bias so a bot walks a
     * short way along a platform (as players do) instead of hopping into the water for every errand; swimming
     * sideways (140 px/s) is a little faster than walking, so long trips still swim.
     */
    static final int TAKEOFF_MS = 400;
    private static final int BUCKET = SPACING * 2;

    enum Kind {
        WATER,
        FLOOR
    }

    /** What the swim physics makes of the controls, px/s. */
    record SwimSpeeds(double sideways, double rise, double sink, double walk) {
        double fastest() {
            return Math.max(Math.max(sideways, rise), Math.max(sink, walk));
        }
    }

    final int mapId;
    final int version = VERSION;
    final int[] xs;
    final int[] ys;
    final Kind[] kinds;
    final int[] footholds;
    final SwimSpeeds speeds;
    /** Per node: where its edges go, their costs (ms) and types. Replaced whole on adopt (copy-on-write). */
    private final Adj[] out;
    /** Edges built during construction; dropped by compact(). */
    private List<int[]>[] building;
    private final Map<Long, BotNavigationGraph.Edge> edgeObjects = new ConcurrentHashMap<>();
    /** Edges with something learned about them (from<<32 | to): the only ones the planner asks learning about. */
    final Set<Long> learned = ConcurrentHashMap.newKeySet();
    private final Map<Long, int[]> buckets;
    private final Map<Integer, int[]> floorNodesByFoothold;
    transient volatile NavEdgeLearning.Book learningBook;

    @SuppressWarnings("unchecked")
    private SwimNavGraph(int mapId, List<int[]> nodes, List<Kind> kinds, SwimSpeeds speeds) {
        this.mapId = mapId;
        int n = nodes.size();
        this.xs = new int[n];
        this.ys = new int[n];
        this.footholds = new int[n];
        this.kinds = kinds.toArray(new Kind[0]);
        for (int i = 0; i < n; i++) {
            xs[i] = nodes.get(i)[0];
            ys[i] = nodes.get(i)[1];
            footholds[i] = nodes.get(i)[2];
        }
        this.speeds = speeds;
        this.out = new Adj[n];
        this.building = new List[n];
        for (int i = 0; i < n; i++) {
            building[i] = new ArrayList<>(8);
        }
        Map<Long, List<Integer>> b = new HashMap<>();
        Map<Integer, List<Integer>> byFh = new HashMap<>();
        for (int i = 0; i < n; i++) {
            b.computeIfAbsent(bucketKey(xs[i], ys[i]), k -> new ArrayList<>()).add(i);
            if (this.kinds[i] == Kind.FLOOR) {
                byFh.computeIfAbsent(footholds[i], k -> new ArrayList<>()).add(i);
            }
        }
        this.buckets = new HashMap<>();
        b.forEach((k, v) -> buckets.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        this.floorNodesByFoothold = new HashMap<>();
        byFh.forEach((k, v) -> floorNodesByFoothold.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    int size() {
        return xs.length;
    }

    Point point(int node) {
        return new Point(xs[node], ys[node]);
    }

    private record Adj(int[] to, int[] cost, byte[] type) {
        static final Adj EMPTY = new Adj(new int[0], new int[0], new byte[0]);
    }

    private static final BotNavigationGraph.EdgeType[] TYPES = {
            BotNavigationGraph.EdgeType.SWIM, BotNavigationGraph.EdgeType.WALK
    };

    private static byte typeCode(BotNavigationGraph.EdgeType type) {
        return (byte) (type == BotNavigationGraph.EdgeType.WALK ? 1 : 0);
    }

    /** Every edge out of a node, as objects (tests, the lab, the player observer - not the planner). */
    List<BotNavigationGraph.Edge> outgoing(int node) {
        Adj adj = out[node];
        List<BotNavigationGraph.Edge> edges = new ArrayList<>(adj.to.length);
        for (int i = 0; i < adj.to.length; i++) {
            edges.add(edgeObject(node, adj, i));
        }
        return edges;
    }

    int edgeCount() {
        int count = 0;
        for (Adj adj : out) {
            count += adj.to.length;
        }
        return count;
    }

    /** The edge from one node to another as an object, or null when the graph does not join them. */
    BotNavigationGraph.Edge edgeBetween(int from, int to) {
        Adj adj = out[from];
        for (int i = 0; i < adj.to.length; i++) {
            if (adj.to[i] == to) {
                return edgeObject(from, adj, i);
            }
        }
        return null;
    }

    private BotNavigationGraph.Edge edgeObject(int from, Adj adj, int i) {
        int to = adj.to[i];
        return edgeObjects.computeIfAbsent(key(from, to),
                k -> edge(from, to, point(from), point(to), TYPES[adj.type[i]], adj.cost[i]));
    }

    static long key(int from, int to) {
        return ((long) from << 32) | (to & 0xffffffffL);
    }

    /** Adds an edge a player proved (SwimNavObserved). Copy-on-write: planners may be reading the node's edges. */
    synchronized boolean adopt(BotNavigationGraph.Edge edge) {
        int from = edge.fromRegionId;
        if (edgeBetween(from, edge.toRegionId) != null) {
            return false;
        }
        Adj adj = out[from];
        int n = adj.to.length;
        int[] to = Arrays.copyOf(adj.to, n + 1);
        int[] cost = Arrays.copyOf(adj.cost, n + 1);
        byte[] type = Arrays.copyOf(adj.type, n + 1);
        to[n] = edge.toRegionId;
        cost[n] = edge.cost;
        type[n] = typeCode(edge.type);
        edgeObjects.put(key(from, edge.toRegionId), edge);
        out[from] = new Adj(to, cost, type);
        return true;
    }

    private void add(int from, int to, BotNavigationGraph.EdgeType type, int ms) {
        for (int[] e : building[from]) {
            if (e[0] == to) {
                return;
            }
        }
        building[from].add(new int[]{to, Math.max(1, ms), typeCode(type)});
    }

    private void compact() {
        for (int i = 0; i < out.length; i++) {
            List<int[]> list = building[i];
            if (list.isEmpty()) {
                out[i] = Adj.EMPTY;
                continue;
            }
            int[] to = new int[list.size()];
            int[] cost = new int[list.size()];
            byte[] type = new byte[list.size()];
            for (int j = 0; j < to.length; j++) {
                to[j] = list.get(j)[0];
                cost[j] = list.get(j)[1];
                type[j] = (byte) list.get(j)[2];
            }
            out[i] = new Adj(to, cost, type);
        }
        building = null;
    }

    // ── cache ───────────────────────────────────────────────────────────────────────────────────

    private static final Map<FootholdTree, SwimNavGraph> GRAPHS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<FootholdTree> BUILDING = ConcurrentHashMap.newKeySet();

    /** The map's graph, or null while it is built in the background (the caller steers straight meanwhile). */
    static SwimNavGraph peekOrWarm(MapleMap map) {
        if (map == null || !map.isSwim() || map.getFootholds() == null) {
            return null;
        }
        FootholdTree tree = map.getFootholds();
        SwimNavGraph graph = GRAPHS.get(tree);
        if (graph != null || !BUILDING.add(tree)) {
            return graph;
        }
        try {
            soloMapling.server.ExecutorServiceManager.runAsync(() -> {
                try {
                    getOrBuild(map);
                } finally {
                    BUILDING.remove(tree);
                }
            });
        } catch (RuntimeException e) {
            BUILDING.remove(tree);
            return getOrBuild(map); // no executor (tools, tests): build right here
        }
        return null;
    }

    /** The map's graph, built now if need be. Null for a map that is not a swim map. */
    static SwimNavGraph getOrBuild(MapleMap map) {
        if (map == null || !map.isSwim() || map.getFootholds() == null) {
            return null;
        }
        FootholdTree tree = map.getFootholds();
        SwimNavGraph graph = GRAPHS.get(tree);
        if (graph != null) {
            return graph;
        }
        graph = build(map, BotMovementProfile.base());
        SwimNavObserved.restore(map, graph);
        GRAPHS.put(tree, graph);
        return graph;
    }

    // ── physics ─────────────────────────────────────────────────────────────────────────────────

    /*
     * Speeds from the swim integrator's own constants (BotPhysicsEngine.applySwimMotion0): the same drag, gravity,
     * thrust and caps, run tick by tick without collision. Sideways: the accel/drag terminal under the SWIM_VEL
     * cap. Sinking: the DOWN-held terminal. Rising: a burst every SWIM_JUMP_COOLDOWN_MS with UP held between, the
     * way BotMovementManager.computeSwimIntents climbs.
     */
    static SwimSpeeds speeds(MapleMap map, BotMovementProfile profile) {
        BotPhysicsEngine.Config c = BotPhysicsEngine.cfg;
        double t = c.TICK_MS / 1000.0;
        double mult = profile != null ? profile.speedMultiplier() : 1.0;
        double sideways = Math.min(c.SWIM_VEL_PXS, c.SWIM_ACCEL_PXS2 / Math.max(0.01, c.SWIM_FRICTION_HZ));
        double sink = c.SWIM_DOWN_MAX_SPEED_PXS;
        double y = 0;
        double vy = 0;
        int ticks = (int) Math.round(5.0 / t);
        int cooldownTicks = Math.max(1, (int) Math.ceil(c.SWIM_JUMP_COOLDOWN_MS / (double) c.TICK_MS));
        for (int i = 0; i < ticks; i++) {
            if (i % cooldownTicks == 0) {
                vy = -c.SWIM_JUMP_BURST_PXS * mult;
            }
            vy *= Math.max(0.0, 1.0 - c.SWIM_FRICTION_HZ * t);
            vy += c.SWIM_GRAVITY_PXS2 * t;
            vy -= c.SWIM_UP_THRUST_PXS2 * t;
            vy = Math.max(-c.SWIM_MAX_SPEED_PXS, Math.min(c.SWIM_UP_MAX_SINK_PXS, vy));
            y += vy * t;
        }
        double rise = Math.max(20.0, -y / (ticks * t));
        double walk = map != null
                ? BotPhysicsEngine.walkStep(map, profile) * 1000.0 / c.TICK_MS
                : 125.0;
        return new SwimSpeeds(sideways, rise, sink, walk);
    }

    /** Milliseconds to swim from a to b: both axes are steered at once, so the slower axis sets the time. */
    static int swimMs(SwimSpeeds s, int ax, int ay, int bx, int by) {
        double dx = Math.abs(bx - ax) / s.sideways();
        int dyPx = by - ay;
        double dy = dyPx < 0 ? -dyPx / s.rise() : dyPx / s.sink();
        return (int) Math.ceil(1000.0 * Math.max(dx, dy)) + 1;
    }

    // ── queries ─────────────────────────────────────────────────────────────────────────────────

    /** True when a bot could swim straight from a to b with CLEARANCE-ish room: the centre line and both sides. */
    static boolean clearSwim(MapleMap map, Point a, Point b) {
        if (BotPhysicsEngine.swimSegment(map, a, b) != BotPhysicsEngine.SwimHit.NONE) {
            return false;
        }
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        // Offsets perpendicular to the dominant axis: a mostly sideways swim bobs up and down, a mostly vertical one
        // drifts sideways.
        int ox = Math.abs(dy) > Math.abs(dx) ? SIDE_CHECK_PX : 0;
        int oy = Math.abs(dy) > Math.abs(dx) ? 0 : SIDE_CHECK_PX;
        return BotPhysicsEngine.swimSegment(map, new Point(a.x + ox, a.y + oy), new Point(b.x + ox, b.y + oy))
                == BotPhysicsEngine.SwimHit.NONE
                && BotPhysicsEngine.swimSegment(map, new Point(a.x - ox, a.y - oy), new Point(b.x - ox, b.y - oy))
                == BotPhysicsEngine.SwimHit.NONE;
    }

    /*
     * True when a bot steering from a at b would get there: the straight line, and the path the swim controls really
     * take - vertical motion (sinking at up to the DOWN-held terminal, rising in bursts) is faster than the 140 px/s
     * sideways cap, so the bot reaches b's height first and covers the rest level. Both must be clear: a straight
     * line that grazes a ledge's corner is not enough when the bot sinks onto the ledge on the way.
     */
    boolean swimReachable(MapleMap map, Point a, Point b) {
        if (!clearSwim(map, a, b)) {
            return false;
        }
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        double vertical = dy > 0 ? speeds.sink() : speeds.rise();
        int run = (int) Math.round(Math.abs(dy) * speeds.sideways() / vertical);
        if (run >= Math.abs(dx) - 4 || Math.abs(dy) < 8) {
            return true; // the vertical leg takes as long as the sideways one: the line is the path
        }
        Point corner = new Point(a.x + Integer.signum(dx) * run, b.y);
        return clearSwim(map, a, corner) && clearSwim(map, corner, b);
    }

    /** True when swimming (sinking) from a lands on the platform node {@code floorNode}. */
    boolean landsOn(MapleMap map, Point a, int floorNode) {
        Point f = point(floorNode);
        Foothold landing = BotPhysicsEngine.swimLandingFoothold(map, a, new Point(f.x, f.y + 4));
        if (landing == null) {
            return false;
        }
        // The very foothold, or one that meets it at this spot (a chain joint): landing on a platform above it
        // is not landing on it.
        return landing.getId() == footholds[floorNode] || Math.abs(footingY(landing, f.x) - f.y) <= 3;
    }

    /** Where a bot is: its platform's nearest node when standing, else the nearest water node it can swim to. */
    int startNode(MapleMap map, Point pos, boolean grounded) {
        if (grounded) {
            Foothold fh = BotPhysicsEngine.findGroundFoothold(map, pos);
            if (fh != null) {
                int node = nearestOnFoothold(fh.getId(), pos);
                if (node >= 0 && Math.abs(xs[node] - pos.x) <= SPACING * 2 && Math.abs(ys[node] - pos.y) <= SPACING) {
                    return node;
                }
            }
        }
        return nearest(map, pos, true);
    }

    /** The node a target is reached from: its platform's node when it stands on one, else a water node in sight. */
    int goalNode(MapleMap map, Point target) {
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(target.x, target.y - 1));
        if (ground != null && Math.abs(ground.y - target.y) <= 6) {
            Foothold fh = BotPhysicsEngine.findGroundFoothold(map, ground);
            if (fh != null) {
                int node = nearestOnFoothold(fh.getId(), target);
                if (node >= 0 && Math.abs(xs[node] - target.x) <= SPACING * 2) {
                    return node;
                }
            }
        }
        return nearest(map, target, false);
    }

    private int nearestOnFoothold(int fhId, Point p) {
        int[] nodes = floorNodesByFoothold.get(fhId);
        if (nodes == null) {
            return -1;
        }
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int node : nodes) {
            long d = sq(xs[node] - p.x) + sq(ys[node] - p.y);
            if (d < bestD) {
                bestD = d;
                best = node;
            }
        }
        return best;
    }

    /*
     * The nearest node with a clear straight swim between it and p (from p when fromP, else to p). Looks in growing
     * rings of buckets, up to 3 buckets out.
     */
    int nearest(MapleMap map, Point p, boolean fromP) {
        int bx = Math.floorDiv(p.x, BUCKET);
        int by = Math.floorDiv(p.y, BUCKET);
        for (int ring = 1; ring <= 3; ring++) {
            List<int[]> candidates = new ArrayList<>();
            for (int gx = bx - ring; gx <= bx + ring; gx++) {
                for (int gy = by - ring; gy <= by + ring; gy++) {
                    int[] nodes = buckets.get(((long) gx << 32) ^ (gy & 0xffffffffL));
                    if (nodes == null) {
                        continue;
                    }
                    for (int node : nodes) {
                        candidates.add(new int[]{node, (int) Math.min(Integer.MAX_VALUE,
                                sq(xs[node] - p.x) + sq(ys[node] - p.y))});
                    }
                }
            }
            candidates.sort((a, b) -> Integer.compare(a[1], b[1]));
            int checked = 0;
            for (int[] c : candidates) {
                int node = c[0];
                Point q = point(node);
                boolean ok;
                if (kinds[node] == Kind.FLOOR) {
                    // A platform node is "in reach" when p stands on it or would sink onto it.
                    ok = fromP ? (Math.abs(q.x - p.x) <= 8 && Math.abs(q.y - p.y) <= 8) || landsOn(map, p, node)
                            : Math.abs(q.y - p.y) <= 8 || BotPhysicsEngine.swimSegment(map, q, p)
                            == BotPhysicsEngine.SwimHit.NONE;
                } else {
                    ok = fromP ? BotPhysicsEngine.swimSegment(map, p, q) == BotPhysicsEngine.SwimHit.NONE
                            : BotPhysicsEngine.swimSegment(map, q, p) == BotPhysicsEngine.SwimHit.NONE;
                }
                if (ok) {
                    return node;
                }
                if (++checked >= 24) {
                    break;
                }
            }
        }
        return -1;
    }

    private static long bucketKey(int x, int y) {
        return ((long) Math.floorDiv(x, BUCKET) << 32) ^ (Math.floorDiv(y, BUCKET) & 0xffffffffL);
    }

    private static long sq(long v) {
        return v * v;
    }

    // ── planning ────────────────────────────────────────────────────────────────────────────────

    /** Edge cost the planner sees: built cost scaled by what failed before; +infinity for an edge not to take. */
    interface CostModel {
        double cost(int from, int to, int builtCost);
    }

    /** The default model: built cost times what NavEdgeLearning learned (only edges with a record are looked up). */
    CostModel learnedCosts() {
        return (from, to, cost) -> learned.isEmpty() || !learned.contains(key(from, to))
                ? cost : cost * NavEdgeLearning.swimFactor(this, edgeBetween(from, to));
    }

    /** A* from start to goal. Empty when start == goal, null when there is no route. */
    List<BotNavigationGraph.Edge> findPath(int start, int goal, CostModel costs) {
        if (start < 0 || goal < 0) {
            return null;
        }
        if (start == goal) {
            return List.of();
        }
        int n = size();
        double[] g = new double[n];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        int[] via = new int[n];
        Arrays.fill(via, -1);
        boolean[] closed = new boolean[n];
        double perPx = 1000.0 / speeds.fastest();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        g[start] = 0;
        open.add(new double[]{heuristic(start, goal, perPx), start});
        while (!open.isEmpty()) {
            int node = (int) open.poll()[1];
            if (closed[node]) {
                continue;
            }
            if (node == goal) {
                break;
            }
            closed[node] = true;
            Adj adj = out[node];
            for (int i = 0; i < adj.to.length; i++) {
                int to = adj.to[i];
                if (closed[to]) {
                    continue;
                }
                double c = costs != null ? costs.cost(node, to, adj.cost[i]) : adj.cost[i];
                if (!(c < Double.POSITIVE_INFINITY)) {
                    continue;
                }
                double ng = g[node] + c;
                if (ng < g[to]) {
                    g[to] = ng;
                    via[to] = node;
                    open.add(new double[]{ng + heuristic(to, goal, perPx), to});
                }
            }
        }
        if (via[goal] < 0) {
            return null;
        }
        List<BotNavigationGraph.Edge> path = new ArrayList<>();
        for (int at = goal; at != start; at = via[at]) {
            path.add(edgeBetween(via[at], at));
        }
        Collections.reverse(path);
        return path;
    }

    private double heuristic(int a, int b, double perPx) {
        return Math.hypot(xs[a] - xs[b], ys[a] - ys[b]) * perPx;
    }

    // ── building ────────────────────────────────────────────────────────────────────────────────

    static SwimNavGraph build(MapleMap map, BotMovementProfile profile) {
        FootholdTree tree = map.getFootholds();
        List<Foothold> all = tree.getAllFootholds();
        List<Foothold> floors = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Foothold fh : all) {
            minX = Math.min(minX, Math.min(fh.getX1(), fh.getX2()));
            maxX = Math.max(maxX, Math.max(fh.getX1(), fh.getX2()));
            if (!fh.isWall()) {
                floors.add(fh);
                minY = Math.min(minY, Math.min(fh.getY1(), fh.getY2()));
                maxY = Math.max(maxY, Math.max(fh.getY1(), fh.getY2()));
            }
        }
        if (floors.isEmpty()) {
            SwimNavGraph empty = new SwimNavGraph(map.getId(), List.of(), List.of(), speeds(map, profile));
            empty.compact();
            return empty;
        }
        Rectangle area = map.getMapArea();
        int left = minX;
        int right = maxX;
        int top = minY - 300;
        if (area != null && area.width > 0 && area.height > 0 && area.width < (1 << 17)) {
            left = Math.max(left, area.x);
            right = Math.min(right, area.x + area.width);
            top = area.y;
        }
        int bottom = maxY;

        List<int[]> nodes = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        Map<Long, Integer> seen = new HashMap<>();

        // Platform nodes: every SPACING px along each floor, plus its two ends.
        for (Foothold fh : floors) {
            int lo = Math.min(fh.getX1(), fh.getX2());
            int hi = Math.max(fh.getX1(), fh.getX2());
            List<Integer> sampleXs = new ArrayList<>();
            if (hi - lo >= 12) {
                sampleXs.add(lo + 4);
                sampleXs.add(hi - 4);
            }
            for (int x = Math.floorDiv(lo, SPACING) * SPACING + SPACING; x < hi - 8; x += SPACING) {
                if (x > lo + 8) {
                    sampleXs.add(x);
                }
            }
            for (int x : sampleXs) {
                int y = footingY(fh, x);
                Foothold standing = BotPhysicsEngine.findGroundFoothold(map, new Point(x, y));
                if (standing == null || standing.getId() != fh.getId() && footingY(standing, x) != y) {
                    continue; // covered by another platform right here
                }
                if (floorAbove(floors, fh, x, y, FLOOR_HEADROOM_PX)) {
                    continue;
                }
                long key = ((long) x << 32) ^ (y & 0xffffffffL);
                if (seen.putIfAbsent(key, nodes.size()) == null) {
                    nodes.add(new int[]{x, y, fh.getId()});
                    kinds.add(Kind.FLOOR);
                }
            }
        }
        int floorCount = nodes.size();

        // Water nodes: a grid aligned to SPACING, clear of every foothold, with ground somewhere below (no ground
        // below means under the sea floor).
        Map<Long, Integer> gridIndex = new HashMap<>();
        for (int x = Math.floorDiv(left, SPACING) * SPACING; x <= right; x += SPACING) {
            if (x <= left + 4 || x >= right - 4) {
                continue;
            }
            for (int y = Math.floorDiv(top, SPACING) * SPACING + SPACING; y < bottom; y += SPACING) {
                if (nearFoothold(all, x, y, CLEARANCE)) {
                    continue;
                }
                if (BotPhysicsEngine.findGroundFoothold(map, new Point(x, y)) == null
                        || floorAbove(floors, null, x, y, WATER_HEADROOM_PX)) {
                    continue;
                }
                gridIndex.put(((long) (x / SPACING) << 32) ^ ((y / SPACING) & 0xffffffffL), nodes.size());
                nodes.add(new int[]{x, y, 0});
                kinds.add(Kind.WATER);
            }
        }

        SwimNavGraph graph = new SwimNavGraph(map.getId(), nodes, kinds, speeds(map, profile));
        graph.connectWater(map, gridIndex);
        graph.connectPlatforms(map, floorCount);
        graph.connectWalks(map, floors);
        graph.compact();
        return graph;
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}
    };

    private void connectWater(MapleMap map, Map<Long, Integer> gridIndex) {
        for (int node = 0; node < size(); node++) {
            if (kinds[node] != Kind.WATER) {
                continue;
            }
            int gx = xs[node] / SPACING;
            int gy = ys[node] / SPACING;
            Point a = point(node);
            for (int[] d : NEIGHBOURS) {
                Integer to = gridIndex.get(((long) (gx + d[0]) << 32) ^ ((gy + d[1]) & 0xffffffffL));
                if (to == null) {
                    continue;
                }
                Point b = point(to);
                if (!clearSwim(map, a, b)) {
                    continue;
                }
                int ms = swimMs(speeds, a.x, a.y, b.x, b.y);
                if (b.y < a.y && BotPhysicsEngine.swimSegment(map, b, a) == BotPhysicsEngine.SwimHit.LAND) {
                    ms = (int) Math.round(ms * THROUGH_FLOOR_FACTOR); // rising through a platform from below
                }
                add(node, to, BotNavigationGraph.EdgeType.SWIM, ms);
            }
        }
    }

    private void connectPlatforms(MapleMap map, int floorCount) {
        for (int f = 0; f < floorCount; f++) {
            Point fp = point(f);
            Point lifted = new Point(fp.x, fp.y - 2);
            for (int w : nodesAround(fp, SPACING * 2)) {
                if (kinds[w] != Kind.WATER || Math.abs(xs[w] - fp.x) > SPACING + SPACING / 2) {
                    continue;
                }
                Point wp = point(w);
                int rise = fp.y - wp.y;
                // Take-off: a jump straight up off the platform into the water above it.
                if (rise >= 10 && rise <= SPACING * 2
                        && BotPhysicsEngine.swimSegment(map, lifted, wp) == BotPhysicsEngine.SwimHit.NONE) {
                    add(f, w, BotNavigationGraph.EdgeType.SWIM, TAKEOFF_MS + swimMs(speeds, fp.x, fp.y, wp.x, wp.y));
                }
                // Landing: sink from the water onto this platform.
                if (rise >= 0 && rise <= SPACING * 2 && landsOn(map, wp, f)) {
                    add(w, f, BotNavigationGraph.EdgeType.SWIM, swimMs(speeds, wp.x, wp.y, fp.x, fp.y));
                }
            }
        }
    }

    private void connectWalks(MapleMap map, List<Foothold> floors) {
        Map<Integer, Foothold> byId = new HashMap<>();
        for (Foothold fh : floors) {
            byId.put(fh.getId(), fh);
        }
        for (Map.Entry<Integer, int[]> e : floorNodesByFoothold.entrySet()) {
            int[] nodes = e.getValue().clone();
            sortByX(nodes);
            for (int i = 0; i + 1 < nodes.length; i++) {
                walk(nodes[i], nodes[i + 1]);
                walk(nodes[i + 1], nodes[i]);
            }
            // Across to the next foothold of the chain (a wall in between ends the chain: it is not in byId).
            Foothold fh = byId.get(e.getKey());
            Foothold next = fh != null ? byId.get(fh.getNext()) : null;
            int[] nextNodes = next != null ? floorNodesByFoothold.get(next.getId()) : null;
            if (nodes.length == 0 || nextNodes == null || nextNodes.length == 0) {
                continue;
            }
            Point joint = new Point(fh.getX2(), fh.getY2());
            int mine = closestTo(nodes, joint);
            int theirs = closestTo(nextNodes, joint);
            if (Math.abs(xs[mine] - xs[theirs]) <= SPACING * 2 && Math.abs(ys[mine] - ys[theirs]) <= SPACING) {
                walk(mine, theirs);
                walk(theirs, mine);
            }
        }
    }

    private void walk(int from, int to) {
        if (from == to) {
            return;
        }
        double px = Math.hypot(xs[to] - xs[from], ys[to] - ys[from]);
        add(from, to, BotNavigationGraph.EdgeType.WALK, (int) Math.ceil(1000.0 * px / speeds.walk()) + 1);
    }

    private int closestTo(int[] nodes, Point p) {
        int best = nodes[0];
        long bestD = Long.MAX_VALUE;
        for (int node : nodes) {
            long d = sq(xs[node] - p.x) + sq(ys[node] - p.y);
            if (d < bestD) {
                bestD = d;
                best = node;
            }
        }
        return best;
    }

    private void sortByX(int[] nodes) {
        Integer[] boxed = Arrays.stream(nodes).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, (a, b) -> Integer.compare(xs[a], xs[b]));
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = boxed[i];
        }
    }

    private List<Integer> nodesAround(Point p, int radius) {
        List<Integer> found = new ArrayList<>();
        int r = radius / BUCKET + 1;
        int bx = Math.floorDiv(p.x, BUCKET);
        int by = Math.floorDiv(p.y, BUCKET);
        for (int gx = bx - r; gx <= bx + r; gx++) {
            for (int gy = by - r; gy <= by + r; gy++) {
                int[] nodes = buckets.get(((long) gx << 32) ^ (gy & 0xffffffffL));
                if (nodes == null) {
                    continue;
                }
                for (int node : nodes) {
                    if (Math.abs(xs[node] - p.x) <= radius && Math.abs(ys[node] - p.y) <= radius) {
                        found.add(node);
                    }
                }
            }
        }
        return found;
    }

    static BotNavigationGraph.Edge edge(int from, int to, Point a, Point b, BotNavigationGraph.EdgeType type, int ms) {
        return new BotNavigationGraph.Edge(from, to, type, a, b, 0, -1, 0, 0, 0, Math.max(1, ms));
    }

    static int footingY(Foothold fh, int x) {
        int x1 = fh.getX1();
        int x2 = fh.getX2();
        if (x1 == x2) {
            return Math.min(fh.getY1(), fh.getY2());
        }
        double ratio = (Math.clamp(x, Math.min(x1, x2), Math.max(x1, x2)) - x1) / (double) (x2 - x1);
        return (int) Math.round(fh.getY1() + (fh.getY2() - fh.getY1()) * ratio);
    }

    /** True when a floor (other than {@code except}) passes over x less than {@code px} above y. */
    private static boolean floorAbove(List<Foothold> floors, Foothold except, int x, int y, int px) {
        for (Foothold fh : floors) {
            if (fh == except || x < Math.min(fh.getX1(), fh.getX2()) || x > Math.max(fh.getX1(), fh.getX2())) {
                continue;
            }
            int fy = footingY(fh, x);
            if (fy < y - 2 && fy >= y - px) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearFoothold(List<Foothold> all, int x, int y, int clearance) {
        long c2 = (long) clearance * clearance;
        for (Foothold fh : all) {
            if (distSq(fh, x, y) < c2) {
                return true;
            }
        }
        return false;
    }

    private static long distSq(Foothold fh, int px, int py) {
        double x1 = fh.getX1();
        double y1 = fh.getY1();
        double dx = fh.getX2() - x1;
        double dy = fh.getY2() - y1;
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.clamp(((px - x1) * dx + (py - y1) * dy) / len, 0.0, 1.0);
        double cx = x1 + t * dx - px;
        double cy = y1 + t * dy - py;
        return Math.round(cx * cx + cy * cy);
    }

    /** One line for logs and the lab. */
    String describe() {
        int water = 0;
        for (Kind k : kinds) {
            if (k == Kind.WATER) {
                water++;
            }
        }
        return "swim graph " + mapId + " v" + version + ": " + water + " water + " + (size() - water)
                + " platform nodes, " + edgeCount() + " edges; speeds " + String.format(
                "side %.0f rise %.0f sink %.0f walk %.0f px/s", speeds.sideways(), speeds.rise(), speeds.sink(),
                speeds.walk());
    }
}
