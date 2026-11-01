package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import client.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b247: the net under every bot line. A leftover {slot} is refilled from live state or the line is dropped, and
 * ordinary braces are text.
 */
class SpeechGuardTest {

    private Character bot;
    private Character player;

    @BeforeEach
    void setUp() {
        SpeechGuard.forgetForTest();
        bot = chr(1_000_000_007, "Bobby", 104_000_000, "Henesys");
        player = chr(42, "Alice", 104_000_000, "Henesys");
    }

    private static Character chr(int id, String name, int mapId, String mapName) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(mapId);
        when(c.getLevel()).thenReturn(35);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(0);
        when(c.getJob()).thenReturn(job);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapName()).thenReturn(mapName);
        when(c.getMap()).thenReturn(map);
        return c;
    }

    @Test
    void slotIsNarrowSoEverydayBracesAreText() {
        assertTrue(SpeechGuard.hasSlot("hey {player}"));
        assertTrue(SpeechGuard.hasSlot("{PLAYER_NAME}!"));
        assertTrue(SpeechGuard.hasSlot("wts {item} 5m"));
        assertFalse(SpeechGuard.hasSlot(":{"));
        assertFalse(SpeechGuard.hasSlot("{}"));
        assertFalse(SpeechGuard.hasSlot("{ }"));
        assertFalse(SpeechGuard.hasSlot("{player"));
        assertFalse(SpeechGuard.hasSlot("player}"));
        assertFalse(SpeechGuard.hasSlot("lol :) {"));
        assertFalse(SpeechGuard.hasSlot(null));
    }

    @Test
    void bracesThatAreNotSlotsGoOutUntouched() {
        for (String line : new String[]{":{", "gg :{ ", "{}", "{ }", "3,000,000 :{ <3"}) {
            assertEquals(line, SpeechGuard.check(bot, player, line), line);
        }
    }

    @Test
    void knownSlotsAreRefilledFromLiveState() {
        assertEquals("hey Alice", SpeechGuard.check(bot, player, "hey {player}"));
        assertEquals("Hey Alice!", SpeechGuard.check(bot, player, "Hey {PLAYER_NAME}!"));
        assertEquals("Alice, im Bobby on Henesys", SpeechGuard.check(bot, player, "{player}, im {me} on {map}"));
        assertEquals("im lvl 35 on Henesys", SpeechGuard.check(bot, player, "im lvl {level} on {map}"));
        assertEquals("Alice is lvl 35 too", SpeechGuard.check(bot, player, "{player} is lvl {player_level} too"));
        assertEquals("my job is Beginner", SpeechGuard.check(bot, player, "my job is {job}"));
    }

    @Test
    void unknownSlotDropsTheLine() {
        assertNull(SpeechGuard.check(bot, player, "wts {item} 5m"));
        assertNull(SpeechGuard.check(bot, player, "only {price} left"));
        assertNull(SpeechGuard.check(bot, player, "talk to {COUNTER_PRICE}"));
        assertNull(SpeechGuard.check(bot, player, "hey {player}, wts {item}"));
    }

    @Test
    void playerSlotWithoutAPlayerDropsTheLine() {
        // map chat has no player in context: the greeting must not go out as "{player} hi"
        assertNull(SpeechGuard.check(bot, "hey {player}, sup"));
        assertEquals("hey Alice, sup", SpeechGuard.check(bot, player, "hey {player}, sup"));
    }

    @Test
    void nullInNullOut() {
        assertNull(SpeechGuard.check(bot, player, null));
        assertNull(SpeechGuard.check(bot, null));
    }
}
