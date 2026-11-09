package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.4 PQ polish, defect 1: STALL printed the whole-map sentinel as its target. On TEST (2026-11-01 00:31 ->
 * 11-02 03:16, 34,816 {@code [Mechanics]} lines) 180 of 186 STALL lines read {@code on 0/2147483647 x 4001008} -
 * stage 5's collectors, whose Fight step counts to {@link Integer#MAX_VALUE} because their goal is "fight while the
 * park still has monsters", not "hold 2,147,483,647 passes". A watching player reads that as a broken requirement.
 * The step's meaning comes from {@link KillCollectHandInSolver} (collector: {@code Fight(0, item, MAX_VALUE)}) and the
 * leader's {@code HAND_IN count 10} ({@code content/kpq.yaml:311-316}).
 *
 * <p>Each test states today's line first, then the corrected one - the two together are the regression pin.
 */
class StallTargetLabelTest {

    /** The exact expression {@code StepExecutor.fight} used before this fix, kept so "today's line" is literal. */
    static String oldStallLine(String name, int held, SolverStep.Fight f, String why) {
        return "STALL " + name + " on " + held + "/" + f.untilItemCount() + " x " + f.itemId() + ": " + why;
    }

    static Stage stage5() {
        Content kpq = KpqStageDataTest.kpq();
        return kpq.stages().stream().filter(s -> s.index() == 5).findFirst().orElseThrow();
    }

    @Test
    void todayTheCollectorsTargetIsTwoBillionAndNowItSaysAll() {
        SolverStep.Fight collector = new SolverStep.Fight(0, 4001008, Integer.MAX_VALUE);
        String why = "+0 in 90 s (under 2 a minute): etc 56 free of 96, 0 on the floor within 600 px (0 it may take), "
                + "at -210,-2775, level 49";
        // Today (TEST 2026-11-01 16:38:07, 180 of 186 STALL lines):
        assertEquals("STALL xCashShopX on 0/2147483647 x 4001008: " + why,
                oldStallLine("xCashShopX", 0, collector, why));
        // After:
        assertEquals("STALL xCashShopX on 0/all x 4001008: " + why, StepExecutor.stallLine("xCashShopX", 0, collector, why));
    }

    @Test
    void theCollectorsWholeMapFightSaysAllNotMaxValue() {
        assertEquals("all", StepExecutor.fightTargetLabel(new SolverStep.Fight(0, 4001008, Integer.MAX_VALUE)),
                "the step means 'every pass on the map', not 2,147,483,647 of them");
    }

    /** A step that does have a count keeps printing the count (stage 1's coupon farm: the answer, 10..35). */
    @Test
    void aCountedFightStillPrintsItsCount() {
        assertEquals("10", StepExecutor.fightTargetLabel(new SolverStep.Fight(9300001, 4001007, 10)));
        assertEquals("35", StepExecutor.fightTargetLabel(new SolverStep.Fight(9300001, 4001007, 35)));
        // And today's real counted line is unchanged, character for character:
        String why = "+2 in 90 s (under 2 a minute): etc 66 free of 96, 1 on the floor within 600 px (1 it may take), "
                + "at -542,-479, level 31";
        String today = "STALL UrSilver on 5/35 x 4001007: " + why;
        assertEquals(today, StepExecutor.stallLine("UrSilver", 5, new SolverStep.Fight(9300001, 4001007, 35), why));
    }

    /** No item: there is no count to print. */
    @Test
    void aFightForNoItemPrintsNoCount() {
        assertEquals("", StepExecutor.fightTargetLabel(new SolverStep.Fight(0, 0, 0)));
    }

    /** The position is printed once (it comes from {@code stallWhy}), so the line gains no second "at x,y". */
    @Test
    void theLineKeepsOnePosition() {
        String why = "+0 in 90 s (under 2 a minute): at -210,-2775, level 49";
        String line = StepExecutor.stallLine("xCashShopX", 0, new SolverStep.Fight(0, 4001008, Integer.MAX_VALUE), why);
        assertEquals(line.indexOf("at -210,-2775"), line.lastIndexOf("at -210,-2775"), "one position, not two");
    }

    /** The shipped stage 5 really hands the collectors the sentinel, so the label is not about a stale path. */
    @Test
    void theCollectorStepOnTheShippedStage5IsTheWholeMapOne() {
        FakeStageView view = new FakeStageView(stage5(), 4);
        SolverStep step = new KillCollectHandInSolver().next(view, new RunMember(1_000_000_002, false, true,
                MechanicsPersona.PERFECT));
        assertTrue(step instanceof SolverStep.Fight, "a collector fights on stage 5: " + step);
        SolverStep.Fight f = (SolverStep.Fight) step;
        assertEquals(Integer.MAX_VALUE, f.untilItemCount(), "the collector's goal is the whole map");
        assertEquals("all", StepExecutor.fightTargetLabel(f));
    }

    /** Four members, one leader (a bot, so no real player is steered), monsters alive, no items held. */
    static final class FakeStageView implements StageView {
        private final Stage stage;
        private final List<RunMember> members;

        FakeStageView(Stage stage, int size) {
            this.stage = stage;
            List<RunMember> m = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                m.add(new RunMember(1_000_000_001 + i, i == 0, true, MechanicsPersona.PERFECT));
            }
            this.members = List.copyOf(m);
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return members.get(0).characterId() == characterId ? "leader" : "collector";
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
            return new int[]{-100, -100};
        }

        @Override
        public int liveMobs() {
            return 11;
        }
    }

    /**
     * lpq1-pass-shortfall (QA, TEST 8 Nov 2026): a stalled stage didn't say which monsters were still alive, so
     * "21 of 25" could not be told apart from "passes lost". The STALL reason now ends with the stage's alive monsters.
     */
    @Test
    void theStallReasonNamesTheMonstersStillAlive() {
        assertEquals(", alive: 2 at (117,-2288) (-99,-2820)",
                StepExecutor.aliveTail(java.util.List.of(new java.awt.Point(117, -2288), new java.awt.Point(-99, -2820))));
        assertEquals(", alive: 0", StepExecutor.aliveTail(java.util.List.of()));
        java.util.List<java.awt.Point> many = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            many.add(new java.awt.Point(i, -i));
        }
        assertEquals(", alive: 9 at (0,0) (1,-1) (2,-2) (3,-3) (4,-4) (5,-5) +3 more", StepExecutor.aliveTail(many),
                "at most six positions, then how many more");
    }
}
