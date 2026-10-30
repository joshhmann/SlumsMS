package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import server.maps.Portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 ({@code !mechanics kpq ... stage4}): staging a run at a later stage puts the instance in the state Cloto's
 * {@code clearStage} leaves for every earlier stage (scripts/npc/9020001.js: the clear property, the gate, the
 * next00 portal linked to kpq{n-1}) and stage 1's grid emptied, without the stage-clear rewards and without moving
 * anybody.
 */
class KpqStagingStageEventTest {

    @Test
    void stagesOneToThreeAreLeftAsClotoLeavesThem() {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Portal[] portals = new Portal[6];
        for (int s = 1; s <= 5; s++) {
            MapleMap map = mock(MapleMap.class);
            portals[s] = mock(Portal.class);
            when(map.getPortal("next00")).thenReturn(portals[s]);
            when(eim.getMapInstance(103000800 + s - 1)).thenReturn(map);
        }
        int linked = KpqStaging.stageEventTo(eim, KpqStageDataTest.kpq(), 4);
        assertEquals(3, linked);
        for (int s = 1; s <= 3; s++) {
            verify(eim).setProperty(s + "stageclear", "true");
            verify(portals[s]).setScriptName("kpq" + (s - 1));
            verify(eim).showClearEffect(true, 103000800 + s - 1);
        }
        verify(eim, never()).setProperty("4stageclear", "true");
        verify(portals[4], never()).setScriptName("kpq3");
        verify(eim).gridClear();
        verify(eim, never()).linkToNextStage(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(eim, never()).giveEventPlayersStageReward(org.mockito.ArgumentMatchers.anyInt());
    }
}
