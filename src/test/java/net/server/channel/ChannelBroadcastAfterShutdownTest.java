package net.server.channel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 1.5.2: Channel.shutdown nulls its player storage on the way out, but Server.broadcastGMMessage can still reach the
 * channel afterwards - on the test server an auto-QA synthetic login did, four times, and each time the NPE
 * "Cannot invoke PlayerStorage.getAllCharacters() because this.players is null" escaped the login handler. A channel
 * with no player storage has nobody to broadcast a GM packet to, so it now just returns.
 */
class ChannelBroadcastAfterShutdownTest {

    @Test
    void gmBroadcastOnAChannelWithoutPlayersDoesNothing() throws Exception {
        Channel channel = mock(Channel.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        Field players = Channel.class.getDeclaredField("players");
        players.setAccessible(true);
        players.set(channel, null);

        assertDoesNotThrow(() -> channel.broadcastGMPacket(null));
    }
}
