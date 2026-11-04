package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.4 PQ polish, defect 2: rope {@code OCCUPY} false alarms. On TEST (2026-11-01 00:31 -> 11-02 03:16) there were
 * 1,513 OCCUPY lines (rope1 456, rope3 356, rope4 327, rope2 320, platforms 54) and 873 of them (57.7%) were followed
 * within 30 s by {@code WAIT <same bot> ... on <same position>} - a member reported as failing, then found fine a
 * median 5 s later (p75 7 s, p90 9 s). Counting all later WAITs it is 1,157 (76.5%). 478 of the alarms came under
 * 30 s after the bot had already said it stood on that very position, because the note's clock reset only on a *new*
 * position id: once a bot had occupied rope1 and left, the next walk back to rope1 re-used the old clock and fired.
 * The rectangle is also 4 px wide and 150-222 px tall ({@code content/kpq.yaml:144-147}), so "not inside yet" is a
 * normal state for a climbing bot, not a stall.
 */
class OccupyStallNoteTest {

    /** KPQ stage 3's first rope, as the solver builds the step. */
    private static SolverStep.Occupy rope1() {
        return new SolverStep.Occupy("rope1", -753, -23, -755, -132, 4, 218, true);
    }

    /** A stander climbing steadily says nothing: every 30 s it is well over 30 px nearer. Fails before: fired at 30 s. */
    @Test
    void aClimbingStanderSaysNothing() {
        StepExecutor.OccupyWatch w = new StepExecutor.OccupyWatch();
        SolverStep.Occupy o = rope1();
        long t0 = 1_000_000L;
        // From the floor (-753, 130) up to the rope's bottom edge (the rectangle ends at y = 86 exclusive): 5 px a beat,
        // one beat a second - slower than a real climb, and still over 30 px inside every 30 s window.
        for (int y = 130; y >= 86; y -= 5) {
            assertFalse(w.due(o, new Point(-753, y), t0 + (130 - y) * 500L), "climbing at y=" + y);
        }
        assertTrue(o.inside(-753, 85), "and one px further the rectangle holds it: the step is done, not stalled");
    }

    /** A stander that stops dead says so after 30 s of no progress, and every 30 s after that. */
    @Test
    void aStanderThatStopsSaysSoEveryThirtySeconds() {
        StepExecutor.OccupyWatch w = new StepExecutor.OccupyWatch();
        SolverStep.Occupy o = rope1();
        long t0 = 1_000_000L;
        Point stuck = new Point(-797, 130);
        assertFalse(w.due(o, stuck, t0), "the first sight of the position starts the clock");
        // The run ticks every 500 ms; a stander is asked on every one of them.
        for (long t = t0 + 500; t < t0 + 30_000; t += 500) {
            assertFalse(w.due(o, stuck, t), t + ": 29 s of no progress is not a stall");
        }
        assertTrue(w.due(o, stuck, t0 + 30_000), "30 s of no progress is");
        assertFalse(w.due(o, stuck, t0 + 30_500), "not on every 500 ms tick");
        for (long t = t0 + 31_000; t < t0 + 60_000; t += 500) {
            assertFalse(w.due(o, stuck, t), t + ": told once per window");
        }
        assertTrue(w.due(o, stuck, t0 + 60_000), "and again 30 s later");
        assertEquals(60_000L, w.walkMs(t0 + 60_000), "the line can say how long it has been walking");
    }

    /**
     * Arriving and being sent back to the same rope is a new walk, not the old one continuing: this is the case that
     * produced 478 of the repeat alarms. Fails before: the old clock survived the arrival and fired at once.
     */
    @Test
    void comingBackToTheSameRopeStartsAClockOfItsOwn() {
        StepExecutor.OccupyWatch w = new StepExecutor.OccupyWatch();
        SolverStep.Occupy o = rope1();
        long t0 = 1_000_000L;
        assertFalse(w.due(o, new Point(-753, 130), t0), "walking to rope1 from the floor");
        w.arrived(); // inside the rectangle: WAIT "on rope1"
        long back = t0 + 60_000;
        assertFalse(w.due(o, new Point(-753, 130), back), "off again, walking back: a new clock");
        assertFalse(w.due(o, new Point(-753, 100), back + 10_000), "and it is making progress");
        assertFalse(w.due(o, new Point(-753, 40), back + 20_000));
        assertFalse(w.due(o, new Point(-753, 6), back + 29_000), "26 s into the new walk: nothing to say");
    }

