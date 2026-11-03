package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 188 F1: Assistant Bonnie (chapel, 9201008.js) read {@code Channel} before the script declared it, so every
 * successful chapel booking died with "Channel is not defined" after the reservation was queued and the ticket taken,
 * and no invitation was ever handed out. Nicole (cathedral, 9201005.js) is the twin and must keep working.
 */
class MarriageChapelBookingTest {

    /** Walks the "I have an engagement and want to arrange the wedding" path (menu choice 1). */
    private static MarriageNpcMocks.Couple book(String script, int ticket, int invitation) throws Exception {
        MarriageNpcMocks.Couple c = new MarriageNpcMocks.Couple();
        c.player.items.put(ticket, 1);
        c.here.timeLeft = "Venue - 59 minutes, 0 seconds from now";
        c.here.pushResult = 3;
        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 1, 1);
        return c;
    }

    @ParameterizedTest
    @CsvSource({"9201005.js,5251000,4031395", "9201008.js,5251001,4031377"})
    void aBookingHandsBothPartnersTheirFifteenInvitations(String script, int ticket, int invitation) throws Exception {
        MarriageNpcMocks.Couple c = book(script, ticket, invitation);

        assertEquals(1, c.here.pushes.size(), "one reservation queued");
        assertTrue(c.log.has("A gain " + invitation + " 15"), "player got the invitations: " + c.log.events);
        assertTrue(c.log.has("B gain " + invitation + " 15"), "partner got the invitations: " + c.log.events);
        assertTrue(c.log.has("A gain " + ticket + " -1"), "ticket consumed: " + c.log.events);
        assertTrue(c.log.lastText.contains("15 Wedding Tickets"), c.log.lastText);
    }
}
