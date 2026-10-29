package soloMapling.companions;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 ticket 13: the activity gate - an owned alt fights and loots only while its owner plays with it (online, on its
 * map, not in town, nearby, active in the last three minutes) and while the map is not already full of fighting alts.
 */
class OwnedAltGateTest {

    static final long NOW = 10_000_000L;

    static MapleMap field(boolean town) {
        MapleMap m = mock(MapleMap.class);
        when(m.isTown()).thenReturn(town);
        return m;
    }

    static Character owner(MapleMap map, int x, long lastAction) {
        Character o = mock(Character.class);
        when(o.getId()).thenReturn(1);
        when(o.isLoggedinWorld()).thenReturn(true);
        when(o.getMap()).thenReturn(map);
        when(o.getPosition()).thenReturn(new Point(x, 0));
        Client c = mock(Client.class);
        when(c.getLastPlayerAction()).thenReturn(lastAction);
        when(o.getClient()).thenReturn(c);
        return o;
    }

    static Character alt(MapleMap map, int x) {
        Character a = mock(Character.class);
        when(a.getId()).thenReturn(2);
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.getMap()).thenReturn(map);
        when(a.getPosition()).thenReturn(new Point(x, 0));
        return a;
    }

    @AfterEach
    void clear() {
        OwnedAltGate.forceIdleForQa(1, false);
    }

    @Test
    void openWhileTheOwnerPlaysNearbyOnAField() {
        MapleMap f = field(false);
        assertEquals(OwnedAltGate.Why.OPEN, OwnedAltGate.check(owner(f, 0, NOW - 5_000), alt(f, 300), NOW, 0));
    }

    @Test
    void closedWhenTheOwnerIsGoneOrOnAnotherMap() {
        MapleMap f = field(false);
        Character gone = owner(f, 0, NOW);
        when(gone.isLoggedinWorld()).thenReturn(false);
        assertEquals(OwnedAltGate.Why.OWNER_GONE, OwnedAltGate.check(gone, alt(f, 0), NOW, 0));
        assertEquals(OwnedAltGate.Why.OWNER_GONE, OwnedAltGate.check(null, alt(f, 0), NOW, 0));
        assertEquals(OwnedAltGate.Why.OTHER_MAP, OwnedAltGate.check(owner(field(false), 0, NOW), alt(f, 0), NOW, 0),
                "an instance copy of the same map id is another map object: no fighting there alone");
    }

    @Test
    void neverInATown() {
        MapleMap town = field(true);
        assertEquals(OwnedAltGate.Why.TOWN, OwnedAltGate.check(owner(town, 0, NOW), alt(town, 0), NOW, 0));
    }

    @Test
    void onlyNearItsOwner() {
        MapleMap f = field(false);
        assertEquals(OwnedAltGate.Why.TOO_FAR,
                OwnedAltGate.check(owner(f, 0, NOW), alt(f, OwnedAltGate.RANGE_X_PX + 1), NOW, 0));
        Character high = alt(f, 0);
        when(high.getPosition()).thenReturn(new Point(0, -OwnedAltGate.RANGE_Y_PX - 1));
        assertEquals(OwnedAltGate.Why.TOO_FAR, OwnedAltGate.check(owner(f, 0, NOW), high, NOW, 0));
    }

    @Test
    void anIdleOwnersAltsStopAndTheOwnerIsTold() {
        MapleMap f = field(false);
        long idle = OwnedAltGate.idleMs();
        assertEquals(3 * 60_000L, idle, "default: three minutes");
        assertEquals(OwnedAltGate.Why.OPEN, OwnedAltGate.check(owner(f, 0, NOW - idle + 1_000), alt(f, 0), NOW, 0));
        assertEquals(OwnedAltGate.Why.OWNER_IDLE, OwnedAltGate.check(owner(f, 0, NOW - idle - 1), alt(f, 0), NOW, 0));
        assertNotNull(OwnedAltGate.Why.OWNER_IDLE.tell);
        assertNull(OwnedAltGate.Why.TOO_FAR.tell, "the quiet reasons say nothing");
        // QA staging: the same rule without waiting three minutes
        OwnedAltGate.forceIdleForQa(1, true);
        assertEquals(OwnedAltGate.Why.OWNER_IDLE, OwnedAltGate.check(owner(f, 0, NOW), alt(f, 0), NOW, 0));
    }

    @Test
    void atMostSixAltsFightOnOneMap() {
        MapleMap f = field(false);
        assertEquals(OwnedAltGate.Why.OPEN,
                OwnedAltGate.check(owner(f, 0, NOW), alt(f, 0), NOW, OwnedAltGate.FIGHTING_PER_MAP - 1));
        assertEquals(OwnedAltGate.Why.MAP_FULL,
                OwnedAltGate.check(owner(f, 0, NOW), alt(f, 0), NOW, OwnedAltGate.FIGHTING_PER_MAP));
        assertEquals(6, OwnedAltGate.FIGHTING_PER_MAP);
        assertEquals(5, OwnedAltService.CAP, "per owner: the shared five companions");
    }
}
