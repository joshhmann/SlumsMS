package client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import scripting.reactor.ReactorActionManager;
import server.maps.Reactor;

/**
 * A population bot acts through its channel's one shared headless client, and that client has no player. Reactor scripts
 * reach the character through it: {@code ReactorActionManager.dropItems} returns without dropping anything when
 * {@code c.getPlayer()} is null ({@code ReactorActionManager.java:157-159}) and Guild Quest's statues, spear altars and gate
 * begin with {@code rm.getPlayer().getEventInstance()} ({@code scripts/reactor/9208000.js:36}, {@code 9208007.js:27},
 * {@code 9208009.js:26}) - so a bot's hit on a box produced no item and a bot's hit on a statue wrote no pattern.
 * {@link BotClient#forScripts} is the client those scripts get instead: the headless client with the bot as its player.
 */
class BotClientBindingTest {

    private static Character population(BotClient shared) {
        Character bot = Character.getDefault(shared);
        bot.setID(1_000_000_000 + 1641);
        return bot;
    }

    @Test
    void theSharedHeadlessClientHasNoPlayerForAScriptToReach() {
        BotClient shared = new BotClient(0, 2);
        Character bot = population(shared);

        assertNull(bot.getClient().getPlayer(), "the shared client is nobody's: this is what a script saw");
        ReactorActionManager before = new ReactorActionManager(bot.getClient(), mock(Reactor.class), null);
        assertNull(before.getPlayer(), "so rm.getPlayer() was null and rm.getPlayer().getEventInstance() threw");
    }

    @Test
    void aPopulationBotsScriptClientHasTheBotAsItsPlayer() {
        BotClient shared = new BotClient(0, 2);
        Character bot = population(shared);

        Client forScripts = BotClient.forScripts(bot);

        assertSame(bot, forScripts.getPlayer());
        assertSame(bot, new ReactorActionManager(forScripts, mock(Reactor.class), null).getPlayer(),
                "rm.getPlayer() is the bot that hit the reactor");
        assertTrue(forScripts instanceof BotClient && forScripts.isLoggedIn(), "still the headless client");
        assertEquals(shared.getWorld(), forScripts.getWorld());
        assertEquals(shared.getChannel(), forScripts.getChannel(), "same world and channel, so the script reads the same server");
    }

    @Test
    void nothingIsWrittenToTheSharedClientSoTwoBotsNeverSeeEachOthersPlayer() {
        BotClient shared = new BotClient(0, 2);
        Character a = population(shared);
        Character b = Character.getDefault(shared);
        b.setID(1_000_000_000 + 1642);

        Client forA = BotClient.forScripts(a);
        Client forB = BotClient.forScripts(b);

        assertNotSame(forA, forB);
        assertSame(a, forA.getPlayer());
        assertSame(b, forB.getPlayer());
        assertNull(shared.getPlayer(), "the shared client is untouched");
    }

    @Test
    void anyoneWithAClientOfTheirOwnGetsItBackUnchanged() {
        // an owned alt: a private headless client whose player is the alt (OwnedAltBackend)
        BotClient own = new BotClient(0, 2);
        Character alt = population(own);
        own.setPlayer(alt);
        assertSame(own, BotClient.forScripts(alt));

        // a real player's client is not a BotClient at all
        Client real = mock(Client.class);
        Character player = mock(Character.class);
        org.mockito.Mockito.when(player.getClient()).thenReturn(real);
        assertSame(real, BotClient.forScripts(player));

        assertNull(BotClient.forScripts(null));
    }
}
