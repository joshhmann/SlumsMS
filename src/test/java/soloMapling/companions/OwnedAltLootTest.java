package soloMapling.companions;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
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

/**
 * 1.6.1 ticket 13: an owned alt picks up its own and its party's drops by the loot rules - never a stranger's, never an
 * item a player dropped - and leaves what its bag has no room for (and says so once).
 */
class OwnedAltLootTest {

    static final long NOW = 70_000_000L;
    static final int OWNER = 1, ALT = 2, STRANGER = 3;
    static final Set<Integer> PARTY = Set.of(OWNER, ALT);

    @BeforeEach
    void roomy() {
        OwnedAltLoot.room = (alt, drop) -> true; // item data needs WZ
    }

    @AfterEach
    void realRoom() {
        OwnedAltLoot.room = OwnedAltLoot.REAL_ROOM;
    }

    static MapItem drop(int ownerId, int x, int itemId, long droppedAt) {
        MapItem d = mock(MapItem.class);
        when(d.getOwnerId()).thenReturn(ownerId);
        when(d.getPosition()).thenReturn(new Point(x, 0));
        when(d.getDropTime()).thenReturn(droppedAt);
        Item item = new Item(itemId, (short) 0, (short) 1);
        when(d.getItem()).thenReturn(item);
        when(d.getItemId()).thenReturn(itemId);
        return d;
    }

    static Character alt(MapleMap map, int x) {
        Character a = mock(Character.class);
        when(a.getId()).thenReturn(ALT);
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.getMap()).thenReturn(map);
        when(a.getPosition()).thenReturn(new Point(x, 0));
        return a;
    }

    @Test
    void itsOwnAndItsPartysDropsOnly() {
        Character alt = alt(mock(MapleMap.class), 0);
        long settled = NOW - OwnedAltLoot.SETTLE_MS - 1;
        assertTrue(OwnedAltLoot.ours(alt, drop(ALT, 0, 4000000, settled), PARTY, NOW), "its own kill's drop");
        assertTrue(OwnedAltLoot.ours(alt, drop(OWNER, 0, 4000000, settled), PARTY, NOW), "its party's");
        assertFalse(OwnedAltLoot.ours(alt, drop(STRANGER, 0, 4000000, NOW - 600_000), PARTY, NOW),
                "a stranger's, even long after its ownership window");
        assertFalse(OwnedAltLoot.ours(alt, drop(0, 0, 4000000, settled), PARTY, NOW), "nobody's (a reactor's)");
        assertFalse(OwnedAltLoot.ours(alt, drop(ALT, 0, 4000000, NOW - 100), PARTY, NOW), "still settling");
        MapItem thrown = drop(OWNER, 0, 4000000, settled);
        when(thrown.isPlayerDrop()).thenReturn(true);
        assertFalse(OwnedAltLoot.ours(alt, thrown, PARTY, NOW), "an item a player dropped on purpose");
        MapItem gone = drop(ALT, 0, 4000000, settled);
        when(gone.isPickedUp()).thenReturn(true);
        assertFalse(OwnedAltLoot.ours(alt, gone, PARTY, NOW));
        MapItem prop = drop(ALT, 0, 4000000, settled);
        when(prop.isConjured()).thenReturn(true);
        assertFalse(OwnedAltLoot.ours(alt, prop, PARTY, NOW));
    }

    @Test
    void theNearestDropItHasRoomFor() {
        long settled = NOW - 5_000;
        MapItem far = drop(ALT, 300, 4000000, settled);
        MapItem near = drop(OWNER, 80, 4000001, settled);
        MapItem theirs = drop(STRANGER, 20, 4000002, settled);
        MapleMap f = mock(MapleMap.class);
        List<MapObject> around = new ArrayList<>(List.of(far, near, theirs));
        when(f.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(around);
        Character alt = alt(f, 0);
        Character owner = alt(f, 0);
        Set<InventoryType> full = EnumSet.noneOf(InventoryType.class);
        assertSame(near, OwnedAltLoot.pick(alt, owner, PARTY, NOW, full, new boolean[1]));
        assertTrue(full.isEmpty());
        assertFalse(OwnedAltLoot.inReach(alt, near));
        assertTrue(OwnedAltLoot.inReach(alt(f, 70), near));
    }

    @Test
    void aFullBagLeavesTheDropAndIsReported() {
        long settled = NOW - 5_000;
        MapItem etc = drop(ALT, 40, 4000000, settled);
        MapleMap f = mock(MapleMap.class);
        when(f.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(new ArrayList<>(List.of(etc)));
        OwnedAltLoot.room = (alt, drop) -> false;
        Set<InventoryType> full = EnumSet.noneOf(InventoryType.class);
        assertNull(OwnedAltLoot.pick(alt(f, 0), alt(f, 0), PARTY, NOW, full, new boolean[1]));
        assertEquals(EnumSet.of(InventoryType.ETC), full);
        assertEquals("My Etc inventory is full, I'm leaving those drops on the ground.",
                OwnedAltLoot.fullLine(InventoryType.ETC));
    }
}
