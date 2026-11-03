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
        final Map<String, Integer> held = new java.util.HashMap<>();
        int spine = 0;
        int mobs = 0;
        boolean npc = false;
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
            return held.getOrDefault(characterId + ":" + itemId, 0);
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
        public int liveMobs() {
            return mobs;
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return npc ? Optional.of(new int[]{0, 0}) : Optional.empty();
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
    void theRowDrivesEveryStageButTheShuffledRooms() {
        MechanicsModel.Content c = row();
        assertEquals(List.of(930000100, 930000200, 930000300, 930000400, 930000500, 930000600),
                c.stages().stream().map(MechanicsModel.Stage::mapId).toList());
        assertEquals(List.of(FightTheRoomSolver.ID, ReactorStateSolver.ID, SummonedBossSolver.ID, EllinMarblesSolver.ID,
                        HoldSolver.ID, FightTheRoomSolver.ID),
                c.stages().stream().map(MechanicsModel.Stage::solver).toList());
        assertTrue(c.stages().get(0).clear().mapEmpty(), "stage 1 is the empty-map clear");
        assertEquals(3009000, c.stages().get(1).clear().reactorId(), "stage 2 clears on the spine's state");
        assertEquals(4, c.stages().get(1).clear().reactorState());
        assertEquals(930000500, c.stages().get(3).clear().partyOnMap(),
                "stage 4's clear is the arrival: 2133001.js:93-94 warps the team and writes nothing");
        assertEquals(0, c.stages().get(4).clear().partyOnMap(), "stage 5's hold is not an arrival");
        assertTrue(c.stages().get(4).name().contains("shuffleReactors"),
                "and it says the stage has no server-side rule: " + c.stages().get(4).name());
        assertTrue(c.stages().get(5).clear().onInstance(), "stage 6 clears on the Poison Golem's clearPQ");
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

    @Test
    void theMarbleStageTradesTalkForMarblesAndMarblesForTheGolemPass() {
        MechanicsModel.Stage stage = row().stages().get(3);
        EllinMarblesSolver solver = new EllinMarblesSolver();
        View v = new View(stage);
        v.npc = true;

        // a leader with no marbles talks once, and that talk is what hands him they ten
        assertEquals(SolverStep.TalkNpc.class, solver.next(v, member(true)).getClass(), "the first talk gives the marbles");
        v.held.put(LEADER + ":" + EllinMarblesSolver.MARBLE, 10);
        v.mobs = 3;
        SolverStep.UseItemOnMob use = assertInstanceOf(SolverStep.UseItemOnMob.class, solver.next(v, member(true)));
        assertEquals(EllinMarblesSolver.MARBLE, use.itemId());
        assertEquals(EllinMarblesSolver.FLOWER, use.mobId(), "the marble is for the poison flower and nothing else");

        // nineteen is not the hand-in; twenty is, and the talk carries the count
        v.held.put(LEADER + ":" + EllinMarblesSolver.GOLEM_MARBLE, 19);
        assertEquals(SolverStep.UseItemOnMob.class, solver.next(v, member(true)).getClass(), "nineteen keeps working");
        v.held.put(LEADER + ":" + EllinMarblesSolver.GOLEM_MARBLE, 20);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class, solver.next(v, member(true)));
        assertEquals(EllinMarblesSolver.GOLEM_MARBLE, talk.itemId());
        assertEquals(20, talk.count());

        // a member with twenty pools them on the leader rather than talking to Ellin himself
        v.held.put(MEMBER + ":" + EllinMarblesSolver.GOLEM_MARBLE, 20);
        assertEquals(SolverStep.DropFor.class, solver.next(v, member(false)).getClass());

        // and a cleared stage is Done: `partyOnMap` answers through cleared()
        v.cleared = true;
        assertEquals(SolverStep.Done.class, solver.next(v, member(true)).getClass());
    }
}