    /** A few px nearer is not progress (the note is about standing still), but it is where the window re-measures. */
    @Test
    void aFewPixelsIsNotProgress() {
        StepExecutor.OccupyWatch w = new StepExecutor.OccupyWatch();
        SolverStep.Occupy o = rope1();
        long t0 = 2_000_000L;
        assertFalse(w.due(o, new Point(-753, 130), t0));
        for (long t = t0 + 500; t <= t0 + 29_500; t += 500) {
            assertFalse(w.due(o, new Point(-753, 130), t), t + ": still at the foot of the rope");
        }
        assertTrue(w.due(o, new Point(-753, 120), t0 + 30_000), "10 px in 30 s is still a stall");
    }

    /** A different position is a different walk: the clock and the reported times start over. */
    @Test
    void aNewPositionStartsTheClockAgain() {
        StepExecutor.OccupyWatch w = new StepExecutor.OccupyWatch();
        SolverStep.Occupy rope3 = new SolverStep.Occupy("rope3", -584, -251, -586, -326, 4, 150, true);
        long t0 = 500L;
        assertFalse(w.due(rope1(), new Point(-797, 130), t0));
        assertFalse(w.due(rope3, new Point(-570, -249), t0 + 35_000), "sent elsewhere 0 s ago: nothing to say");
    }

    /** The line states both times and both points, and keeps the word a player asked for. */
    @Test
    void theLineNamesHowLongItHasBeenStuckAndHowLongItHasWalked() {
        String before = "OCCUPY xXToyAquaXx has not reached position rope1 in 30 s: at -797,130, wants -753,-23 (a rope)";
        assertTrue(before.contains("in 30 s"), "the old shape, kept here so the change is not silent");
        String line = StepExecutor.occupyStallLine("xXToyAquaXx", rope1(), new Point(-797, 130),
                30_000L, 30_000L);
        assertEquals("OCCUPY xXToyAquaXx has not got closer to rope1 for 30 s (walking 30 s): at -797,130, "
                + "wants -753,-23 (a rope)", line);
        String later = StepExecutor.occupyStallLine("SirArrow", rope1(), new Point(-809, 118),
                60_000L, 60_000L);
        assertTrue(later.contains("for 60 s (walking 60 s)"), later);
    }

    /**
     * The defect, reproduced: the old clock (position id only, reset on a new id, never re-armed by arrival) fires on
     * the first tick of a *second* walk to the same rope, because the id had not changed since the first walk - which
     * is how 478 alarms came under 30 s after the bot had reported standing on the very position. The watch does not.
     */
    @Test
    void theOldClockFiredOnASecondWalkToTheSameRopeAndTheWatchDoesNot() {
        // The pre-fix state: occupyAt (a position id), occupySinceMs, occupyToldMs, reset only on a new id.
        String[] oldAt = {null};
        long[] oldSince = {0};
        long[] oldTold = {0};
        java.util.function.LongPredicate oldFires = now -> {
            if (!"rope1".equals(oldAt[0])) {
                oldAt[0] = "rope1";
                oldSince[0] = now;
                oldTold[0] = 0;
                return false;
            }
            if (!StepExecutor.occupyDueForNote(oldSince[0], oldTold[0], now)) {
                return false;
            }
            oldTold[0] = now;
            return true;
        };
        StepExecutor.OccupyWatch w = new StepExecutor.OccupyWatch();
        SolverStep.Occupy o = rope1();
        long t0 = 3_000_000L;
        // Walk 1: from the floor, 30 s, then the bot is inside the rectangle (the old code set occupyAt = null;
        // the run also sets a new position).
        for (long t = t0; t <= t0 + 30_000; t += 1_000) {
            oldFires.test(t);
            w.due(o, new Point(-753, 130), t);
        }
        long back = t0 + 120_000; // off the rope, sent back to it
        assertTrue(oldFires.test(back), "the old clock fires on the very first tick of the second walk");
        assertFalse(w.due(o, new Point(-753, 130), back), "the watch starts a new walk");
        for (long t = back; t < back + 30_000; t += 500) {
            assertFalse(w.due(o, new Point(-753, (int) (130 - (t - back) / 1_000 * 1.5)), t),
                    "climbing, " + (t - back) + " ms into the second walk");
        }
    }
}
