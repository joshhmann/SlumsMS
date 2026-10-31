package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST 10-31 (t-mx-2, t-mx-5): a run member the orchestrator cannot drive used to wait in total silence, so a stage
 * that never finished looked like a stall with no cause (stage 3 sat 19 minutes; an alt that never entered the PQ
 * asked Cloto nothing). The run now names the member, where it is and that it cannot be driven, once.
 */
class StrayMemberNoteTest {

    static final int STAGE1 = 103000800;
    static final int RECRUIT = 103000000;

    static final class Strays implements StageView {
        final RunState run;
        final Set<Integer> down;
        final java.util.Map<Integer, Integer> maps = new java.util.HashMap<>();

        Strays(RunState run, Set<Integer> down) {
            this.run = run;
            this.down = down;
        }

        @Override public Stage stage() {
            return run.stageData();
        }

        @Override public List<RunMember> members() {
            return run.members();
        }

        @Override public String roleOf(int id) {
            return run.roleOf(id);
        }

        @Override public OptionalInt answerFor(int id) {
            return OptionalInt.empty();
        }

        @Override public int itemCount(int id, int itemId) {
            return 0;
        }

        @Override public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override public int[] positionOf(int id) {
            return new int[]{0, 0};
        }

        @Override public int mapOf(int id) {
            return maps.getOrDefault(id, STAGE1);
        }

        @Override public boolean alive(int id) {
            return !down.contains(id);
        }
    }

    static RunState run() {
        Orchestrator o = new Orchestrator(Orchestrator.phase1Solvers(), new java.util.Random(3), 15 * 60_000L, 1);
        List<RunMember> party = List.of(
                new RunMember(11, true, true, MechanicsPersona.PERFECT),
                new RunMember(12, false, true, MechanicsPersona.PERFECT),
                new RunMember(13, false, true, MechanicsPersona.PERFECT),
                new RunMember(14, false, true, MechanicsPersona.PERFECT));
        return o.start(KpqStage1OrchestrationTest.kpq(), Tier.NORMAL, party);
    }

    static long notes(RunState run) {
        return run.events().stream().filter(e -> e.line().contains("the run cannot drive it")).count();
    }

    @Test
    void aMemberLeftOutsideTheStageIsNamedOnceAfterTheGrace() {
        RunState run = run();
        Strays view = new Strays(run, Set.of());
        view.maps.put(14, RECRUIT); // never came in: the mx-5 shape

        Orchestrator.noteStrays(run, view, 1_000);
        assertEquals(0, notes(run), "a member still walking between stages says nothing: " + run.events());

        long late = 1_000 + Orchestrator.STRAY_GRACE_MS + 1;
        Orchestrator.noteStrays(run, view, late);
        assertEquals(1, notes(run), run.events().toString());
        String line = run.events().stream().filter(e -> e.line().contains("the run cannot drive it"))
                .findFirst().orElseThrow().line();
        assertTrue(line.contains("14") && line.contains(String.valueOf(RECRUIT)), line);
        assertTrue(line.contains("stage 1"), line);

        Orchestrator.noteStrays(run, view, late + 60_000);
        assertEquals(1, notes(run), "one note per stray member, not one a tick: " + run.events());

        view.maps.remove(14); // back on the stage map
        Orchestrator.noteStrays(run, view, late + 61_000);
        view.maps.put(14, RECRUIT); // and away again: a second episode is worth a second line
        Orchestrator.noteStrays(run, view, late + 62_000);
        long second = late + 62_000 + Orchestrator.STRAY_GRACE_MS + 1;
        Orchestrator.noteStrays(run, view, second);
        assertEquals(2, notes(run), run.events().toString());
    }

    @Test
    void aMemberThatIsDownIsNamed() {
        RunState run = run();
        Strays view = new Strays(run, Set.of(12));

        Orchestrator.noteStrays(run, view, 1_000);
        assertEquals(0, notes(run));
        Orchestrator.noteStrays(run, view, 1_000 + Orchestrator.STRAY_GRACE_MS + 1);
        String line = run.events().stream().filter(e -> e.line().contains("the run cannot drive it"))
                .findFirst().orElseThrow().line();
        assertTrue(line.startsWith("12 is down"), line);
    }

    @Test
    void everybodyOnTheStageMapSaysNothing() {
        RunState run = run();
        Orchestrator.noteStrays(run, new Strays(run, Set.of()), 1_000);
        Orchestrator.noteStrays(run, new Strays(run, Set.of()), 1_000 + Orchestrator.STRAY_GRACE_MS + 1);
        assertEquals(0, notes(run), run.events().toString());
    }

    @Test
    void aRunTickCarriesTheNote() {
        Orchestrator o = new Orchestrator(Orchestrator.phase1Solvers(), new java.util.Random(3), 15 * 60_000L, 1);
        List<RunMember> party = List.of(
                new RunMember(11, true, true, MechanicsPersona.PERFECT),
                new RunMember(12, false, true, MechanicsPersona.PERFECT),
                new RunMember(13, false, true, MechanicsPersona.PERFECT));
        RunState run = o.start(KpqStage1OrchestrationTest.kpq(), Tier.NORMAL, party);
        Strays view = new Strays(run, Set.of());
        view.maps.put(13, RECRUIT);
        o.tick(run, view, 1_000);
        assertEquals(0, notes(run), run.events().toString());
        o.tick(run, view, 1_000 + Orchestrator.STRAY_GRACE_MS + 1);
        assertEquals(1, notes(run), run.events().toString());
    }
}
