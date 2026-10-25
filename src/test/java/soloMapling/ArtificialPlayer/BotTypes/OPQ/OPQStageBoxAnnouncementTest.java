package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "PQ bots are not great": handleStage2Navigate speaks "I'll get the Nth box!" on every tick while the bot walks to
 * it (a tick lands every ~1.5 s), so the party chat repeated the same line five or six times per box.
 */
class OPQStageBoxAnnouncementTest {

    @Test
    void theFirstAssignmentIsAnnounced() {
        assertTrue(OPQBot.shouldAnnounceBox(-1, 4242));
    }

    @Test
    void walkingToTheSameBoxDoesNotRepeatTheLine() {
        assertFalse(OPQBot.shouldAnnounceBox(4242, 4242));
    }

    @Test
    void aDifferentBoxIsAnnouncedAgain() {
        assertTrue(OPQBot.shouldAnnounceBox(4242, 5150));
    }

    @Test
    void noAssignmentMeansNothingToSay() {
        assertFalse(OPQBot.shouldAnnounceBox(-1, -1));
        assertFalse(OPQBot.shouldAnnounceBox(4242, -1));
    }
}
