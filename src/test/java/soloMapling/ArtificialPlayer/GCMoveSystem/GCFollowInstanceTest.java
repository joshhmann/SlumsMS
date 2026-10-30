package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.GCMoveSystem.GCFollowOwnedAltTest.chr;

/**
 * 1.6.2 b168: a population FollowerBot whose leader is inside an event instance (Zakum expedition, PQ stage) must not
 * walk to the public map with the same id and call it "same map". It goes by the owned alts' rule (one shared gate):
 * inside only when the event registered it with the party, otherwise it waits where it is until the leader comes out.
 */
class GCFollowInstanceTest {

    @Test void aFollowerOnThePublicCopyIsNotOnItsLeadersInstanceMap() {
        MapleMap publicZakum = mock(MapleMap.class), zakumAltar = mock(MapleMap.class);
        EventInstanceManager expedition = mock(EventInstanceManager.class);
        when(zakumAltar.getEventInstance()).thenReturn(expedition);
        Character follower = chr("Bot", 280030000, publicZakum, null);
        Character leader = chr("Leader", 280030000, zakumAltar, expedition);
        GCFollow.Session s = new GCFollow.Session(follower, leader);

        GCFollow.tick(s);

        assertEquals(280030000, s.blockedDest, "waits (blocked), not same-map tailing on the public copy");
        verify(leader, times(1)).yellowMessage(org.mockito.ArgumentMatchers.contains("waits outside"));
        GCFollow.tick(s);
        verify(leader, times(1)).yellowMessage(org.mockito.ArgumentMatchers.anyString()); // told once
    }

    @Test void aFollowerIsGatedLikeAnAlt() {
        MapleMap town = mock(MapleMap.class), stage = mock(MapleMap.class), stage2 = mock(MapleMap.class);
        EventInstanceManager pq = mock(EventInstanceManager.class);
        when(stage.getEventInstance()).thenReturn(pq);
        when(stage2.getEventInstance()).thenReturn(pq);
        // not registered: never into the instance
        assertNotNull(GCFollow.followBlockReason(chr("Bot", 103000000, town, null), chr("Leader", 103000800, stage, pq)));
        // registered with the party: follows from stage to stage inside it
        assertNull(GCFollow.followBlockReason(chr("Bot", 103000800, stage, pq), chr("Leader", 103000801, stage2, pq)));
        // the leader came out: free to follow again
        assertNull(GCFollow.followBlockReason(chr("Bot", 103000000, town, null), chr("Leader", 103000000, town, null)));
    }
}
