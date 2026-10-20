package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 1.3.0 first-boot rehearsal: bots the population director logged out mid-errand kept being warped by a travel
// poller every 300 ms ("Chr X got stuck when moving to map 10504040x", 11.5k lines in 17 minutes). A bot its channel
// no longer holds must not start a trip, and a trip already polling must end without a warp or a callback.
class GCTravelLoggedOutTest {

    private static final int BOT_ID = 1_000_777_001;
    private static final int SAUNA = 105040401;
    private static final int HOTEL = 105040400;

    private final PlayerStorage storage = new PlayerStorage();

    private Character botInSauna() {
        Character bot = spy(Character.getDefault(new BotClient(0, 0)));
        bot.setID(BOT_ID);
        bot.setName("SaunaBot");
        BotClient client = mock(BotClient.class);
        Channel channel = mock(Channel.class);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(storage);
        doReturn(client).when(bot).getClient();
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(SAUNA);
        doReturn(map).when(bot).getMap();
        doReturn(SAUNA).when(bot).getMapId();
        doNothing().when(bot).changeMap(anyInt());
        doNothing().when(bot).changeMap(anyInt(), anyInt());
        return bot;
    }

    @Test
    void aBotIsInTheWorldWhileItsChannelHoldsIt() {
        Character bot = botInSauna();
        storage.addPlayer(bot);
        assertFalse(GCMovement.hasLeftWorld(bot));

        storage.removePlayer(BOT_ID);
        assertTrue(GCMovement.hasLeftWorld(bot));
    }

    @Test
    void aBotWithNoChannelToAskCountsAsPresent() {
        Character bot = spy(Character.getDefault(new BotClient(0, 0)));
        assertFalse(GCMovement.hasLeftWorld(bot)); // no running server in a unit test: getChannelServer() is null
    }

    @Test
    void aLoggedOutBotGetsNoTripNoDriverAndNoCallback() {
        Character bot = botInSauna(); // never added: logged out before its errand asked for the trip
        AtomicInteger callbacks = new AtomicInteger();

        GCTravel.travel(bot, HOTEL, ok -> callbacks.incrementAndGet());
        GCMovement.enable(bot);

        assertFalse(GCTravel.isTraveling(bot));
        assertFalse(GCMovement.isEnabled(bot));
        assertEquals(0, callbacks.get());
        verify(bot, never()).changeMap(anyInt());
    }

    @Test
    void aTripPollingABotThatLoggedOutEndsWithoutAWarp() {
        Character bot = botInSauna();
        AtomicInteger callbacks = new AtomicInteger();
        GCTravel.Trip trip = new GCTravel.Trip(bot, HOTEL, ok -> callbacks.incrementAndGet());
        trip.hopStartAtMs = System.nanoTime() / 1_000_000L - 600_000L; // any hop ceiling long since passed
        trip.hopProgressAtMs = trip.hopStartAtMs;

        for (int i = 0; i < 10; i++) { // the old flood: one warp per 300 ms poll
            GCTravel.tick(trip);
        }

        verify(bot, never()).changeMap(anyInt());
        verify(bot, never()).changeMap(anyInt(), anyInt());
        assertFalse(GCTravel.isTraveling(bot));
        assertEquals(0, callbacks.get());
    }
}
