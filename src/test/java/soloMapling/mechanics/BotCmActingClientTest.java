package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

/**
 * Guild Quest's Gatekeeper schedules the statues' display hits with the client it is handed
 * ({@code scripts/npc/9040009.js:110}: {@code delayedHitReactor(cm.getActingClient(), ..)}); the reactor's script runs for that
 * client later and asks it for a player. {@code getActingClient()} is the one accessor that changes for a population bot;
 * {@code getClient()} - which every dialogue send and most of the conversation manager use - is untouched, and a conversation
 * for a player gets his own client from both.
 */
class BotCmActingClientTest {

    @Test
    void aPopulationBotsConversationHandsOnAClientWithTheBotAsItsPlayerAndKeepsGetClientAsItWas() {
        BotClient shared = new BotClient(0, 2);
        Character bot = Character.getDefault(shared);
        bot.setID(1_000_000_000 + 1647);

        BotNpcTalk.BotCm cm = new BotNpcTalk.BotCm(bot, 9040009, 0);

        assertSame(bot, cm.getActingClient().getPlayer(), "the statue's script will ask this client for the bot");
        assertSame(shared, cm.getClient(), "getClient() is the shared headless client, as before: packet sends included");
        assertNull(shared.getPlayer());
    }

    @Test
    void aPlayersConversationHandsOnHisOwnClientEitherWay() {
        Client own = org.mockito.Mockito.mock(Client.class);
        NPCConversationManager cm = new NPCConversationManager(own, 9040009, -1, null, false);

        assertSame(own, cm.getClient());
        assertSame(own, cm.getActingClient(), "nothing is bound for a player: his client already has his player");
    }
}
