package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import scripting.event.EventManager;
import scripting.event.EventScriptManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;

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

// Ticket 03: boarding and riding, on a real bot Character (its wallet is real, so the fare really leaves it). Only
// the map, the channel's event manager and the world's travel rate are mocks - the seams a boat ride reads.
class GCTravelTransitTest {

    private static final BotTransit.Edge BOAT = new BotTransit.Edge("boat to Orbis", 101000300, 101000301,
            BotTransit.Via.NPC, 1032008, 1032007, null, 5000, -1, "Boats", true, 150, -1, null, null, null);
    private static final BotTransit.Edge DECK = new BotTransit.Edge("boat to Orbis", 200090010, 200000100,
            BotTransit.Via.RIDE, -1, -1, null, 0, -1, null, true, 600, -1, null, null, null);
    private static final BotTransit.Edge CRANE = new BotTransit.Edge("crane to Mu Lung", 200090300, 250000100,
            BotTransit.Via.RIDE, -1, -1, null, 0, -1, null, false, 60, 0, null, null, null);

    private static Character botOn(int mapId, String entry, int meso) {
        Character bot = spy(Character.getDefault(new BotClient(0, 0)));
        MapleMap map = mock(MapleMap.class);
        Channel channel = mock(Channel.class);
        EventScriptManager esm = mock(EventScriptManager.class);
        EventManager em = mock(EventManager.class);
        World world = mock(World.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getChannelServer()).thenReturn(channel);
        when(map.getWorldServer()).thenReturn(world);
        when(world.getTransportationTime(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(channel.getEventSM()).thenReturn(esm);
        when(esm.getEventManager("Boats")).thenReturn(em);
        when(em.getProperty("entry")).thenReturn(entry);
        doReturn(map).when(bot).getMap();
        doReturn(mapId).when(bot).getMapId();
        doNothing().when(bot).changeMap(anyInt());
        doNothing().when(bot).changeMap(anyInt(), anyInt());
        if (meso > 0) {
            BotHoldings.grantMeso(bot, meso);
        }
        return bot;
    }

    private static GCTravel.Trip tripFor(Character bot) {
        return new GCTravel.Trip(bot, 200000000, null);
    }

    @Test
    void aClosedBoardingWindowQueuesTheBotWithoutCharging() {
        Character bot = botOn(101000300, "false", 10_000);
        GCTravel.Trip trip = tripFor(bot);

        assertFalse(GCTravel.tryBoard(trip, bot, BOAT));

        assertTrue(trip.awaitingBoarding);
        assertEquals(10_000, bot.getMeso());
        verify(bot, never()).changeMap(anyInt());
    }

    @Test
    void anOpenWindowTakesTheFareAndBoards() {
        Character bot = botOn(101000300, "true", 10_000);
        GCTravel.Trip trip = tripFor(bot);

        assertTrue(GCTravel.tryBoard(trip, bot, BOAT));

        assertFalse(trip.awaitingBoarding);
        assertEquals(5_000, bot.getMeso());
        verify(bot).changeMap(101000301);
    }

    @Test
    void aBotThatCantPayStaysAshoreAndReplansOnFreeHops() {
        Character bot = botOn(101000300, "true", 1_000);
        GCTravel.Trip trip = tripFor(bot);

        GCTravel.tryBoard(trip, bot, BOAT);

        assertTrue(trip.noFares);
        assertEquals(1_000, bot.getMeso());
        verify(bot, never()).changeMap(anyInt());
    }

    @Test
    void aMissingEventDoesNotHoldTheBotAshore() {
        Character bot = botOn(101000300, null, 10_000);

        assertTrue(GCTravel.tryBoard(tripFor(bot), bot, BOAT));
        verify(bot).changeMap(101000301);
    }

    @Test
    void theServerMovesBotsOffADeckAndTheBotOnlyStepsInWhenItNeverDoes() {
        Character bot = botOn(200090010, "true", 0);
        GCTravel.Trip trip = tripFor(bot);
        long now = System.nanoTime() / 1_000_000L;

        trip.hopStartAtMs = now - 600_000L; // the voyage's length: the event's arrived() is due any moment
        GCTravel.ride(trip, bot, DECK);
        verify(bot, never()).changeMap(anyInt());
        assertTrue(trip.aboard);

        trip.hopStartAtMs = now - 600_000L - 121_000L; // past the grace: the event never came
        GCTravel.ride(trip, bot, DECK);
        verify(bot).changeMap(200000100);
    }

    @Test
    void aPerPlayerRideEndsOnTheBotsOwnClock() {
        Character bot = botOn(200090300, "true", 0);
        GCTravel.Trip trip = tripFor(bot);
        long now = System.nanoTime() / 1_000_000L;

        trip.hopStartAtMs = now - 30_000L;
        GCTravel.ride(trip, bot, CRANE);
        verify(bot, never()).changeMap(anyInt(), anyInt());

        trip.hopStartAtMs = now - 61_000L;
        GCTravel.ride(trip, bot, CRANE);
        verify(bot).changeMap(250000100, 0);
    }
}
