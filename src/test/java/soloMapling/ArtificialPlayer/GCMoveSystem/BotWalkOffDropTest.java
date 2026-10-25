package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Graph v69: walk-off DROP fixes (ported from nutnnut/Cosmic, AGPL-3.0. Credit: NutNNut):
 *  - f5787ab0c / 8a7e7b3a5: the builder authors a directional walk-off's landing with the same walk-off sim the
 *    executor gate runs (a real dismount leaves the ground up to a walk step PAST the lip, which can land on another
 *    platform than a fall from the exact lip pixel), and the executor gate only asks for a real descent that starts
 *    before the steering point, not the exact authored landing - otherwise the bot is steered back to the runway
 *    anchor every tick and parks there (Henesys department store 100000102).
 *  - 362926fdf: a walk-off is authored only when every live launch state (sub-pixel phase, carry, arrival speed)
 *    lands the same region; knife-edge drops were planner lies the bot replanned through forever (FM 910000000).
 *  - 26b372e7c: the walk-off sim starts on the region's own foothold, not a coordinate lookup that can pick a shelf
 *    up to 26 px overhead; otherwise the drop waypoint flips between two opposite targets inside the region.
 */
class BotWalkOffDropTest {

    private static final int DEPT_STORE = 100000102;
    private static final Point DEPT_STORE_EXIT = new Point(300, 182); // out01 -> Henesys market
    private static final int FREE_MARKET = 910000000;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    private static List<BotNavigationGraph.Edge> walkOffDrops(BotNavigationGraph graph) {
        List<BotNavigationGraph.Edge> drops = new ArrayList<>();
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type == BotNavigationGraph.EdgeType.DROP && edge.launchStepX != 0) {
                    drops.add(edge);
                }
            }
        }
        return drops;
    }

    private static int landedRegion(BotNavigationGraph graph, BotPhysicsEngine.WalkOffLanding walkOff) {
        return walkOff == null || walkOff.landing() == null || walkOff.landing().foothold() == null ? -1
                : graph.regionIdByFootholdId.getOrDefault(walkOff.landing().foothold().getId(), -1);
    }

    private static Foothold sourceFoothold(MapleMap map, BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        return BotPhysicsEngine.findWalkRegionGroundFoothold(map, graph, edge.fromRegionId, edge.startPoint.x,
                edge.startPoint.y);
    }

    /* Every authored walk-off lands where the executor's walk-off sim lands, for every live launch state. */
    private static List<String> lyingDrops(MapleMap map, BotNavigationGraph graph) {
        List<String> lies = new ArrayList<>();
        for (BotNavigationGraph.Edge edge : walkOffDrops(graph)) {
            for (BotPhysicsEngine.WalkOffLanding variant : BotPhysicsEngine.walkOffLandingVariants(map,
                    edge.startPoint, Integer.signum(edge.launchStepX), sourceFoothold(map, graph, edge),
                    BotMovementProfile.base())) {
                int landed = landedRegion(graph, variant);
                if (landed != edge.toRegionId) {
                    lies.add("DROP r" + edge.fromRegionId + "->r" + edge.toRegionId + " from " + edge.startPoint
                            + " lands r" + landed);
                    break;
                }
            }
        }
        return lies;
    }

    private static BotMovementState bot(MapleMap map, Point pos, int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setMap(map);
        chr.setPosition(new Point(pos));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.movementProfile = BotMovementProfile.base();
        entry.physX = pos.x;
        entry.physY = pos.y;
        return entry;
    }

    private static boolean reaches(BotNavigationGraph graph, int from, int to) {
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(from));
        Set<Integer> seen = new java.util.HashSet<>(queue);
        while (!queue.isEmpty()) {
            int r = queue.poll();
            if (r == to) {
                return true;
            }
            for (BotNavigationGraph.Edge e : graph.getOutgoing(r)) {
                if (seen.add(e.toRegionId)) {
                    queue.add(e.toRegionId);
                }
            }
        }
        return false;
    }

    /* GCMovementDriver.stepMovementCore without the LOD, flaw and watchdog layers. */
    private static void step(BotMovementState entry, Point target, int tick) {
        boolean runAiTick = tick % 2 == 0;
        BotNavigationManager.NavigationDirective nav = BotNavigationManager.resolveTarget(entry, target, runAiTick);
        if (nav.consumedTick) {
            return;
        }
        Point steering = nav.targetPos;
        if (entry.climbing) {
            BotMovementManager.tickClimbing(entry, steering, runAiTick);
        } else if (entry.inAir) {
            BotMovementManager.tickAirborne(entry, steering);
        } else {
            BotMovementManager.tickGrounded(entry, steering);
        }
        if (runAiTick && !entry.inAir && !entry.climbing) {
            BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(entry, target);
        }
    }

    // ---- synthetic knife edge (362926fdf) ----

    /* An upper platform whose right walk-off falls past a shelf edge: depending on the launch state the fall clips the
     * shelf or misses it. Swept over shelf edges so one of them sits inside the launch variance band. */
    private static MapleMap knifeEdgeMap(int shelfEdgeX) {
        MapleMap map = new MapleMap(910000044, 0, 0, 910000044, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(new Foothold(new Point(0, 100), new Point(100, 100), 1));
        tree.insert(new Foothold(new Point(shelfEdgeX, 160), new Point(280, 160), 2));
        tree.insert(new Foothold(new Point(-100, 400), new Point(600, 400), 3));
        map.setFootholds(tree);
        return map;
    }

    @Test
    void knifeEdgeWalkOffsAreNotAuthored() {
        List<String> lies = new ArrayList<>();
        for (int edgeX = 104; edgeX <= 200; edgeX += 2) {
            MapleMap map = knifeEdgeMap(edgeX);
            BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
            for (String lie : lyingDrops(map, graph)) {
                lies.add("shelf@" + edgeX + ": " + lie);
            }
        }
        assertTrue(lies.isEmpty(), "walk-off drops whose landing region flips with the launch state: " + lies);
    }

    // ---- real maps ----

    @Test
    void deptStoreWalkOffsLandWhereTheBotReallyLands() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEPT_STORE);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertTrue(!walkOffDrops(graph).isEmpty(), "the store has walk-off drops");
        List<String> lies = lyingDrops(map, graph);
        assertTrue(lies.isEmpty(), "authored walk-offs the bot cannot reproduce: " + lies);
    }

    @Test
    void freeMarketWalkOffsAreLaunchStateStableAndTheGachaLedgeStillReachesThePortal() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(FREE_MARKET);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        List<String> lies = lyingDrops(map, graph);
        assertTrue(lies.isEmpty(), "variant-unstable walk-offs: " + lies);
        int gacha = graph.findRegionId(map, new Point(431, -266));
        int portalLedge = graph.findRegionId(map, new Point(306, -176));
        assertTrue(gacha >= 0 && portalLedge >= 0, "fixture stances resolve");
        assertTrue(reaches(graph, gacha, portalLedge), "pruning the lie must not orphan the gacha ledge");
    }

    /* 26b372e7c: selectDropWaypoint must give one answer anywhere on the source region; two opposite answers is a
     * limit cycle (bots on r14 of the store bounced in a 12 px band forever). */
    @Test
    void deptStoreDropWaypointDoesNotFlipAcrossTheSourceRegion() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEPT_STORE);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        List<String> flips = new ArrayList<>();
        int checked = 0;
        for (BotNavigationGraph.Edge edge : walkOffDrops(graph)) {
            BotNavigationGraph.Region region = graph.getRegion(edge.fromRegionId);
            BotMovementState entry = bot(map, region.pointAt(region.minX), 9000 + checked);
            entry.navGraph = graph;
            Set<String> waypoints = new LinkedHashSet<>();
            for (int x = region.minX; x <= region.maxX; x++) {
                Point stance = region.pointAt(x);
                if (stance.x != x) {
                    continue;
                }
                entry.bot.setPosition(new Point(stance));
                entry.lastRegionId = region.id;
                entry.inAir = false;
                entry.physX = stance.x;
                entry.groundPhysicsCarryMs = 0.0;
                for (double hspeed : new double[]{0.0, 1.0, -1.0}) {
                    entry.hspeed = hspeed;
                    Point wp = BotNavigationManager.selectDropWaypoint(entry, graph, stance, edge);
                    waypoints.add(wp.x + "," + wp.y);
                }
            }
            if (waypoints.size() != 1) {
                flips.add("DROP r" + edge.fromRegionId + "->r" + edge.toRegionId + " steers to " + waypoints);
            }
            checked++;
        }
        assertTrue(checked > 0);
        assertEquals(List.of(), flips, "drop waypoints that switch inside the source region");
    }

    /* Upstream's live symptom: bots that resupplied in the store never left it. From every shelf, spawn point and
     * the shop counter, a bot heading for the exit portal gets there. (Ours already got down before v69 thanks to the
     * runway shortcut v69 removes, so this is the guard that removing it costs nothing.) */
    @Test
    void deptStoreDescentFromEveryShelfReachesTheExit() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEPT_STORE);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        Point[] starts = {
                new Point(-178, 98), new Point(-147, 98), new Point(-117, 98),   // r17 shelf
                new Point(-100, 103), new Point(-30, 103), new Point(42, 103),   // r18 shelf
                new Point(-160, 141), new Point(-114, 141), new Point(-99, 141), // r21 shelf
                new Point(-258, 84),                                             // shop counter
                new Point(-114, 115), new Point(126, 28),                        // spawn portals
        };
        List<String> stuck = new ArrayList<>();
        int id = 7100;
        for (Point start : starts) {
            Point ground = BotPhysicsEngine.findGroundPoint(map, start);
            BotMovementState entry = bot(map, ground != null ? ground : start, id++);
            boolean reached = false;
            for (int i = 0; i < 600 && !reached; i++) {
                step(entry, DEPT_STORE_EXIT, i);
                Point p = entry.bot.getPosition();
                reached = Math.abs(p.x - DEPT_STORE_EXIT.x) <= BotMovementManager.cfg.STOP_DIST
                        && Math.abs(p.y - DEPT_STORE_EXIT.y) <= 6; // our motor stops STOP_DIST short
            }
            if (!reached) {
                Point p = entry.bot.getPosition();
                stuck.add(start.x + "," + start.y + " -> parked at " + p.x + "," + p.y + " (" + entry.lastNavDecision
                        + ", " + entry.lastEdgeBlockReason + ")");
            }
        }
        assertEquals(List.of(), stuck, "bots that never reached the exit portal");
    }

    /* Upstream 8a7e7b3a5 also widened a jump's launch acceptance to one walk step on either side of a narrow window.
     * Not taken: the store's vertical JUMP r21->r18 has the window [-100,-99] at r18's very edge, and a launch 3-4 px
     * outside it misses the shelf - a quarter of the bots from the lower spawn point then hung at (-103,94). The 16 px
     * drift radius on the blocked-position watchdog (also 8a7e7b3a5) covers the bounce that widening was for. */
    @Test
    void deptStoreSpawnPointDescentIsStableAcrossRuns() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEPT_STORE);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        List<String> stuck = new ArrayList<>();
        for (int rep = 0; rep < 40; rep++) {
            Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(-114, 115));
            BotMovementState entry = bot(map, ground, 8000 + rep);
            boolean reached = false;
            for (int i = 0; i < 600 && !reached; i++) {
                step(entry, DEPT_STORE_EXIT, i);
                if (rep % 3 == 0 && i % 7 == 0) {
                    try {
                        Thread.sleep(1); // vary the wall-clock timers the motor reads
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Point p = entry.bot.getPosition();
                reached = Math.abs(p.x - DEPT_STORE_EXIT.x) <= BotMovementManager.cfg.STOP_DIST
                        && Math.abs(p.y - DEPT_STORE_EXIT.y) <= 6;
            }
            if (!reached) {
                stuck.add("run " + rep + " parked at " + entry.bot.getPosition());
            }
        }
        assertEquals(List.of(), stuck);
    }
}
