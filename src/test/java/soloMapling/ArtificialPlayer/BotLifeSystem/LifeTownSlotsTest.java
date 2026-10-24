package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMapCap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LifeTownSlotsTest {
    @Test void travellingBotsCannotAllTakeTheLastFreeTownSlot() {
        var map=mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000); when(map.getChannelId()).thenReturn(1); when(map.getBotCount()).thenReturn(49);
        try(var caps=mockStatic(BotMapCap.class,CALLS_REAL_METHODS)) {
            caps.when(() -> BotMapCap.cap(100000000)).thenReturn(50);
            assertTrue(LifeTownSlots.reserve(1,map));
            assertTrue(LifeTownSlots.reserve(1,map));
            assertFalse(LifeTownSlots.reserve(2,map));
            LifeTownSlots.release(1);
            assertTrue(LifeTownSlots.reserve(2,map));
        } finally { LifeTownSlots.release(1);LifeTownSlots.release(2); }
        assertEquals(0,LifeTownSlots.size());
    }
}
