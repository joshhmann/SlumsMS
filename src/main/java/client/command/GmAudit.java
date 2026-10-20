package client.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit trail of GM commands (ticket 43): who ran what, with which arguments, where and when, into the
 * {@code gm_command_audit} table (migration 009). The game thread only puts an entry on a bounded queue
 * ({@link #record}); one daemon thread writes the queue to the database in batches, so a slow or missing database
 * never blocks a command. When the queue is full new entries are dropped and counted, and every entry is also written
 * to the server log by the caller, so nothing is lost silently.
 *
 * <p>Tunables (JVM properties, not config.yaml): {@code -Dslumsms.gmAudit=false} turns the table writes off,
 * {@code -Dslumsms.gmAudit.queue=N} sizes the queue (default 10000).
 */
public final class GmAudit {

    private static final Logger log = LoggerFactory.getLogger(GmAudit.class);

    /** Longest text kept per column; longer values are cut (the columns are this wide). */
    static final int MAX_ARGS = 512;
    static final int MAX_SHORT = 64;
    private static final int BATCH = 200;
    /** {@code at} is stored in UTC, whatever the JVM's or the database's time zone. */
    static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final long WARN_EVERY_MS = TimeUnit.MINUTES.toMillis(5);

    /** One GM command. {@code outcome} is ok, denied (rank too low) or error (the command threw). */
    public record Entry(long at, int accountId, String accountName, int charId, String charName, int gmLevel,
                        String command, String args, int rank, int mapId, int channel, String ip, String outcome) {
    }

    /** Where batches go; the database by default, a fake in tests. */
    interface Sink {
        void write(List<Entry> batch) throws Exception;
    }

    private static final AtomicLong dropped = new AtomicLong();
    private static final AtomicLong written = new AtomicLong();
    private static volatile Sink sink = GmAudit::writeToDatabase;
    private static volatile BlockingQueue<Entry> queue;
    private static volatile Thread worker;
    private static volatile long lastWarnAt;

    private GmAudit() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("slumsms.gmAudit", "true"));
    }

    /** Queues one entry for the table; never blocks. False when it was dropped (queue full or audit off). */
    public static boolean record(Entry entry) {
        if (entry == null || !enabled()) {
            return false;
        }
        if (!ensureWorker().offer(entry)) {
            long n = dropped.incrementAndGet();
            warnThrottled("GM audit queue full; dropped {} entries so far (they are still in the server log)", n);
            return false;
        }
        return true;
    }

    public static long droppedCount() {
        return dropped.get();
    }

    public static long writtenCount() {
        return written.get();
    }

    /** Entries still waiting for the writer. */
    public static int pending() {
        BlockingQueue<Entry> q = queue;
        return q == null ? 0 : q.size();
    }

    private static BlockingQueue<Entry> ensureWorker() {
        BlockingQueue<Entry> q = queue;
        if (q != null) {
            return q;
        }
        synchronized (GmAudit.class) {
            if (queue == null) {
                int capacity = Math.max(16, Integer.getInteger("slumsms.gmAudit.queue", 10_000));
                BlockingQueue<Entry> created = new LinkedBlockingQueue<>(capacity);
                Thread t = new Thread(() -> drain(created), "gm-audit-writer");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                queue = created;
                worker = t;
                t.start();
            }
            return queue;
        }
    }

    private static void drain(BlockingQueue<Entry> q) {
        List<Entry> batch = new ArrayList<>(BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                batch.add(q.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            q.drainTo(batch, BATCH - 1);
            try {
                sink.write(batch);
                written.addAndGet(batch.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                dropped.addAndGet(batch.size());
                warnThrottled("Could not write {} GM audit entries (is migration 009 applied?): {}", batch.size(),
                        e.toString());
            }
            batch.clear();
        }
    }

    private static void warnThrottled(String format, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_EVERY_MS) {
            lastWarnAt = now;
            log.warn(format, args);
        }
    }

    private static void writeToDatabase(List<Entry> batch) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO gm_command_audit (at, account_id, account_name, "
                     + "character_id, character_name, gm_level, command, args, command_rank, map_id, channel, ip, "
                     + "outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Entry e : batch) {
                ps.setString(1, UTC.format(Instant.ofEpochMilli(e.at())));
                ps.setInt(2, e.accountId());
                ps.setString(3, cut(e.accountName(), MAX_SHORT));
                ps.setInt(4, e.charId());
                ps.setString(5, cut(e.charName(), MAX_SHORT));
                ps.setInt(6, e.gmLevel());
                ps.setString(7, cut(e.command(), MAX_SHORT));
                ps.setString(8, cut(e.args(), MAX_ARGS));
                ps.setInt(9, e.rank());
                ps.setInt(10, e.mapId());
                ps.setInt(11, e.channel());
                ps.setString(12, cut(e.ip(), MAX_SHORT));
                ps.setString(13, cut(e.outcome(), 16));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Test seam: a fake sink (null restores the database), fresh counters and a fresh queue/worker. */
    static synchronized void useSinkForTest(Sink testSink) {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        worker = null;
        queue = null;
        sink = testSink == null ? GmAudit::writeToDatabase : testSink;
        dropped.set(0);
        written.set(0);
        lastWarnAt = 0;
    }
}
