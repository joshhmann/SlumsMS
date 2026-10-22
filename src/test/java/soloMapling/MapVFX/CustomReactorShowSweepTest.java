package soloMapling.MapVFX;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;
import server.maps.ReactorStats;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/* 1.5.0: a show bot's gacha pop never enters the economy: what it sprayed vanishes before anyone else may pick it up. */
class CustomReactorShowSweepTest {

    static Reactor box(MapleMap map) {
        Reactor box = new Reactor(new ReactorStats(), 2202004);
        box.setPosition(new Point(366, 154));
        box.setMap(map);
        map.addMapObject(box);
        return box;
    }

    static MapItem drop(MapleMap map, Object dropper, boolean conjured) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(7);
        when(owner.getPartyId()).thenReturn(-1);
        MapItem item = new MapItem(new Item(1082223, (short) 0, (short) 1), new Point(400, 154),
                (server.maps.MapObject) dropper, owner, null, (byte) 0, false);
        item.setConjured(conjured);
        map.addMapObject(item);
        return item;
    }

    @Test
    void theSweepRemovesOnlyTheBoxsConjuredDrops() {
        MapleMap map = new MapleMap(100000100, 0, 1, 0, 1f);
        Reactor box = box(map), other = box(map);
        MapItem prize = drop(map, box, true), filler = drop(map, box, true);
        MapItem realDrop = drop(map, box, false), otherBox = drop(map, other, true);

        assertEquals(2, CustomReactor.sweepShowDrops(map, box));

        assertNull(map.getMapObject(prize.getObjectId()));
        assertNull(map.getMapObject(filler.getObjectId()));
        assertTrue(prize.isPickedUp());
        assertSame(realDrop, map.getMapObject(realDrop.getObjectId()), "a real drop is never swept");
        assertSame(otherBox, map.getMapObject(otherBox.getObjectId()), "another pop's spray waits for its own sweep");
        assertEquals(0, CustomReactor.sweepShowDrops(map, box), "twice is harmless");
    }

    @Test
    void theSweepComesBeforeOwnerProtectionEnds() {
        assertTrue(CustomReactor.SHOW_SWEEP_MS < 15_000, "MapItem ownership lasts 15 s");
        assertTrue(CustomReactor.SHOW_SWEEP_MS > CustomReactor.retireDelayMs(20), "after the spray has landed");
    }
}
