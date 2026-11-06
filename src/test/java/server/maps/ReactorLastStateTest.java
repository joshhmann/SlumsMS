package server.maps;

import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.reactor.ReactorScriptManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 b331: ZPQ stage 1's rock 2112012 (linked to 2112007) has WZ states 0-4; state 4 is the broken rock, with no
 * event, so it loads as an inert state ({@code ReactorFactory.addInertState}, type 999, next -1). A player's client
 * never hits it there, but a server-side bot hit took {@code hitReactor}'s legacy branch: {@code state++} to 5, a
 * REACTOR_HIT broadcast for a state the client has no data for (the client closes, TEST 2026-11-06 05:14:57), and a
 * second {@code act} (a second drop). A hit must never advance a reactor past the last state its WZ defines.
 */
class ReactorLastStateTest {

    /** The shape ReactorFactory builds for 2112007: four hit states, then the broken rock. */
    static ReactorStats rock() {
        ReactorStats stats = new ReactorStats();
        for (byte s = 0; s < 4; s++) {
            stats.addState(s, List.of(new ReactorStats.StateData(0, null, null, (byte) (s + 1))), -1);
        }
        stats.addState((byte) 4, 999, null, (byte) -1, 0, (byte) 0); // what addInertState records
        return stats;
    }

    @Test
    void aHitOnTheLastStateNeitherAdvancesNorBroadcastsNorActsAgain() {
        Reactor reactor = new Reactor(rock(), 2112012);
        MapleMap map = mock(MapleMap.class);
        reactor.setMap(map);
        reactor.setAlive(true);
        reactor.setState((byte) 4);
        ReactorScriptManager rsm = mock(ReactorScriptManager.class);

        try (MockedStatic<ReactorScriptManager> s = mockStatic(ReactorScriptManager.class)) {
            s.when(ReactorScriptManager::getInstance).thenReturn(rsm);
            reactor.hitReactor(mock(Client.class));
        }

        assertEquals(4, reactor.getState(), "b331: a hit must not push 2112012 past its last WZ state (4)");
        verify(map, never()).broadcastMessage(any(Packet.class));
        verify(rsm, never()).act(any(), any());
    }

    @Test
    void anInertStateWithAStateAfterItStillAdvancesAsBefore() {
        // the legacy branch is still how an inert state in the middle of a reactor moves on (CPQ 9980000 has 0-5)
        ReactorStats stats = new ReactorStats();
        stats.addState((byte) 0, 999, null, (byte) 1, 0, (byte) 0);
        stats.addState((byte) 1, List.of(new ReactorStats.StateData(0, null, null, (byte) 2)), -1);
        Reactor reactor = new Reactor(stats, 9999999);
        MapleMap map = mock(MapleMap.class);
        reactor.setMap(map);
        reactor.setAlive(true);
        reactor.setState((byte) 0);
        ReactorScriptManager rsm = mock(ReactorScriptManager.class);

        try (MockedStatic<ReactorScriptManager> s = mockStatic(ReactorScriptManager.class)) {
            s.when(ReactorScriptManager::getInstance).thenReturn(rsm);
            reactor.hitReactor(mock(Client.class));
        }

        assertEquals(1, reactor.getState());
        verify(map, times(1)).broadcastMessage(any(Packet.class));
        verify(rsm, times(1)).act(any(), any());
    }

}
