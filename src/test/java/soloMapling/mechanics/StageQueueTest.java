package soloMapling.mechanics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import testutil.PropsRestored;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 mechanics-pq-staging, the TEST queue trigger: tools/ops/mechanics-stage appends
 * {@code <id>TAB<utc stamp>TAB<request>} lines, the server takes them in order and drops what it cannot run. QA's
 * leader is a pretend player (gm 0, {@code AqClient}) who cannot type a GM 3 command, so the {@code for NAME} form of
 * {@code !mechanics pq} needs a way in that does not need a keyboard.
 */
@ExtendWith(PropsRestored.class)
class StageQueueTest {

    @TempDir
    Path dir;

    @AfterEach
    void switchesOff() {
        System.clearProperty(StageQueue.FLAG);
        System.clearProperty(Mechanics.FLAG);
        System.clearProperty(Mechanics.LPQ_FLAG);
    }

    private void append(String line) throws Exception {
        Files.writeString(dir.resolve(StageQueue.QUEUE_FILE), line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private StageQueue queue() {
        return new StageQueue(dir);
    }

    @Test
    void requestsAreTakenOnceInArrivalOrder() throws Exception {
        append("1\t2026-11-07T01:00:00Z\tpq lpq for AqClient 4 ranged2");
        append("2\t2026-11-07T01:00:01Z\tpq ellinpq for AqClient");
        assertEquals(2, queue().pending());
        assertEquals("pq lpq for AqClient 4 ranged2", queue().takeNext());
        assertEquals("pq ellinpq for AqClient", queue().takeNext());
        assertNull(queue().takeNext(), "each line runs once");
        assertEquals(0, queue().pending());
    }

    @Test
    void aMalformedLineIsDroppedAndTheNextOneStillRuns() throws Exception {
        append("1\t2026-11-07T01:00:00Z\thellom there");
        append("2\t2026-11-07T01:00:01Z\tpq nosuchrow for AqClient");
        append("3\t2026-11-07T01:00:02Z\tpq lpq me");
        append("4\t2026-11-07T01:00:03Z\tpq lpq for AqClient 4");
        StageQueue q = queue();
        assertTrue(q.run(q.takeNext()).startsWith("dropped"), "not a pq request");
        assertTrue(q.run(q.takeNext()).contains("nosuchrow"), "no such row");
        assertTrue(q.run(q.takeNext()).startsWith("dropped"), "no one typed it: me is refused");
        assertEquals("pq lpq for AqClient 4", q.takeNext(), "the queue kept going past all three");
    }

    @Test
    void aLeaderNobodyCanFindIsDroppedAndNotStaged() throws Exception {
        System.setProperty(StageQueue.FLAG, "true");
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty(Mechanics.LPQ_FLAG, "true");
        append("1\t2026-11-07T01:00:00Z\tpq lpq for GoneGuy 4 ranged2");
        StageQueue q = queue();
        q.lookup = name -> null; // no channel, no world: the lookup is the only server-side seam this test needs
        String outcome = q.run(q.takeNext());
        assertTrue(outcome.contains("GoneGuy"), outcome);
        assertNull(q.takeNext(), "dropped, never retried");
    }

    @Test
    void nothingIsTakenWhileQaIsOff() throws Exception {
        System.setProperty(Mechanics.FLAG, "true"); // the queue needs QA too: a live server has neither
        append("1\t2026-11-07T01:00:00Z\tpq lpq for AqClient 4");
        StageQueue q = queue();
        assertFalse(StageQueue.enabled());
        assertNull(q.tick());
        assertEquals(1, q.pending(), "the line waits for a QA server");
        assertFalse(Files.exists(dir.resolve(StageQueue.WORK_FILE)));
    }

    @Test
    void theQueueNeedsBothSwitches() {
        assertFalse(StageQueue.enabled());
        System.setProperty(StageQueue.FLAG, "true");
        assertFalse(StageQueue.enabled(), "no mechanics, no staging");
        System.setProperty(Mechanics.FLAG, "true");
        assertTrue(StageQueue.enabled());
    }
}
