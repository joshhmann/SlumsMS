package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.awt.Point;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST 10-31 (t-mx-2, run 4d3de9837): mx-2's stage 3 sat 1164 s between a wrong check and the right one and wrote not
 * one line, because a member walking to its position and a member waiting both kept quiet. Now a bot that sits on one
 * wait message says so after 30 s and at most once a minute after that, and the leader's wait carries what the script
 * compares: every member's position and the combo's mask.
 */
class WaitNoteTest {

    static final int ID = 1_000_000_337;

    @Test
    void aWaitSpeaksAfterThirtySecondsAndThenOnceAMinute() {
        long t0 = 1_000_000L;
        assertFalse(StepExecutor.waitDueForNote(t0, 0, t0 + 29_000), "29 s is not a stall");
        assertTrue(StepExecutor.waitDueForNote(t0, 0, t0 + 30_000));
        long told = t0 + 30_000;
        assertFalse(StepExecutor.waitDueForNote(t0, told, told + 59_000),
                "a 500 ms tick must not write a line every time");
        assertTrue(StepExecutor.waitDueForNote(t0, told, told + 60_000));
    }

    @Test
    void theLineSaysHowLongWhatAndWhere() {
        String line = StepExecutor.waitNoteLine("AutoQaAlt", "waiting for formation 8: wanted [1, 1, 0, 1, 0]",
                new Point(608, -155), 604_000);
        assertEquals("WAIT AutoQaAlt 604 s: waiting for formation 8: wanted [1, 1, 0, 1, 0] (at 608,-155)", line);
    }

    @Test
    void aNewStepStartsTheClockAgain() {
        long t0 = 500L;
        assertFalse(StepExecutor.waitDueForNote(t0 + 10_000, 0, t0 + 35_000),
                "a different wait 25 s ago: nothing to say yet");
    }

    // ---- the leader's wait message: who counts where against the mask ----

    static Content kpq() {
        return StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR)).content().stream()
                .filter(c -> c.id().equals("kpq")).findFirst().orElseThrow();
    }

    /** KPQ's third stage (platforms) with the members where a test puts them. */
    static final class View implements StageView {
        final Stage stage = kpq().stage(3).orElseThrow();
        final Map<Integer, int[]> at = new HashMap<>();
        final Map<Integer, Integer> on = new HashMap<>();

        View put(int id, int x, int y) {
            at.put(id, new int[]{x, y});
            on.put(id, stage.mapId());
            return this;
        }

        View elsewhere(int id, int mapId) {
            at.put(id, new int[]{0, 0});
            on.put(id, mapId);
            return this;
        }

        @Override public Stage stage() { return stage; }
        @Override public List<RunMember> members() {
            return at.keySet().stream().map(id -> new RunMember(id, id == ID, true, null)).toList();
        }
        @Override public String roleOf(int id) { return "answerer"; }
        @Override public OptionalInt answerFor(int id) { return OptionalInt.empty(); }
        @Override public int itemCount(int id, int itemId) { return 0; }
        @Override public Optional<String> eventProperty(String key) { return Optional.empty(); }
        @Override public int[] positionOf(int id) { return at.getOrDefault(id, new int[]{0, 0}); }
        @Override public int mapOf(int id) { return on.getOrDefault(id, stage.mapId()); }
    }

    @Test
    void theReportNamesTheEmptyPositionTheMaskWants() {
        // answer 8 = [1,1,0,1,0]: platform1, 2 and 4 want a stander; 3 and 5 want none. Two bots stand on 1 and 2,
        // the third is still on the floor between platforms (the TEST stall, with the mover mid-walk).
        MechanicsModel.Position p1 = kpq().stage(3).orElseThrow().positions().get(0);
        MechanicsModel.Position p2 = kpq().stage(3).orElseThrow().positions().get(1);
        View v = new View().put(ID, p1.centerX(), p1.centerY())
                .put(ID + 1, p2.centerX(), p2.centerY())
                .put(ID + 2, 900, -180);
        String line = CombinationSolver.formationReport(v, 8);
        assertTrue(line.startsWith("wanted [1, 1, 0, 1, 0]"), line);
        assertTrue(line.contains(ID + " at " + p1.id()), line);
        assertTrue(line.contains((ID + 1) + " at " + p2.id()), line);
        assertTrue(line.contains((ID + 2) + " off (900,-180)"), line);
    }

    @Test
    void aMemberOffTheStageMapSaysWhichMap() {
        MechanicsModel.Position p1 = kpq().stage(3).orElseThrow().positions().get(0);
        View v = new View().put(ID, p1.centerX(), p1.centerY()).elsewhere(ID + 1, 103000000);
        String line = CombinationSolver.formationReport(v, 8);
        assertTrue(line.contains((ID + 1) + " on map 103000000"), line);
    }
}
