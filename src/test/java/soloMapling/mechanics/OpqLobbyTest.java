package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;

/**
 * Ticket 164, the last piece: bots start Orbis PQ themselves. Kerning's lobby picks a party out of the bots already
 * standing at Lakelis - a population this fork keeps on {@code 103000000} - and the tower has no such population, so
 * the Kerning lobby is structurally unable to staff it. {@link OpqLobby} spawns its party on the row's own recruit map
 * with the row's own band and band of levels, then hands it to the same {@link KpqStaging} every party quest uses.
 *
 * <p>Nothing here runs a channel; what is tested is the gate (three switches, all off by default), the row the lobby
 * reads, the reason the Kerning path cannot do this, and the back-off that stops a channel from being retried every
 * minute.
 */
class OpqLobbyTest {

    private static final String[] SWITCHES = {
            "slums.mechanics", "slums.mechanics.opq", OpqLobby.FLAG, "slums.mechanics.opq.runs"};

    @AfterEach
    void switchesBackOff() {
        for (String s : SWITCHES) {
            System.clearProperty(s);
        }
        OpqLobby.clearForTest();
    }

    private static Content opq() {
        Content content = MechanicsRuns.contentFor(OpqLobby.EVENT);
        assertNotNull(content, "content/opq.yaml must be listed in content/index.txt");
        return content;
    }

    // ---- the gate ----------------------------------------------------------------------------------

    @Test
    void theLobbyIsOffUnlessAllThreeSwitchesAreGiven() {
        assertFalse(OpqLobby.enabled(), "no switches: nothing starts");

        System.setProperty("slums.mechanics", "true");
        assertFalse(OpqLobby.enabled(), "turning mechanics on must not turn an Orbis party on");

        System.setProperty("slums.mechanics.opq", "true");
        assertFalse(OpqLobby.enabled(), "the row's flag plays the tower, it does not staff it");

        System.setProperty(OpqLobby.FLAG, "true");
        assertTrue(OpqLobby.enabled(), "three flags, and only then bots form an Orbis party");

        System.clearProperty("slums.mechanics");
        assertFalse(OpqLobby.enabled(), "and losing either of the others turns it off again");
    }

    @Test
    void oneRunPerChannelUnlessTheServerSaysOtherwise() {
        assertEquals(1, OpqLobby.maxRuns(), "the default is one Orbis run at a time, like Kerning's lobby");
        System.setProperty("slums.mechanics.opq.runs", "3");
        assertEquals(3, OpqLobby.maxRuns());
        System.setProperty("slums.mechanics.opq.runs", "0");
        assertEquals(0, OpqLobby.maxRuns(), "zero is honoured: a server can keep the switch and start nothing");
    }

    // ---- the row the lobby reads -------------------------------------------------------------------

    @Test
    void theLobbyTakesItsFrontDoorAndItsBandFromTheRow() {
        Content content = opq();
        assertEquals(2013000, content.entryNpc(), "Wonky on 200080101 is who a bot leader accepts from");
        assertEquals(200080101, content.recruitMap());
        assertEquals(5, content.minPlayers(), "OrbisPQ.js:27 minPlayers = 5");
        assertEquals(6, content.maxPlayers());
        assertEquals(51, content.minLevel());
        assertEquals(255, content.maxLevel(), "OrbisPQ.js:28 maxLevel = 255, not upstream's 70");
        assertEquals("OrbisPQ", content.eventScript());
        assertTrue(Mechanics.enabledFor(content) == false, "the row still needs its own switch, which is off here");
    }

    @Test
    void theKerningLobbyCannotStaffTheTower() {
        Content opq = opq();
        List<KpqLobby.Candidate> nobodyAtWonky = new ArrayList<>();

        assertEquals(List.of(), KpqLobby.pickParty(nobodyAtWonky, opq.minPlayers(), opq.maxPlayers(),
                        opq.minLevel(), opq.maxLevel()),
                "Kerning's lobby picks out of bots already standing at the recruit NPC, and the tower has none - this is"
                        + " the gap OpqLobby fills by spawning the party on the row's own map");

        List<KpqLobby.Candidate> fiveAtKerning = new ArrayList<>();
        for (int id = 1; id <= 5; id++) {
            fiveAtKerning.add(new KpqLobby.Candidate(id, 60, true));
        }
        assertEquals(List.of(1, 2, 3, 4, 5), KpqLobby.pickParty(fiveAtKerning, opq.minPlayers(),
                opq.maxPlayers(), opq.minLevel(), opq.maxLevel()), "given five free bots in the band it would take them");
    }

    // ---- the back-off ------------------------------------------------------------------------------

    @Test
    void aChannelThatRefusedIsLeftAloneForTenMinutes() {
        long now = 1_000_000L;
        assertTrue(OpqLobby.mayTry(1, now), "a fresh channel is tried");

        OpqLobby.refused(1, now);
        assertFalse(OpqLobby.mayTry(1, now + 1), "a refused start backs the channel off");
        assertFalse(OpqLobby.mayTry(1, now + OpqLobby.REFUSED_BACKOFF_MS - 1));
        assertTrue(OpqLobby.mayTry(1, now + OpqLobby.REFUSED_BACKOFF_MS), "and tries again a minute past ten");

        assertTrue(OpqLobby.mayTry(2, now), "another channel is unaffected");
    }

    // ---- Kerning is untouched ----------------------------------------------------------------------

    @Test
    void theKerningRowKeepsItsOwnFrontDoorAndItsOwnSwitch() {
        Content kpq = MechanicsRuns.contentFor("KerningPQ");
        assertNotNull(kpq);
        assertEquals(9020000, kpq.entryNpc(), "Lakelis, the Kerning handshake");
        assertEquals(103000000, kpq.recruitMap());
        assertEquals(21, kpq.minLevel());
        assertEquals(255, kpq.maxLevel(), "KerningPQ.js:28 maxLevel = 255: the row copies the script, not a level cap");
        assertTrue(kpq.flag() == null || kpq.flag().isBlank(),
                "Kerning answers to -Dslums.mechanics alone, as it always did - a blank row flag means exactly that");
    }
}
