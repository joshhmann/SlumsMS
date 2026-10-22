package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** SlumsMS 1.5.0 (ticket 58, owner 2026-10-21): any two characters may get engaged and marry. */
class SameGenderEngagementTest {

    @Test
    void breakingAnEngagementFindsThePartnersTokenWithoutGender() {
        // Recorded on the partner: used as is.
        assertEquals(4031358, RingActionHandler.partnerEngagementItem(4031357, 4031358, id -> false));
        // Not recorded: the other half of the pair, whichever the partner carries.
        assertEquals(4031358, RingActionHandler.partnerEngagementItem(4031357, -1, id -> id == 4031358));
        assertEquals(4031357, RingActionHandler.partnerEngagementItem(4031358, -1, id -> id == 4031357));
    }

    @Test
    void noGenderRuleIsLeftInTheProposalOrTheRingBox() throws Exception {
        String handler = Files.readString(Path.of("src/main/java/net/server/channel/handlers/RingActionHandler.java"));
        assertFalse(handler.contains("You may only propose to a"), "the same-gender refusal is gone");
        assertFalse(handler.contains("getGender()"), "nothing in the engagement flow reads gender");
        String moony = Files.readString(Path.of("scripts/npc/9201000.js"));
        assertFalse(moony.contains("only for males"), "Moony crafts ring boxes for anyone");
    }
}
