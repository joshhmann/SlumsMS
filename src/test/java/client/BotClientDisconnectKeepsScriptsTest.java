package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.5 b331 (live 2026-11-06 01:58 UTC, ZPQ stage 1, reactor 2112012): a PQ bot's reactor hit failed 9 times with
 * {@code NPE: "this.engines" is null} at {@code Client.getScriptEngine}, through {@link BotClient#forScripts}, which
 * hands every population bot's script the engine cache of the bot's shared headless client. {@code
 * Client.disconnectInternal} nulls {@code engines} on every path ({@code clear()}, or {@code engines = null} on the
 * transition path), and {@link BotClient#isLoggedIn()} is always true, so one disconnect of a channel's shared bot
 * client, from any caller, leaves every later bot script on that client dead until a restart.
 * <p>
 * A shared bot client has no session to end and no player of its own; a disconnect must leave it usable.
 */
class BotClientDisconnectKeepsScriptsTest {

    @Test
    void aDisconnectOfTheSharedBotClientLeavesItsScriptEnginesUsable() {
        BotClient shared = new BotClient(1, 1);
        Character bot = Character.getDefault(shared);
        assertNull(shared.getPlayer(), "fixture: a shared headless client has no player of its own");

        shared.forceDisconnect(); // any caller: an anti-cheat check, a channel sweep, a GM command

        Client forScripts = BotClient.forScripts(bot);
        assertDoesNotThrow(() -> forScripts.getScriptEngine("reactor/2112012.js"),
                "b331: a bot's reactor script needs the shared client's engine cache after a disconnect");
        assertDoesNotThrow(() -> forScripts.setScriptEngine("reactor/2112012.js", null));
    }
}
