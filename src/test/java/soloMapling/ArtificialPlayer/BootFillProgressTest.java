package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * e08 probe: the boot fill reports how far it has come, so a ramp-shaped fill can be measured against today's flat
 * one (runs A, B and C of the e08 plan): bots online against the target, regulars online against all regulars, and
 * the logins still queued, until the fill is done.
 */
class BootFillProgressTest {

    private static final int NOON = 12 * 60;

    /** Active, with a play window of the given start and length (minutes of the day). */
    private static BotPopulationDirector.Candidate bot(int id, boolean online, boolean active, int windowStart, int len) {
        return new BotPopulationDirector.Candidate(id, online, active, 0L, 0L, windowStart, len, 60, true);
    }

    @Test
    void regularsAreTheActiveBotsWhoseWindowIsNow() {
        List<BotPopulationDirector.Candidate> roster = new ArrayList<>();
        roster.add(bot(1, true, true, NOON - 60, 120));   // a regular, online
        roster.add(bot(2, false, true, NOON - 30, 60));   // a regular, not yet
        roster.add(bot(3, true, true, 0, 60));            // online, but not its hour
        roster.add(bot(4, false, false, NOON - 60, 120)); // its hour, but not active: not a regular
        roster.add(bot(5, false, true, 23 * 60, 14 * 60)); // a window over midnight that covers noon

        BotPopulationDirector.FillProgress p = BotPopulationDirector.fillProgress(roster, 4, NOON, 2);

        assertEquals(2, p.online());
        assertEquals(3, p.regulars());
        assertEquals(1, p.regularsOnline());
        assertEquals(2, p.queued());
        assertFalse(p.done());
        assertEquals("[Director] fill +65 s: online 2 of 4, regulars online 1 of 3, logins queued 2", p.line(65_400));
    }

    @Test
    void theFillIsDoneInsideTheDirectorsSlackWithNothingQueued() {
        assertTrue(new BotPopulationDirector.FillProgress(980, 1000, 0, 0, 0).done(), "the director stops at 98%");
        assertFalse(new BotPopulationDirector.FillProgress(979, 1000, 0, 0, 0).done());
        assertFalse(new BotPopulationDirector.FillProgress(1000, 1000, 0, 0, 1).done(), "a login still on its way");
        assertTrue(new BotPopulationDirector.FillProgress(1000, 1000, 5, 5, 0).line(600_000)
                .endsWith("; fill done in 600 s"));
    }
}
