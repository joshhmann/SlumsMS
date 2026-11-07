package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 b365: Crimsonwood's Relic of Mastery (610030500). The five weapon reactors 6109005-9 are item reactors (Reactor.wz
 * state 0 type 100), each taking its job's relic from that job's chamber; the row hit all five, a hit no client can make.
 * The rule (Coordinator, 7 Nov): a real player brings their own job's relic; a weapon whose job no player in the run has
 * may still open by a hit ({@code opensByHit: regularJobsOnly}) until regulars learn the chambers (1.6.7).
 */
class CwkpqRelicTest {

    static final int SWORD_REACTOR = 6109005;
    static final int BOW_REACTOR = 6109006;
    static final int SWORD = 4001259;
    static final int HUMAN = 7;
    static final int HUMAN2 = 8;

    static MechanicsModel.Stage relicRoom() {
        return MechanicsRuns.contentFor("CWKPQ").stages().get(2);
    }

    /** A human warrior (job 110) or none, and regulars of the other four jobs. */
    static final class View implements StageView {
        final MechanicsModel.Stage stage = relicRoom();
        final List<RunMember> party = new ArrayList<>();
        final Map<String, Integer> held = new HashMap<>();
        final Set<Integer> restored = new HashSet<>();
        final Map<String, Object> memory = new HashMap<>();

        View(int... humanJobs) {
            int id = HUMAN;
            for (int job : humanJobs) {
                party.add(new RunMember(id++, party.isEmpty(), false, MechanicsPersona.PERFECT, job, 120));
            }
            int[] regularJobs = {110, 310, 210, 410, 510};
            int rid = 1_000_000_100;
            for (int job : regularJobs) {
                party.add(new RunMember(rid++, party.isEmpty(), true, MechanicsPersona.PERFECT, job, 120));
            }
        }

        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public List<RunMember> members() { return party; }
        @Override public String roleOf(int characterId) { return stage.roles().get(0).id(); }
        @Override public OptionalInt answerFor(int characterId) { return OptionalInt.empty(); }
        @Override public int itemCount(int characterId, int itemId) { return held.getOrDefault(characterId + ":" + itemId, 0); }
        @Override public Optional<String> eventProperty(String key) { return Optional.empty(); }
        @Override public int[] positionOf(int characterId) { return new int[]{0, 0}; }
        @Override public Map<String, Object> stageMemory() { return memory; }
        @Override public String itemName(int itemId) { return "Item" + itemId; }

        @Override
        public int[] reactorTakes(int reactorId) {
            if (restored.contains(reactorId)) {
                return null;
            }
            for (MechanicsModel.Objective o : stage.objectives()) {
                if (o.hasReactor() && Integer.parseInt(o.reactor().trim()) == reactorId) {
                    return new int[]{o.itemId(), 1};
                }
            }
            return null;
        }
    }

    /** Every step any member would take now. */
    static List<SolverStep> everyone(View v) {
        List<SolverStep> out = new ArrayList<>();
        for (RunMember m : v.members()) {
            out.add(new CwkpqSigilSolver().next(v, m));
        }
        return out;
    }

    @Test
    void theRowNamesEachWeaponsRelicAndJobAndTheWrittenException() {
        MechanicsModel.Stage room = relicRoom();
        assertEquals(MechanicsModel.Stage.REGULAR_JOBS_ONLY, room.opensByHit());
        assertFalse(room.objectives().stream().anyMatch(o -> o.kind() == MechanicsModel.ObjectiveKind.HIT_REACTOR),
                "the weapons take relics; none is opened by a plain hit");
        MechanicsModel.Objective sword = room.objectives().stream()
                .filter(o -> "6109005".equals(o.reactor())).findFirst().orElseThrow();
        assertEquals(SWORD, sword.itemId());
        assertEquals(1, sword.job(), "the sword is the warrior's");
    }

    /** (a) A human warrior without the Master Sword: the sword's weapon is never hit; asked for once. */
    @Test
    void aHumanWarriorsWeaponWaitsForTheSwordAndIsNeverHit() {
        View v = new View(110);
        List<SolverStep> steps = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            steps.addAll(everyone(v));
        }
        assertFalse(steps.stream().anyMatch(s -> s instanceof SolverStep.HitReactor h && h.reactorId() == SWORD_REACTOR),
                "the warrior's weapon is the warrior's to restore: " + steps);
        assertEquals(1, steps.stream().filter(s -> s instanceof SolverStep.Say say
                && say.line().equals("You'll need Item" + SWORD + " to open this.")).count(), "said once");
    }

    /** (b) A job no player in the run has (the bow): its weapon opens by a hit. */
    @Test
    void aWeaponWhoseJobOnlyARegularHasOpensByAHit() {
        View v = new View(110);
        assertTrue(everyone(v).stream().anyMatch(s -> s instanceof SolverStep.HitReactor h && h.reactorId() == BOW_REACTOR),
                "the bow is a regular's job: the written exception");
    }

    /** (c) The warrior holds the sword: it goes back on its weapon. */
    @Test
    void theSwordIsPlacedByWhoeverHoldsIt() {
        View v = new View(110);
        v.held.put(HUMAN + ":" + SWORD, 1);
        RunMember warrior = v.members().get(0);
        SolverStep.PlaceItemOn place = assertInstanceOf(SolverStep.PlaceItemOn.class,
                new CwkpqSigilSolver().next(v, warrior));
        assertEquals(SWORD_REACTOR, place.reactorId());
        assertEquals(SWORD, place.itemId());
    }

    /** Two human warriors: one sword is enough, from either; once it is on, nothing asks or hits there again. */
    @Test
    void twoHumanWarriorsNeedOneSwordBetweenThem() {
        View v = new View(110, 112);
        v.held.put(HUMAN2 + ":" + SWORD, 1);
        assertEquals(SWORD_REACTOR, assertInstanceOf(SolverStep.PlaceItemOn.class,
                new CwkpqSigilSolver().next(v, v.members().get(1))).reactorId(), "the second warrior's sword serves");
        v.held.clear();
        v.restored.add(SWORD_REACTOR);
        List<SolverStep> steps = everyone(v);
        assertFalse(steps.stream().anyMatch(s -> s instanceof SolverStep.HitReactor h && h.reactorId() == SWORD_REACTOR),
                "restored: not hit");
        assertFalse(steps.stream().anyMatch(s -> s instanceof SolverStep.Say), "and nobody asks for it");
    }

    /** (d) Regulars on their own: all five weapons are theirs, and all five open by a hit. */
    @Test
    void regularsOnTheirOwnOpenAllFiveByAHit() {
        View v = new View();
        Set<Integer> hit = new HashSet<>();
        for (SolverStep s : everyone(v)) {
            if (s instanceof SolverStep.HitReactor h) {
                hit.add(h.reactorId());
            }
        }
        assertEquals(Set.of(6109005, 6109006, 6109007, 6109008, 6109009), hit);
    }
}
