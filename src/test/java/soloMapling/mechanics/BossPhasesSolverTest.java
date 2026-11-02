package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.BossTargetRules.Part;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 130 phase 5 phase A2: {@code solver: boss-phases} against a fake stage, no server.
 *
 * <p>What the room actually is, from {@code scripts/event/ZakumBattle.js} and {@code scripts/reactor/2111001.js}: the
 * fake body 8800000 and the kill target 8800002 stand before anyone clicks; the click sets {@code summoned} and spawns
 * the eight arms 8800003-8800010; each arm hit drains the body ({@code MapleMap.java:4939-4947}), so the eight arms are
 * the fight and the body is only a target once they are all down; the script sets {@code defeatedBoss=1} when 8800002
 * dies, and that - not anything the solvers can see - is the clear.
 */
class BossPhasesSolverTest {

    private static final int BODY = 8800000;
    private static final int KILL_TARGET = 8800002;
    private static final int FIRST_ARM = 8800003;
    private static final int LAST_ARM = 8800010;

    private final BossPhasesSolver solver = new BossPhasesSolver();

    @Test
    void waitsForTheSummonWhileOnlyTheMapLifeStands() {
        // before the altar click: the fake body and 8800002 stand, and neither is a target
        StageView view = new View(List.of(body(BODY, false), body(KILL_TARGET, true)), Map.of("defeatedBoss", "0"));
        SolverStep step = solver.next(view, member());

        assertInstanceOf(SolverStep.Wait.class, step);
        assertTrue(((SolverStep.Wait) step).why().contains("waiting for the boss to be summoned"), step.toString());
        assertFalse(step instanceof SolverStep.Done, "an unsummoned room is not a clear");
    }

