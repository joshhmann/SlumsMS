package soloMapling.companions;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AL-2 (ticket 183): an alt held back by the activity gate says why, per name. Before, only the idle and map-full
 * reasons had a line (and neither said which alt), and the other four said nothing at all.
 */
class OwnedAltGateReasonTest {

    static final String ALT = "Second";
    static final long NOW = 1_700_000_000_000L;

    static MapleMap field(boolean town) {
        MapleMap m = mock(MapleMap.class);
        when(m.isTown()).thenReturn(town);
        when(m.getId()).thenReturn(town ? 100_000_000 : 104_040_000);
        return m;
    }

    static Character owner(MapleMap map, long lastAction) {
        Character o = mock(Character.class);
        when(o.getId()).thenReturn(1);
        when(o.getName()).thenReturn("Main");
        when(o.isLoggedinWorld()).thenReturn(true);
        when(o.getMap()).thenReturn(map);
        when(o.getPosition()).thenReturn(new Point(0, 0));
        Client c = mock(Client.class);
        when(c.getLastPlayerAction()).thenReturn(lastAction);
        when(o.getClient()).thenReturn(c);
        return o;
    }

    static Character alt(Character owner, MapleMap map, int x) {
        Character a = mock(Character.class);
        when(a.getId()).thenReturn(2);
        when(a.getName()).thenReturn(ALT);
        when(a.getMap()).thenReturn(map);
        when(a.getPosition()).thenReturn(new Point(x, 0));
        return a;
    }

    /** The reason the gate gives, staged, and the line its owner then hears about that alt. */
    static String line(OwnedAltGate.Why why) {
        return why.tell(ALT);
    }

    @Test void everyReasonThatHoldsAnAltBackHasItsOwnLineNamingIt() {
        List<String> lines = new ArrayList<>();
        for (OwnedAltGate.Why why : List.of(OwnedAltGate.Why.OTHER_MAP, OwnedAltGate.Why.TOWN,
                OwnedAltGate.Why.TOO_FAR, OwnedAltGate.Why.OWNER_IDLE, OwnedAltGate.Why.MAP_FULL)) {
            String line = line(why);
            assertNotNull(line, why + " has no line");   // AL-2: four of these were null before
            assertTrue(line.contains(ALT), why + " does not name the alt: " + line);
            lines.add(line);
        }
        assertEquals(5, lines.stream().distinct().count(), "each reason says something of its own: " + lines);
        assertNull(line(OwnedAltGate.Why.OPEN), "an open gate says nothing");
        assertNull(line(OwnedAltGate.Why.OWNER_GONE), "there is nobody left to tell");
    }

    @Test void anotherMapSaysItIsOnItsWay() {
        MapleMap mine = field(false), theirs = field(false);
        Character owner = owner(mine, NOW);
        assertEquals(OwnedAltGate.Why.OTHER_MAP, OwnedAltGate.check(owner, alt(owner, theirs, 0), NOW, 0));
        String line = line(OwnedAltGate.Why.OTHER_MAP);
        assertTrue(line.contains("another map"), line);
        assertTrue(line.contains("reaches you"), "it will fight again when it gets there: " + line);
    }

    @Test void aTownSaysWhyAnAltWontFightThere() {
        MapleMap town = field(true);
        Character owner = owner(town, NOW);
        assertEquals(OwnedAltGate.Why.TOWN, OwnedAltGate.check(owner, alt(owner, town, 0), NOW, 0));
        String line = line(OwnedAltGate.Why.TOWN);
        assertTrue(line.contains("town") && line.contains("monsters"), line);
    }

    @Test void tooFarSaysItIsWalkingBackAndHowCloseItFights() {
        MapleMap f = field(false);
        Character owner = owner(f, NOW);
        assertEquals(OwnedAltGate.Why.TOO_FAR,
                OwnedAltGate.check(owner, alt(owner, f, OwnedAltGate.RANGE_X_PX + 100), NOW, 0));
        String line = line(OwnedAltGate.Why.TOO_FAR);
        assertTrue(line.contains("walking back"), line);
        assertTrue(line.contains("screen"), "it says how close is close enough: " + line);
    }

    @Test void anIdleOwnerIsToldWhichAltStopped() {
        MapleMap f = field(false);
        Character owner = owner(f, NOW - OwnedAltGate.idleMs() - 1_000);
        assertEquals(OwnedAltGate.Why.OWNER_IDLE, OwnedAltGate.check(owner, alt(owner, f, 0), NOW, 0));
        String line = line(OwnedAltGate.Why.OWNER_IDLE);
        assertTrue(line.contains(ALT) && line.contains("idle"), line);
    }

    @Test void aFullMapSaysTheLimitAndWhichAltIsWaiting() {
        MapleMap f = field(false);
        Character owner = owner(f, NOW);
        assertEquals(OwnedAltGate.Why.MAP_FULL,
                OwnedAltGate.check(owner, alt(owner, f, 0), NOW, OwnedAltGate.FIGHTING_PER_MAP));
        String line = line(OwnedAltGate.Why.MAP_FULL);
        assertTrue(line.contains(ALT), line);
        assertTrue(line.contains(String.valueOf(OwnedAltGate.FIGHTING_PER_MAP)), "the limit is in the line: " + line);
    }

    @Test void theLinesStayFriendlyAndNameless() {
        for (OwnedAltGate.Why why : OwnedAltGate.Why.values()) {
            String line = line(why);
            if (line == null) {
                continue;
            }
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            for (String banned : List.of("maplestory", "nexon", "wizet", "gms", "v83", "mapleroot", " ai ", "ai.", "bot")) {
                assertFalse(lower.contains(banned), why + " says '" + banned + "': " + line);
            }
        }
    }
}
