package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164, Ellin PQ: the two primitives it added, and what the row honestly claims.
 *
 * <p>The marble rule is not re-tested here - it lives in {@code server.life.MonsterCatch} and is HP-gated by the same
 * numbers the client handler used. What this class pins is the part the engine owns: the reactor-state clear reaching the
 * row reader, the solver that opens the spine, and the fact that the marble stage is a HOLD rather than a claim.
 */
class EllinPqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content row() {
        MechanicsModel.Content c = null;
        for (MechanicsModel.Content each : StageData.load().content()) {
            if ("ellinpq".equals(each.id())) {
                c = each;
            }
        }
        assertTrue(c != null, "the row must load from the classpath: " + StageData.load().errors());
        return c;
    }

    static final class View implements StageView {
        private final MechanicsModel.Stage stage;
        final Map<Integer, Integer> at = new java.util.HashMap<>();
        int spine = 0;
        boolean cleared;

        View(MechanicsModel.Stage stage) {
            this.stage = stage;
            at.put(LEADER, stage.mapId());
            at.put(MEMBER, stage.mapId());
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public int mapOf(int characterId) {
            return at.getOrDefault(characterId, stage.mapId());
        }

        @Override
        public java.util.OptionalInt answerFor(int characterId) {
            return java.util.OptionalInt.empty();
        }

        @Override
        public List<RunMember> members() {
            return List.of(member(true), member(false));
        }

        @Override
        public String roleOf(int characterId) {
            return null;
        }

        @Override
        public Optional<RunMember> leader() {
            return Optional.of(member(true));
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
        public int reactorState(int reactorId) {
            return reactorId == 3009000 ? spine : -1;
        }

        @Override
        public boolean cleared() {
            if (cleared) {
                return true;
            }
            MechanicsModel.Clear c = stage.clear();
            return c != null && c.reactorId() > 0 && spine >= c.reactorState();
        }
    }

    private static RunMember member(boolean leader) {
        return new RunMember(leader ? LEADER : MEMBER, leader, true, MechanicsPersona.PERFECT);
    }

    @Test
    void theRowDrivesThreeStagesAndHoldsTheMarbleOne() {
        MechanicsModel.Content c = row();
        assertEquals(List.of(930000100, 930000200, 930000300, 930000400),
                c.stages().stream().map(MechanicsModel.Stage::mapId).toList());
        assertEquals(List.of(FightTheRoomSolver.ID, ReactorStateSolver.ID, SummonedBossSolver.ID, HoldSolver.ID),
                c.stages().stream().map(MechanicsModel.Stage::solver).toList());
        assertTrue(c.stages().get(0).clear().mapEmpty(), "stage 1 is the empty-map clear");
        assertEquals(3009000, c.stages().get(1).clear().reactorId(), "stage 2 clears on the spine's state");
        assertEquals(4, c.stages().get(1).clear().reactorState());
        assertTrue(c.stages().get(3).clear().onInstance(), "the hold never clears on its own");
        assertTrue(c.stages().get(3).name().contains("warp"), "and it says the clear is a warp: "
                + c.stages().get(3).name());
        assertEquals(44, c.minLevel(), "the level band comes from EllinPQ.js:28");
        assertEquals(30, c.timeLimitMinutes());
        assertEquals(4, c.minPlayers());
        assertEquals(6, c.maxPlayers());
    }

    @Test
    void theSpineSolverHitsUntilTheStateThePortalWants() {
        MechanicsModel.Stage stage = row().stages().get(1);
        View v = new View(stage);
        ReactorStateSolver solver = new ReactorStateSolver();
        for (int state = 0; state < 4; state++) {
            v.spine = state;
            assertEquals(new SolverStep.HitReactor(3009000), solver.next(v, member(true)),
                    "state " + state + " is under 4");
        }
        v.spine = 4;
        assertFalse(solver.next(v, member(true)) instanceof SolverStep.HitReactor,
                "state 4 is the clear: nothing more to hit");
        v.spine = 6;
        assertFalse(solver.next(v, member(true)) instanceof SolverStep.HitReactor, "a later state is still done");
        v.at.put(MEMBER, 930000100);
        v.spine = 0;
        assertInstanceOf(SolverStep.WarpTo.class, solver.next(v, member(false)), "off the map, back to the reactor");
        v.at.put(MEMBER, 930000200);
        v.cleared = true;
        assertInstanceOf(SolverStep.Done.class, solver.next(v, member(true)));
    }

    @Test
    void aStaleSpineOfTheWrongReactorNeverOpensTheStage() {
        MechanicsModel.Stage stage = row().stages().get(1);
        View v = new View(stage);
        v.spine = 4;
        // the clear asks for 3009000 specifically; another reactor answering -1 must not count
        assertTrue(v.cleared(), "this fake answers 3009000");
        MechanicsModel.Clear other = MechanicsModel.Clear.ofReactorState(3002000, 4);
        assertFalse(other.reactorId() == 3009000, "and a different reactor is a different clear");
    }
}
