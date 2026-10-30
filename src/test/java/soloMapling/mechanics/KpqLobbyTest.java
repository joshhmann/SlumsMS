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
        assertEquals(List.of(), KpqLobby.pickParty(List.of(c(1, 25, true), c(2, 25, true), c(3, 25, false)), 3, 4),
                "two free bots are not a KPQ party");
        assertEquals(List.of(4, 2, 1), KpqLobby.pickParty(List.of(c(1, 22, true), c(2, 25, true), c(3, 40, true),
                c(4, 30, true), c(5, 20, true)), 3, 4), "21-30 only, highest first");
        assertEquals(List.of(6, 7, 8, 9), KpqLobby.pickParty(List.of(c(9, 21, true), c(8, 23, true), c(7, 27, true),
                c(6, 29, true), c(10, 21, true)), 3, 4), "at most four, ties by id");
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
