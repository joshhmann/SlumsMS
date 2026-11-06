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
        when(rock.getStats()).thenReturn(hittable()); // 1.6.5 b331: bots hit only a hittable state
        List<String> log = new ArrayList<>();

        when(map.getReactorByName("rock")).thenReturn(rock);

        new StepExecutor().execute(null, null, null, bot, new SolverStep.HitReactorNamed("rock"), NOW, log::add);

        ArgumentCaptor<Client> hitWith = ArgumentCaptor.forClass(Client.class);
        verify(rock).hitReactor(hitWith.capture());
        assertSame(bot, hitWith.getValue().getPlayer(), "the script's rm.getPlayer() is the bot: " + log);
        assertNull(shared.getPlayer(), "and nothing was written to the shared client");
    }

    @Test
    void anOwnedAltAndARealPlayerStillHitThroughTheirOwnClient() {
        // an owned alt: a private headless client whose player is the alt
        BotClient own = new BotClient(0, 2);
        Character alt = Character.getDefault(own);
        alt.setID(1_000_000_000 + 1648);
        alt.setName("Alty");
        own.setPlayer(alt);
        MapleMap map = mock(MapleMap.class);
        alt.setMap(map);
        alt.setPosition(new Point(100, 100));
        Reactor rock = mock(Reactor.class);
        when(rock.getPosition()).thenReturn(new Point(100, 100));
        when(rock.getStats()).thenReturn(hittable()); // 1.6.5 b331: bots hit only a hittable state
        when(map.getReactorByName("rock")).thenReturn(rock);

        new StepExecutor().execute(null, null, null, alt, new SolverStep.HitReactorNamed("rock"), NOW, new ArrayList<String>()::add);

        verify(rock).hitReactor(own); // the very same client object: nothing was bound for it

        // a real player's client is not a headless one at all
        Client human = mock(Client.class);
        Character player = mock(Character.class);
        when(player.getClient()).thenReturn(human);
        when(player.getName()).thenReturn("Human");
        when(player.getId()).thenReturn(5);
        when(player.getMap()).thenReturn(map);
        when(player.getPosition()).thenReturn(new Point(100, 100));
        Reactor lever = mock(Reactor.class);
        when(lever.getPosition()).thenReturn(new Point(100, 100));
        when(lever.getStats()).thenReturn(hittable()); // 1.6.5 b331: bots hit only a hittable state
        when(map.getReactorByName("lever")).thenReturn(lever);

        new StepExecutor().execute(null, null, null, player, new SolverStep.HitReactorNamed("lever"), NOW + 20_000,
                new ArrayList<String>()::add);

        verify(lever).hitReactor(human);
    }

    /** A reactor state that takes a hit (event type 0), as a real rock or lever has; see StepExecutor.clientCanHit. */
    private static server.maps.ReactorStats hittable() {
        server.maps.ReactorStats stats = new server.maps.ReactorStats();
        stats.addState((byte) 0, 0, null, (byte) 0, -1, (byte) 0);
        return stats;
    }
}