    @Test
    void allEightArmsAreTheCandidatesAndTheBodyIsNotOne() {
        StageView view = summoned(armParts(8), true);
        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()));

        assertEquals(armIds(), fight.mobIds().stream().filter(id -> id != KILL_TARGET).toList(),
                "every arm, in the order the data lists them, and never the body");
        assertFalse(fight.mobIds().contains(BODY), "the body is damage the server throws away while an arm is up");
    }

    @Test
    void oneArmLeftIsStillTheArms() {
        List<Part> parts = armParts(1);
        parts.add(body(KILL_TARGET, true));
        parts.add(body(BODY, true)); // the body is standing and hit-able: it still must not be offered
        StageView view = new View(parts, Map.of("summoned", "true"));

        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()));
        assertEquals(List.of(LAST_ARM), fight.mobIds());
    }

    @Test
    void theBodyIsTheStepOnceTheLastArmIsDown() {
        List<Part> parts = new ArrayList<>();
        parts.add(body(BODY, true));
        parts.add(body(KILL_TARGET, true));
        StageView view = new View(parts, Map.of("summoned", "true"));
        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()));

        assertEquals(List.of(BODY, KILL_TARGET), fight.mobIds(), "the body's forms, in the data's order");
    }

    @Test
    void theFakeBodyIsNeverACandidate() {
        StageView withRealBody = new View(List.of(body(BODY, false), body(KILL_TARGET, true)),
                Map.of("summoned", "true"));
        assertFalse(assertInstanceOf(SolverStep.Fight.class, solver.next(withRealBody, member())).mobIds()
                .contains(BODY), "the fake body cannot be hit, but the real one beside it can");

        StageView fakeOnly = new View(List.of(body(BODY, false)), Map.of("summoned", "true"));
        assertInstanceOf(SolverStep.Wait.class, solver.next(fakeOnly, member()));
    }

    @Test
    void theInstanceEndsTheRunOnceTheScriptSaysCleared() {
        StageView cleared = new View(List.of(), Map.of("summoned", "true", "defeatedBoss", "1"));
        StageView standing = new View(List.of(arm(FIRST_ARM)), Map.of("summoned", "true", "defeatedBoss", "0"));

        assertInstanceOf(SolverStep.Done.class, solver.next(cleared, member()));
        assertFalse(solver.next(standing, member()) instanceof SolverStep.Done);
    }

    /**
     * The reason {@code zakum.yaml} clears on the property's <em>value</em>: the script writes {@code defeatedBoss=0} at
     * setup ({@code scripts/event/ZakumBattle.js:101}), so a presence clear would end the run on its first tick.
     */
    @Test
    void theZeroTheScriptWritesAtSetupIsNotAClear() {
        assertFalse(new View(List.of(), Map.of("defeatedBoss", "0")).cleared());
        assertTrue(new View(List.of(), Map.of("defeatedBoss", "1")).cleared());
    }

    /**
     * The body is a chain: 8800000 waits fake, the last arm's death calls {@code makeMonsterReal} on it
     * ({@code MapleMap.java:1793-1810}), and it becomes 8800001 and then 8800002 as damage is forwarded. So "8800002 is
     * not on the map" is the normal state for the first half of the fight and must never end the run.
     */
    @Test
    void armsStandingWithoutTheThirdBodyPhaseDoNotEndTheRun() {
        List<Part> parts = armParts(8);
        parts.add(body(BODY, false)); // the fake body waits, no 8800001 and no 8800002 exist yet
        StageView view = new View(parts, Map.of("summoned", "true"));

        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()));
        assertEquals(armIds(), fight.mobIds(), "the eight arms, and no verdict about the body that has not woken yet");
    }

    /** Whichever body phase is on the map is the target: the chain may be at any of the three when an arm falls. */
    @Test
    void theNextBodyPhaseIsTheTargetWhicheverPhaseItIs() {
        StageView middle = new View(List.of(body(8800001, true)), Map.of("summoned", "true"));
        assertEquals(List.of(8800001), assertInstanceOf(SolverStep.Fight.class, solver.next(middle, member())).mobIds(),
                "8800001 is a target even though nothing named 8800002 stands on the map");

        StageView twoPhases = new View(List.of(body(KILL_TARGET, true), body(BODY, true)), Map.of("summoned", "true"));
        assertEquals(List.of(BODY, KILL_TARGET),
                assertInstanceOf(SolverStep.Fight.class, solver.next(twoPhases, member())).mobIds(),
                "two body phases at once are worked in the order they wake, not the order the data lists them");
    }

    /** An empty summoned room is a moment between phases until the grace runs out, then it is the run's end. */
    @Test
    void anEmptySummonedRoomEndsTheRunOnlyAfterTheGrace() {
        View view = new View(List.of(), Map.of("summoned", "true"));
        view.now = 1_000;

        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()), "the first tick only starts the clock");
        view.now = 1_000 + BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS - 1;
        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()), "still inside the grace");

        view.now = 1_000 + BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS;
        SolverStep.Abandon abandon = assertInstanceOf(SolverStep.Abandon.class, solver.next(view, member()));
        assertTrue(abandon.why().contains("saw no part at all"), abandon.why());
    }

    /** A fake body is a body phase of an id: the room is not empty, so the grace never ends the run under it. */
    @Test
    void aWaitingBodyPhaseIsNeverAnEmptyRoom() {
        View view = new View(List.of(body(BODY, false)), Map.of("summoned", "true"));
        view.now = BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS * 10;

        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()));
    }

    /** Horntail's body room is behind a reactor, so with nothing alive the bot clicks it instead of waiting. */
    @Test
    void anEmptyRoomBehindAReactorIsOpenedByTheBot() {
        BossPhasesSolver horntail = new BossPhasesSolver();
        View horntailRoom = new View(MechanicsRuns.contentFor("HorntailBattle").stageOnMap(240060200).orElseThrow(),
                List.of(), Map.of());
        SolverStep.HitReactor hit = assertInstanceOf(SolverStep.HitReactor.class,
                horntail.next(horntailRoom, member()));

        assertEquals(2401000, hit.reactorId(), "the reactor that spawns the whole fight");
    }

    @Test
    void theShippedZakumRowIsOneBossStageWithAnEqualityClear() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("ZakumBattle");

        assertTrue(content != null, "content/zakum.yaml must be listed in content/index.txt");
        assertEquals(MechanicsModel.Kind.BOSS, content.kind());
        assertEquals(1, content.stages().size(), "one map, no stage transition");
        MechanicsModel.Stage stage = content.stages().get(0);
        assertEquals(280030000, stage.mapId());
        assertEquals(BossPhasesSolver.ID, stage.solver());
        assertNull(stage.nextPortal());
        assertEquals("defeatedBoss", stage.clear().eventProperty());
        assertEquals("1", stage.clear().equals());
        assertEquals(armIds().size() + 3, stage.mobs().size(), "eight arms and the body's three forms");
    }

    private static StageView summoned(List<Part> parts, boolean withKillTarget) {
        List<Part> all = new ArrayList<>(parts);
        if (withKillTarget) {
            all.add(body(KILL_TARGET, true));
        }
        return new View(all, Map.of("summoned", "true"));
    }

    private static List<Integer> armIds() {
        List<Integer> ids = new ArrayList<>();
        for (int id = FIRST_ARM; id <= LAST_ARM; id++) {
            ids.add(id);
        }
        return ids;
    }

    private static List<Part> armParts(int count) {
        List<Part> parts = new ArrayList<>();
        for (int id = LAST_ARM - count + 1; id <= LAST_ARM; id++) {
            parts.add(arm(id));
        }
        return parts;
    }

    private static Part arm(int mobId) {
        return new Part(mobId, BossTargetRules.Role.ARM, false, true, false, false, 0);
    }

    private static Part body(int mobId, boolean real) {
        return new Part(mobId, BossTargetRules.Role.BODY, !real, true, false, false, 0);
    }

    private static RunMember member() {
        return new RunMember(1, true, false, MechanicsPersona.PERFECT);
    }

    /** The shipped stage with nothing live on it but what the test puts there. */
    private static final class View implements StageView {

        private final MechanicsModel.Stage stage;
        private final List<Part> parts;
        private final Map<String, String> props;
        private final Map<String, Object> memory = new HashMap<>();
        long now;

        View(List<Part> parts, Map<String, String> props) {
            this(MechanicsRuns.contentFor("ZakumBattle").stages().get(0), parts, props);
        }

        View(MechanicsModel.Stage stage, List<Part> parts, Map<String, String> props) {
            this.stage = stage;
            this.parts = parts;
            this.props = new HashMap<>(props);
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return List.of(member());
        }

        @Override
        public String roleOf(int characterId) {
            return "arms";
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
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public List<Part> bossParts() {
            return parts;
        }

        @Override
        public BossTargetRules.Member fighterOf(int characterId) {
            return new BossTargetRules.Member(false, true);
        }
    }
}
