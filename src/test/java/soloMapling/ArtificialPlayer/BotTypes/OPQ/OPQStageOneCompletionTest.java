package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "PQ bots are not great": stage 1 counted as complete as soon as the phase was STAGE_1, because the check asked
 * whether NPC 2013001 stood on the leader's map and that NPC is part of 920010000 from the moment it loads. Bots
 * then warped to the top platform and stood there while the leader was still breaking clouds.
 */
class OPQStageOneCompletionTest {

    @Test
    void theTopPlatformIsStageOneComplete() {
        assertTrue(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_STAGE_1,
                new Point(OPQConstants.STAGE_1_TOP_PLATFORM_X, OPQConstants.STAGE_1_TOP_PLATFORM_Y)));
    }

    @Test
    void theFloorOfStageOneIsNotComplete() {
        // The old check answered true here, on the very first tick of the stage.
        assertFalse(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_STAGE_1, new Point(0, 0)));
        assertFalse(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_STAGE_1,
                new Point(OPQConstants.STAGE_1_ENTRY_TP.x, OPQConstants.STAGE_1_ENTRY_TP.y)));
    }

    @Test
    void theToleranceBoxIsRespected() {
        int x = OPQConstants.STAGE_1_TOP_PLATFORM_X;
        int y = OPQConstants.STAGE_1_TOP_PLATFORM_Y;
        assertTrue(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_STAGE_1,
                new Point(x + OPQConstants.STAGE_1_TOP_PLATFORM_TOL_X_PX, y)));
        assertFalse(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_STAGE_1,
                new Point(x + OPQConstants.STAGE_1_TOP_PLATFORM_TOL_X_PX + 1, y)));
        assertFalse(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_STAGE_1,
                new Point(x, y + OPQConstants.STAGE_1_TOP_PLATFORM_TOL_Y_PX + 1)));
    }

    @Test
    void theSamePositionOnAnotherMapIsNotComplete() {
        assertFalse(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_LOBBY,
                new Point(OPQConstants.STAGE_1_TOP_PLATFORM_X, OPQConstants.STAGE_1_TOP_PLATFORM_Y)));
        assertFalse(OPQOrchestrator.isAtStageOneExit(OPQConstants.OPQ_STAGE_1, null));
    }
}
