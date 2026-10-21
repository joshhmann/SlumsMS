package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.1, "bots tend to get stuck on top of ladders". Real map data: Wild Boar Land (107000400) has a rope whose
 * step-off foothold sits just below the rope head, the shape that parked bots there for good. Skipped without wz/.
 */
class RopeTopStallTest {

    private static final int MAP = 107000400;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    private record Setup(MapleMap map, BotNavigationGraph graph, Rope rope, Point landing, BotNavigationGraph.Edge stepOff,
                         BotMovementState entry) {
    }

    private static Setup onRopeHead() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertNotNull(graph);
        for (Rope rope : map.getRopes()) {
            Point landing = BotPhysicsEngine.findTopExitLanding(map, rope);
            if (landing == null || landing.y <= rope.topY()) {
                continue;
            }
            int ropeRegion = graph.findRopeRegionId(new Point(rope.x(), BotPhysicsEngine.firstClimbableY(rope)));
            for (BotNavigationGraph.Edge e : graph.getOutgoing(ropeRegion)) {
                if (e.type == BotNavigationGraph.EdgeType.CLIMB && e.launchStepX == 0 && e.startPoint.y == rope.topY()) {
                    Character chr = Character.getDefault(new BotClient(0, 0));
                    chr.setID(424242);
                    chr.setMap(map);
                    BotMovementState entry = new BotMovementState(chr, null);
                    entry.flaws.off = true;
                    BotPhysicsEngine.attachToRope(entry, chr, rope, BotPhysicsEngine.firstClimbableY(rope));
                    return new Setup(map, graph, rope, landing, e, entry);
                }
            }
        }
        throw new AssertionError("no rope with a step-off below its head on " + MAP);
    }

    @Test
    void steeringAtTheLandingWouldHangTheBotOnTheRopeHead() {
        Setup s = onRopeHead();
        // The trap: the landing is at or below the bot's climb height, so dy <= 0 and it never climbs over the head.
        assertTrue(s.landing().y >= s.entry().bot.getPosition().y);
        for (int i = 0; i < 20; i++) {
            BotMovementManager.tickClimbing(s.entry(), new Point(s.rope().x(), s.landing().y), true);
        }
        assertTrue(s.entry().climbing, "still hanging at the top");
    }

    @Test
    void theTopStepOffWaypointAimsAboveTheHeadSoTheBotClimbsOff() {
        Setup s = onRopeHead();
        BotMovementState entry = s.entry();
        entry.navEdge = s.stepOff();
        Point wp = BotNavigationManager.selectClimbWaypoint(s.graph(), entry, entry.bot.getPosition(), s.stepOff());
        assertTrue(wp.y < s.rope().topY(), "waypoint above the head: " + wp);
        for (int i = 0; i < 5 && entry.climbing; i++) {
            BotMovementManager.tickClimbing(entry, wp, true);
        }
        assertFalse(entry.climbing, "stepped off onto the platform");
        assertEquals(s.landing(), entry.bot.getPosition());
    }

    @Test
    void aHungBotWithSomewhereToGoClimbsOffAfterTheStallTime() {
        Setup s = onRopeHead();
        BotMovementState entry = s.entry();
        entry.moveTarget = new Point(s.landing().x + 150, s.landing().y);
        for (int i = 0; i < GCMovementDriver.ROPE_STALL_MS / BotPhysicsEngine.cfg.TICK_MS - 2; i++) {
            GCMovementDriver.tickRopeStallWatchdog(entry);
        }
        assertTrue(entry.climbing, "a short hold is left alone");
        for (int i = 0; i < 4; i++) {
            GCMovementDriver.tickRopeStallWatchdog(entry);
        }
        assertFalse(entry.climbing, "unhung");
        assertFalse(entry.inAir, "at the head it steps off onto the platform");
        assertEquals(s.landing(), entry.bot.getPosition());
    }

    @Test
    void midRopeTheWatchdogJumpsOffTowardTheGoal() {
        Setup s = onRopeHead();
        BotMovementState entry = s.entry();
        int midY = (s.rope().topY() + s.rope().bottomY()) / 2;
        BotPhysicsEngine.attachToRope(entry, entry.bot, s.rope(), midY);
        entry.moveTarget = new Point(s.rope().x() + 300, s.rope().bottomY());
        for (int i = 0; i < GCMovementDriver.ROPE_STALL_MS / BotPhysicsEngine.cfg.TICK_MS + 2; i++) {
            GCMovementDriver.tickRopeStallWatchdog(entry);
        }
        assertFalse(entry.climbing);
        assertTrue(entry.inAir, "jumped off");
    }

    @Test
    void deliberateHangsAreLeftAlone() {
        Setup s = onRopeHead();
        BotMovementState entry = s.entry();
        int ticks = GCMovementDriver.ROPE_STALL_MS / BotPhysicsEngine.cfg.TICK_MS * 2;
        for (int i = 0; i < ticks; i++) {
            GCMovementDriver.tickRopeStallWatchdog(entry); // nowhere to go: hanging is the plan
        }
        assertTrue(entry.climbing);
        entry.moveTarget = new Point(s.landing().x + 150, s.landing().y);
        entry.resting = true;
        for (int i = 0; i < ticks; i++) {
            GCMovementDriver.tickRopeStallWatchdog(entry);
        }
        assertTrue(entry.climbing, "a rest hang stays");
    }

    @Test
    void aGroundTargetBesideTheHeadIsNotReachedWhileStillOnTheRope() {
        Setup s = onRopeHead();
        BotMovementState entry = s.entry();
        assertTrue(GCMovementDriver.hangingShortOfGroundTarget(entry, s.landing()), "the step-off foothold");
        assertTrue(GCMovementDriver.hangingShortOfGroundTarget(entry, new Point(s.landing().x + 25, s.landing().y)));
        int midY = (s.rope().topY() + s.rope().bottomY()) / 2;
        assertFalse(GCMovementDriver.hangingShortOfGroundTarget(entry, new Point(s.rope().x(), midY)), "on the rope");
        entry.resting = true;
        assertFalse(GCMovementDriver.hangingShortOfGroundTarget(entry, s.landing()));
    }
}
