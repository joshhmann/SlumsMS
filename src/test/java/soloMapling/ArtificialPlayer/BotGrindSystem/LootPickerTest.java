package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.2: the one drop picker population bots and owned alts share (and b082's ledge rule). */
class LootPickerTest {

    static final long NOW = 80_000_000L;
    static final long OLD = NOW - 10_000;

    static MapItem drop(int ownerId, int x, int y, long at) {
        MapItem d = mock(MapItem.class);
        when(d.getOwnerId()).thenReturn(ownerId);
        when(d.getPosition()).thenReturn(new Point(x, y));
        when(d.getDropTime()).thenReturn(at);
        return d;
    }

    static Character picker(List<MapItem> drops) {
        MapleMap map = mock(MapleMap.class);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), anyList()))
                .thenReturn(new ArrayList<MapObject>(drops));
        Character c = mock(Character.class);
        when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new Point(0, 0));
        return c;
    }

    static LootPicker.Reach reach() {
        return new LootPicker.Reach(400, -350, 350, LootPicker.SAME_LEDGE_DY, LootPicker.SETTLE_MS);
    }

    @Test
    void aDropOnAnotherLedgeIsNeverPickedB082() {
        // b082 (1.6.2 staging): the shell fell to the ledge below the alt (150 px down) and was left; that is right
        MapItem below = drop(1, 30, 150, OLD);
        MapItem level = drop(1, 300, 40, OLD);
        Character c = picker(List.of(below, level));
        assertSame(level, LootPicker.nearest(c, reach(), NOW, d -> true), "the farther one on its own ledge");
        assertNull(LootPicker.nearest(picker(List.of(below)), reach(), NOW, d -> true));
        assertFalse(LootPicker.onItsLedge(new Point(0, 0), new Point(30, 150), LootPicker.SAME_LEDGE_DY));
        assertTrue(LootPicker.onItsLedge(new Point(0, 0), new Point(30, 120), LootPicker.SAME_LEDGE_DY));
    }

    @Test
    void settledInsideTheBoundsStillThereAndEligible() {
        MapItem fresh = drop(1, 10, 0, NOW - LootPicker.SETTLE_MS + 1);
        MapItem outside = drop(1, 360, 0, OLD);
        MapItem taken = drop(1, 15, 0, OLD);
        when(taken.isPickedUp()).thenReturn(true);
        MapItem notMine = drop(9, 20, 0, OLD);
        MapItem mine = drop(1, 200, 0, OLD);
        Character c = picker(List.of(fresh, outside, taken, notMine, mine));
        assertSame(mine, LootPicker.nearest(c, reach(), NOW, d -> d.getOwnerId() == 1));
        assertEquals(List.of(mine), LootPicker.all(c, reach(), NOW, d -> d.getOwnerId() == 1));
    }

    @Test
    void theCallersRuleIsAskedAboutEveryDropInReachNotJustTheNearest() {
        // an owned alt notes each bag it has no room in from this rule, so a nearer drop must not hide a farther one
        MapItem near = drop(1, 10, 0, OLD), far = drop(1, 300, 0, OLD);
        Set<MapItem> asked = new HashSet<>();
        LootPicker.nearest(picker(List.of(near, far)), reach(), NOW, d -> asked.add(d));
        assertEquals(Set.of(near, far), asked);
    }

    @Test
    void ownershipOneRuleWithOrWithoutAWindow() {
        MapItem young = drop(9, 0, 0, NOW - 1_000), old = drop(9, 0, 0, NOW - 20_000);
        assertFalse(LootPicker.claimable(young, id -> id == 1, 15_000, NOW));
        assertTrue(LootPicker.claimable(old, id -> id == 1, 15_000, NOW), "the window ran out (bots, pets)");
        assertFalse(LootPicker.claimable(old, id -> id == 1, -1, NOW), "no window (owned alts): never a stranger's");
        assertTrue(LootPicker.claimable(young, id -> id == 9, -1, NOW));
    }

    @Test
    void atFeetIsTheBotsPickupReach() {
        assertTrue(LootPicker.atFeet(new Point(0, 0), new Point(LootPicker.PICKUP_PX, 0)));
        assertFalse(LootPicker.atFeet(new Point(0, 0), new Point(50, 40)), "a 64 px diagonal");
    }
}
