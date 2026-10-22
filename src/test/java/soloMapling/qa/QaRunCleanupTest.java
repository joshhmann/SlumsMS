package soloMapling.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 59 (review 2026-10-22): a log write failure (a full disk) used to mark the run stopped, so stop() skipped
 * every cleanup - cv-7 then left the whole server in bank mode. The cleanups now always run.
 */
class QaRunCleanupTest {
    @TempDir
    Path dir;

    @Test
    void aBrokenLogStillRunsEveryCleanupAtStop() throws Exception {
        QaRun run = new QaRun("cv-7", 1, "Gm", Instant.parse("2026-10-22T15:00:00Z"), dir, EnumSet.of(QaLog.Topic.CONVO),
                Set.of(), Set.of());
        run.open(List.of("header"));
        AtomicBoolean restored = new AtomicBoolean();
        run.onStop("bank mode off", () -> restored.set(true));
        java.lang.reflect.Field f = QaRun.class.getDeclaredField("out");
        f.setAccessible(true);
        ((Writer) f.get(run)).close(); // the next write fails, as on a full disk
        run.note("STEP", "this line cannot be written");
        assertFalse(run.isStopped(), "a broken log is not a stopped run");
        List<String> results = run.stop("!qa stop", Instant.parse("2026-10-22T15:05:00Z"));
        assertTrue(restored.get(), "the cleanup ran");
        assertEquals(List.of("undone: bank mode off"), results);
        assertTrue(run.isStopped());
        assertTrue(run.stop("again", Instant.now()).isEmpty(), "a second stop does nothing");
    }
}
