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
    private static final int HORNTAIL_PREHEAD = 8810000;

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

    /**
     * Room 1 of Horntail, as it really is: {@code HorntailBattle.js:86-89} spawns the pre-head 8810000 at setup and
     * nothing writes a summon property, because the reactor that would ({@code 2408003.js}) only defines {@code touch()}
     * and a bot's click runs {@code onHit}/{@code act}. The row used to name {@code summonProperty: summoned}, so the
     * stage sat in {@code WAITING_FOR_SUMMON} - and its abandon needs the summon too - until the 120-minute timer.
     */
    @Test
    void aHeadRoomFightsFromTheFirstTickWithNoSummonProperty() {
        MechanicsModel.Stage room = MechanicsRuns.contentFor("HorntailBattle").stages().get(0);
        assertNull(room.summonProperty(), "a row that waits for 'summoned' can never start: " + room.summonProperty());

        // the setup's pre-head and the fake body the reactor adds; no event property is set at all
        List<Part> parts = List.of(body(HORNTAIL_PREHEAD, true), body(8800000, false));
        StageView view = new View(room, parts, Map.of());

        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()),
                "the head is standing, so the room is a fight from tick 0: " + solver.next(view, member()));
        assertEquals(List.of(HORNTAIL_PREHEAD), fight.mobIds(), "the fake body is never a target");
    }

    /**
     * The summoned add family 8810019-8820023 arrives mid-fight through the parts' own mob skill 200
     * ({@code MobSkill.java:286} SUMMON), and it must never become a target nor push the solver into an abandon: a live
     * add has no role in this fight, so {@code LiveStageView.bossParts} drops it before the rules see it.
     */
    @Test
    void aSummonedAddWaveNeverBecomesATargetAndNeverStarvesTheFight() {
        MechanicsModel.Stage room = MechanicsRuns.contentFor("HorntailBattle").stages().get(2);
        List<Integer> listed = room.mobs();
        for (int add = 8810019; add <= 8820023; add++) {
            assertNull(LiveStageView.partRole(add, listed), "add " + add + " is not part of the fight");
        }
        assertEquals(BossTargetRules.Role.ARM, LiveStageView.partRole(8810002, listed));
        assertEquals(BossTargetRules.Role.BODY, LiveStageView.partRole(8810018, listed));

        // and with the wave on the map the solver still fights the parts, never Wait/Abandon
        List<Part> parts = new ArrayList<>();
        for (int id = 8810002; id <= 8810009; id++) {
            parts.add(arm(id));
        }
        parts.add(body(8810018, true));
        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(new View(room, parts, Map.of()),
                member()));
        assertFalse(fight.mobIds().stream().anyMatch(id -> id >= 8810019),
                "a summoned add is never offered: " + fight.mobIds());
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

    /**
     * Branch {@code 164-bossrows}: seven rows are one room whose targets are the map's own life, with no summon
     * property and no reactor - so the room can stand empty while the instance's life is still coming, and "nothing to
     * hit" there says nothing about whether the fight can be won. {@code TD_Battle2} declares how long with
     * {@code spawnWaitSeconds: 60}; the abandon must use that, not the 30 s grace alone.
     */
    @Test
    void aRoomThatDeclaresItsSpawnWaitIsNotAbandonedAtTheGrace() {
        MechanicsModel.Stage dunas = MechanicsRuns.contentFor("TD_Battle2").stages().get(0);
        assertEquals(BossPhasesSolver.ID, dunas.solver());
        assertEquals(60, dunas.spawnWaitSeconds(), "the row states the wait; the solver does not guess it");
        View view = new View(dunas, List.of(), Map.of());

        view.now = 1_000;
        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()), "the first empty tick starts the clock");
        view.now = 1_000 + BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS;
        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()),
                "past the 30 s grace but inside the row's 60 s: the boss has simply not stood up yet");
        view.now = 46_000;
        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()), "the boss arrives at 45 s: no abandon");

        view.now = 61_000;
        SolverStep.Abandon abandon = assertInstanceOf(SolverStep.Abandon.class, solver.next(view, member()),
                "60 s of an empty room is the end the row declared");
        assertTrue(abandon.why().contains("60 s"), abandon.why());
    }

    /**
     * The other side of the same rule: a row that declares nothing keeps today's 30 s, because its parts are the
     * script's own spawn and an empty room after the summon really is an instance nobody can clear.
     */
    @Test
    void aRoomWithNoDeclaredSpawnWaitStillAbandonsAtTheGrace() {
        MechanicsModel.Stage zakum = MechanicsRuns.contentFor("ZakumBattle").stages().get(0);
        assertEquals(0, zakum.spawnWaitSeconds(), "Zakum's parts come from the altar, not from the map's life");
        View view = new View(zakum, List.of(), Map.of("summoned", "true"));

        view.now = 1_000;
        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()));
        view.now = 1_000 + BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS;
        assertInstanceOf(SolverStep.Abandon.class, solver.next(view, member()), "no declaration, no extra time");
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

    /**
     * Scarga's room has two reactors and each one brings half the fight: {@code scripts/reactor/5511000.js:5-9} summons
     * Targa after 3.2 s and {@code scripts/reactor/5511001.js:5-9} summons Scarlion the same way. Clicking the second
     * one only "while the room is empty" would never happen, because Targa is already standing by then - which is why
     * the row's reactors are an ordered list and both are clicked before anything is fought.
     */
    @Test
    void bothOfScargasReactorsAreClickedBeforeEitherHalfIsFought() {
        MechanicsModel.Stage room = withReactors(horntailBodyRoom(), List.of(5511000, 5511001), 60);
        View view = new View(room, List.of(), Map.of());

        assertEquals(5511000, assertInstanceOf(SolverStep.HitReactor.class, solver.next(view, member())).reactorId(),
                "the first reactor, first");
        view.now += 100;

        // Targa is on the map 3.2 s after the click, and the half it brings must not stop the second click
        view.parts.add(add(9420542));
        assertEquals(5511001, assertInstanceOf(SolverStep.HitReactor.class, solver.next(view, member())).reactorId(),
                "the second reactor is clicked even though the first already brought its boss");
        view.now += 3_200;

        view.parts.add(add(9420547));
        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()));
        assertTrue(fight.mobIds().containsAll(List.of(9420542, 9420547)), fight.mobIds().toString());

        // and neither reactor is ever clicked a second time: 2201004.js has no guard, so a repeat would double-spawn
        view.now += 100;
        assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()));
    }

    /**
     * The fail-before for the list: a row that names only the first of Scarga's two reactors leaves Targa's group as
     * the whole fight, and once that group is down the instance can never be cleared. The run must end in an abandon,
     * never in a clear.
     */
    @Test
    void aRoomThatOpensOnlyHalfItsFightIsNeverCalledCleared() {
        MechanicsModel.Stage room = withReactors(horntailBodyRoom(), List.of(5511000), 60);
        View view = new View(room, List.of(), Map.of());

        assertEquals(5511000, assertInstanceOf(SolverStep.HitReactor.class, solver.next(view, member())).reactorId());
        view.parts.add(add(9420542));
        view.now += 100;
        assertInstanceOf(SolverStep.Fight.class, solver.next(view, member()), "Targa's group is all there is");

        view.parts.clear();
        view.now += 100;
        assertInstanceOf(SolverStep.Wait.class, solver.next(view, member()), "the room is empty and the clock starts");
        view.now += 60_100;
        SolverStep step = solver.next(view, member());

        assertFalse(step instanceof SolverStep.Done, "half a fight is not a clear");
        assertInstanceOf(SolverStep.Abandon.class, step, "Scarlion's half never arrives, so the room is a stall");
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

    /**
     * Round 3, primitive A: a door that wants an item is a declaration, not a special case. The row says what the run
     * must have in hand; without it the solver names the item and does nothing else - no reactor click, no fight, no
     * abandon (Latanica's scripts/portal/captinsg00.js:4 refuses anyone without 4000381).
     */
    @Test
    void aDoorItemTheRunDoesNotHoldIsAWaitThatNamesItAndTouchesNothingElse() {
        MechanicsModel.Stage room = MechanicsRuns.contentFor("LatanicaBattle").stages().get(0);
        assertEquals(List.of(4000381), room.entryItems(), "the row declares the door's item");
        View empty = new View(room, List.of(), Map.of());

        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, solver.next(empty, member()));
        assertTrue(wait.why().contains("4000381"), wait.why());
        empty.now = 1_000 + BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS * 4;
        SolverStep later = solver.next(empty, member());
        assertInstanceOf(SolverStep.Wait.class, later, "a dry run is not a room to give up on");
        assertTrue(((SolverStep.Wait) later).why().contains("4000381"), "the wait keeps naming the item");

        View holds = new View(room, List.of(), Map.of());
        holds.hold(4000381, 1);
        assertEquals(5411000, assertInstanceOf(SolverStep.HitReactor.class, solver.next(holds, member())).reactorId(),
                "with the item in hand the row does what it was written to do");
    }

    /**
     * Round 10 (Chaos Zakum): the body's phase order comes from the row's own {@code mobs:} list, not from a hardcoded
     * normal-Zakum family. A chaos row (8800100 -> 8800101 -> 8800102) had no rank in the old family and fell back to
     * the order the parts happened to arrive in.
     */
    @Test
    void theBodyPhasesFollowTheRowsOwnOrderNotAHardcodedFamily() {
        List<Integer> chaosIds = List.of(8800100, 8800101, 8800102);
        MechanicsModel.Stage chaosRow = withMobs(zakumRoom(), chaosIds);
        List<Part> chaosParts = List.of(body(8800102, true), body(8800100, true), body(8800101, true));
        View view = new View(chaosRow, chaosParts, Map.of("summoned", "true", "defeatedBoss", "0"));
        BossTargetRules.Member melee = new BossTargetRules.Member(false, true);

        assertEquals(chaosIds, BossPhasesSolver.order(view, chaosParts, melee, BossTargetRules.Phase.BODY),
                "three chaos body phases, in the row's wake order");

        // the deterministic half: a row naming a chaos body before a normal one must be obeyed, which the old
        // bodyRank (8800001 ranks 1, an unknown id ranked last) could not do
        MechanicsModel.Stage mixed = withMobs(zakumRoom(), List.of(8800100, 8800001));
        List<Part> mixedParts = List.of(body(8800001, true), body(8800100, true));
        View mixedView = new View(mixed, mixedParts, Map.of("summoned", "true", "defeatedBoss", "0"));
        assertEquals(List.of(8800100, 8800001),
                BossPhasesSolver.order(mixedView, mixedParts, melee, BossTargetRules.Phase.BODY));
    }

    /** The shipped Zakum room, so a test can rewrite only what the row names. */
    private static MechanicsModel.Stage zakumRoom() {
        return MechanicsRuns.contentFor("ZakumBattle").stages().get(0);
    }

    /** A copy of a stage with a different {@code mobs:} list. */
    private static MechanicsModel.Stage withMobs(MechanicsModel.Stage base, List<Integer> mobs) {
        return new MechanicsModel.Stage(base.index(), base.mapId(), base.name(), base.npcId(), base.solver(),
                base.roles(), base.objectives(), base.positions(), base.items(), base.answers(), base.phases(),
                base.clear(), base.answerProperty(), base.combos(), mobs, base.nextPortal(),
                base.summonProperty(), base.spawnWaitSeconds(), base.timeLimitMinutes(), base.hitReactors(),
                base.entryItems(), base.killTarget(), base.hitReactor(), base.maps());
    }

    /** A copy of a shipped stage with a different reactor list, so the click rules can be pinned without a row. */
    private static MechanicsModel.Stage withReactors(MechanicsModel.Stage base, List<Integer> reactors, int spawnWait) {
        return new MechanicsModel.Stage(base.index(), base.mapId(), base.name(), base.npcId(), base.solver(),
                base.roles(), base.objectives(), base.positions(), base.items(), base.answers(), base.phases(),
                base.clear(), base.answerProperty(), base.combos(), base.mobs(), base.nextPortal(),
                base.summonProperty(), spawnWait, base.timeLimitMinutes(), reactors, base.entryItems(),
                base.killTarget(), base.hitReactor(), base.maps());
    }

    /** Horntail's body room: no summon property, so a room with nothing alive is a fight the map brings, not a gate. */
    private static MechanicsModel.Stage horntailBodyRoom() {
        return MechanicsRuns.contentFor("HorntailBattle").stageOnMap(240060200).orElseThrow();
    }

    /** An ordinary listed fight target: every non-Zakum, non-Horntail id is an add (BOSS-ROW-DATA.md R3). */
    private static Part add(int mobId) {
        return new Part(mobId, BossTargetRules.Role.ADD, false, true, false, false, 0);
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
        private final Map<Integer, Integer> held = new HashMap<>();
        long now;

        View(List<Part> parts, Map<String, String> props) {
            this(MechanicsRuns.contentFor("ZakumBattle").stages().get(0), parts, props);
        }

        View(MechanicsModel.Stage stage, List<Part> parts, Map<String, String> props) {
            this.stage = stage;
            this.parts = new ArrayList<>(parts);
            this.props = new HashMap<>(props);
        }

        /** What one member of the run is carrying, for the rows that declare {@code entryItems}. */
        void hold(int itemId, int count) {
            held.put(itemId, count);
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
            return held.getOrDefault(itemId, 0);
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
