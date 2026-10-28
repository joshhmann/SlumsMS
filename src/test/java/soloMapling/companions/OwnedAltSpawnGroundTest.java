package soloMapling.companions;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnedAltSpawnGroundTest {
    @Test void airborneOwnerPositionSnapsDownToTheFoothold() {
        MapleMap map = mock(MapleMap.class);
        Point air = new Point(100, -300);
        when(map.getGroundBelow(air)).thenReturn(new Point(100, -151));
        assertEquals(new Point(100, -151), OwnedAltService.groundedSpawn(map, air));
    }
    @Test void noFootholdFallsBackToTheOwnerPosition() {
        MapleMap map = mock(MapleMap.class);
        Point p = new Point(5, 5);
        when(map.getGroundBelow(p)).thenReturn(null);
        assertEquals(p, OwnedAltService.groundedSpawn(map, p));
    }
}
