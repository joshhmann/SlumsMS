package client;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic ae5753df0): changeMap counts nested warps in canWarpCounter and only
 * lets a warp through while canWarpMap is true. A warp that threw (a script, a broken destination map) skipped the
 * decrement, so the counter never got back to 0, the next warp set canWarpMap false for good, and every later warp
 * of that character silently did nothing: a player stuck on one map until relog, a bot stuck for its whole session.
 */
class WarpLatchTest {

    private static int counter(Character chr) throws Exception {
        Field f = Character.class.getDeclaredField("canWarpCounter");
        f.setAccessible(true);
        return f.getInt(chr);
    }

    private static boolean canWarp(Character chr) throws Exception {
        Field f = Character.class.getDeclaredField("canWarpMap");
        f.setAccessible(true);
        return f.getBoolean(chr);
    }

    private static MapleMap brokenMap() {
        MapleMap target = mock(MapleMap.class);
        when(target.getId()).thenThrow(new IllegalStateException("broken destination"));
        return target;
    }

    @Test
    void aThrownWarpByPositionDoesNotLatchTheCharacter() throws Exception {
        Character chr = Character.getDefault(mock(Client.class));

        assertThrows(IllegalStateException.class, () -> chr.changeMap(brokenMap(), new Point(0, 0)));

        assertEquals(0, counter(chr));
        assertTrue(canWarp(chr));
    }

    @Test
    void aThrownWarpByPortalDoesNotLatchTheCharacter() throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));

        assertThrows(IllegalStateException.class, () -> chr.changeMap(brokenMap(), (Portal) null));

        assertEquals(0, counter(chr));
        assertTrue(canWarp(chr));
    }

    @Test
    void aThrownForcedWarpDoesNotLatchTheCharacter() throws Exception {
        Character chr = Character.getDefault(mock(Client.class));
        MapleMap target = mock(MapleMap.class);
        when(target.getEventInstance()).thenThrow(new IllegalStateException("broken destination"));

        assertThrows(IllegalStateException.class, () -> chr.forceChangeMap(target, null));

        assertEquals(0, counter(chr));
        assertTrue(canWarp(chr));
    }
}
