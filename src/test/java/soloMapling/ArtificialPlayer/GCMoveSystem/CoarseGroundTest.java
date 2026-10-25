package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.GenericPortal;
import server.maps.MapleMap;
import soloMapling.autoqa.FootholdCheck;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.0 rc5 auto-QA foothold FAIL (sweeps of 2026-10-22 22:11 and 22:16 UTC). The geometry is the servers' Map.wz;
 * the points are flagged bots of those sweeps.
 */
class CoarseGroundTest {

    // Victoria Road 104010001 around x 149: the floor (y 155), the platform above (y -325), and the rope at x 143
    // (y -182..95). The baked CLIMB edge (143,-43)>(226,155) is the rope jump-off to the floor.
    private static final int[][] VICTORIA = {
            {47, 90, 155, 129, 155}, {51, 129, 155, 180, 155}, {64, 180, 155, 270, 155}, {102, 141, -325, 219, -325},
    };

    // El Nath 211030000's left edge: the VR starts at x -908 (miniMap centerX 908, centerY 1195), the footholds at
    // -850: the floor (y 34) and a ledge (y -277) further in.
    private static final int[][] EL_NATH = {
            {95, -850, 34, -770, 34}, {96, -770, 34, -600, 34}, {59, -618, -277, -469, -277},
    };

