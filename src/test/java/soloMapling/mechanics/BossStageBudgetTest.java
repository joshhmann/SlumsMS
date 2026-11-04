package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

/**
 * 1.6.4 boss lane: a boss run's clock is per stage. Horntail's head rooms each get their own 120 minutes, and the body
 * room gets 60 because its reactor restarts the script's own timer ({@code scripts/reactor/2401000.js:33}); a party
 * that spends 110 minutes in the head rooms must not be thrown out of the body room nine minutes in, as the row's one
 * 120-minute deadline counted from stage 1 would do.
 */
class BossStageBudgetTest {

    private static final long MIN = 60_000L;
    private static final long START = 1_000_000L;

    @Test
    void aLateBodyRoomIsNotCutOffByTheRowsOneDeadline() {
        Content horntail = MechanicsRuns.contentFor("HorntailBattle");
        Orchestrator orch = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = orch.start(horntail, Tier.NORMAL, party());
        View view = new View(horntail);

        long bodyRoomAt = START + 110 * MIN;
        run.markStarted(START);
        run.setStage(3, bodyRoomAt); // the party reached the body room 110 minutes in
        view.stage = horntail.stages().get(2);

        // 30 minutes into the body room: the row's one 120-minute clock counted from stage 1 would end the run here
        // (140 minutes elapsed), while the room's own hour has half of itself left
        orch.tick(run, view, bodyRoomAt + 30 * MIN);
        assertFalse(run.ended(), "stage 3's own hour still had half left: " + run.endedBecause());
    }

    @Test
    void theBodyRoomsOwnHourEndsTheRun() {
        Content horntail = MechanicsRuns.contentFor("HorntailBattle");
        Orchestrator orch = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = orch.start(horntail, Tier.NORMAL, party());
        View view = new View(horntail);

        long bodyRoomAt = START + 110 * MIN;
        run.markStarted(START);
        run.setStage(3, bodyRoomAt);
        view.stage = horntail.stages().get(2);

        orch.tick(run, view, bodyRoomAt + 61 * MIN);
        assertTrue(run.ended(), "the room's own hour ran out");
        assertTrue(run.endedBecause().contains("time budget of 60 min"), run.endedBecause());
    }

    @Test
    void aStageWithoutItsOwnLimitUsesTheRows() {
        Content horntail = MechanicsRuns.contentFor("HorntailBattle");
        Orchestrator orch = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = orch.start(horntail, Tier.NORMAL, party());
        View view = new View(horntail);

        long headRoomAt = START + 30 * MIN;
        run.markStarted(START);
        run.setStage(1, headRoomAt);
        view.stage = horntail.stages().get(0);

        // stage 1 carries its own 120 minutes, so its fresh clock is not the row's 120 minutes from START
        orch.tick(run, view, headRoomAt + 119 * MIN);
        assertFalse(run.ended(), run.endedBecause());
    }

    /** A human leading five bots: exactly one leader, at least one bot, and Horntail's own six-member minimum. */
    private static List<RunMember> party() {
        List<RunMember> out = new java.util.ArrayList<>();
        out.add(new RunMember(1, true, false, MechanicsPersona.PERFECT));
        for (int id = 2; id <= 6; id++) {
            out.add(new RunMember(id, false, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    /** The shipped row with the stage the run is standing on; everything else is empty. */
    private static final class View implements StageView {

        private final Content content;
        private Stage stage;

        View(Content content) {
            this.content = content;
            this.stage = content.stages().get(0);
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return party();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public String roleOf(int characterId) {
            return "head";
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
    }
}
