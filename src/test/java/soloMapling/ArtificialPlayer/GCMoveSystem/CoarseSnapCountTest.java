package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b370 (1.6.6): an unobserved regular whose goal has no route is put on the goal (GCMovementDriver's coarse branch);
 * that snap is now counted in the stuck log, and nothing else changes. The case is the b344 proof run's: 280010110
 * without v72's hop edges, a regular at east00, the 11-1 key chest 2112004 as its goal. See
 * docs/bots/coarse-unplannable-snap.md.
 */
class CoarseSnapCountTest {
    static final int ZPQ_ROPE_ROOM = RopeHopGraphTest.ZPQ_ROPE_ROOM;
    static final Point EAST00 = new Point(1812, 135);

    @TempDir
    Path dir;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @AfterEach
    void reset() {
        BotNavigationGraphProvider.clientKickRopeHops = true;
        NavStuckLog.useForTest(null, null);
    }

    private static BotNavigationGraph v71(MapleMap map) {
        BotNavigationGraphProvider.clientKickRopeHops = false;
        try {
            return BotNavigationGraphProvider.buildUncachedGraph(map, BotMovementProfile.base());
        } finally {
            BotNavigationGraphProvider.clientKickRopeHops = true;
        }
    }

    private static BotMovementState regularAt(MapleMap map, Point at) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(7700);
        chr.setMap(map);
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(at.x, at.y - 1));
        chr.setPosition(ground != null ? ground : at);
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.movementProfile = BotMovementProfile.base();
        entry.moveTargetSource = "gcmove";
        entry.moveTargetCaller = "StepExecutor.moveNear";
        return entry;
    }

    private List<String> snaps() {
        NavStuckLog.flush();
        List<String> out = new ArrayList<>();
        for (NavStuckLog.Spot sp : NavStuckLog.read(NavStuckLog.fileFor(dir, LocalDate.now(ZoneOffset.UTC)))) {
            if (sp.kind == NavStuckLog.Kind.COARSE_SNAP) {
                out.add(sp.mapId + " " + sp.edge + " x" + sp.count);
            }
        }
        return out;
    }

    @Test
    void aSnapOntoAGoalWithNoRouteIsCountedAndTheBotStillArrives() {
        WzTestData.assumePresent("Map.wz");
        NavStuckLog.useForTest(dir, null);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ZPQ_ROPE_ROOM);
        BotNavigationGraph graph = v71(map);
        BotMovementState entry = regularAt(map, EAST00);
        Character bot = entry.bot;
        Point goal = RopeHopGraphTest.REACTOR_2112004;
        assertEquals(null, MovementPlan.inMap(graph, map, bot.getPosition(), goal), "v71: no route from east00");

        assertTrue(GCMovementDriver.coarseAdvanceOn(entry, bot, goal, graph, false));

        assertTrue(Math.abs(bot.getPosition().x - goal.x) <= 60, "behaviour unchanged: still snapped onto the goal, "
                + bot.getPosition());
        assertEquals(List.of(ZPQ_ROPE_ROOM + " COARSE_SNAP no-path StepExecutor.moveNear x1"), snaps());
    }

    @Test
    void anArrivalInsideTheGoalsRegionIsNotASnap() {
        WzTestData.assumePresent("Map.wz");
        NavStuckLog.useForTest(dir, null);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ZPQ_ROPE_ROOM);
        BotNavigationGraph graph = v71(map);
        BotMovementState entry = regularAt(map, EAST00);
        Character bot = entry.bot;
        Point near = new Point(bot.getPosition().x - 80, bot.getPosition().y);

        GCMovementDriver.coarseAdvanceOn(entry, bot, near, graph, false);

        assertEquals(List.of(), snaps());
    }

    @Test
    void theCallerIsWhoeverCalledGcMovement() {
        assertEquals("CoarseSnapCountTest.theCallerIsWhoeverCalledGcMovement", GCMovement.callerLabel());
    }
}
