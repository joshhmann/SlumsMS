package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TEST 10-31 (t-nv-3, run 4d3de9837): "a bot on a full-simulation map has no path trace ring after 8 s". The driver
 * read the trace only in the branches where the bot had a goal and walked, so a bot that stood still (idle between
 * mobs, held by a QA check, or just given up) kept no ring, and its stuck report had no trace at all. The trace is
 * now read after every tick, whatever the tick did.
 */
class TraceEveryTickTest {

    private static final int WATCHED = 104040000;
    private static final int UNWATCHED = 104040099;

    @BeforeEach
    void tracing() {
        NavTrace.useForTest(true, 20);
    }

    @AfterEach
    void clean() {
        NavTrace.useForTest(false, 20);
        GCMovement.lodUnpin(WATCHED);
    }

    private static BotMovementState bot(int mapId) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1_000_000_001);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getPosition()).thenReturn(new Point(-100, 148));
        return new BotMovementState(chr, null);
    }

    @Test
    void aTickThatEndsStandingStillStillTraces() {
        GCMovement.lodPin(WATCHED);
        BotMovementState entry = bot(WATCHED);
        assertNull(entry.navTrace, "nothing traced before the first tick");

        GCMovementDriver.traceAfterTick(entry);

        assertNotNull(entry.navTrace, "a watched bot that stood still has no trace: exactly what TEST failed on");
        List<String> ring = entry.navTrace.lines();
        assertTrue(ring.get(ring.size() - 1).contains(" m" + WATCHED + " "), ring.get(ring.size() - 1));
    }

    @Test
    void anUnwatchedTickKeepsNothing() {
        BotMovementState entry = bot(UNWATCHED);
        entry.navTrace = new NavTrace.Ring();
        entry.navTrace.add(0, UNWATCHED, new Point(1, 2), null, null, "exec", 0, 0);

        GCMovementDriver.traceAfterTick(entry);

        assertNull(entry.navTrace, "coarse movement has no nav ticks: a stale ring would mislead a report");
    }

    @Test
    void aBotThatIsGoneIsNotTraced() {
        GCMovementDriver.traceAfterTick(null);
        BotMovementState entry = new BotMovementState((Character) null, null);
        GCMovementDriver.traceAfterTick(entry); // the tick called stop(): skipped, never a throw
        assertNull(entry.navTrace);
    }
}
