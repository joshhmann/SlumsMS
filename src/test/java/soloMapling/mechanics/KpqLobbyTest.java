package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 KPQ automation: which lobby bots make a bot-only KPQ party, and that it stays off without its switches. */
class KpqLobbyTest {

    static KpqLobby.Candidate c(int id, int level, boolean free) {
        return new KpqLobby.Candidate(id, level, free);
    }

    @Test
    void threeToFourFreeBotsInTheBandTheStrongestLeads() {
        assertEquals(List.of(), KpqLobby.pickParty(List.of(c(1, 25, true), c(2, 25, true), c(3, 25, false)), 3, 4,
                21, 255), "two free bots are not a KPQ party");
        assertEquals(List.of(3, 4, 2, 1), KpqLobby.pickParty(List.of(c(1, 22, true), c(2, 25, true), c(3, 40, true),
                c(4, 30, true), c(5, 20, true)), 3, 4, 21, 255), "the content's band, highest first");
        assertEquals(List.of(6, 7, 8, 9), KpqLobby.pickParty(List.of(c(9, 21, true), c(8, 23, true), c(7, 27, true),
                c(6, 29, true), c(10, 21, true)), 3, 4, 21, 255), "at most four, ties by id");
    }

    /** B9 (bot audit): the band comes from the content file (kpq.yaml 21-255), not a hardcoded 21-30. */
    @Test
    void theLevelBandIsTheContentFiles() {
        MechanicsModel.Content kpq = KpqStageDataTest.kpq();
        List<KpqLobby.Candidate> lobby = List.of(c(1, 45, true), c(2, 60, true), c(3, 33, true), c(4, 20, true));
        assertEquals(List.of(2, 1, 3), KpqLobby.pickParty(lobby, kpq.minPlayers(), kpq.maxPlayers(), kpq.minLevel(),
                kpq.maxLevel()));
    }

    /** K9 and the one-lobby rule: a busy channel is skipped, a refused one is left alone for a while. */
    @Test
    void aBusyOrRecentlyRefusedChannelIsLeftAlone() {
        KpqLobby.clearForTest();
        assertTrue(KpqLobby.mayTry(102, 0, false));
        assertFalse(KpqLobby.mayTry(102, 0, true), "its one KPQ is taken");
        KpqLobby.refused(102, 1_000);
        assertFalse(KpqLobby.mayTry(102, 1_000 + KpqLobby.REFUSED_BACKOFF_MS - 1, false));
        assertTrue(KpqLobby.mayTry(103, 2_000, false), "other channels are not affected");
        assertTrue(KpqLobby.mayTry(102, 1_000 + KpqLobby.REFUSED_BACKOFF_MS, false));
        KpqLobby.clearForTest();
    }

    /** K7: a throw while the party is made breaks the party up and never escapes the lobby tick. */
    @Test
    void aThrowWhileStartingDisbandsAndReturns() {
        client.Character leader = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(leader.getId()).thenReturn(1);
        org.mockito.Mockito.when(leader.getParty()).thenThrow(new IllegalStateException("boom"));
        client.Character other = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(other.getId()).thenReturn(2);
        assertFalse(org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> KpqLobby.start(KpqStageDataTest.kpq(),
                List.of(leader, other), List.of(1, 2))));
        org.mockito.Mockito.verify(other, org.mockito.Mockito.atLeastOnce()).getParty(); // disband looked at it
    }

    @Test
    void offWithoutBothSwitches() {
        String m = System.getProperty(Mechanics.FLAG), l = System.getProperty(KpqLobby.FLAG);
        try {
            System.setProperty(KpqLobby.FLAG, "true");
            System.clearProperty(Mechanics.FLAG);
            assertFalse(KpqLobby.enabled());
            System.setProperty(Mechanics.FLAG, "true");
            assertTrue(KpqLobby.enabled());
            System.clearProperty(KpqLobby.FLAG);
            assertFalse(KpqLobby.enabled());
        } finally {
            if (m == null) System.clearProperty(Mechanics.FLAG); else System.setProperty(Mechanics.FLAG, m);
            if (l == null) System.clearProperty(KpqLobby.FLAG); else System.setProperty(KpqLobby.FLAG, l);
        }
    }
}
