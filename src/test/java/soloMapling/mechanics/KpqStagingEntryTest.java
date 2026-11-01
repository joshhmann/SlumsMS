package soloMapling.mechanics;

import client.Character;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * TEST 10-31, t-mx-5 (the mx-5 sequence: a GM calls AutoQaAlt and AutoQaAltB, leads the party at Lakelis, staging starts
 * Kerning PQ): the event started for the party Lakelis' own eligibility check returned, but its entry
 * ({@code EventInstanceManager.registerParty}) only registers the members it can find on the channel's copy of the
 * recruit map, skipping the rest without a word. Stage 1 is cleared with one pass per other member, so the party can no
 * longer clear, and the run used to answer a member left outside with a teleport onto the stage map. These tests hold
 * the replacement: the skipped member goes in through the event's own entry ({@code registerPlayer}, which runs {@code
 * playerEntry} - the route every other member took), nothing is moved onto a stage map, and a member the event will not
 * take is a refusal naming it instead of a PQ that cannot clear.
 */
class KpqStagingEntryTest {

    private static PartyCharacter member(int id, String name) {
        PartyCharacter m = mock(PartyCharacter.class);
        lenient().when(m.getId()).thenReturn(id);
        lenient().when(m.getName()).thenReturn(name);
        return m;
    }

    private static Character character(int id, String name) {
        Character c = mock(Character.class);
        lenient().when(c.getId()).thenReturn(id);
        lenient().when(c.getName()).thenReturn(name);
        return c;
    }

    /** What the run's event holds; {@code registerPlayer} is what adds a member to it. */
    private static EventInstanceManager eventHolding(List<Character> inside) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getPlayers()).thenReturn(inside);
        return eim;
    }

    @Test
    void theAltThePartyWarpSkippedIsTheOneNamedAsOutside() {
        Character gm = character(6, "AqClient");
        Character altA = character(66, "AutoQaAlt");
        Character altB = character(67, "AutoQaAltB");
        EventInstanceManager eim = eventHolding(new ArrayList<>(List.of(gm, altA))); // the warp took two of the three
        MapleMap recruit = mock(MapleMap.class);
        when(recruit.getCharacterById(67)).thenReturn(altB);

        List<KpqStaging.Outside> outside = KpqStaging.outsideTheInstance(eim,
                List.of(member(6, "AqClient"), member(66, "AutoQaAlt"), member(67, "AutoQaAltB")), recruit);
        assertEquals("AutoQaAltB", KpqStaging.names(outside), "only the alt the warp skipped is outside");
        assertSame(altB, outside.get(0).character(), "it is on this channel's map, so it can still be brought in");
    }

    @Test
    void theSkippedAltGoesInThroughTheEventsOwnEntryAndIsNeverMovedOntoTheMap() {
        Character gm = character(6, "AqClient");
        Character altB = character(67, "AutoQaAltB");
        EventInstanceManager eim = eventHolding(new ArrayList<>(List.of(gm)));
        doAnswer(inv -> {
            eim.getPlayers().add(inv.getArgument(0));
            return null;
        }).when(eim).registerPlayer(altB);
        MapleMap recruit = mock(MapleMap.class);
        when(recruit.getCharacterById(67)).thenReturn(altB);
        List<String> said = new ArrayList<>();

        String refusal = KpqStaging.bringThePartyIn(eim, List.of(member(6, "AqClient"), member(67, "AutoQaAltB")),
                recruit, said::add);

        assertNull(refusal, "the event took it: the party is whole, no refusal");
        verify(eim).registerPlayer(altB);
        verify(altB, never()).changeMap(any(MapleMap.class));
        verify(altB, never()).changeMap(any(MapleMap.class), anyInt());
        verify(altB, never()).changeMap(any(MapleMap.class), any(server.maps.Portal.class));
        verify(altB, never()).changeMap(any(MapleMap.class), any(java.awt.Point.class));
        assertTrue(said.stream().anyMatch(l -> l.contains("went in short") && l.contains("AutoQaAltB")),
                "the run says who came in late: " + said);
    }

    @Test
    void anAltTheEventWillNotTakeIsARefusalNamingItNotASilentShortParty() {
        Character gm = character(6, "AqClient");
        Character altB = character(67, "AutoQaAltB");
        EventInstanceManager eim = eventHolding(new ArrayList<>(List.of(gm))); // registerPlayer does not take it
        MapleMap recruit = mock(MapleMap.class);
        when(recruit.getCharacterById(67)).thenReturn(altB);
        List<String> said = new ArrayList<>();

        String refusal = KpqStaging.bringThePartyIn(eim, List.of(member(6, "AqClient"), member(67, "AutoQaAltB")),
                recruit, said::add);

        assertNotNull(refusal, "a PQ that cannot clear is refused, not run");
        assertTrue(refusal.contains("AutoQaAltB"), refusal);
        verify(altB, never()).changeMap(any(MapleMap.class), any(server.maps.Portal.class));
        assertTrue(said.isEmpty(), "nothing went in, so no run line claims it did: " + said);
    }

    @Test
    void aPartyTheEventTookInFullIsLeftAlone() {
        Character gm = character(6, "AqClient");
        Character altA = character(66, "AutoQaAlt");
        Character altB = character(67, "AutoQaAltB");
        EventInstanceManager eim = eventHolding(new ArrayList<>(List.of(gm, altA, altB)));
        List<String> said = new ArrayList<>();

        String refusal = KpqStaging.bringThePartyIn(eim,
                List.of(member(6, "AqClient"), member(66, "AutoQaAlt"), member(67, "AutoQaAltB")), null, said::add);

        assertNull(refusal);
        assertTrue(said.isEmpty(), "nobody was outside: " + said);
        verify(eim, never()).registerPlayer(any());
    }
}
