package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.qa.QaSuite;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TEST 11-01 (t-nv-3, run at 23:19:52Z): "nav trace: path trace: on, 20/20 this hour, 20 kept, 202 over the cap; bot on
 * 104040000 (full simulation true), ring none". The check read the shared ring, which exists only once the bot has
 * ticked full physics - a bot the check has just spawned and placed may never get a movement tick chain at all - and
 * whose copies are the other bots' stuck reports. The check now keeps a trace of its own where the QA suite runs: one
 * sample a read, spending none of the hour's copies. Live builds none.
 */
class NavTraceQaRingTest {

    private static final int FIELD = 104040000;
    private static final int BOT = 1_000_000_042;

    @BeforeEach
    void tracing() {
        System.setProperty(QaSuite.PROPERTY, "true");
        NavTrace.useForTest(true, 20);
    }

    @AfterEach
    void clean() {
        System.clearProperty(QaSuite.PROPERTY);
        NavTrace.useForTest(false, 20);
        GCMovement.lodUnpin(FIELD);
    }

    /** A bot the check has just placed: a position and a map, no movement state, hence no shared ring. */
    private static Character placedBot() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(BOT);
        when(chr.getMapId()).thenReturn(FIELD);
        when(chr.getPosition()).thenReturn(new Point(-100, 148));
        return chr;
    }

    @Test
    void theCheckKeepsItsOwnRingWithThisHoursCopiesGone() {
        GCMovement.lodPin(FIELD);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            assertTrue(NavTrace.take(now), "copy " + (i + 1) + " of the hour goes to another bot's stuck report");
        }
        assertTrue(NavTrace.describe().contains("20/20 this hour"), NavTrace.describe());

        Character bot = placedBot();
        for (int i = 0; i < 3; i++) {
            List<String> ring = NavTrace.qaRing(bot);
            assertNotNull(ring, "a bot on a full-simulation map has no path trace ring after 8 s");
            assertEquals(i + 1, ring.size(), "one sample a read: " + ring);
        }
        List<String> ring = NavTrace.qaRing(bot);
        assertEquals(4, ring.size(), ring.toString());
        String last = ring.get(ring.size() - 1);
        assertTrue(last.contains(" m" + FIELD + " "), last);
        assertTrue(last.contains("qa-watch"), last);

        // The reads spent nothing: had they gone through take(), the hour would show them over the cap.
        assertTrue(NavTrace.describe().contains("20/20 this hour"), NavTrace.describe());
        assertTrue(NavTrace.describe().contains("20 kept"), NavTrace.describe());
        assertTrue(NavTrace.describe().contains("0 over the cap"), NavTrace.describe());

        NavTrace.qaRelease(bot);
        assertEquals(1, NavTrace.qaRing(bot).size(), "a released bot starts a fresh ring, not the old samples");
    }

    @Test
    void withoutTheQaSuiteThereIsNoRingOfItsOwn() {
        System.clearProperty(QaSuite.PROPERTY);
        assumeFalse(QaSuite.isEnabled(), "attack capture is on in this JVM, so the suite counts as running");
        assertNull(NavTrace.qaRing(placedBot()), "live must not keep a ring per bot for a check that is not running");
    }
}
