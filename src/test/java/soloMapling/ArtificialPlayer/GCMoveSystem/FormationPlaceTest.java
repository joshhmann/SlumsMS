package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.2 ticket 99: a follower walks to its formation place beside the one it follows, never past the map's edge. */
class FormationPlaceTest {

    @Test
    void besideTheLeaderInsideTheMap() {
        MapleMap map = mock(MapleMap.class);
        when(map.getMapArea()).thenReturn(new Rectangle(-500, -300, 1000, 600));
        Point leader = new Point(100, 20);
        assertSame(leader, GCMovementDriver.formationPlace(leader, 0, map), "no place: on the leader, as before");
        assertEquals(new Point(160, 20), GCMovementDriver.formationPlace(leader, 60, map));
        assertEquals(new Point(490, 20), GCMovementDriver.formationPlace(new Point(450, 20), 120, map));
        assertEquals(new Point(-490, 20), GCMovementDriver.formationPlace(new Point(-450, 20), -120, map));
        assertEquals(new Point(220, 20), GCMovementDriver.formationPlace(leader, 120, null));
    }

    /** t-oa-1 (TEST 2026-10-31): a place past the end of the leader's ledge is pulled back onto it. */
    @Test
    void thePlaceStaysOnTheLeadersLedge() {
        assertEquals(29, GCMovementDriver.onLedge(-20, 40, new int[]{-129, 39}), "right 60 off a step: its edge");
        assertEquals(-119, GCMovementDriver.onLedge(-20, -200, new int[]{-129, 39}));
        assertEquals(160, GCMovementDriver.onLedge(100, 160, new int[]{-39, 1299}), "room on the ledge: as asked");
        assertEquals(5, GCMovementDriver.onLedge(5, 60, new int[]{0, 12}), "too narrow: on the leader");
        assertEquals(160, GCMovementDriver.onLedge(100, 160, null), "no ledge known: as before");
    }

    /**
     * t-oa-1 on the real map: the owner on the small step at (-20,-505) of Henesys Hunting Ground I, its first alt's
     * place (+60) must be on that step, not over the floor below it, where the alt stood and never fought.
     */
    @Test
    void anAltsPlaceBesideItsOwnerOnAStepIsOnTheStep() {
        soloMapling.WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(104040000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        Point owner = new Point(-20, -505);
        int step = graph.findRegionId(map, owner);
        Point place = GCMovementDriver.formationPlace(owner, 60, map, graph);
        assertEquals(step, graph.findRegionId(map, place), "place " + place + " is on the owner's step");
        Point floor = GCMovementDriver.formationPlace(new Point(400, -400), 60, map, graph);
        assertEquals(new Point(460, -400), floor, "room on the ledge: the place as asked");
    }
}
