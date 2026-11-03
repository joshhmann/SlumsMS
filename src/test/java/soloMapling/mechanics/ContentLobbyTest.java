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
 * Ticket 164, the last piece of the PQ lane: bots start a party quest themselves, for any row whose lobby is switched on.
 *
 * <p>Every assertion here is row-parameterised - it runs for each event script name in {@link ContentLobby#rows()} - so
 * a row that joins the list without a content row, a front door, a recruit map or a party size fails here rather than at
 * boot. Three rows join the loop today - Orbis PQ, Ludibrium's nine stages and Henesys PQ - and a fourth joins it by
 * being added to {@link ContentLobby#rows()} once its content row lands.
 *
 * <p>What is tested is the gate (three switches per row, all off by default), the row each lobby reads, the ten-minute
 * back-off, and the reason Kerning's lobby - which stays exactly as it was - cannot staff these towers. Nothing here runs
 * a channel.
 */
class ContentLobbyTest {

    private static final String MECHANICS = "slums.mechanics";

    @AfterEach
    void switchesBackOff() {
        for (String row : ContentLobby.rows()) {
            Content content = MechanicsRuns.contentFor(row);
            if (content != null) {
                System.clearProperty(ContentLobby.switchFor(content));
                System.clearProperty(ContentLobby.SWITCH_PREFIX + content.id() + ".runs");
            }
        }
        System.clearProperty(MECHANICS);
        ContentLobby.clearForTest();
    }

    private static List<Content> staffedRows() {
        List<Content> out = new ArrayList<>();
        for (String row : ContentLobby.rows()) {
            Content content = MechanicsRuns.contentFor(row);
            assertNotNull(content, row + " is in ContentLobby.rows() but has no content row");
            out.add(content);
        }
        return out;
    }

    // ---- every staffed row can be started by a bot party -------------------------------------------

    @Test
    void everyStaffedRowNamesAFrontDoorARecruitMapAndAParty() {
        assertEquals(List.of("OrbisPQ", "LudiPQ", "HenesysPQ",
                "CafePQ_1", "CafePQ_2", "CafePQ_3", "CafePQ_4", "CafePQ_5", "CafePQ_6", "LudiMazePQ", "PiratePQ"),
                ContentLobby.rows(),
                "every row in the list staffs itself: the tower, the Ludibrium nine, the primrose hill, the six cafe"
                        + " areas and the maze");
        for (Content content : staffedRows()) {
            assertEquals(MechanicsModel.Kind.PQ, content.kind(), content.id() + " is a party quest");
            assertTrue(content.entryNpc() > 0, content.id() + " needs an entryNpc: a bot talks to an NPC, it has no button");
            assertTrue(content.recruitMap() > 0, content.id() + " needs a recruitMap to spawn the party on");
            assertTrue(content.minPlayers() >= 2 && content.maxPlayers() >= content.minPlayers(),
                    content.id() + " needs a party size the staging can satisfy");
            assertTrue(content.minLevel() > 0 && content.maxLevel() >= content.minLevel(),
                    content.id() + " needs a level band for the bots it spawns");
            assertFalse(content.stages().isEmpty(), content.id() + " needs stages to play");
            assertEquals(ContentLobby.SWITCH_PREFIX + content.id() + ContentLobby.SWITCH_SUFFIX,
                    ContentLobby.switchFor(content), "one switch per row, named after the row");
        }
    }

    // ---- the gate ----------------------------------------------------------------------------------

    /** The switch that turns the ROW on: the row's own {@code flag:}, which several rows may share. */
    private static String rowSwitch(Content content) {
        return content.flag() == null || content.flag().isBlank() ? MECHANICS + "." + content.id() : content.flag();
    }

    @Test
    void aLobbyNeedsAllThreeSwitchesAndOrbisIsTheRowThatProvesIt() {
        for (Content content : staffedRows()) {
            String lobby = ContentLobby.switchFor(content);
            assertFalse(ContentLobby.enabledFor(content), content.id() + ": no switches, nothing starts");

            System.setProperty(MECHANICS, "true");
            assertFalse(ContentLobby.enabledFor(content), content.id() + ": mechanics alone must not start a lobby");

            System.setProperty(rowSwitch(content), "true");
            assertFalse(ContentLobby.enabledFor(content), content.id() + ": the row alone must not spawn a party");

            System.setProperty(lobby, "true");
            System.clearProperty(rowSwitch(content));
            assertFalse(ContentLobby.enabledFor(content),
                    content.id() + ": the lobby switch without the row's own switch is still off - a server that plays a"
                            + " row by hand never gets spawned bots");

            System.setProperty(rowSwitch(content), "true");
            assertTrue(ContentLobby.enabledFor(content), content.id() + ": three flags, and then bots form a party");

            System.clearProperty(MECHANICS);
            assertFalse(ContentLobby.enabledFor(content), content.id() + ": losing any one of them turns it off");
            System.setProperty(MECHANICS, "true");
        }
    }

    @Test
    void oneRunPerRowUnlessTheServerSaysOtherwise() {
        for (Content content : staffedRows()) {
            assertEquals(1, ContentLobby.maxRuns(content), content.id() + " runs one party at a time by default");
            System.setProperty(ContentLobby.SWITCH_PREFIX + content.id() + ".runs", "3");
            assertEquals(3, ContentLobby.maxRuns(content));
            System.setProperty(ContentLobby.SWITCH_PREFIX + content.id() + ".runs", "0");
            assertEquals(0, ContentLobby.maxRuns(content), "zero is honoured: a switch can be on and start nothing");
            System.clearProperty(ContentLobby.SWITCH_PREFIX + content.id() + ".runs");
        }
    }

    @Test
    void nothingIsOnAtBootWithoutSwitches() {
        for (Content content : staffedRows()) {
            assertFalse(ContentLobby.enabledFor(content));
        }
        ContentLobby.startIfEnabled();
        assertEquals(0, ContentLobby.runningForTest(), "no row is on, so the timer is never registered");
    }

    // ---- the back-off ------------------------------------------------------------------------------

    @Test
    void aChannelThatRefusedIsLeftAloneForTenMinutesPerRow() {
        long now = 1_000_000L;
        String orbis = "opq:1";
        assertTrue(ContentLobby.mayTry(orbis, now), "a fresh channel is tried");

        ContentLobby.refuse(orbis, now);
        assertFalse(ContentLobby.mayTry(orbis, now + 1), "a refused start backs that channel off");
        assertFalse(ContentLobby.mayTry(orbis, now + ContentLobby.REFUSED_BACKOFF_MS - 1));
        assertTrue(ContentLobby.mayTry(orbis, now + ContentLobby.REFUSED_BACKOFF_MS), "and tries again a minute past ten");

        assertTrue(ContentLobby.mayTry("lpq:1", now), "another row on the same channel is unaffected");
        assertTrue(ContentLobby.mayTry("opq:2", now), "another channel is unaffected");
    }

    // ---- Kerning is untouched ----------------------------------------------------------------------

    @Test
    void kerningKeepsItsOwnLobbyAndItsRowIsUnchanged() {
        assertFalse(ContentLobby.rows().contains("KerningPQ"),
                "Kerning's lobby picks bots already standing at Lakelis; it is not a spawn-and-start row");
        Content kpq = MechanicsRuns.contentFor("KerningPQ");
        assertNotNull(kpq);
        assertEquals(9020000, kpq.entryNpc(), "Lakelis still the handshake KpqStaging talks to");
        assertEquals(103000000, kpq.recruitMap());
        assertTrue(kpq.flag() == null || kpq.flag().isBlank(),
                "Kerning answers to -Dslums.mechanics alone, as it always did");
    }

    @Test
    void kerningLobbyPickerCannotStaffAnyOfTheseRows() {
        List<KpqLobby.Candidate> nobodyAtTheDoor = new ArrayList<>();
        for (Content content : staffedRows()) {
            assertEquals(List.of(), KpqLobby.pickParty(nobodyAtTheDoor, content.minPlayers(), content.maxPlayers(),
                            content.minLevel(), content.maxLevel()),
                    content.id() + ": the picker only sees bots already standing there, and these towers have none - this"
                            + " is the gap ContentLobby fills by spawning the party on the row's recruit map");

            List<KpqLobby.Candidate> free = new ArrayList<>();
            for (int id = 1; id <= content.minPlayers(); id++) {
                free.add(new KpqLobby.Candidate(id, content.minLevel() + 1, true));
            }
            assertEquals(content.minPlayers(),
                    KpqLobby.pickParty(free, content.minPlayers(), content.maxPlayers(), content.minLevel(),
                            content.maxLevel()).size(),
                    content.id() + ": given enough free bots in the band the picker would take them");
        }
    }

    // ---- the row this commit ships ----------------------------------------------------------------

    @Test
    void orbisFrontDoorIsWonkyOnTheRecruitMap() {
        Content opq = MechanicsRuns.contentFor("OrbisPQ");
        assertNotNull(opq);
        assertEquals(2013000, opq.entryNpc(), "Wonky on 200080101 is who a bot leader accepts from");
        assertEquals(200080101, opq.recruitMap());
        assertEquals(5, opq.minPlayers(), "OrbisPQ.js:27 minPlayers = 5");
        assertEquals(6, opq.maxPlayers());
        assertEquals(51, opq.minLevel());
        assertEquals(255, opq.maxLevel(), "OrbisPQ.js:28 maxLevel = 255, not upstream's 70");
        assertEquals("OrbisPQ", opq.eventScript());
    }
}
