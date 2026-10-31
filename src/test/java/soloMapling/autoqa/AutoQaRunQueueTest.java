package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** b016: back-to-back run requests queue in order instead of the second replacing the first. */
class AutoQaRunQueueTest {

    @TempDir
    Path dir;

    private void append(String line) throws Exception {
        Files.writeString(dir.resolve(AutoQaRunQueue.QUEUE_FILE), line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void requestsRunInArrivalOrderOneAtATime() throws Exception {
        append("1\t2026-10-30T10:00:00Z\tt-ld-1");
        append("2\t2026-10-30T10:00:01Z\tt-bt-1 --fast");
        assertEquals(2, AutoQaRunQueue.pending(dir));
        assertEquals("t-ld-1", AutoQaRunQueue.takeNext(dir));
        append("3\t2026-10-30T10:00:05Z\tsmoke"); // arrives while the first run is going
        assertEquals("t-bt-1 --fast", AutoQaRunQueue.takeNext(dir));
        assertEquals("smoke", AutoQaRunQueue.takeNext(dir));
        assertNull(AutoQaRunQueue.takeNext(dir));
        assertEquals(0, AutoQaRunQueue.pending(dir));
    }

    @Test
    void aLegacyRequestFileStillRunsAheadOfTheQueue() throws Exception {
        append("1\t2026-10-30T10:00:00Z\tall");
        Files.writeString(dir.resolve(AutoQaService.REQUEST_FILE), "smoke\n");
        assertEquals("smoke", AutoQaRunQueue.takeNext(dir));
        assertEquals("all", AutoQaRunQueue.takeNext(dir));
        assertNull(AutoQaRunQueue.takeNext(dir));
    }

    @Test
    void plainLinesWithoutStampsAreRequestsToo() throws Exception {
        append("t-ld-1");
        assertEquals("t-ld-1", AutoQaRunQueue.takeNext(dir));
    }
}
