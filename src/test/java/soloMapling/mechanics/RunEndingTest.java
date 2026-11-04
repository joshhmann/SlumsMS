package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RunState.Ending;

/**
 * Run chatter (owner 2026-11-03: "outcomes once bots exit out they say things like woo we beat or dang we tried but
 * failed"): every way a run can end is one of three endings, and only two of them are spoken about. A run says nothing
 * unless it is known to have been played to a win or to a failure, so a run a human ended, one that ended for lack of
 * members, or one that never started can never make a bot blame the boss.
 */
class RunEndingTest {

    private static final long START = 1_000_000L;
    private static final int LEADER = 1;

    private static Content kpq() {
        return KpqStageDataTest.kpq();
    }

    private static List<RunMember> party() {
        List<RunMember> out = new ArrayList<>();
        out.add(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT));
        for (int id = 2; id <= 4; id++) {
            out.add(new RunMember(id, false, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    private static Orchestrator stageOneOnly() {
        return new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 1);
    }

    /** The instance as the orchestrator reads it; {@code clearedNow} says whether the stage's clear is on. */
    private static final class View implements StageView {
        final RunState run;
        boolean clearedNow;

        View(RunState run) {
            this.run = run;
        }

        @Override
        public Stage stage() {
            return run.stageData();
        }

        @Override
        public List<RunMember> members() {
            return run.members();
        }

        @Override
        public String roleOf(int characterId) {
            return "any";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public boolean cleared() {
            return clearedNow;
        }
    }

    // ---- WON --------------------------------------------------------------------------------------

    @Test
    void clearingTheLastStageToPlayIsAWin() {
        Orchestrator o = stageOneOnly();
        RunState run = o.start(kpq(), Tier.NORMAL, party());
        View view = new View(run);
        view.clearedNow = true;

        o.tick(run, view, START);

        assertTrue(run.ended());
        assertEquals("cleared", run.endedBecause());
        assertEquals(Ending.WON, run.ending());
    }

    @Test
    void aRunStillGoingHasNothingToSayYet() {
        Orchestrator o = stageOneOnly();
        RunState run = o.start(kpq(), Tier.NORMAL, party());

        assertEquals(Ending.SILENT, run.ending(), "no ending before the end");
    }

    @Test
    void aRunStoppedAfterItClearedIsStillAWin() {
        Orchestrator o = stageOneOnly();
        RunState run = o.start(kpq(), Tier.NORMAL, party());
        run.markCleared(5_000); // the last stage cleared, the prizes and bonus are still being played
        o.abort(run, "stopped by a GM");

        assertEquals(Ending.WON, run.ending(), "the party did clear it, however the end came");
    }

    // ---- LOST -------------------------------------------------------------------------------------

    @Test
    void theStageClockRunningOutIsALoss() {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 1_000, 1);
        RunState run = o.start(kpq(), Tier.NORMAL, party());
        View view = new View(run);

        o.tick(run, view, START);
        assertEquals(Ending.SILENT, run.ending(), "still playing");
        o.tick(run, view, START + 60_000);

        assertTrue(run.ended());
        assertTrue(run.endedBecause().startsWith("time budget"), run.endedBecause());
        assertEquals(Ending.LOST, run.ending());
    }

    @Test
    void aStageThatSaysItCannotBeClearedIsALoss() {
        StageSolver giveUp = new StageSolver() {
            @Override
            public String id() {
                return "question-coupons";
            }

            @Override
            public SolverStep next(StageView view, RunMember member) {
                return new SolverStep.Abandon("nothing left to hit");
            }
        };
        Orchestrator o = new Orchestrator(Map.of("question-coupons", giveUp), new Random(1), 0, 1);
        RunState run = o.start(kpq(), Tier.NORMAL, party());

        o.tick(run, new View(run), START);

        assertEquals("nothing left to hit", run.endedBecause());
        assertEquals(Ending.LOST, run.ending());
    }

    @Test
    void aBotThatCannotBePutInItsRoomIsALoss() {
        Orchestrator o = stageOneOnly();
        RunState run = o.start(kpq(), Tier.NORMAL, party());
        EventInstanceManager eim = mock(EventInstanceManager.class);

        new LiveStageView(run, eim, START).endRun("bot2 could not be put in map 1");

        assertEquals(Ending.LOST, run.ending());
    }

    @Test
    void anNpcThatKeepsFailingIsALoss() {
        Orchestrator o = stageOneOnly();
        RunState run = o.start(kpq(), Tier.NORMAL, party());

        o.fail(run, "NPC 2040034's script failed 3 times in a row");

        assertEquals(Ending.LOST, run.ending());
        assertTrue(run.events().stream().anyMatch(e -> e.line().startsWith("abort: NPC 2040034")),
                "the run's own log says the same thing it always did: " + run.events());
    }

    // ---- SILENT -----------------------------------------------------------------------------------

    @Test
    void aPartyThatFallsApartIsSilent() {
        for (int lost : new int[]{LEADER, 4}) {
            Orchestrator o = stageOneOnly();
            RunState run = o.start(kpq(), Tier.NORMAL, party());
            o.memberLost(run, lost, "left the party", START);
            if (!run.ended()) {
                o.memberLost(run, 3, "left the party", START);
                o.memberLost(run, 2, "left the party", START);
            }
            assertTrue(run.ended(), "member " + lost);
            assertEquals(Ending.SILENT, run.ending(), run.endedBecause());
        }
    }

    @Test
    void everyOtherEndIsSilent() {
        for (String why : List.of("the event ended", "the server is shutting down", "exception: boom",
                "stopped by a GM", "yielded to another party", "roles unfilled for 30 s: [x]",
                "this event instance already has a run", "could not start: refused")) {
            Orchestrator o = stageOneOnly();
            RunState run = o.start(kpq(), Tier.NORMAL, party());
            o.abort(run, why);
            assertEquals(Ending.SILENT, run.ending(), why);
        }
    }

    @Test
    void aRunThatNeverStartedIsSilent() {
        Orchestrator o = stageOneOnly();
        RunState run = o.start(kpq(), Tier.NORMAL, List.of(new RunMember(LEADER, true, false, MechanicsPersona.PERFECT)));

        assertNotNull(run.endedBecause(), "no bots: nothing to orchestrate");
        assertEquals(Ending.SILENT, run.ending());
    }

    @Test
    void theFirstReasonToEndARunDecidesItsEnding() {
        Orchestrator o = stageOneOnly();
        RunState lostFirst = o.start(kpq(), Tier.NORMAL, party());
        o.fail(lostFirst, "time budget");
        o.abort(lostFirst, "the event ended");
        assertEquals(Ending.LOST, lostFirst.ending(), "a later abort does not turn a loss into silence");

        RunState silentFirst = o.start(kpq(), Tier.NORMAL, party());
        o.abort(silentFirst, "the leader left");
        o.fail(silentFirst, "time budget");
        assertEquals(Ending.SILENT, silentFirst.ending(), "a later failure does not blame the party for a human's exit");
        assertEquals("the leader left", silentFirst.endedBecause());
    }
}
