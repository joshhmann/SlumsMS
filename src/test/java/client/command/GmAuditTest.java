package client.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmAuditTest {

    private final List<GmAudit.Entry> written = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        System.clearProperty("slumsms.gmAudit");
        System.clearProperty("slumsms.gmAudit.queue");
    }

    @AfterEach
    void tearDown() {
        GmAudit.useSinkForTest(null);
        System.clearProperty("slumsms.gmAudit");
        System.clearProperty("slumsms.gmAudit.queue");
    }

    private static GmAudit.Entry entry(String command) {
        return new GmAudit.Entry(1_800_000_000_000L, 7, "owner", 42, "Josh", 6, command, "Bob 100000000", 3,
                100000000, 1, "203.0.113.7", "ok");
    }

    @Test
    void entriesReachTheSinkOnTheWriterThreadInOrder() throws Exception {
        CountDownLatch done = new CountDownLatch(3);
        GmAudit.useSinkForTest(batch -> {
            threads.add(Thread.currentThread().getName());
            written.addAll(batch);
            batch.forEach(e -> done.countDown());
        });

        assertTrue(GmAudit.record(entry("warp")));
        assertTrue(GmAudit.record(entry("item")));
        assertTrue(GmAudit.record(entry("ban")));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("warp", "item", "ban"), written.stream().map(GmAudit.Entry::command).toList());
        assertEquals("Bob 100000000", written.get(0).args());
        assertTrue(threads.stream().allMatch("gm-audit-writer"::equals));
        assertNotEquals(Thread.currentThread().getName(), threads.get(0));
    }

    @Test
    void aStuckDatabaseNeverBlocksTheCallerAndAFullQueueDropsAndCounts() throws Exception {
        System.setProperty("slumsms.gmAudit.queue", "16");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerBusy = new CountDownLatch(1);
        GmAudit.useSinkForTest(batch -> {
            writerBusy.countDown();
            release.await();
        });

        GmAudit.record(entry("first"));
        assertTrue(writerBusy.await(5, TimeUnit.SECONDS));
        long start = System.nanoTime();
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            if (GmAudit.record(entry("c" + i))) {
                accepted++;
            }
        }
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        release.countDown();

        assertTrue(tookMs < 1000, "recording must not wait for the database, took " + tookMs + " ms");
        assertEquals(16, accepted, "the queue holds 16 while the writer is stuck");
        assertEquals(84, GmAudit.droppedCount());
    }

    @Test
    void aFailingWriteIsCountedAndTheWriterCarriesOn() throws Exception {
        CountDownLatch second = new CountDownLatch(1);
        GmAudit.useSinkForTest(batch -> {
            if (batch.get(0).command().equals("boom")) {
                throw new java.sql.SQLSyntaxErrorException("Table 'gm_command_audit' doesn't exist");
            }
            written.addAll(batch);
            second.countDown();
        });

        GmAudit.record(entry("boom"));
        waitUntil(() -> GmAudit.droppedCount() == 1);
        GmAudit.record(entry("after"));

        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertEquals("after", written.get(0).command());
    }

    @Test
    void turnedOffItRecordsNothing() {
        GmAudit.useSinkForTest(written::addAll);
        System.setProperty("slumsms.gmAudit", "false");
        assertFalse(GmAudit.record(entry("warp")));
        assertEquals(0, GmAudit.pending());
    }

    @Test
    void timesAreWrittenInUtc() {
        assertEquals("2027-01-15 08:00:00.123", GmAudit.UTC.format(java.time.Instant.ofEpochMilli(1_800_000_000_123L)));
    }

    @Test
    void longTextIsCutToTheColumnWidth() {
        assertEquals(GmAudit.MAX_ARGS, GmAudit.cut("x".repeat(2000), GmAudit.MAX_ARGS).length());
        assertEquals("", GmAudit.cut(null, 10));
        assertEquals("abc", GmAudit.cut("abc", 10));
    }

    @Test
    void theMigrationCreatesTheTableTheWriterUsesAndRecordsItselfInTheLedger() throws Exception {
        String apply = java.nio.file.Files.readString(java.nio.file.Path.of("database/migrations/009-gm-audit.sql"));
        String rollback = java.nio.file.Files.readString(
                java.nio.file.Path.of("database/migrations/009-gm-audit.rollback.sql"));
        for (String column : List.of("at", "account_id", "account_name", "character_id", "character_name", "gm_level",
                "command", "args", "command_rank", "map_id", "channel", "ip", "outcome")) {
            assertTrue(apply.contains("    " + column + " "), column);
        }
        assertTrue(apply.contains("CREATE TABLE IF NOT EXISTS gm_command_audit"));
        assertTrue(apply.contains("CREATE TABLE IF NOT EXISTS gm_level_changes"));
        assertTrue(apply.contains("VALUES ('009-gm-audit')"));
        assertTrue(rollback.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id='009-gm-audit'"));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }
}
