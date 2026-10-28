package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.0.4 (P0): an owned alt follows its owner only where it is itself a member. Never into an event instance (PQ,
 * boss, event map) it did not enter with the party, never through a warp or paid/boarded transit.
 */
class GCFollowOwnedAltTest {
    static Character chr(String name, int mapId, MapleMap map, EventInstanceManager eim) {
        Character c = mock(Character.class);
        when(c.getName()).thenReturn(name); when(c.getMapId()).thenReturn(mapId); when(c.getMap()).thenReturn(map);
        when(c.getEventInstance()).thenReturn(eim); when(c.isOwnedAlt()).thenReturn(name.startsWith("Alt"));
        return c;
    }

    @Test void followsAcrossOrdinaryMaps() {
        MapleMap a = mock(MapleMap.class), b = mock(MapleMap.class);
        assertNull(GCFollow.altBlockReason(chr("Alt", 100, a, null), chr("Owner", 101, b, null)));
        assertNull(GCFollow.altBlockReason(chr("Alt", 100, a, null), chr("Owner", 100, a, null)));
    }

    @Test void neverIntoAnInstanceItDidNotEnter() {
        MapleMap town = mock(MapleMap.class), stage = mock(MapleMap.class);
        EventInstanceManager pq = mock(EventInstanceManager.class);
        when(stage.getEventInstance()).thenReturn(pq);
        Character alt = chr("Alt", 103000000, town, null);
        String why = GCFollow.altBlockReason(alt, chr("Owner", 103000800, stage, pq));
        assertNotNull(why);
        assertTrue(why.contains("instance"));
        // the owner registered for an event, its map not (yet) an instance map
        assertNotNull(GCFollow.altBlockReason(alt, chr("Owner", 103000000, town, pq)));
        // an instance map shares its id with the public map: same id is not the same map
        MapleMap publicCopy = mock(MapleMap.class);
        assertNotNull(GCFollow.altBlockReason(chr("Alt", 103000800, publicCopy, null), chr("Owner", 103000800, stage, pq)));
        // a map an event owns, even with the owner not (or no longer) registered in it
        assertNotNull(GCFollow.altBlockReason(alt, chr("Owner", 103000800, stage, null)));
    }

    @Test void followsInsideAnInstanceBothEnteredWithTheParty() {
        EventInstanceManager pq = mock(EventInstanceManager.class);
        MapleMap s1 = mock(MapleMap.class), s2 = mock(MapleMap.class);
        when(s1.getEventInstance()).thenReturn(pq); when(s2.getEventInstance()).thenReturn(pq);
        assertNull(GCFollow.altBlockReason(chr("Alt", 103000800, s1, pq), chr("Owner", 103000801, s2, pq)));
    }

    @Test void anAltsTripWalksPortalsOnly() {
        Character alt = chr("Alt", 100, mock(MapleMap.class), null);
        GCTravel.Trip trip = new GCTravel.Trip(alt, 200, null);
        assertTrue(trip.ownedAlt);
        assertTrue(trip.noWarp, "no warp: a warp could land it in an event map's public copy");
        assertTrue(trip.noFares, "no fares out of the alt's own mesos");
        Character bot = chr("Bot", 100, mock(MapleMap.class), null);
        when(bot.getId()).thenReturn(1_000_777_001);
        GCTravel.Trip botTrip = new GCTravel.Trip(bot, 200, null);
        assertFalse(botTrip.noWarp); assertFalse(botTrip.noFares); // population bots unchanged
    }

}
