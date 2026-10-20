package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Ticket 34: the swim graph, its physics costs, learning and player observation on a small synthetic swim map, and
 * one end-to-end swim with real physics. The map: a sea floor from x -600 to 600 at y 0, with a rock pillar from
 * x -40 to 40 rising to y -300 in the middle (walls on both sides, a flat top). Water up to y -800.
 */
class SwimNavGraphTest {

    private static final int MAP = 990_340_000;

    @TempDir
    Path dir;

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir.resolve("learned"));
        SwimNavObserved.useDirForTest(dir.resolve("observed"));
        NavStuckLog.useForTest(dir.resolve("stuck"), null);
    }

    @AfterEach
    void restore() {
        NavEdgeLearning.useDirForTest(null);
        NavStuckLog.useForTest(null, null);
        SwimNavigator.enabled = true;
    }

    static MapleMap pillarMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        map.setSwim(true);
        map.setMapLineBoundings(-800, 100, -600, 600);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        int[][] fhs = {
                {1, -600, 0, -40, 0, 0, 2},
                {2, -40, 0, -40, -300, 1, 3},   // west wall of the pillar
                {3, -40, -300, 40, -300, 2, 4}, // its top
                {4, 40, -300, 40, 0, 3, 5},     // east wall
                {5, 40, 0, 600, 0, 4, 0},
                {6, -420, -560, -220, -560, 0, 0}, // a thin ledge floating in the water, west of the pillar
        };
        for (int[] f : fhs) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    @Test
    void costsComeFromTheSwimPhysics() {
        SwimNavGraph.SwimSpeeds s = SwimNavGraph.speeds(null, BotMovementProfile.base());
        BotPhysicsEngine.Config c = BotPhysicsEngine.cfg;
        assertEquals(Math.min(c.SWIM_VEL_PXS, c.SWIM_ACCEL_PXS2 / c.SWIM_FRICTION_HZ), s.sideways(), 1e-6);
        assertEquals(c.SWIM_DOWN_MAX_SPEED_PXS, s.sink(), 1e-6);
        // A 1000 px/s burst every 500 ms against drag, gravity and UP thrust: a few hundred px/s up.
        assertTrue(s.rise() > 150 && s.rise() < 600, "rise " + s.rise());
        // Both axes are steered at once: the slower one sets the time.
        assertEquals((int) Math.ceil(1000.0 * 280 / s.sideways()) + 1, SwimNavGraph.swimMs(s, 0, 0, 280, 10));
        assertEquals((int) Math.ceil(1000.0 * 400 / s.sink()) + 1, SwimNavGraph.swimMs(s, 0, 0, 0, 400));
        assertEquals((int) Math.ceil(1000.0 * 400 / s.rise()) + 1, SwimNavGraph.swimMs(s, 0, 400, 0, 0));
    }

    @Test
    void theRiseModelMatchesTheSwimIntegrator() {
        // The integrator itself, in open water: a burst every cooldown, UP held between, for 3 s.
        MapleMap map = pillarMap(MAP + 1);
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setMap(map);
        chr.setPosition(new Point(-400, -100));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.inAir = true;
        entry.swimming = true;
        entry.physX = -400;
        entry.physY = -100;
        int ticks = 3000 / BotPhysicsEngine.cfg.TICK_MS;
        int every = BotPhysicsEngine.cfg.SWIM_JUMP_COOLDOWN_MS / BotPhysicsEngine.cfg.TICK_MS;
        for (int i = 0; i < ticks; i++) {
            entry.swimVerticalHold = -1;
            entry.swimJumpRequested = i % every == 0;
            BotPhysicsEngine.applySwimMotion(entry);
        }
        double measured = (-100 - chr.getPosition().y) / 3.0;
        double model = SwimNavGraph.speeds(map, BotMovementProfile.base()).rise();
        assertTrue(Math.abs(measured - model) / model < 0.15, "integrator " + measured + " px/s vs model " + model);
    }

    @Test
    void theGraphGoesOverThePillarNotThroughIt() {
        MapleMap map = pillarMap(MAP + 2);
        SwimNavGraph graph = SwimNavGraph.build(map, BotMovementProfile.base());
        assertFalse(SwimNavGraph.clearSwim(map, new Point(-200, -100), new Point(200, -100)), "the pillar blocks");
        assertTrue(SwimNavGraph.clearSwim(map, new Point(-200, -400), new Point(200, -400)), "open water above it");

        int start = graph.startNode(map, new Point(-300, 0), true);
        int goal = graph.goalNode(map, new Point(300, 0));
        assertEquals(SwimNavGraph.Kind.FLOOR, graph.kinds[start]);
        assertEquals(SwimNavGraph.Kind.FLOOR, graph.kinds[goal]);
        List<BotNavigationGraph.Edge> path = graph.findPath(start, goal, graph.learnedCosts());
        assertNotNull(path);
        Point prev = graph.point(start);
        boolean tookOff = false;
        for (BotNavigationGraph.Edge e : path) {
            assertTrue(e.type == BotNavigationGraph.EdgeType.SWIM || e.type == BotNavigationGraph.EdgeType.WALK);
            Point p = e.endPoint;
            for (int k = 0; k <= 20; k++) {
                double x = prev.x + (p.x - prev.x) * k / 20.0;
                double y = prev.y + (p.y - prev.y) * k / 20.0;
                assertTrue(Math.abs(x) >= 40 || y < -300, "crosses the pillar above its top: " + prev + " -> " + p);
            }
            tookOff |= graph.kinds[e.fromRegionId] == SwimNavGraph.Kind.FLOOR
                    && graph.kinds[e.toRegionId] == SwimNavGraph.Kind.WATER;
            prev = p;
        }
        assertTrue(tookOff, "leaves the sea floor with a take-off edge");
        assertEquals(SwimNavGraph.Kind.FLOOR, graph.kinds[path.get(path.size() - 1).toRegionId], "and lands");

        // Walking along the floor uses WALK edges.
        int a = graph.startNode(map, new Point(-500, 0), true);
        int b = graph.goalNode(map, new Point(-200, 0));
        for (BotNavigationGraph.Edge e : graph.findPath(a, b, graph.learnedCosts())) {
            assertEquals(BotNavigationGraph.EdgeType.WALK, e.type);
        }
    }

    @Test
    void noWaterNodeHidesUnderALedge() {
        MapleMap map = pillarMap(MAP + 7);
        SwimNavGraph graph = SwimNavGraph.build(map, BotMovementProfile.base());
        for (int i = 0; i < graph.size(); i++) {
            if (graph.kinds[i] == SwimNavGraph.Kind.WATER && graph.xs[i] >= -420 && graph.xs[i] <= -220) {
                int below = graph.ys[i] - -560;
                assertFalse(below > 0 && below <= SwimNavGraph.WATER_HEADROOM_PX, "node " + graph.point(i));
            }
        }
        // The ledge itself is a platform bots can land on and take off from.
        int ledge = graph.goalNode(map, new Point(-300, -560));
        assertEquals(SwimNavGraph.Kind.FLOOR, graph.kinds[ledge]);
        assertNotNull(graph.findPath(graph.startNode(map, new Point(-500, 0), true), ledge, graph.learnedCosts()));
    }

    @Test
    void failuresRaiseASwimEdgesCostAndSurviveARestart() {
        MapleMap map = pillarMap(MAP + 3);
        SwimNavGraph graph = SwimNavGraph.build(map, BotMovementProfile.base());
        int from = graph.nearest(map, new Point(-200, -400), true);
        BotNavigationGraph.Edge edge = graph.outgoing(from).get(0);
        assertEquals(BotNavigationGraph.EdgeType.SWIM, edge.type);
        assertEquals(1.0, NavEdgeLearning.swimFactor(graph, edge));
        NavEdgeLearning.swimSuccess(null, graph, edge);
        assertTrue(graph.learned.isEmpty(), "a make on a never-failed edge writes nothing");
        for (int i = 0; i < 4; i++) {
            NavEdgeLearning.swimFailure(null, graph, edge);
        }
        double factor = NavEdgeLearning.swimFactor(graph, edge);
        assertTrue(factor > 1.0, "factor " + factor);
        double planned = graph.learnedCosts().cost(edge.fromRegionId, edge.toRegionId, edge.cost);
        assertEquals(edge.cost * factor, planned, 0.01);

        // A deliberate wobble is never learned.
        BotNavigationGraph.Edge other = graph.outgoing(from).get(1);
        BotMovementState entry = new BotMovementState(Character.getDefault(new BotClient(0, 0)), null);
        NavEdgeLearning.tagDeliberateFlaw(entry, other);
        NavEdgeLearning.swimFailure(entry, graph, other);
        assertEquals(1.0, NavEdgeLearning.swimFactor(graph, other));

        assertTrue(NavEdgeLearning.flush() >= 1);
        NavEdgeLearning.useDirForTest(dir.resolve("learned")); // a restart
        SwimNavGraph rebuilt = SwimNavGraph.build(map, BotMovementProfile.base());
        BotNavigationGraph.Edge same = rebuilt.edgeBetween(edge.fromRegionId, edge.toRegionId);
        assertEquals(factor, NavEdgeLearning.swimFactor(rebuilt, same), 0.01);
        assertTrue(rebuilt.learned.contains(SwimNavGraph.key(edge.fromRegionId, edge.toRegionId)));
    }

    @Test
    void aPlayersSwimAdoptsAGapAfterTwoSightingsAndConfirmsKnownEdges() {
        MapleMap map = pillarMap(MAP + 4);
        SwimNavGraph graph = SwimNavGraph.getOrBuild(map);
        Point a = new Point(-200, -400);
        Point b = new Point(-120, -320); // two grid steps both ways: not a built neighbour
        int from = graph.nearest(map, a, true);
        int to = graph.nearest(map, b, true);
        assertEquals(a, graph.point(from));
        assertEquals(b, graph.point(to));
        assertNull(graph.edgeBetween(from, to));
        assertNull(SwimNavObserved.observe(map, a, b), "one sighting is not enough");
        BotNavigationGraph.Edge adopted = SwimNavObserved.observe(map, a, b);
        assertNotNull(adopted);
        assertEquals(adopted, graph.edgeBetween(from, to));

        // Known edges: a player's swim pays off what bots failed there.
        BotNavigationGraph.Edge known = graph.edgeBetween(from, graph.nearest(map, new Point(-160, -400), true));
        assertNotNull(known);
        for (int i = 0; i < 2; i++) {
            NavEdgeLearning.swimFailure(null, graph, known);
        }
        double before = NavEdgeLearning.swimFactor(graph, known);
        for (int i = 0; i < 6; i++) {
            SwimNavObserved.observe(map, a, new Point(-160, -400));
        }
        assertTrue(NavEdgeLearning.swimFactor(graph, known) < before);
    }

    @Test
    void aBotSwimsOverThePillarWithRealPhysics() throws Exception {
        assertTrue(swimAcross(MAP + 5, true), "with the swim graph the bot reaches the far side");
    }

    private static boolean swimAcross(int mapId, boolean swimNav) throws Exception {
        SwimNavigator.enabled = swimNav;
        MapleMap map = pillarMap(mapId);
        SwimNavGraph.getOrBuild(map);
        ObserverTracker.markObservedNow(mapId);
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(mapId % 100_000 + 700_000);
        chr.setMap(map);
        chr.setPosition(new Point(-300, 0));
        AtomicBoolean arrived = new AtomicBoolean();
        try {
            GCMovement.enable(chr);
            GCMovement.move(chr, 300, 0, () -> arrived.set(true));
            long end = System.currentTimeMillis() + 15_000;
            while (!arrived.get() && System.currentTimeMillis() < end) {
                ObserverTracker.markObservedNow(mapId);
                Thread.sleep(50);
            }
            return arrived.get();
        } finally {
            GCMovement.disable(chr);
        }
    }
}
