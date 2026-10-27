package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.Fads;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 1.6.0 towns: where a hang-out goes: friend > plan > pull > fad > the BOT_LIFE_TOWN_WEIGHTS pick. */
class LifeRuntimeTownsTest {
    @Test
    void theHangOutTownFollowsTheWeightsThePullAndTheFadInOrder() {
        var server = config.YamlConfig.config.server;
        String saved = server.BOT_LIFE_TOWN_WEIGHTS;
        var bot = mock(TrainingBot.class);
        var chr = mock(Character.class);
        var map = mock(MapleMap.class);
        when(bot.getChr()).thenReturn(chr);
        when(chr.getId()).thenReturn(1000007001);
        when(chr.getLevel()).thenReturn(20);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(101010000); // a field outside Ellinia
        when(map.getReturnMapId()).thenReturn(101000000);
        when(map.getChannelId()).thenReturn(1);
        try (var fads = mockStatic(Fads.class); var roster = mockStatic(BotRoster.class)) {
            fads.when(() -> Fads.spotFor(anyInt(), anyInt())).thenReturn(0);
            var runtime = new LifeRuntime(bot);
            server.BOT_LIFE_TOWN_WEIGHTS = "henesys:100";
            assertEquals(LifeRuntime.HOME_TOWN, runtime.townDestination(), "shipped: Henesys, as before");
            server.BOT_LIFE_TOWN_WEIGHTS = "near:100";
            assertEquals(101000000, runtime.townDestination(), "near: the field's town");
            when(chr.getLevel()).thenReturn(5);
            when(map.getReturnMapId()).thenReturn(211000000);
            assertEquals(LifeRuntime.HOME_TOWN, runtime.townDestination(), "no Lv5 in El Nath: back to Henesys");
            when(map.getReturnMapId()).thenReturn(101000000);
            when(chr.getLevel()).thenReturn(20);
            fads.when(() -> Fads.spotFor(anyInt(), anyInt())).thenReturn(100000200);
            assertEquals(100000200, runtime.townDestination(), "a fad beats the weighted pick");
            runtime.pullTown(103000000);
            assertEquals(103000000, runtime.townDestination(), "a town short of bots beats the fad");
            assertTrue(runtime.mayLoad(103000000), "a town may be unloaded; it loads on arrival");
            assertTrue(runtime.mayLoad(101000000));
            assertFalse(runtime.mayLoad(101010000), "a field is not a hang-out town");
        } finally {
            server.BOT_LIFE_TOWN_WEIGHTS = saved;
        }
    }
}
