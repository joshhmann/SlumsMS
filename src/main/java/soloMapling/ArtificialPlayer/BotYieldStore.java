package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps each bot's daily meso count (ticket 15, {@link BotYieldCaps}) across a restart, in table {@code botyield}
 * from migration 006: one row per bot, holding the day it last took mesos and how many.
 * <p>
 * Today's rows are read once, the first time a bot's count is needed; changed counts are written by the hourly
 * autosave and the shutdown sweep. Counts are never written before that read succeeded, so a stored count is
 * only ever added to, never overwritten with a smaller one. A crash loses at most the counts since the last
 * write. Without migration 006 the counts simply live in memory and start from zero at each boot.
 */
final class BotYieldStore {

    private static final Logger log = LoggerFactory.getLogger(BotYieldStore.class);

    static final String SELECT_DAY = "SELECT BotId, Meso FROM botyield WHERE Day = ?";
    static final String UPSERT = "INSERT INTO botyield (BotId, Day, Meso) VALUES (?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE Day = VALUES(Day), Meso = VALUES(Meso)";

    // A failed read (pool not up yet, a dropped connection) is retried, but no more often than this.
    static final long RETRY_MS = 300_000;

    /** Where connections come from: the pool, or a mock a unit test swaps in. */
    @FunctionalInterface
    interface ConnectionSource {
        Connection get() throws SQLException;
    }

    // The read happens on a bot's tick thread, which may be virtual: a ReentrantLock, not a monitor, so a
    // thread waiting here does not pin its carrier.
    private static final ReentrantLock loadLock = new ReentrantLock();
    private static volatile boolean loaded;
    private static volatile boolean schemaMissing;
    private static volatile long nextAttemptMs;
    // The read goes straight to the pool, as the store's own one-time read does (it may run while other bot
    // threads wait on the lock above); writes take the bots' share of the pool (BotDbGate).
    private static final ConnectionSource POOL = DatabaseConnection::getConnection;
    private static final ConnectionSource GATED = BotDbGate::connection;
    private static volatile ConnectionSource readConnections = POOL;
    private static volatile ConnectionSource writeConnections = GATED;

    private BotYieldStore() {
    }

    /** Reads today's stored counts once. Cheap after the first success, or while the store is unavailable. */
    static void ensureLoaded(LocalDate today) {
        if (loaded || schemaMissing || System.currentTimeMillis() < nextAttemptMs) {
            return;
        }
        if (!loadLock.tryLock()) {
            return;   // another thread is reading; this take goes on the in-memory count, added to the stored one
        }
        try {
            if (loaded || schemaMissing) {
                return;
            }
            try (Connection con = readConnections.get();
                 PreparedStatement ps = con.prepareStatement(SELECT_DAY)) {
                ps.setDate(1, Date.valueOf(today));
                int rows = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int key = rs.getInt("BotId");   // seller keys (2e9 + account) still fit an int
                        if (key >= BotSellerCaps.STORE_KEY_BASE) {   // ticket 56A: a seller account's count
                            BotSellerCaps.restore(key - BotSellerCaps.STORE_KEY_BASE, today, rs.getLong("Meso"));
                            continue;
                        }
                        BotYieldCaps.restore(BotStore.BOT_ID_BASE + key, today, rs.getLong("Meso"));
                        rows++;
                    }
                }
                loaded = true;
                log.info("Bot yield caps: restored today's meso counts for {} bots", rows);
            } catch (SQLException e) {
                if (BotStore.isMissingSchema(e)) {
                    schemaMissing = true;
                    log.warn("Bot yield caps: table botyield is missing (migration 006 not applied); daily meso"
                            + " counts are kept in memory only and restart from zero at each boot");
                } else {
                    nextAttemptMs = System.currentTimeMillis() + RETRY_MS;
                    log.warn("Bot yield caps: could not read today's meso counts; retrying later", e);
                }
            } catch (RuntimeException e) {
                // No pool in this environment (a headless test, or a boot that has not reached the database).
                nextAttemptMs = System.currentTimeMillis() + RETRY_MS;
                log.debug("Bot yield caps: no database to read counts from yet", e);
            }
        } finally {
            loadLock.unlock();
        }
    }

    /** Writes every changed count. Nothing is written until today's counts were read (see the class note). */
    static void flush() {
        if (!loaded || schemaMissing) {
            return;
        }
        List<long[]> rows = BotYieldCaps.drainDirty();
        rows.removeIf(row -> !BotHelpers.isArtificial((int) row[0]));
        List<long[]> sellerRows = BotSellerCaps.drainDirty();
        if (rows.isEmpty() && sellerRows.isEmpty()) {
            return;
        }
        try (Connection con = writeConnections.get();
             PreparedStatement ps = con.prepareStatement(UPSERT)) {
            for (long[] row : rows) {
                ps.setInt(1, (int) row[0] - BotStore.BOT_ID_BASE);
                ps.setDate(2, Date.valueOf(LocalDate.ofEpochDay(row[1])));
                ps.setLong(3, row[2]);
                ps.addBatch();
            }
            for (long[] row : sellerRows) {   // ticket 56A: seller accounts, keyed above every bot id
                ps.setInt(1, BotSellerCaps.STORE_KEY_BASE + (int) row[0]);
                ps.setDate(2, Date.valueOf(LocalDate.ofEpochDay(row[1])));
                ps.setLong(3, row[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException | RuntimeException e) {
            BotYieldCaps.markDirty(rows);
            BotSellerCaps.markDirty(sellerRows);
            log.warn("Bot yield caps: could not write {} daily meso counts; kept for the next write",
                    rows.size() + sellerRows.size(), e);
        }
    }

    static String status() {
        if (schemaMissing) {
            return "in memory (migration 006 not applied)";
        }
        return loaded ? "botyield" : "not read yet";
    }

    // ---- test seams ----

    static void useConnectionSourceForTest(ConnectionSource source) {
        readConnections = source == null ? POOL : source;
        writeConnections = source == null ? GATED : source;
    }

    static void resetForTest() {
        loaded = false;
        schemaMissing = false;
        nextAttemptMs = 0;
    }
}
