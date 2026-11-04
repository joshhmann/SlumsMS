package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 1.6.4 boss lane, review fix: a {@link SolverStep.WarpTo} is only carried out for the run's current room, and a warp
 * that does not land is retried a couple of times, then reported with the bot's real map and the run is ended - a
 * member that cannot be put in its room must not be retried forever, and a step naming another map is a solver bug.
 */
class BossWarpGuardTest {

    @Test
    void aWarpThatDoesNotLandGivesUpAfterThreeTries() {
        StepExecutor.Beat beat = new StepExecutor.Beat();

        assertFalse(StepExecutor.warpGaveUp(beat), "first failure: retry");
        assertFalse(StepExecutor.warpGaveUp(beat), "second failure: retry");
        assertTrue(StepExecutor.warpGaveUp(beat), "third failure: stop retrying and end the run");
        assertTrue(StepExecutor.warpGaveUp(beat), "and it stays given up");
    }

    @Test
    void aLandedWarpResetsTheTries() {
        StepExecutor.Beat beat = new StepExecutor.Beat();
        StepExecutor.warpGaveUp(beat);
        StepExecutor.warpGaveUp(beat);
        beat.warpTries = 0; // what a landing does
        assertFalse(StepExecutor.warpGaveUp(beat), "a bot that arrives starts its count again");
    }

    @Test
    void aStepForAnotherRoomIsRefused() {
        SolverStep.WarpTo elsewhere = new SolverStep.WarpTo(240060100, "somewhere else");

        assertTrue(StepExecutor.wrongRoom(elsewhere, 240060000));
        assertFalse(StepExecutor.wrongRoom(new SolverStep.WarpTo(240060100, "this room"), 240060100));
    }

    @Test
    void endingTheRunFromTheExecutorCarriesTheReason() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("HorntailBattle");
        RunState run = new RunState(content, MechanicsModel.Tier.NORMAL,
                List.of(new RunMember(1, true, false, MechanicsPersona.PERFECT)), 7_200_000L, 3);
        LiveStageView view = new LiveStageView(run, mock(EventInstanceManager.class));
        String why = "Blip could not be put in map 240060100 (it is on 240060000): stage 2 is in this room";

        view.endRun(why);

        assertTrue(run.ended(), "the run is over");
        assertEquals(why, run.endedBecause(), "and says what happened to which bot");
    }
}
