package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Ticket 34: how a bot gets around a swim map. BotNavigationManager.resolveTarget hands swim maps here instead of
 * the ground nav graph.
 *
 * Plans an A* route over the map's SwimNavGraph (water grid + platform nodes) and turns it into one steering point a
 * tick, which the existing controls follow: BotMovementManager.computeSwimIntents (left/right, UP/DOWN held, a burst
 * when the point is well above) while swimming, the ground walk (with the old fallback's rope and ledge handling)
 * while standing. Nothing here moves the bot or sends a packet itself, except a take-off: a standing bot whose next
 * point is in the water jumps (the same ground jump a player presses; in a swim map the swim integrator takes over
 * once it leaves the foothold).
 *
 * Path smoothing: while swimming, the bot steers at the furthest of the next LOOKAHEAD points it can swim to in a
 * straight, clear line (SwimNavGraph.clearSwim), so it swims in long curves rather than grid steps; standing, it
 * walks straight to the furthest point along the platform walk.
 *
 * Re-plans when the target moves, when a teleport or knock-back moved the bot, when it lost sight of its point,
 * and when it stopped getting closer to it: that last one is a swim stuck, which counts as a failure of the edge
 * (NavEdgeLearning swim section), bans the edge for this bot for a while and goes in the stuck log as SWIM_STUCK.
 *
 * Off with -Dbot.swimnav.enabled=false: swim maps then steer straight at the target as before (the "before" mode of
 * SwimNavLab).
 */
final class SwimNavigator {

    static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("bot.swimnav.enabled"));

    static final int ARRIVE_PX = 22;
    static final int LOOKAHEAD = 8;
    static final int DIRECT_PX = 480;
    static final int REPLAN_GOAL_PX = 48;
    static final long REPLAN_MIN_MS = 400;
    static final long STUCK_MS = 2500;
    private static final long AI_TICK_MS = 100;
    static final long LOST_MS = 1200;
    static final long BAN_MS = 20_000;
    static final int TELEPORT_PX = 160;
    /** Hovering this close to the final point is arriving, not being stuck. */
    static final int HOVER_PX = 60;
    static final int WOBBLE_MAX_PX = 8;
    /** A goal's route may take this long (planned cost x factor + base) before the bot counts as going in circles. */
    static final long BUDGET_BASE_MS = 5000;
    static final long BUDGET_FACTOR = 3;

    /** Lab / GM counters. */
    static final AtomicLong plans = new AtomicLong();
    static final AtomicLong noPaths = new AtomicLong();
    static final AtomicLong stucks = new AtomicLong();
    static final AtomicLong takeoffs = new AtomicLong();
    static final AtomicLong replansLost = new AtomicLong();

    /** One bot's route. Owned by the bot's tick thread, like the rest of BotMovementState. */
    static final class State {
        final SwimNavGraph graph;
        Point goal;
        Point[] wps;                       // steering points; the last is the raw target
        boolean[] floor;                   // wps[i] is on a platform
        BotNavigationGraph.Edge[] via;     // the edge that arrives at wps[i] (null for the start node and the target)
        int p;
        long plannedAt = Long.MIN_VALUE / 2;
        boolean replan;
        int bestDist = Integer.MAX_VALUE;
        long progressAt;
        long lostSince;
        Point lastPos;
        boolean wasGrounded;
        long noPathLoggedAt = Long.MIN_VALUE / 2;
        long goalSince;                    // when this goal (within REPLAN_GOAL_PX) was first planned
        long budgetMs;
        boolean noDirect;                  // a straight swim at this goal got stuck once: use the graph                     // how long the route to it may take before it counts as stuck
        final Map<Long, Long> banned = new HashMap<>();

        State(SwimNavGraph graph) {
            this.graph = graph;
        }

        Point steering() {
            return wps == null ? null : wps[p];
        }
    }

    private SwimNavigator() {
    }

    /**
     * The steering point for a bot on a swim map, or null to steer straight at the raw target (feature off, graph
     * still building, climbing a rope).
     */
    static BotNavigationManager.NavigationDirective resolve(BotMovementState entry, Point raw, boolean runAiTick) {
        if (!enabled || raw == null || entry == null || entry.bot == null || entry.climbing) {
            return null;
        }
        Character bot = entry.bot;
        MapleMap map = bot.getMap();
        SwimNavGraph graph = SwimNavGraph.peekOrWarm(map);
        if (graph == null || graph.size() == 0) {
            return null;
        }
        State st = entry.swimNav;
        if (st == null || st.graph != graph) {
            st = new State(graph);
            entry.swimNav = st;
        }
        Point pos = bot.getPosition();
        boolean grounded = !entry.inAir;
        long now = System.currentTimeMillis();

        if (st.lastPos != null && Math.abs(st.lastPos.x - pos.x) + Math.abs(st.lastPos.y - pos.y) > TELEPORT_PX) {
            st.replan = true; // a teleport, a knock-back, a respawn
        }
        if (grounded && !st.wasGrounded && st.wps != null && !landedAsPlanned(map, st, pos)) {
            st.replan = true; // came down on another platform than the route's
        }
        st.lastPos = pos;
        st.wasGrounded = grounded;

        boolean goalMoved = st.goal == null
                || Math.abs(st.goal.x - raw.x) + Math.abs(st.goal.y - raw.y) > REPLAN_GOAL_PX;
        if (runAiTick && (st.replan || goalMoved || st.wps == null) && now - st.plannedAt >= REPLAN_MIN_MS) {
            plan(entry, st, map, pos, grounded, raw, now);
        }
        if (st.wps == null) {
            entry.lastNavDecision = "swim-nopath";
            return null;
        }
        st.wps[st.wps.length - 1] = new Point(raw); // a target that crept less than REPLAN_GOAL_PX

        advance(entry, st, map, pos, grounded, runAiTick);
        if (runAiTick) {
            checkProgress(entry, st, map, pos, grounded, now);
        }

        Point steer = st.steering();
        if (grounded && !st.floor[st.p]
                && entry.jumpCooldownMs <= 0 && !entry.downJumpPending && entry.attackCooldownMs <= 0) {
            // The next point is in the water: take off, like a player pressing jump.
            BotMovementManager.initiateJump(entry, bot, Integer.signum(steer.x - pos.x));
            takeoffs.incrementAndGet();
            entry.lastNavDecision = "swim-takeoff";
            return new BotNavigationManager.NavigationDirective(new Point(steer), true);
        }
        if (!grounded && st.p > 0 && !st.floor[st.p] && runAiTick
                && BotPhysicsEngine.swimSegment(map, pos, steer) != BotPhysicsEngine.SwimHit.NONE
                && BotPhysicsEngine.swimSegment(map, pos, st.wps[st.p - 1]) == BotPhysicsEngine.SwimHit.NONE) {
            // Cut a corner and the point went behind a rock: back to the last point first, the graph's edge from
            // there is clear.
            entry.lastNavDecision = "swim-backoff";
            return new BotNavigationManager.NavigationDirective(new Point(st.wps[st.p - 1]), false);
        }
        entry.lastNavDecision = grounded ? "swim-walk" : "swim";
        return new BotNavigationManager.NavigationDirective(wobble(entry, st, steer, grounded), false);
    }

    // ── planning ────────────────────────────────────────────────────────────────────────────────

    private static void plan(BotMovementState entry, State st, MapleMap map, Point pos, boolean grounded, Point raw,
                             long now) {
        boolean newGoal = st.goal == null
                || Math.abs(st.goal.x - raw.x) + Math.abs(st.goal.y - raw.y) > REPLAN_GOAL_PX;
        if (newGoal) {
            st.goalSince = now;
            st.budgetMs = 0;
            st.noDirect = false;
        }
        st.plannedAt = now;
        st.replan = false;
        st.goal = new Point(raw);
        st.p = 0;
        st.bestDist = Integer.MAX_VALUE;
        st.progressAt = now;
        st.lostSince = 0;
        st.banned.values().removeIf(until -> until <= now);
        plans.incrementAndGet();
        boolean rawOnFloor = onFloor(map, raw);
        if (!st.noDirect && direct(st.graph, map, pos, raw, grounded, rawOnFloor)) {
            st.wps = new Point[]{new Point(raw)};
            st.floor = new boolean[]{rawOnFloor};
            st.via = new BotNavigationGraph.Edge[1];
            return;
        }
        SwimNavGraph graph = st.graph;
        int start = graph.startNode(map, pos, grounded);
        int goal = graph.goalNode(map, raw);
        SwimNavGraph.CostModel learnedCosts = graph.learnedCosts();
        List<BotNavigationGraph.Edge> path = start < 0 || goal < 0 ? null
                : graph.findPath(start, goal, (from, to, cost) -> !st.banned.isEmpty()
                && st.banned.containsKey(SwimNavGraph.key(from, to))
                ? Double.POSITIVE_INFINITY : learnedCosts.cost(from, to, cost));
        if (path == null) {
            st.wps = null;
            noPaths.incrementAndGet();
            if (now - st.noPathLoggedAt >= NavStuckLog.PLAN_FAILURE_EVERY_MS) {
                st.noPathLoggedAt = now;
                NavStuckLog.record(start < 0 ? NavStuckLog.Kind.NO_REGION : NavStuckLog.Kind.NO_PATH, map.getId(),
                        pos, null, start < 0 ? "swim" : "swim n" + start + ">n" + goal);
            }
            return;
        }
        if (st.budgetMs == 0) {
            long cost = 0;
            for (BotNavigationGraph.Edge e : path) {
                cost += e.cost;
            }
            st.budgetMs = BUDGET_BASE_MS + BUDGET_FACTOR * cost;
        }
        int n = path.size() + 2;
        st.wps = new Point[n];
        st.floor = new boolean[n];
        st.via = new BotNavigationGraph.Edge[n];
        st.wps[0] = graph.point(start);
        st.floor[0] = graph.kinds[start] == SwimNavGraph.Kind.FLOOR;
        for (int i = 0; i < path.size(); i++) {
            BotNavigationGraph.Edge e = path.get(i);
            st.wps[i + 1] = new Point(e.endPoint);
            st.floor[i + 1] = graph.kinds[e.toRegionId] == SwimNavGraph.Kind.FLOOR;
            st.via[i + 1] = e;
        }
        st.wps[n - 1] = new Point(raw);
        st.floor[n - 1] = rawOnFloor;
    }

    /** Close enough and nothing in between: no graph needed. */
    static boolean direct(SwimNavGraph graph, MapleMap map, Point pos, Point raw, boolean grounded, boolean rawOnFloor) {
        if (Math.abs(pos.x - raw.x) + Math.abs(pos.y - raw.y) > DIRECT_PX) {
            return false;
        }
        if (grounded) {
            Foothold here = BotPhysicsEngine.findGroundFoothold(map, pos);
            Foothold there = rawOnFloor ? BotPhysicsEngine.findGroundFoothold(map, raw) : null;
            return here != null && there != null && here.getId() == there.getId();
        }
        if (rawOnFloor) {
            Foothold landing = BotPhysicsEngine.swimLandingFoothold(map, pos, new Point(raw.x, raw.y + 4));
            Foothold there = BotPhysicsEngine.findGroundFoothold(map, raw);
            return landing != null && there != null && landing.getId() == there.getId();
        }
        return graph.swimReachable(map, pos, raw);
    }

    static boolean onFloor(MapleMap map, Point p) {
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(p.x, p.y - 1));
        return ground != null && Math.abs(ground.y - p.y) <= 6;
    }

    private static boolean landedAsPlanned(MapleMap map, State st, Point pos) {
        Foothold fh = BotPhysicsEngine.findGroundFoothold(map, pos);
        if (fh == null) {
            return false;
        }
        for (int i = st.p; i < st.wps.length; i++) {
            if (st.floor[i]) {
                Foothold there = BotPhysicsEngine.findGroundFoothold(map, st.wps[i]);
                return there != null && there.getId() == fh.getId();
            }
        }
        return false;
    }

    // ── following the route ─────────────────────────────────────────────────────────────────────

    private static void advance(BotMovementState entry, State st, MapleMap map, Point pos, boolean grounded,
                                boolean runAiTick) {
        int last = st.wps.length - 1;
        while (st.p < last && near(pos, st.wps[st.p], ARRIVE_PX)) {
            reached(entry, st, st.p);
            moveTo(st, st.p + 1);
        }
        if (!runAiTick || st.p >= last) {
            return;
        }
        if (grounded) {
            // Standing: walk straight to the furthest point of an unbroken platform walk.
            if (!st.floor[st.p]) {
                return;
            }
            int j = st.p;
            while (j + 1 <= Math.min(last, st.p + LOOKAHEAD) && st.via[j + 1] != null
                    && st.via[j + 1].type == BotNavigationGraph.EdgeType.WALK) {
                j++;
            }
            if (j > st.p) {
                moveTo(st, j);
            }
            return;
        }
        // Swimming: the furthest water point (or the target) in plain, clear sight.
        for (int j = Math.min(last, st.p + LOOKAHEAD); j > st.p; j--) {
            if (st.floor[j] && j != last) {
                continue; // platforms are reached by landing on them, not skipped to
            }
            boolean ok = st.floor[j]
                    ? direct(st.graph, map, pos, st.wps[j], false, true)
                    : st.graph.swimReachable(map, pos, st.wps[j]);
            if (ok) {
                moveTo(st, j);
                return;
            }
        }
    }

    private static void moveTo(State st, int p) {
        if (p != st.p) {
            st.p = p;
            st.bestDist = Integer.MAX_VALUE;
            st.progressAt = System.currentTimeMillis();
            st.lostSince = 0;
        }
    }

    private static void reached(BotMovementState entry, State st, int i) {
        BotNavigationGraph.Edge edge = st.via[i];
        if (edge != null) {
            NavEdgeLearning.swimSuccess(entry, st.graph, edge);
        }
    }

    private static void checkProgress(BotMovementState entry, State st, MapleMap map, Point pos, boolean grounded,
                                      long now) {
        Point target = st.wps[st.p];
        int d = Math.abs(pos.x - target.x) + Math.abs(pos.y - target.y);
        boolean busy = entry.attackCooldownMs > 0 || entry.reactingUntilMs > now || entry.flaws.holdUntilMs > now;
        boolean hovering = st.p == st.wps.length - 1 && d <= HOVER_PX;
        if (busy) {
            st.goalSince += AI_TICK_MS; // fighting is not travelling: the budget waits
        }
        if (st.budgetMs > 0 && now - st.goalSince > st.budgetMs) {
            st.goalSince = now; // going in circles (e.g. off a ledge and back onto it): fail the leg, route around
            st.budgetMs = 0;
            stuck(entry, st, map, pos);
            return;
        }
        if (d < st.bestDist - 6 || busy || hovering) {
            st.bestDist = Math.min(st.bestDist, d);
            st.progressAt = now;
            // A detour (up and over a rock) takes the bot away from the target for a while; the driver's
            // no-progress give-up only sees straight-line distance, so tell it the route is moving.
            entry.moveProgressAtMs = now;
        } else if (now - st.progressAt >= STUCK_MS) {
            stuck(entry, st, map, pos);
            return;
        }
        // Lost sight of the point while swimming (pushed off course): re-plan from here.
        if (!grounded && !st.floor[st.p]
                && BotPhysicsEngine.swimSegment(map, pos, target) != BotPhysicsEngine.SwimHit.NONE) {
            if (st.lostSince == 0) {
                st.lostSince = now;
            } else if (now - st.lostSince >= LOST_MS) {
                st.replan = true;
                st.lostSince = 0;
                replansLost.incrementAndGet();
            }
        } else {
            st.lostSince = 0;
        }
    }

    private static void stuck(BotMovementState entry, State st, MapleMap map, Point pos) {
        stucks.incrementAndGet();
        BotNavigationGraph.Edge edge = st.via[st.p];
        if (edge == null && st.p + 1 < st.via.length) {
            edge = st.via[st.p + 1];
        }
        String name = edge != null ? NavStuckLog.describe(edge) : "swim direct";
        if (st.wps.length == 1) {
            st.noDirect = true;
        }
        NavStuckLog.record(NavStuckLog.Kind.SWIM_STUCK, map.getId(), pos, null, name, NavTrace.capture(entry));
        if (edge != null) {
            NavEdgeLearning.swimFailure(entry, st.graph, edge);
            st.banned.put(SwimNavGraph.key(edge.fromRegionId, edge.toRegionId), System.currentTimeMillis() + BAN_MS);
        }
        st.replan = true;
        st.plannedAt = Long.MIN_VALUE / 2; // re-plan on the next AI tick
        st.progressAt = System.currentTimeMillis();
        st.bestDist = Integer.MAX_VALUE;
    }

    private static boolean near(Point a, Point b, int r) {
        return Math.abs(a.x - b.x) <= r && Math.abs(a.y - b.y) <= r;
    }

    // ── ticket 33: a swimmer's wobble ───────────────────────────────────────────────────────────

    /*
     * A watched bot does not swim a ruler-straight line: each intermediate point is nudged up or down by a few px,
     * more for a sloppier bot (NavMovementFlaws.skill), never more than WOBBLE_MAX_PX so it stays inside the room
     * SwimNavGraph.clearSwim checked. The final point is never moved.
     */
    private static Point wobble(BotMovementState entry, State st, Point steer, boolean grounded) {
        if (grounded || st.p == st.wps.length - 1 || !wobbles(entry)) {
            return new Point(steer);
        }
        double sloppiness = Math.max(0.0, 1.0 - NavMovementFlaws.skill(entry)) * NavMovementFlaws.tuning.rate;
        int amp = (int) Math.min(WOBBLE_MAX_PX, Math.round(sloppiness * 80));
        if (amp <= 0) {
            return new Point(steer);
        }
        long h = (entry.bot.getId() * 0x9E3779B97F4A7C15L) ^ (steer.x * 31L + steer.y);
        int dy = (int) Math.floorMod(h >>> 7, 2L * amp + 1) - amp;
        return new Point(steer.x, steer.y + dy);
    }

    private static boolean wobbles(BotMovementState entry) {
        NavMovementFlaws.Tuning t = NavMovementFlaws.tuning;
        return t.enabled && t.rate > 0 && !entry.flaws.off
                && (!t.observedOnly || ObserverTracker.isFull(entry.bot.getMapId()));
    }

    // ── offscreen (coarse) bots ─────────────────────────────────────────────────────────────────

    /** The same route as edges with their swim times, for an unwatched bot's analytic advance (MovementPlan). */
    static List<BotNavigationGraph.Edge> coarseRoute(MapleMap map, Point pos, Point target) {
        SwimNavGraph graph = SwimNavGraph.peekOrWarm(map);
        if (graph == null || graph.size() == 0) {
            return null;
        }
        int start = graph.startNode(map, pos, true);
        int goal = graph.goalNode(map, target);
        List<BotNavigationGraph.Edge> path = graph.findPath(start, goal, graph.learnedCosts());
        if (path == null) {
            return null;
        }
        List<BotNavigationGraph.Edge> route = new ArrayList<>(path.size() + 2);
        Point s = graph.point(start);
        route.add(SwimNavGraph.edge(start, start, pos, s, BotNavigationGraph.EdgeType.SWIM,
                SwimNavGraph.swimMs(graph.speeds, pos.x, pos.y, s.x, s.y)));
        route.addAll(path);
        Point e = graph.point(goal);
        route.add(SwimNavGraph.edge(goal, goal, e, target, BotNavigationGraph.EdgeType.SWIM,
                SwimNavGraph.swimMs(graph.speeds, e.x, e.y, target.x, target.y)));
        return route;
    }

    /** One line for the lab and GM read-outs. */
    static String describe() {
        return "swim nav: " + plans.get() + " plans, " + noPaths.get() + " no-path, " + stucks.get() + " stuck, "
                + replansLost.get() + " lost-sight replans, " + takeoffs.get() + " take-offs";
    }

    static void resetCounters() {
        plans.set(0);
        noPaths.set(0);
        stucks.set(0);
        takeoffs.set(0);
        replansLost.set(0);
    }
}
