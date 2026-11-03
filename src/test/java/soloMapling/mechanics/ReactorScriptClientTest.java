package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapleMap;
import server.maps.Reactor;

/**
 * What a reactor's script is handed when a <b>population</b> bot hits it. The bot's own client is the channel's shared
 * headless one and has no player, so {@code rm.getPlayer()} was null in the script and {@code rm.dropItems()} dropped
 * nothing ({@code ReactorActionManager.java:157-159}): every row whose reactors give or count something
 * (Guild Quest's rocks and statues, Zakum PQ's chests, the Ellin stone) was driven by bots that could not earn it. The
 * executor now hits with {@link BotClient#forScripts}, whose player is the bot; a real player's or an alt's own client is
 * passed through as before ({@link ReactorByOidTest} pins that with a mocked character).
 */
class ReactorScriptClientTest {

    private static final long NOW = 9_000_000L;

    @Test
    void aPopulationBotsHitReachesTheScriptWithTheBotAsItsPlayer() {
        BotClient shared = new BotClient(0, 2);
        Character bot = Character.getDefault(shared);
        bot.setID(1_000_000_000 + 1643);
        bot.setName("Rocky");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        bot.setPosition(new Point(100, 100));
        Reactor rock = mock(Reactor.class);
        when(rock.getPosition()).thenReturn(new Point(100, 100));
        List<String> log = new ArrayList<>();

        when(map.getReactorByName("rock")).thenReturn(rock);

        new StepExecutor().execute(null, null, null, bot, new SolverStep.HitReactorNamed("rock"), NOW, log::add);

        ArgumentCaptor<Client> hitWith = ArgumentCaptor.forClass(Client.class);
        verify(rock).hitReactor(hitWith.capture());
        assertSame(bot, hitWith.getValue().getPlayer(), "the script's rm.getPlayer() is the bot: " + log);
        assertNull(shared.getPlayer(), "and nothing was written to the shared client");
    }
}
