package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (ticket 80, after nutnnut/Cosmic ae5753df0): removeBotFromServer takes the bot out of its map and then out of
 * the channel. A warp on the bot's tick thread that lands it in a map in between used to leave it there as a ghost
 * (in the map's lists, in no storage). After the channel removal the bot is taken out of whatever map it is in now.
 */
class BotLogoutWarpRaceTest {

    private static Character bot(MapleMap now) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BotStore.BOT_ID_BASE + 77);
        when(bot.getMap()).thenReturn(now);
        return bot;
    }

    @Test
    void aBotWarpedIntoAMapDuringLogoutIsTakenOutOfIt() {
        MapleMap landed = mock(MapleMap.class);
        Character bot = bot(landed);
        when(landed.getCharacterById(bot.getId())).thenReturn(bot);

        BotGeneration.leaveMapWarpedIntoMeanwhile(bot);

        verify(landed).removePlayer(bot);
    }

    @Test
    void aBotThatIsInNoMapAnyMoreIsLeftAlone() {
        MapleMap old = mock(MapleMap.class);
        Character bot = bot(old);   // already removed from it: the map no longer lists the bot

        BotGeneration.leaveMapWarpedIntoMeanwhile(bot);

        verify(old, never()).removePlayer(bot);
    }
}
