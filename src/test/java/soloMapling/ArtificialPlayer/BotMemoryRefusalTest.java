package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.5 b324 and b327: what bot memory does when the bots' share of the pool refuses it.
 *
 * <p>b324: at full TEST population the share refused about 240 bot memory writes an hour (live 1.6.4: ~370/h), and
 * every one was lost: a "last used" or "followed up" stamp was dropped at debug level, a new note's insert returned
 * null and the note was never written. b327: a refused <em>read</em> came back as an empty list and {@code about}
 * cached it, so the bot "knew nothing" about that player until the pair was evicted or the server restarted.
 *
 * <p>Acceptance (owner, 6 Nov): writes survive contention through bounded retry or batching; a refused load is never
 * cached as empty; failures are counted and never reported as success; the retry and the queues are capped, with the
 * drops counted.
 */
class BotMemoryRefusalTest {

    private static final int BOT = 7;
    private static final int PLAYER = 46;

    private final FakeMemoryTable table = new FakeMemoryTable(false);
    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    @BeforeEach
    void install() {
        table.install();
        BotMemory.useForTest(Runnable::run, now::get, 0);
    }

    @AfterEach
    void uninstall() {
        BotMemory.useCapsForTest(0, 0);
        BotMemory.useForTest(null, null, 0);
        FakeMemoryTable.uninstall();
    }

    private void rememberOne(String text) {
        BotMemory.remember(BOT, PLAYER, List.of(text), BotMemory.Source.DIRECT, 0, 0, now.get());
    }

    /** Forgets every cached pair but keeps the table, as a restart does. */
    private void coldCache() {
        table.install();
    }

    // ---- b327 ----

    @Test
    void aRefusedLoadIsNotCachedAsKnowingNothing() {
        rememberOne("gave me a red potion");
        coldCache();

        table.refuseWhen = sql -> sql.equals(BotMemory.SELECT_PAIR);
        table.refuseNext.set(1);
        BotMemory.about(BOT, PLAYER); // the share is busy: nothing could be read

        List<BotMemory.Note> later = BotMemory.about(BOT, PLAYER);
        assertEquals(1, later.size(), "the next look reads the table again instead of a cached 'knows nothing'");
        assertEquals("gave me a red potion", later.get(0).text());
        assertEquals(1, BotMemory.stats().loadsRefused(), "the refused read is counted");
    }

    @Test
    void aBotThatCouldNotReadAPairDoesNotWriteOverIt() {
        for (int i = 0; i < BotMemory.MAX_PER_PAIR; i++) {
            rememberOne("note " + i);
        }
        coldCache();
        table.refuseWhen = sql -> sql.equals(BotMemory.SELECT_PAIR);
        table.refuseNext.set(1);

        rememberOne("one more"); // its read of the pair was refused

        List<String> known = BotMemory.about(BOT, PLAYER).stream().map(BotMemory.Note::text).toList();
        assertTrue(known.contains("one more"), "the new note is kept: " + known);
        assertTrue(known.contains("note " + (BotMemory.MAX_PER_PAIR - 1)),
                "and the pair is not cached as the new note alone (the old ones were never read): " + known);
        long stored = table.rows.stream().filter(r -> r.get("BotId").equals(BOT)).count();
        assertTrue(stored <= BotMemory.MAX_PER_PAIR + 1, "nor written past its cap (" + stored + " rows)");
    }

    // ---- b324: stamps ----

    @Test
    void aRefusedLastUsedStampIsWrittenOnTheNextFlush() {
        rememberOne("likes zakum runs");
        long id = (long) table.rows.get(0).get("Id");
        table.refuseWhen = sql -> sql.equals(BotMemory.TOUCH);
        table.refuseNext.set(1);

        now.addAndGet(60_000);
        BotMemory.recall(BOT, PLAYER, now.get());
        BotMemory.flushPending(); // refused
        BotMemory.flushPending(); // the share has room again

        assertEquals(now.get(), ((Number) row(id).get("LastUsedAt")).longValue(), "the stamp was kept and written");
        BotMemory.Stats s = BotMemory.stats();
        assertEquals(1, s.touchesWritten(), "written once");
        assertEquals(0, s.touchesDropped());
    }

    @Test
    void stampsOfOneNoteCoalesceIntoOneWritePerFlush() {
        rememberOne("hates ksers");
        long id = (long) table.rows.get(0).get("Id");
        for (int i = 0; i < 5; i++) {
            now.addAndGet(1_000);
            BotMemory.recall(BOT, PLAYER, now.get());
        }
        BotMemory.flushPending();

        assertEquals(now.get(), ((Number) row(id).get("LastUsedAt")).longValue(), "the latest stamp wins");
        assertEquals(1, BotMemory.stats().touchesWritten(), "five stamps of one note are one write");
    }

    @Test
    void pendingStampsAreCappedAndTheDropsCounted() {
        BotMemory.useCapsForTest(2, 0);
        for (int i = 0; i < 3; i++) {
            rememberOne("note " + i);
        }
        table.refuseWhen = sql -> sql.equals(BotMemory.TOUCH);
        table.refuseNext.set(1_000);

        now.addAndGet(1_000);
        BotMemory.recall(BOT, PLAYER, now.get()); // three notes stamped, room for two
        BotMemory.flushPending();

        BotMemory.Stats s = BotMemory.stats();
        assertEquals(2, s.pendingTouches(), "the queue holds at most its cap");
        assertEquals(1, s.touchesDropped(), "and the one past it is counted, not silently lost");
        assertEquals(0, s.touchesWritten(), "nothing was written, and nothing says it was");
    }

    // ---- b324: inserts ----

    @Test
    void aRefusedInsertIsQueuedAndStoredOnTheNextFlush() {
        rememberOne("warmup"); // the schema probes ran on a served connection
        table.refuseWhen = sql -> sql.startsWith("INSERT INTO botmemory");
        table.refuseNext.set(1);

        rememberOne("helped me at the zakum altar");
        assertEquals(1, table.rows.size(), "the refused insert did not run");
        assertEquals(1, BotMemory.stats().insertsQueued(), "it is queued, not dropped");

        BotMemory.flushPending();
        assertEquals(2, table.rows.size(), "the queued note is stored");
        long stored = BotMemory.about(BOT, PLAYER).stream()
                .filter(n -> n.text().equals("helped me at the zakum altar")).mapToLong(BotMemory.Note::id).findFirst()
                .orElse(-1);
        assertTrue(stored > 0, "and the cached note carries its real row id (" + stored + ")");
        assertEquals(1, BotMemory.stats().insertsRetriedOk());
        assertEquals(0, BotMemory.stats().pendingInserts());
    }

    @Test
    void pendingInsertsAreCappedAndTheDropsCounted() {
        rememberOne("warmup");
        BotMemory.useCapsForTest(0, 1);
        table.refuseWhen = sql -> sql.startsWith("INSERT INTO botmemory");
        table.refuseNext.set(1_000);

        rememberOne("a");
        rememberOne("b");

        BotMemory.Stats s = BotMemory.stats();
        assertEquals(1, s.pendingInserts(), "one queued: the cap");
        assertEquals(1, s.insertsDropped(), "one dropped and counted");
        assertEquals(1, s.insertsWritten(), "only the warmup was ever written");
    }

    private Map<String, Object> row(long id) {
        return table.rows.stream().filter(r -> r.get("Id").equals(id)).findFirst().orElseThrow();
    }
}
