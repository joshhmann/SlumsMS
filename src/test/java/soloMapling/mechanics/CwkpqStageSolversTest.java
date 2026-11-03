package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CWKPQ's door room and its last room, driven off the shipped row rather than a hand-built stage: `content/cwkpq.yaml`
 * is loaded from the classpath, so these tests fail if the row and the solvers stop agreeing.
 */
class CwkpqStageSolversTest {

    private static MechanicsModel.Content row() {
        StageData.Result result = StageData.load();
        assertTrue(result.errors().isEmpty(), "the shipped rows must load cleanly: " + result.errors());
        return result.content().stream().filter(c -> "cwkpq".equals(c.id())).findFirst()
                .orElseThrow(() -> new AssertionError("cwkpq row missing"));
    }

    /** A party in the stage's room, the event's properties, and however many monsters are out. */
    private static final class View implements StageView {
        private final MechanicsModel.Stage stage;
        private final List<RunMember> members = new ArrayList<>();
        private final Map<Integer, Integer> at = new LinkedHashMap<>();
        private final Map<String, String> props = new LinkedHashMap<>();
        private int mobs;

        View(MechanicsModel.Stage stage, int party) {
            this.stage = stage;
            for (int i = 1; i <= party; i++) {
                members.add(new RunMember(i, i == 1, true, MechanicsPersona.PERFECT));
                at.put(i, stage.mapId());
            }
        }

        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public List<RunMember> members() { return members; }
        @Override public String roleOf(int id) { return stage.roles().get(0).id(); }
        @Override public OptionalInt answerFor(int id) { return OptionalInt.empty(); }
        @Override public int itemCount(int id, int item) { return 0; }
        @Override public Optional<String> eventProperty(String name) { return Optional.ofNullable(props.get(name)); }
        @Override public int[] positionOf(int id) { return new int[]{0, 0}; }
        @Override public int mapOf(int id) { return at.getOrDefault(id, stage.mapId()); }
        @Override public int liveMobs() { return mobs; }
    }

    @Test
    void theRowCarriesFourStagesAndTheTwoNewSolversAreTheirs() {
        MechanicsModel.Content c = row();
        assertEquals(4, c.stages().size(), "four rooms are modelled; the flames are not (nothing reads glpq_fN)");
        assertEquals(List.of(CwkpqSigilSolver.ID, CwkpqDoorSolver.ID, CwkpqSigilSolver.ID, CwkpqBossSolver.ID),
                c.stages().stream().map(MechanicsModel.Stage::solver).toList());
        assertEquals(List.of(610030400, 610030300, 610030500, 610030800),
                c.stages().stream().map(MechanicsModel.Stage::mapId).toList());
        assertEquals(6, c.minPlayers(), "the script's own floor (CWKPQ.js:27)");
        assertEquals(30, c.maxPlayers());
        assertEquals(90, c.minLevel());
        assertTrue(c.stages().get(3).clear().onInstance(), "the last room clears on the flag clearPQ() sets");
    }

    @Test
    void theDoorRoomRaisesItsSigilsThenCountsPassersAndStopsAtFive() {
        MechanicsModel.Stage door = row().stages().get(1);
        assertEquals("glpq3_p", door.clear().eventProperty(), "glpqPortal3.js:4 is the exit's own condition");
        assertEquals("5", door.clear().equals());
        View v = new View(door, 6);
        CwkpqDoorSolver solver = new CwkpqDoorSolver();

        // glpq3 short: the two reactors are worked, the leader on one and the rest on the other
        v.props.put("glpq3", "0");
        assertEquals(CwkpqDoorSolver.REACTOR_A,
                assertInstanceOf(SolverStep.HitReactor.class, solver.next(v, v.members().get(0))).reactorId());
        assertEquals(CwkpqDoorSolver.REACTOR_B,
                assertInstanceOf(SolverStep.HitReactor.class, solver.next(v, v.members().get(1))).reactorId());

        // sigils up, nobody through yet: the door is the step, for every member
        v.props.put("glpq3", "5");
        v.props.put("glpq3_p", "0");
        for (RunMember m : v.members()) {
            assertEquals(CwkpqDoorSolver.DOOR, assertInstanceOf(SolverStep.ScriptedPortal.class,
                    solver.next(v, m)).portalName());
        }

        // four through: still the door; five through: nothing more, the flag is the clear
        v.props.put("glpq3_p", "4");
        assertEquals(CwkpqDoorSolver.DOOR, assertInstanceOf(SolverStep.ScriptedPortal.class,
                solver.next(v, v.members().get(0))).portalName());
        v.props.put("glpq3_p", "5");
        assertTrue(v.cleared(), "five passers is the clear, and the row reads it from glpq3_p");
        assertInstanceOf(SolverStep.Done.class, solver.next(v, v.members().get(0)),
                "a cleared room is Done, never a sixth pass");
        // an overshoot (nothing stops a stray pass before the clear is read) is still not a reason to keep pressing
        View over = new View(door, 6);
        over.props.put("glpq3", "5");
        over.props.put("glpq3_p", "6");
        assertInstanceOf(SolverStep.Wait.class, solver.next(over, over.members().get(0)),
                "glpqPortal2.js:15 refuses a pass past five, so an overshot counter waits");
    }

    @Test
    void theLastRoomFightsWhateverIsOutAndOnlyTheLeaderTalksToTheHost() {
        MechanicsModel.Stage boss = row().stages().get(3);
        View v = new View(boss, 6);
        CwkpqBossSolver solver = new CwkpqBossSolver();

        v.props.put("glpq6", "0");
        v.mobs = 30;
        assertEquals(0, assertInstanceOf(SolverStep.Fight.class, solver.next(v, v.members().get(0))).mobId(),
                "the guardians are ordinary monsters: fight them, do not talk over them");

        v.mobs = 0;
        SolverStep.Converse ask = assertInstanceOf(SolverStep.Converse.class, solver.next(v, v.members().get(0)));
        assertEquals(CwkpqBossSolver.HOST, ask.npcId());
        assertEquals(List.of(0, 0), ask.answers(), "status 0 greets, status 1 acts: two answers per step");
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.members().get(1)),
                "a member who is not the leader never talks");

        // the masters appear through the same two-step talk, then the host clears the instance himself
        v.props.put("glpq6", "2");
        v.mobs = 4;
        assertInstanceOf(SolverStep.Fight.class, solver.next(v, v.members().get(0)));
        v.mobs = 0;
        assertInstanceOf(SolverStep.Converse.class, solver.next(v, v.members().get(0)));
        v.props.put("glpq6", "3");
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.members().get(0)),
                "at glpq6 == 3 the host has already called clearPQ()");
    }

    @Test
    void aMemberOffTheRoomsMapIsSentBackFirst() {
        MechanicsModel.Stage boss = row().stages().get(3);
        View v = new View(boss, 6);
        v.at.put(3, 610030600);
        SolverStep.WarpTo w = assertInstanceOf(SolverStep.WarpTo.class,
                new CwkpqBossSolver().next(v, v.members().get(2)));
        assertEquals(610030800, w.mapId());
    }
}
