package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /*
     * 1.6.2 S3: a follower the expedition registered (inside the instance) and a leader who left it to a public map.
     * Before, the gate read this as "an instance it did not enter" and the bot stood inside saying it waits outside.
     * It now leaves through the event's own exit (playerExit: unregister + the exit map), never a raw warp, then
     * follows. Alts and population followers alike.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Bot", "Alt"})
    void aRegisteredFollowerLeavesTheInstanceWithItsLeader(String who) throws Exception {
        MapleMap altar = mock(MapleMap.class), town = mock(MapleMap.class);
        EventInstanceManager expedition = mock(EventInstanceManager.class);
        when(altar.getEventInstance()).thenReturn(expedition);
        when(expedition.getPlayerCount()).thenReturn(3);
        Character follower = chr(who, 280030000, altar, expedition);
        Character leader = chr("Leader", 211042300, town, null); // out to El Nath's Zakum door
        GCFollow.Session s = new GCFollow.Session(follower, leader);

        assertTrue(GCFollow.leavesWithLeader(follower, leader));
        assertNull(GCFollow.followBlockReason(follower, leader), "nothing to wait for: it goes out with its leader");

        GCFollow.tick(s);

        verify(expedition).invokeScriptFunction("playerExit", expedition, follower); // the event's own exit
        verify(follower, never()).changeMap(anyInt());           // no raw warp out of the event
        verify(follower, never()).forceChangeMap(any(), any());
        verify(leader, never()).yellowMessage(anyString());
        assertEquals(-1, s.blockedDest, "not waiting");

        GCFollow.tick(s); // the script is still moving it: no second exit
        verify(expedition, times(1)).invokeScriptFunction(eq("playerExit"), any(), any());
    }

    @org.junit.jupiter.api.Test
    void theWaitLineIsOnlyForAnInstanceItReallyCannotEnter() {
        MapleMap altar = mock(MapleMap.class), stage = mock(MapleMap.class), town = mock(MapleMap.class);
        EventInstanceManager expedition = mock(EventInstanceManager.class), otherPq = mock(EventInstanceManager.class);
        when(altar.getEventInstance()).thenReturn(expedition);
        when(stage.getEventInstance()).thenReturn(otherPq);
        // the leader left the expedition straight into another event: out of this one, then it waits outside that one
        Character inside = chr("Bot", 280030000, altar, expedition);
        Character intoOther = chr("Leader", 103000800, stage, otherPq);
        assertTrue(GCFollow.leavesWithLeader(inside, intoOther));
        assertNotNull(GCFollow.followBlockReason(inside, intoOther));
        // both still inside the expedition: no exit, follows inside
        assertFalse(GCFollow.leavesWithLeader(inside, chr("Leader", 280030000, altar, expedition)));
        // not registered anywhere: nothing to leave
        assertFalse(GCFollow.leavesWithLeader(chr("Bot", 211042300, town, null), chr("Leader", 211042300, town, null)));
    }
}
