package server.reports;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerTrailTest {

    @Test
    void ringsAreBoundedAndKeepTheNewest() {
        PlayerTrail t = new PlayerTrail();
        for (int i = 0; i < 100; i++) {
            t.recordMap(i, 100000000 + i, "Map " + i);
            t.recordNpc(i, 9000000 + i, "Npc " + i);
            t.record(i, "use", 2000000 + i, null);
        }
        assertEquals(PlayerTrail.MAPS, t.maps().size());
        assertEquals(PlayerTrail.NPCS, t.npcs().size());
        assertEquals(PlayerTrail.EVENTS, t.events().size());
        assertEquals(100000099, t.maps().getLast().id());
        assertEquals(100000090, t.maps().getFirst().id());
        assertEquals(2000050, t.events().getFirst().id());
    }

    @Test
    void repeatsFoldIntoOneEntryWithACount() {
        PlayerTrail t = new PlayerTrail();
        t.record(1, "warp", 100000000, null);
        for (int i = 0; i < 500; i++) {
            t.record(10 + i, "attack", 1001004, "close");
        }
        t.record(600, "attack", 1001005, "close");
        assertEquals(3, t.events().size(), "500 hits with one skill are one entry");
        PlayerTrail.Entry spam = t.events().get(1);
        assertEquals(500, spam.count());
        assertEquals(509, spam.time(), "the folded entry carries the last time");
        assertEquals("warp", t.events().getFirst().kind(), "what happened before the fight is still there");
    }

    @Test
    void botsGetNoTrailAndRealPlayersOne() {
        Character bot = mock(Character.class);
        when(bot.isBot()).thenReturn(true);
        PlayerTrail.event(bot, "skill", 1, null);
        verify(bot, never()).bugTrail();
        assertNull(PlayerTrail.of(bot));
        assertNull(PlayerTrail.of(null));

        Character me = mock(Character.class);
        PlayerTrail mine = new PlayerTrail();
        when(me.bugTrail()).thenReturn(mine);
        PlayerTrail.map(me, 104000000, "Lith Harbor");
        PlayerTrail.npc(me, 1012000, "Regular Cab");
        assertEquals("Lith Harbor", mine.maps().getFirst().note());
        assertEquals("warp", mine.events().getFirst().kind(), "a map change is also an event, in order");
        assertEquals(1012000, mine.npcs().getFirst().id());
    }
}
