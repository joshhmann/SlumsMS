package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Henesys Invasion (TEST 2026-10-27): a roamer whose walk was dropped must walk again to a boss that stands still. */
class RoamApproachReissueTest {

    @Test
    void stillWalkingToTheSameSpotIsNotReissued() {
        assertTrue(RoamStrategy.alreadyHeading(1000, 1004, true));
    }

    @Test
    void aDroppedWalkToTheSameSpotIsReissued() {
        // the driver gave up after 8 s without progress; the boss has not moved, so tx == last target
        assertFalse(RoamStrategy.alreadyHeading(1000, 1000, false));
    }

    @Test
    void aMovedTargetIsReissuedEvenWhileWalking() {
        assertFalse(RoamStrategy.alreadyHeading(1000, 1400, true));
    }
}
