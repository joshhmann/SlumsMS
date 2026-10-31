package soloMapling.ArtificialPlayer;

import client.Character;
import client.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotConvo.RepeatGuard;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** b247 and b248 at the map-chat sink itself: what actually lands in a map's chat log. */
class SpeechSinkTest {

    private static final int MAP = 104_000_000;
    private static final int BOT_A = 1_000_000_011;
    private static final int BOT_B = 1_000_000_012;

    @BeforeEach
    void setUp() {
        RepeatGuard.forgetForTest();
        MapChatLog.resetForTest();
    }

    @Test
    void anUnfilledSlotNeverReachesTheMapChatLog() {
        Character bot = chr(BOT_A);
        SocialCommands.BotFullChat(bot, "hey {player}, sup"); // no player in map chat: the slot cannot be filled
        assertTrue(MapChatLog.recent(1, MAP, System.currentTimeMillis()).isEmpty(),
                MapChatLog.recent(1, MAP, System.currentTimeMillis()).toString());
    }

    @Test
    void aRefillableSlotGoesOutFilled() {
        Character bot = chr(BOT_A);
        Character player = chr(42);
        when(player.getName()).thenReturn("Alice");
        String line = soloMapling.ArtificialPlayer.BotConvo.SpeechGuard.check(bot, player, "hey {player}!");
        SocialCommands.BotFullChat(bot, line);
        List<MapChatLog.Line> lines = MapChatLog.recent(1, MAP, System.currentTimeMillis());
        assertEquals(1, lines.size(), lines.toString());
        assertEquals("hey Alice!", lines.get(0).text());
    }

    @Test
    void twoBotsOnOneMapDoNotSayTheSameLine() {
        SocialCommands.BotFullChat(chr(BOT_A), "Gz man!");
        SocialCommands.BotFullChat(chr(BOT_B), "gz   MAN");
        List<MapChatLog.Line> lines = MapChatLog.recent(1, MAP, System.currentTimeMillis());
        assertEquals(1, lines.size(), "the second bot must not send the same text: " + lines);

        SocialCommands.BotFullChat(chr(BOT_B), "gz on the level up");
        assertEquals(2, MapChatLog.recent(1, MAP, System.currentTimeMillis()).size(), "a different line goes out");
    }

    private static Character chr(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("bot" + id);
        when(c.getMapId()).thenReturn(MAP);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(0);
        when(c.getJob()).thenReturn(job);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP);
        when(map.getAllPlayers()).thenReturn(List.of());
        when(c.getMap()).thenReturn(map);
        return c;
    }
}
