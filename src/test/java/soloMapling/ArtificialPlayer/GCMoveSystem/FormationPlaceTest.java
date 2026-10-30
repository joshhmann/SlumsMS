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
}
