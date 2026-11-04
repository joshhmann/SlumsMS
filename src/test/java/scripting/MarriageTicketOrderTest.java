package scripting;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 188 F12: the wedding ticket is a paid cash item. It is spent only after the reservation is queued and after
 * both partners were handed their invitations, and not at all when any step before that refuses.
 */
class MarriageTicketOrderTest {

    private static Invocable open(MarriageNpcMocks.Couple c, String script, int ticket) throws Exception {
        c.player.items.put(ticket, 1);
        c.here.timeLeft = "Venue - 59 minutes, 0 seconds from now";
        Invocable npc = MarriageNpcMocks.load(script, c.cm);
        npc.invokeFunction("start");
        return npc;
    }

    @ParameterizedTest
    @CsvSource({"9201005.js,5251000,4031395", "9201008.js,5251001,4031377"})
    void theTicketIsSpentAfterBothInvitationGrants(String script, int ticket, int invitation) throws Exception {
        MarriageNpcMocks.Couple c = new MarriageNpcMocks.Couple();
        open(c, script, ticket).invokeFunction("action", 1, 1, 1);

        int spent = c.log.indexOf("A gain " + ticket + " -1");
        assertTrue(spent >= 0, "ticket spent: " + c.log.events);
        assertTrue(spent > c.log.indexOf("A gain " + invitation + " 15"), "after the player's invitations: " + c.log.events);
        assertTrue(spent > c.log.indexOf("B gain " + invitation + " 15"), "after the partner's invitations: " + c.log.events);
        assertEquals(1, c.log.events.stream().filter(e -> e.equals("A gain " + ticket + " -1")).count(), "spent once");
    }

    @ParameterizedTest
    @CsvSource({"9201005.js,5251000", "9201008.js,5251001"})
    void aRefusedReservationKeepsTheTicket(String script, int ticket) throws Exception {
        MarriageNpcMocks.Couple c = new MarriageNpcMocks.Couple();
        c.here.pushResult = -1; // queue full, or this couple is already queued
        open(c, script, ticket).invokeFunction("action", 1, 1, 1);

        assertEquals(1, c.here.pushes.size(), "the reservation was attempted");
        assertEquals(1, c.player.items.get(ticket), "ticket kept: " + c.log.events);
        assertFalse(c.log.events.stream().anyMatch(e -> e.contains("gain")), "nothing granted or consumed: " + c.log.events);
    }

    @ParameterizedTest
    @CsvSource({"9201005.js,5251000", "9201008.js,5251001"})
    void noRoomForTheInvitationsMeansNoReservationAndNoTicketUse(String script, int ticket) throws Exception {
        MarriageNpcMocks.Couple c = new MarriageNpcMocks.Couple();
        c.partner.hasSpace = false;
        open(c, script, ticket).invokeFunction("action", 1, 1, 1);

        Assertions.assertTrue(c.here.pushes.isEmpty(), "nothing reserved");
        assertEquals(1, c.player.items.get(ticket), "ticket kept");
        assertFalse(c.log.events.stream().anyMatch(e -> e.contains("gain")), c.log.events.toString());
    }
}
