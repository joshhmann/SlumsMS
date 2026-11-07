package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v72 (b356/b360): how far sideways a rope-to-rope hop reaches. Client hopped on the real v83 client (TEST ch3, speed
 * 100 / jump 100, no buffs, 2026-11-07; agent-runs/client/evidence/b360-reach): caught at 95, 98, 107, 108, 108 and
 * 109 px, missed at 117. The calibrated sim (BotPhysicsEngine.clientCatchesRopeHop) must give the same answer on each
 * real pair, from the height the character hung at before the hop (its !pos). Zakum PQ's 280010110 needs the 98 px
 * hop x 359 -> 261: the old rope jump cannot make it, the client kick the executor now flies can.
 */
class RopeHopReachTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    /** One real-client hop: from the rope at fromX, hanging at fromY, toward the rope at toX. */
    record Trial(int mapId, int fromX, int fromY, int toX, boolean caught) {
        int gap() {
            return Math.abs(toX - fromX);
        }
    }

    static final Trial[] TRIALS = {
            new Trial(280_010_110, 454, -159, 359, true),
            new Trial(280_010_110, 359, -88, 261, true),
            new Trial(240_010_901, -644, -110, -751, true),
            new Trial(240_010_901, 806, 9, 914, true),
            new Trial(240_010_901, 1399, -26, 1507, true),
            new Trial(680_000_000, -614, 216, -505, true),
            new Trial(324_010_000, 1347, -57, 1464, false),
    };

    private static Rope rope(MapleMap map, int x) {
        return map.getRopes().stream().filter(r -> r.x() == x).findFirst().orElseThrow();
    }

    private static boolean calibrated(Trial t) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(t.mapId());
        return BotPhysicsEngine.clientCatchesRopeHop(map, new Point(t.fromX(), t.fromY()), t.toX() - t.fromX(),
                rope(map, t.toX()));
    }

    @Test
    void theCalibratedSimGivesTheRealClientsAnswerOnEveryMeasuredHop() {
        WzTestData.assumePresent("Map.wz");
        StringBuilder wrong = new StringBuilder();
        for (Trial t : TRIALS) {
            boolean sim = calibrated(t);
            System.out.println("rope hop " + t.mapId() + " " + t.fromX() + "->" + t.toX() + " (" + t.gap() + " px): client "
                    + (t.caught() ? "caught" : "missed") + ", calibrated sim " + (sim ? "caught" : "missed"));
            if (sim != t.caught()) {
                wrong.append(' ').append(t);
            }
        }
        assertEquals("", wrong.toString(), "calibrated sim disagrees with the real client on:");
    }

    @Test
    void theReachIsTheMeasuredCatchLessTheMargin() {
        int widestCaught = 0;
        int narrowestMissed = Integer.MAX_VALUE;
        for (Trial t : TRIALS) {
            if (t.caught()) {
                widestCaught = Math.max(widestCaught, t.gap());
            } else {
                narrowestMissed = Math.min(narrowestMissed, t.gap());
            }
        }
        assertEquals(widestCaught, BotPhysicsEngine.CLIENT_ROPE_HOP_REACH_PX, "the reach is the widest measured catch");
        assertTrue(BotPhysicsEngine.CLIENT_ROPE_HOP_REACH_PX < narrowestMissed);
        assertEquals(BotPhysicsEngine.CLIENT_ROPE_HOP_REACH_PX - BotPhysicsEngine.ROPE_HOP_MARGIN_PX,
                BotPhysicsEngine.ROPE_HOP_MAX_GAP_PX);
        assertTrue(BotPhysicsEngine.ROPE_HOP_MAX_GAP_PX >= 98, "ZPQ's 98 px hop stays inside the graph's reach");
    }

    @Test
    void theZakumRoomsNinetyEightPixelHopNeedsTheClientKickAndTheExecutorMakesIt() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(280_010_110);
        Rope r261 = rope(map, 261);
        Point hanging = new Point(359, -88);
        assertNull(BotPhysicsEngine.simulateRopeJumpGrab(map, hanging, -BotPhysicsEngine.walkStep(map, BotMovementProfile.base()),
                r261, BotMovementProfile.base()), "the old rope jump (walk speed, 375 up) misses: the b360 gap");
        assertNotNull(BotPhysicsEngine.simulateClientRopeHop(map, hanging, -1, r261), "the executor's client kick catches");
        assertTrue(BotPhysicsEngine.clientCatchesRopeHop(map, hanging, -1, r261), "and so does the real client");
    }
}
