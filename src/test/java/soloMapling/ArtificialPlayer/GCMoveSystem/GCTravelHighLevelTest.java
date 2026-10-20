package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;

import java.util.List;

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

// Ticket 03 follow-up: boarding the new hops on a real bot Character - a script's level check turns a low bot away
// (no free pass past it) and the hyper rock keeps HyperTPHandler's 5-minute cooldown.
class GCTravelHighLevelTest {

    private static final BotTransit.Edge JEFF = new BotTransit.Edge("Jeff's gate", 211040200, 211040300,
            BotTransit.Via.NPC, 2030000, -1, null, 0, -1, null, true, 20, 5, null, null, null, 50, 0);

    private static Character botOn(int mapId, int level) {
        Character bot = spy(Character.getDefault(new BotClient(0, 0)));
        MapleMap map = mock(MapleMap.class);
        Channel channel = mock(Channel.class);
        World world = mock(World.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getChannelServer()).thenReturn(channel);
        when(map.getWorldServer()).thenReturn(world);
        doReturn(map).when(bot).getMap();
        doReturn(mapId).when(bot).getMapId();
        doReturn(level).when(bot).getLevel();
        doNothing().when(bot).changeMap(anyInt());
        doNothing().when(bot).changeMap(anyInt(), anyInt());
        doNothing().when(bot).saveLocationOnWarp();
        return bot;
    }

    @AfterEach
    void restore() {
        BotTransit.reload();
        BotTransit.clearHyperCooldowns();
    }

    @Test
    void jeffTurnsAwayABotUnderFiftyAndTheTripFails() {
        Character bot = botOn(211040200, 45);
        boolean[] result = {true};
        GCTravel.Trip trip = new GCTravel.Trip(bot, 211040400, ok -> result[0] = ok);

        assertTrue(GCTravel.tryBoard(trip, bot, JEFF));

        assertFalse(result[0]);
        verify(bot, never()).changeMap(anyInt(), anyInt());
    }

    @Test
    void jeffLetsALevelFiftyBotThrough() {
        Character bot = botOn(211040200, 50);

        assertTrue(GCTravel.tryBoard(new GCTravel.Trip(bot, 211040400, null), bot, JEFF));
        verify(bot).changeMap(211040300, 5);
    }

    @Test
    void theHyperRockWaitsOutItsCooldownBetweenJumps() {
        BotTransit.install(List.of());
        BotTransit.installHyperRock(new BotTransit.HyperRock(300, List.of(310070000),
                List.<int[]>of(new int[]{310000000, 311000000}), List.of(100000000)));
        BotTransit.Edge in = BotTransit.edge(100000000, 310070000);
        Character bot = botOn(100000000, 200);

        assertEquals(0L, BotTransit.hyperCooldownLeftMs(bot));
        assertTrue(GCTravel.tryBoard(new GCTravel.Trip(bot, 310070000, null), bot, in));
        verify(bot).changeMap(310070000);
        verify(bot).saveLocationOnWarp();

        long left = BotTransit.hyperCooldownLeftMs(bot);
        assertTrue(left > 290_000L && left <= 300_000L, "cooldown " + left);
        BotTransit.Edge home = BotTransit.edge(310070000, 100000000);
        GCTravel.Trip back = new GCTravel.Trip(bot, 100000000, null);
        assertFalse(GCTravel.tryBoard(back, bot, home), "queued until the rock is ready");
        assertTrue(back.awaitingBoarding);
        verify(bot, never()).changeMap(100000000);
    }
}
