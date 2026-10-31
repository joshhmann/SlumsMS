package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST 10-31 (t-mx-2): mx-2's stage 3 spent 1164 s between a wrong check and the right one and wrote not one line,
 * because a member walking to its puzzle position and a member waiting are both silent. A stander now says where it
 * is stuck after 30 s and every 30 s after that, and starts over when the formation sends it somewhere else.
 */
class OccupyStallNoteTest {

    @Test
    void aStanderSpeaksAfterThirtySecondsAndEveryThirtySecondsAfter() {
        long t0 = 1_000_000L;
        assertFalse(StepExecutor.occupyDueForNote(t0, 0, t0 + StepExecutor.OCCUPY_NOTE_MS - 1),
                "29 s walking is not a stall");
        assertTrue(StepExecutor.occupyDueForNote(t0, 0, t0 + StepExecutor.OCCUPY_NOTE_MS));
        long told = t0 + StepExecutor.OCCUPY_NOTE_MS;
        assertFalse(StepExecutor.occupyDueForNote(t0, told, told + StepExecutor.OCCUPY_NOTE_MS - 1),
                "it does not repeat on every 500 ms tick");
        assertTrue(StepExecutor.occupyDueForNote(t0, told, told + StepExecutor.OCCUPY_NOTE_MS));
    }

    @Test
    void aNewPositionStartsTheClockAgain() {
        long t0 = 500L;
        assertFalse(StepExecutor.occupyDueForNote(t0 + 10_000, 0, t0 + 35_000),
                "sent elsewhere 25 s ago: nothing to say yet");
    }
}
