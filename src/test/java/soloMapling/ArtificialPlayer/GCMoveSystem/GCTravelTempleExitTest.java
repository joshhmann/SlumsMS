package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuestLog;
import soloMapling.ArtificialPlayer.BotQuestSystem.TempleOfTime;
import soloMapling.ArtificialPlayer.BotQuestSystem.TempleRoute;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Ticket 158 item 5: a Temple of Time lane exit (timeQuest.js) is a transit edge only the lane's quest opens: a bot
// without it is turned away (no free pass, as the script sends a player back to the safe lane), one with it walks on.
class GCTravelTempleExitTest {

    private static Character botOn(int id, int mapId) {
        Character bot = spy(Character.getDefault(new BotClient(0, 0)));
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getChannelServer()).thenReturn(mock(Channel.class));
        when(map.getWorldServer()).thenReturn(mock(World.class));
        doReturn(id).when(bot).getId();
        doReturn(map).when(bot).getMap();
        doReturn(mapId).when(bot).getMapId();
        doNothing().when(bot).changeMap(anyInt());
        doNothing().when(bot).changeMap(anyInt(), anyInt());
        doNothing().when(bot).changeMap(anyInt(), anyString());
        return bot;
    }

    private static BotQuestLog progress(int done) {
        Map<String, Object> s = new HashMap<>(new BotQuestLog().save());
        s.put("temple", done);
        return BotQuestLog.read(s);
    }

    @Test
    void laneOnesExitNeedsQuest3501() {
        BotTransit.Edge exit = BotTransit.templeExits().get(0); // 270010100 -> 270010110, 3501
        Character fresh = botOn(1_000_000_101, 270010100);
        TempleOfTime.note(1_000_000_101, progress(1)); // only 3500 done
        boolean[] result = {true};
        assertTrue(GCTravel.tryBoard(new GCTravel.Trip(fresh, 270010110, ok -> result[0] = ok), fresh, exit));
        assertFalse(result[0], "the trip fails");
        verify(fresh, never()).changeMap(anyInt(), anyString());

        Character done = botOn(1_000_000_102, 270010100);
        TempleOfTime.note(1_000_000_102, progress(TempleRoute.indexOf(3501) + 1));
        assertTrue(GCTravel.tryBoard(new GCTravel.Trip(done, 270010110, null), done, exit));
        verify(done).changeMap(270010110, "out00");
    }
}
