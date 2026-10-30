package scripting.event;

import client.BotClient;
import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionLeaderboard;
import server.expeditions.ExpeditionType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TEST 2026-10-29 23:55 UTC (QA run 4, t-rb-2): a Von Leon clear threw "Client.getPlayer() is null" from
 * Character.setExpeditionCompleted for a bot (its client has no player), which aborted setEventCleared's loop: the
 * players after the bot got no completion row or message, and the timer, leaderboard and dispose were skipped.
 */
class ExpeditionClearBotTest {

    private static final int BOT_ID = 100, PLAYER_ID = 200; // HashMap order: the bot comes first

    @Test
    void aBotWithoutAPlayerOnItsClientDoesNotStopThePlayersClear() {
        EventManager em = mock(EventManager.class);
        when(em.getName()).thenReturn("VonLeonBattle");
        when(em.getWorldServer()).thenReturn(mock(World.class));
        when(em.getChannelServer()).thenReturn(mock(Channel.class));
        EventInstanceManager eim = new EventInstanceManager(em, "VonLeon1");

        Character bot = spy(Character.getDefault(new BotClient(0, 0)));
        bot.setID(BOT_ID);
        bot.setName("ClearBot1");
        doReturn(true).when(bot).isLoggedinWorld();
        assertNull(bot.getClient().getPlayer(), "a bot's client has no player, as on TEST");

        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Player1");
        when(player.isLoggedinWorld()).thenReturn(true);

        eim.registerPlayer(bot, false);
        eim.registerPlayer(player, false);
        assertEquals(List.of(BOT_ID, PLAYER_ID), eim.getPlayers().stream().map(Character::getId).toList());

        try (var log = mockStatic(ExpeditionBossLog.class, Mockito.CALLS_REAL_METHODS);
             var board = mockStatic(ExpeditionLeaderboard.class)) {
            log.when(() -> ExpeditionBossLog.setExpeditionCompleted(anyInt(), any(ExpeditionType.class), anyLong(),
                    anyLong(), any())).thenAnswer(a -> null); // no database here; the Client overloads stay real

            eim.setEventCleared(ExpeditionType.VONLEON);

            log.verify(() -> ExpeditionBossLog.setExpeditionCompleted(eq(BOT_ID), eq(ExpeditionType.VONLEON), anyLong(),
                    anyLong(), anyString()));
            board.verify(() -> ExpeditionLeaderboard.updateLeaderboardFromExpedition(eq(ExpeditionType.VONLEON),
                    anyString()));
        }
        verify(player).setExpeditionCompleted(eq(ExpeditionType.VONLEON), anyLong(), anyLong(), anyString());
        verify(player).dropMessage(eq(6), contains("expedition completed"));
        verify(em).disposeInstance("VonLeon1");
        assertTrue(eim.isEventCleared());
    }

    @Test
    void oneCharacterThatThrowsDoesNotStopTheOthersOrTheDispose() {
        EventManager em = mock(EventManager.class);
        when(em.getName()).thenReturn("ZakumBattle");
        when(em.getWorldServer()).thenReturn(mock(World.class));
        when(em.getChannelServer()).thenReturn(mock(Channel.class));
        EventInstanceManager eim = new EventInstanceManager(em, "Zakum1");

        Character broken = mock(Character.class);
        when(broken.getId()).thenReturn(BOT_ID);
        when(broken.isLoggedinWorld()).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(broken).setExpeditionCompleted(any(), anyLong(), anyLong(),
                anyString());
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER_ID);
        when(player.isLoggedinWorld()).thenReturn(true);
        eim.registerPlayer(broken, false);
        eim.registerPlayer(player, false);

        try (var board = mockStatic(ExpeditionLeaderboard.class)) {
            eim.setEventCleared(ExpeditionType.ZAKUM);
            board.verify(() -> ExpeditionLeaderboard.updateLeaderboardFromExpedition(eq(ExpeditionType.ZAKUM),
                    anyString()));
        }
        verify(player).dropMessage(eq(6), contains("expedition completed"));
        verify(em).disposeInstance("Zakum1");
    }
}
