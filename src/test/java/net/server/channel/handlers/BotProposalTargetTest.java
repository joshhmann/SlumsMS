package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.0 rc6 (QA t-rm-2): using the ring box on a bot partner threw a NullPointerException in the stock ETC-space check
 * (a bot's shared client has no player), so the proposal never reached the bot and it never answered, in any gender
 * combination. A bot's engagement token is recorded on it, never put in an inventory.
 */
class BotProposalTargetTest {

    @Test
    void aBotPartnerAlwaysHasRoomForItsToken() {
        Character bot = mock(Character.class);
        BotClient client = mock(BotClient.class);
        when(bot.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(null); // what threw before
        assertTrue(RingActionHandler.targetHasEtcSpace(bot, 4031358));
    }
}
