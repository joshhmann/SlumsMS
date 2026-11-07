package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import server.maps.MapleMap;

import java.awt.Point;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.6 bot-offmap-frozen (2a): warpBotToLocation (the OPQ regulars' lobby, stage and exit warps, the GM spawn command)
 * took a bot off its map only when the target was that same map, so a bot warped from X to Y stayed in X's lists for
 * good and was shown there, frozen, at its live position in Y (the B-24 picture). It now leaves X first.
 */
class WarpBotLeavesOldMapTest {

    private static Character botOn(MapleMap now) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BotStore.BOT_ID_BASE + 88);
        when(bot.getMap()).thenReturn(now);
        return bot;
    }

    @Test
    void aBotWarpedToAnotherMapLeavesTheOldOneFirst() {
        MapleMap lobby = mock(MapleMap.class);
        MapleMap stage = mock(MapleMap.class);
        Character bot = botOn(lobby);

        BotGeneration.placeBotOnMap(bot, new Point(-233, 174), stage);

        InOrder order = inOrder(lobby, bot, stage);
        order.verify(lobby).removePlayer(bot);
        order.verify(bot).setMap(stage);
        order.verify(stage).addPlayer(bot);
        verify(stage, never()).removePlayer(bot);
    }

    @Test
    void aWarpOnTheSameMapStillTakesItOffAndPutsItBackOnce() {
        MapleMap lobby = mock(MapleMap.class);
        Character bot = botOn(lobby);

        BotGeneration.placeBotOnMap(bot, new Point(-233, 174), lobby);

        InOrder order = inOrder(lobby, bot);
        order.verify(lobby).removePlayer(bot);
        order.verify(lobby).addPlayer(bot);
        verify(lobby, times(1)).removePlayer(bot);
    }

    @Test
    void aBotWithNoMapYetIsJustPlaced() {
        MapleMap lobby = mock(MapleMap.class);
        Character bot = botOn(null);

        BotGeneration.placeBotOnMap(bot, new Point(-233, 174), lobby);

        verify(lobby, never()).removePlayer(bot);
        verify(lobby).addPlayer(bot);
    }
}