    private static MapleMap map(int id, int[][] footholds) {
        MapleMap map = new MapleMap(id, 0, 0, id, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : footholds) {
            tree.insert(new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]));
        }
        map.setFootholds(tree);
        return map;
    }

    private static List<FootholdCheck.Seg> segs(int[][] footholds) {
        List<FootholdCheck.Seg> out = new ArrayList<>();
        for (int[] f : footholds) {
            out.add(new FootholdCheck.Seg(f[1], f[2], f[3], f[4]));
        }
        return out;
    }

    private static MovementPlan ropeJumpOff() {
        BotNavigationGraph.Edge climb = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.CLIMB,
                new Point(143, -43), new Point(226, 155), 1, -1, 143, -182, 95, 1000);
        return MovementPlan.inMap(104010001, List.of(climb));
    }

    @Test
    void aCoarseTripIsInTheAirMidEdgeAndTheSweepCallsItCoarse() {
        MovementPlan plan = ropeJumpOff();
        Point mid = plan.positionAt(72); // 7.2% along: xxwizludixx and clockhunter's (149, -29)
        assertEquals(new Point(149, -29), mid);
        assertEquals(FootholdCheck.Kind.FLOATING,
                FootholdCheck.check(segs(VICTORIA), mid.x, mid.y, FootholdCheck.Pose.GROUNDED).kind(),
                "checked as standing, the interpolated point is 184 px over the floor");

        BotMovementState entry = new BotMovementState(Character.getDefault(new BotClient(0, 0)), null);
        assertEquals("GROUNDED", GCMovement.sweepPose(entry));
        entry.coarsePlan = plan;
        assertEquals("COARSE", GCMovement.sweepPose(entry));
        assertEquals(FootholdCheck.Kind.SKIPPED,
                FootholdCheck.check(segs(VICTORIA), mid.x, mid.y, FootholdCheck.Pose.valueOf("COARSE")).kind());
        entry.climbing = true;
        assertEquals("CLIMBING", GCMovement.sweepPose(entry), "physics poses still win");
        assertNull(GCMovement.sweepPose((BotMovementState) null));
    }

    @Test
    void aCoarseTripWhoseGoalWentAwayLandsOnTheFloorInsteadOfHangingInTheAir() {
        MapleMap map = map(104010001, VICTORIA);
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setMap(map);
        bot.setPosition(new Point(149, -29));
        BotMovementState entry = new BotMovementState(bot, null);
        entry.coarsePlan = ropeJumpOff();
        entry.coarseActive = true;

        GCMovementDriver.landAbandonedCoarseTrip(entry, bot);

        assertEquals(new Point(149, 155), bot.getPosition());
        assertNull(entry.coarsePlan);
        assertEquals("GROUNDED", GCMovement.sweepPose(entry));

        // No trip in flight: nothing moves.
        bot.setPosition(new Point(200, 155));
        GCMovementDriver.landAbandonedCoarseTrip(entry, bot);
        assertEquals(new Point(200, 155), bot.getPosition());
    }

    @Test
    void aBotThatFellOffElNathsLeftEdgeIsPutOnTheFloorNotInTheSky() {
        MapleMap map = map(211030000, EL_NATH);
        map.setMapPointBoundings(-908, -1195, 1515, 3005);
        // FELL_OFF_MAP at (-908, 737) with no goal: the old recovery clamped x to the VR (-908), found no foothold
        // there and parked the bot at the VR's top-left corner (-908, -1195), where the sweep found two bots.
        Point ground = GCMovementDriver.recoveryGround(map, new Point(-908, 737), null);
        assertEquals(new Point(-850, 34), ground);
        assertEquals(FootholdCheck.Kind.OK,
                FootholdCheck.check(segs(EL_NATH), ground.x, ground.y, FootholdCheck.Pose.GROUNDED).kind());

        // Off the side by more than the slack, too.
        assertEquals(new Point(-850, 34), GCMovementDriver.recoveryGround(map, new Point(-1400, 20), null));
        // Inside the footholds' x range: the top floor at that x.
        assertEquals(new Point(-500, -277), GCMovementDriver.recoveryGround(map, new Point(-500, 900), null));
        // A goal on the ground still wins.
        assertEquals(new Point(-700, 34), GCMovementDriver.recoveryGround(map, new Point(-908, 737), new Point(-700, 20)));
    }

    @Test
    void withNoFloorAtTheClampedXTheBotGoesToTheSpawnPoint() {
        // Two platforms with a gap (x -400..400) under which the bot fell.
        MapleMap map = map(910000998, new int[][]{{1, -1000, 100, -400, 100}, {2, 400, 100, 1000, 100}});
        GenericPortal sp = new GenericPortal(0); // a spawn point ("sp", pt 0)
        sp.setPosition(new Point(700, 60));
        sp.setId(0);
        map.addPortal(sp);
        Point ground = GCMovementDriver.recoveryGround(map, new Point(0, 2000), null);
        assertEquals(new Point(700, 100), ground, "the floor under the spawn point");
    }

    // Henesys 100000000 around x 0 (Map.wz): the floor at y 94 (x -128..38, a wall under its right end), the
    // platform above it at y 34 (x -38..128, a wall under its left end), and the lower floors at y 163, 218 and 274.
    private static final int[][] HENESYS = {
            {25, -128, 94, -90, 94}, {28, -90, 94, -52, 94}, {30, -52, 94, -38, 94}, {33, -38, 94, 0, 94},
            {34, 0, 94, 38, 94}, {37, 38, 94, 38, 120}, {32, -38, 60, -38, 34}, {31, -38, 34, 38, 34},
            {36, 38, 34, 52, 34}, {38, 52, 34, 128, 34}, {27, -105, 163, 6, 163}, {22, -158, 218, 41, 218},
            {21, -180, 274, -90, 274}, {29, -90, 274, 0, 274}, {35, 0, 274, 90, 274}, {39, 90, 274, 180, 274},
    };

    /*
     * 1.6.0 foothold regression (TEST sweep 2026-10-25 23:25 UTC): Siher at (-73, 34), 60 px over the y 94 floor, and
     * Sa5ukeDark at (41, 94), 60 px under the y 34 platform, both idle in Henesys with nobody watching. Each is the
     * height of the ledge it stood on, a few px past that ledge's end: the idle fidget's wander target (anchor x
     * +-45, anchor y), on which the coarse (unobserved) arrival put the bot as is.
     */
    @Test
    void anUnobservedArrivalPastALedgesEndLandsOnTheFloorBelowInsteadOfStandingInTheAir() {
        MapleMap map = map(100000000, HENESYS);
        List<FootholdCheck.Seg> segs = segs(HENESYS);
        assertEquals(FootholdCheck.Kind.FLOATING,
                FootholdCheck.check(segs, -73, 34, FootholdCheck.Pose.GROUNDED).kind(), "the old arrival point");
        assertEquals(FootholdCheck.Kind.SUNK,
                FootholdCheck.check(segs, 41, 94, FootholdCheck.Pose.GROUNDED).kind(), "the old arrival point");

        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setMap(map);
        BotMovementState entry = new BotMovementState(bot, null);

        bot.setPosition(new Point(-28, 34)); // on the y 34 platform, 10 px from its left end
        entry.moveTarget = new Point(-73, 34);
        GCMovementDriver.arriveCoarse(entry, bot, new Point(-73, 34));
        assertEquals(new Point(-73, 94), bot.getPosition(), "stepped off the platform onto the floor under it");
        assertNull(entry.moveTarget);

        bot.setPosition(new Point(30, 94)); // on the y 94 floor, 8 px from its right end
        GCMovementDriver.arriveCoarse(entry, bot, new Point(41, 94));
        assertEquals(new Point(41, 218), bot.getPosition(), "walked off the floor's end and dropped to y 218");

        for (Point p : List.of(new Point(-73, 94), new Point(41, 218))) {
            assertEquals(FootholdCheck.Kind.OK, FootholdCheck.check(segs, p.x, p.y, FootholdCheck.Pose.GROUNDED).kind());
        }
    }

    @Test
    void anArrivalOnTheGroundStaysWhereItWasSent() {
        MapleMap map = map(100000000, HENESYS);
        assertEquals(new Point(0, 34), GCMovementDriver.coarseArrivalPoint(map, new Point(0, 34), null));
        assertEquals(new Point(0, 94), GCMovementDriver.coarseArrivalPoint(map, new Point(0, 94), null),
                "the floor under the platform, not the platform");
        assertEquals(new Point(-60, 94), GCMovementDriver.coarseArrivalPoint(map, new Point(-60, 90), null),
                "a few px over the floor: on it");
        assertEquals(new Point(-60, 94), GCMovementDriver.coarseArrivalPoint(map, new Point(-60, 99), null),
                "a few px inside the floor: on it");
    }

    @Test
    void anArrivalWithNoFloorUnderTheTargetStaysOnTheGroundItHad() {
        MapleMap map = map(100000000, HENESYS);
        // x 500: no foothold at all. The bot stands on the y 34 platform.
        assertEquals(new Point(100, 34), GCMovementDriver.coarseArrivalPoint(map, new Point(500, 34), new Point(100, 34)));
    }

    @Test
    void aSwimMapKeepsTheArrivalPoint() {
        MapleMap map = map(230000003, HENESYS);
        map.setSwim(true);
        assertEquals(new Point(-73, 34), GCMovementDriver.coarseArrivalPoint(map, new Point(-73, 34), null));
    }

    @Test
    void theIdleWanderStaysOnTheAnchorsLedge() {
        MapleMap map = map(100000000, HENESYS);
        Point anchor = new Point(-28, 34); // 10 px from the y 34 platform's left end
        assertNull(GCFidget.wanderTarget(map, anchor, -45), "past the end: no wander (it was (-73, 34), in the air)");
        assertEquals(new Point(2, 34), GCFidget.wanderTarget(map, anchor, 30));
        Point floor = new Point(30, 94); // 8 px from the y 94 floor's right end
        assertNull(GCFidget.wanderTarget(map, floor, 11), "past the end: no wander (it was (41, 94), under the platform)");
        assertEquals(new Point(-10, 94), GCFidget.wanderTarget(map, floor, -40));

        // A slope is the same ledge: the wander follows it.
        MapleMap hill = map(910000997, new int[][]{{1, 0, 100, 100, 80}});
        Point onHill = GCFidget.wanderTarget(hill, new Point(10, 98), 40);
        assertTrue(onHill != null && onHill.x == 50 && Math.abs(onHill.y - 90) <= 1, "on the slope: " + onHill);
    }
}
