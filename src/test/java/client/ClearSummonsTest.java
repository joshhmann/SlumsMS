package client;

import constants.skills.Ranger;
import constants.skills.Sniper;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Summon;

import java.lang.reflect.Field;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.1: clearSummons (MapleRoot) removed the Silver Hawk map object in the Golden Eagle branch, a copy-paste slip:
 * a Sniper's Golden Eagle stayed on the map and a Ranger's hawk could be removed by the wrong branch.
 */
class ClearSummonsTest {

    @Test
    void goldenEagleIsRemovedFromTheMap() throws Exception {
        Character sniper = mock(Character.class, CALLS_REAL_METHODS);
        MapleMap map = mock(MapleMap.class);
        doReturn(map).when(sniper).getMap();
        doNothing().when(sniper).sendPacket(any(Packet.class));

        Summon eagle = mock(Summon.class);
        when(eagle.getOwner()).thenReturn(sniper);
        Field f = Character.class.getDeclaredField("summons");
        f.setAccessible(true);
        Map<Integer, Summon> summons = new java.util.LinkedHashMap<>();
        summons.put(Sniper.GOLDEN_EAGLE, eagle);
        f.set(sniper, summons);

        sniper.clearSummons();

        verify(map).removeMapObject(eagle);
        verify(map, never()).removeMapObject((server.maps.MapObject) null);
    }
}
