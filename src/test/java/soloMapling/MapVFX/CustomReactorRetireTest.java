package soloMapling.MapVFX;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Reactor;
import server.maps.ReactorStats;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/*
 * 1.4.2 (perf): a gacha pop's box leaves the map once its spray has landed. Before, every pop left its broken box
 * in the map's objects for good, and every client entering Henesys Market was sent all of them (about 9,000 after a
 * day of uptime).
 */
class CustomReactorRetireTest {

    private static Reactor boxOn(MapleMap map) {
        Reactor box = new Reactor(new ReactorStats(), 2202004);
        box.setPosition(new Point(366, 154));
        box.setMap(map);
        map.addMapObject(box);
        return box;
    }

    @Test
    void aRetiredBoxIsOffTheMapAndNoLongerSpawnsForAnyone() {
        MapleMap map = new MapleMap(100000100, 0, 1, 0, 1f);
        Reactor box = boxOn(map);
        Reactor other = boxOn(map);
        assertSame(box, map.getMapObject(box.getObjectId()));

        CustomReactor.retire(map, box);

        assertNull(map.getMapObject(box.getObjectId()));
        assertFalse(box.isAlive());
        assertSame(other, map.getMapObject(other.getObjectId()), "only the pop's own box goes");
        assertEquals(1, map.getAllReactors().size());

        CustomReactor.retire(map, box); // twice is harmless
        assertEquals(1, map.getAllReactors().size());
    }

    @Test
    void theBoxStaysUntilTheLastSprayedItemHasLanded() {
        // the spray drops one item every 200 ms
        assertEquals(1500, CustomReactor.retireDelayMs(1));
        assertEquals(9 * 200 + 1500, CustomReactor.retireDelayMs(10));
        assertEquals(1500, CustomReactor.retireDelayMs(0));
    }
}
