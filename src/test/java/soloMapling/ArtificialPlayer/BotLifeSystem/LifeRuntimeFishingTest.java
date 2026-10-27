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

/**
 * 1.6.0 t-fh-2 (TEST 2026-10-27): FISH is only offered when the fishing map can take the bot, and an unloaded map
 * only counts when {@link LifeRuntime#mayLoad} says so. The fishing maps are fields nobody stands on, so they were
 * never loaded and no bot ever fished.
 */
class LifeRuntimeFishingTest {
    @Test
    void theFishingMapsMayLoadOnArrival() {
        var bot = mock(TrainingBot.class);
        var chr = mock(Character.class);
        var map = mock(MapleMap.class);
        when(bot.getChr()).thenReturn(chr);
        when(chr.getId()).thenReturn(1000007001);
        when(chr.getLevel()).thenReturn(40);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(101010000);
        when(map.getReturnMapId()).thenReturn(101000000);
        when(map.getChannelId()).thenReturn(1);
        try (var fads = mockStatic(Fads.class); var roster = mockStatic(BotRoster.class)) {
            fads.when(() -> Fads.spotFor(anyInt(), anyInt())).thenReturn(0);
            var runtime = new LifeRuntime(bot);
            for (int fishing : new int[]{120010000, 251000100, 541010110}) {
                assertTrue(runtime.mayLoad(fishing), "fishing map " + fishing + " loads when a bot goes to fish");
            }
            assertFalse(runtime.mayLoad(101010000), "an ordinary field still does not");
        }
    }
}
