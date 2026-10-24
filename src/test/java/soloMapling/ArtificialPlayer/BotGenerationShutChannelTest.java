package soloMapling.ArtificialPlayer;

import client.Character;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * rc6 auto-QA triage (gu-8, 2026-10-23): "unstage the guild bots" threw a NullPointerException from
 * Channel.getPlayerStorage() - a shut-down channel keeps its place in the world with no storage. Taking a bot out of
 * the other channels skips such a channel and still removes it where it is.
 */
class BotGenerationShutChannelTest {

    @Test
    void aShutChannelIsSkippedAndTheBotStillLeavesTheOthers() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_123);
        Channel own = mock(Channel.class);
        Channel shut = mock(Channel.class);
        when(shut.getPlayerStorage()).thenReturn(null);
        Channel holds = mock(Channel.class);
        PlayerStorage ps = mock(PlayerStorage.class);
        when(ps.getCharacterById(1_000_000_123)).thenReturn(bot);
        when(holds.getPlayerStorage()).thenReturn(ps);
        assertDoesNotThrow(() -> BotGeneration.removeFromOthers(bot, own, Arrays.asList(own, shut, null, holds)));
        verify(holds).removePlayer(bot);
        verify(shut, never()).removePlayer(bot);
        verify(own, never()).removePlayer(bot);
    }
}
