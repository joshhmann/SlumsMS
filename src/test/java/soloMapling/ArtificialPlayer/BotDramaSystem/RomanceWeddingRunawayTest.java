package soloMapling.ArtificialPlayer.BotDramaSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6 t-rm-4 (2026-10-23 full run): the wedding staged for MARRIED and CRASH was run away from at +38 s by the random
 * 8% beach, so the objection and the crash never happened. The QA staging turns that beat off for the wedding it
 * stages; a forced runaway still runs away.
 */
class RomanceWeddingRunawayTest {

    @AfterEach
    void reset() {
        RomanceWedding.resetForTest();
    }

    @Test
    void theStagedWeddingDoesNotRunAwayOnTheRandomRoll() {
        RomanceWedding.noRandomRunaway();
        assertFalse(RomanceWedding.runawayBeat(true, 0.0), "0.0 is under the 0.08 chance: it would have run away");
        assertTrue(RomanceWedding.runawayBeat(true, 0.0), "off for that one wedding only");
    }

    @Test
    void aForcedRunawayStillRunsAwayFromTheStagedWedding() {
        RomanceWedding.noRandomRunaway();
        RomanceWedding.force(RomanceWedding.Drama.RUNAWAY);
        assertTrue(RomanceWedding.runawayBeat(true, 0.9), "a forced beat does not roll");
        assertTrue(RomanceWedding.runawayBeat(true, 0.0),
                "and the staging is used up with it: the next wedding rolls the 0.08 again");
    }

    @Test
    void everyOtherWeddingStillRollsTheEightPercent() {
        assertTrue(RomanceWedding.runawayBeat(true, 0.0));
        assertFalse(RomanceWedding.runawayBeat(true, 0.5));
        assertFalse(RomanceWedding.runawayBeat(false, 0.0), "@botdrama off: no drama at all");
        RomanceWedding.force(RomanceWedding.Drama.RUNAWAY);
        assertFalse(RomanceWedding.runawayBeat(false, 0.0), "@botdrama off wins over a forced beat");
    }
}
