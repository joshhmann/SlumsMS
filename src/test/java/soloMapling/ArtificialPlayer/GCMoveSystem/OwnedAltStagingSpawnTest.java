package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * t-oa-1 (TEST 2026-10-31 07:14, map 104040000): the auto-QA owned-alt probe's staged "owner's mob" must stand on the
 * OWNER's ledge. The probe spawned it on the ground under {@code owner.x + dx}; the owner stood on the 100 px step at
 * y 148 (nav region 21) and the ground eighty px either side is the floor at y 214 (region 23), so the mob landed on
 * another ledge, the alt (which never fights across ledges) never went for it, and the check failed with "did not
 * fight again once its owner was active (gate OPEN, doing: following)". Same class as b082 (the QA drop that landed on
 * the ledge below the alt).
 */
class OwnedAltStagingSpawnTest {

    private static MapleMap field() {
        soloMapling.WzTestData.assumePresent("Map.wz");
        return BotNavigationMapLoader.loadMapGeometry(104040000);
    }

    @Test
    void theStagedMobStandsOnTheOwnersOwnLedge() {
        MapleMap field = field();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(field, BotMovementProfile.base());
        Point owner = new Point(-83, 148);
        int home = graph.findRegionId(field, owner);
        assertEquals(21, home, "the owner's step on Henesys Hunting Ground I");
        Point plain = field.getGroundBelow(new Point(owner.x - 80, owner.y));
        assertNotEquals(home, graph.findRegionId(field, plain),
                "the old owner.x - 80 spawn lands on the floor below, at " + plain + ": the bug this fixes");
        for (int dx : new int[]{-80, 80}) {
            int x = GCMovement.stageMobX(field, owner, dx);
            Point landed = field.getGroundBelow(new Point(x, owner.y));
            assertEquals(home, graph.findRegionId(field, landed),
                    "dx " + dx + ": the mob stands on the owner's own step, at " + landed);
            assertFalse(GCMovement.onDifferentLedge(field, owner.x, owner.y, landed.x, landed.y),
                    "and the alt's own ledge rule lets it fight that mob (dx " + dx + ")");
        }
    }

    /** A ledge with room keeps the side asked for, so the two attempts stay apart. */
    @Test
    void roomOnTheLedgeKeepsThePlaceAsked() {
        MapleMap field = field();
        Point owner = new Point(400, 215); // the long floor of the map
        assertEquals(320, GCMovement.stageMobX(field, owner, -80));
        assertEquals(480, GCMovement.stageMobX(field, owner, 80));
    }
}
