package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 v72 groundwork (b356/b360, the ZPQ rope room): how far sideways a rope-to-rope jump reaches. Zakum PQ's
 * 280010110 is a chain of rope hops; at the rope at x 359 the next is at x 261, 98 px off, and the base graph has no
 * edge for it, so a regular without Haste cannot reach that room's key rock. The model's rope jump flies the walk speed
 * sideways and JUMP_ROPE_PXS up; the client's kick is (162, -277) px/s (BotPhysicsEngine's own note). This measures
 * both on the real rope with the engine's own grab simulation.
 */
class RopeHopReachTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    /** The widest gap (px) a jump off {@code from} at any height grabs a same-height rope, flying vy/step per tick. */
    static int widestGrab(MapleMap map, Rope from, float vyPerTick, int stepX) {
        int widest = 0;
        for (int gap = 40; gap <= 160; gap++) {
            Rope target = new Rope(from.x() - gap, from.y1(), from.y2(), false);
            boolean grabbed = false;
            for (int y = Math.min(from.y1(), from.y2()) + 5; y <= Math.max(from.y1(), from.y2()) && !grabbed; y += 3) {
                grabbed = BotPhysicsEngine.simulateRopeGrab(map, new Point(from.x(), y), vyPerTick, -stepX, target, 0L) != null;
            }
            if (grabbed) {
                widest = gap;
            }
        }
        return widest;
    }

    @Test
    void theClientsKickReachesTheZakumRoomsNinetyEightPixelHopAndTheModelsDoesNot() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(280_010_110);
        Rope r359 = map.getRopes().stream().filter(r -> r.x() == 359).findFirst().orElseThrow();
        Rope r261 = map.getRopes().stream().filter(r -> r.x() == 261).findFirst().orElseThrow();
        int need = r359.x() - r261.x();
        float tick = BotPhysicsEngine.cfg.TICK_MS / 1000f;
        BotMovementProfile base = BotMovementProfile.base();
        int model = widestGrab(map, r359, -BotPhysicsEngine.ropeJumpForcePerTick(base),
                BotPhysicsEngine.walkStep(map, base));
        int client = widestGrab(map, r359, -277f * tick, Math.round(162f * tick));
        System.out.println("rope hop from x 359 (" + r359 + "): need " + need + " px; model reaches " + model
                + " px, client kick (162, -277) reaches " + client + " px");
        assertTrue(model < need, "the model's rope jump does not make the hop (that is the gap): " + model);
        assertTrue(client >= need, "the client's kick does: " + client);
    }
}
