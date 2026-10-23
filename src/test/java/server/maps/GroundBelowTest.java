package server.maps;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 1.5.0 rc6 bug hunt (P2): getGroundBelow dereferenced a null point when nothing was below, so addPlayer's pet loop
 * threw for a character standing off the ground and the entry stopped before viewers saw it spawn.
 */
class GroundBelowTest {

    private static MapleMap mapWithFloorAt(int y) {
        MapleMap map = new MapleMap(910000998, 0, 0, 910000998, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        if (y != Integer.MIN_VALUE) {
            tree.insert(new Foothold(new Point(-400, y), new Point(400, y), 1));
        }
        map.setFootholds(tree);
        return map;
    }

    @Test
    void groundIsJustAboveTheFootholdBelow() {
        assertEquals(new Point(10, 399), mapWithFloorAt(400).getGroundBelow(new Point(10, 300)));
    }

    @Test
    void nothingBelowFallsBackToTheGivenPoint() {
        Point pos = new Point(10, 500);
        Point ground = mapWithFloorAt(400).getGroundBelow(pos);
        assertEquals(pos, ground, "below the only floor: stand where the character is");
        assertNotSame(pos, ground, "a copy, so a pet's position never aliases its owner's");
        assertEquals(new Point(3, 4), mapWithFloorAt(Integer.MIN_VALUE).getGroundBelow(new Point(3, 4)));
    }
}
