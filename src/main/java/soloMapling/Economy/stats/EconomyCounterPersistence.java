package soloMapling.Economy.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the day's {@link EconomyCounters} across a restart (owner 2026-10-26; migration 032, table
 * {@code economycounters}: one row per day and counter key). The counters stay in memory; this class only copies their
 * totals to the database every few minutes and at shutdown (from the economy-snapshot thread, never the game thread),
 * and adds today's stored totals back at boot. About fifteen rows per save, whatever the bot count.
 *
 * <p>The row day is the counters' window: the day they are counting. It starts as today at boot and moves to the next
 * day when the nightly snapshot closes the window, so a save that runs just after midnight but before the nightly job
 * still writes under the day those counts belong to. Without the migration the store logs once and keeps nothing:
 * the counters then work from memory only, as before.
 */
public final class EconomyCounterPersistence {

    private static final Logger log = LoggerFactory.getLogger(EconomyCounterPersistence.class);

    /** How many days of counter rows (and nightly snapshot files) to keep (owner 2026-10-26). */
    public static final int KEEP_DAYS = 90;

    /** Where the rows go. */
    public interface Store {
        /** The stored totals for {@code day}, by counter key (empty when none). */
        Map<String, Long> load(LocalDate day);

        /**
         * Stores the totals for {@code day}. A day's totals only grow (the boot restores them first), so a store
         * keeps the larger of the stored and the new value: a boot whose read failed never wipes the stored day.
         */
        void save(LocalDate day, Map<String, Long> totals, long nowMs);

        /** Deletes rows for days before {@code day}. */
        void pruneBefore(LocalDate day);
    }

    private final Store store;
    private volatile LocalDate windowDay;

    public EconomyCounterPersistence(Store store) {
        this.store = store;
    }

    /** The day the in-memory counters are counting (null before {@link #boot}). */
    public LocalDate windowDay() {
        return windowDay;
    }

    /** At boot: the counters count {@code today}, starting from whatever was stored for it before the restart. */
    public synchronized void boot(LocalDate today) {
        windowDay = today;
        Map<String, Long> stored = store.load(today);
        if (!stored.isEmpty()) {
            EconomyCounters.restore(stored);
            log.info("Economy counters: restored {} stored total(s) for {}", stored.size(), today);
        }
    }

    /** Every few minutes and at shutdown: writes the window's totals so far. */
    public synchronized void save(long nowMs) {
        LocalDate day = windowDay;
        if (day != null) {
            store.save(day, totals(EconomyCounters.peek()), nowMs);
        }
    }

    /**
     * The nightly snapshot just closed the window for {@code closedDay} with these final totals: stores them under
     * that day, moves the window to the next day and drops rows older than {@link #KEEP_DAYS}.
     */
    public synchronized void closeDay(LocalDate closedDay, EconomyCounters.Snapshot finalTotals, long nowMs) {
        store.save(closedDay, totals(finalTotals), nowMs);
        windowDay = closedDay.plusDays(1);
        store.pruneBefore(closedDay.minusDays(KEEP_DAYS - 1));
    }

    static Map<String, Long> totals(EconomyCounters.Snapshot snap) {
        Map<String, Long> out = new HashMap<>();
        for (var e : snap.byKey().entrySet()) {
            out.put(e.getKey().dbKey, e.getValue());
        }
        return out;
    }

    /** The real store: the server's database. */
    public static final class Jdbc implements Store {
        static final String LOAD = "SELECT CounterKey, Amount FROM economycounters WHERE Day = ?";
        static final String UPSERT = "INSERT INTO economycounters (Day, CounterKey, Amount, UpdatedAt) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE Amount = GREATEST(Amount, VALUES(Amount)), UpdatedAt = VALUES(UpdatedAt)";
        static final String PRUNE = "DELETE FROM economycounters WHERE Day < ?";

        private volatile boolean warned;

        private void failed(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Economy counters are not stored (is migration 032-economy-counters applied?); a restart"
                        + " will lose the day's totals", e);
            } else {
                log.debug("Economy counter store", e);
            }
        }

        @Override
        public Map<String, Long> load(LocalDate day) {
            Map<String, Long> out = new HashMap<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(LOAD)) {
                ps.setDate(1, Date.valueOf(day));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getLong(2));
                    }
                }
            } catch (SQLException e) {
                failed(e);
            }
            return out;
        }

        @Override
        public void save(LocalDate day, Map<String, Long> totals, long nowMs) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(UPSERT)) {
                for (var e : totals.entrySet()) {
                    ps.setDate(1, Date.valueOf(day));
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.setLong(4, nowMs);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                failed(e);
            }
        }

        @Override
        public void pruneBefore(LocalDate day) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(PRUNE)) {
                ps.setDate(1, Date.valueOf(day));
                ps.executeUpdate();
            } catch (SQLException e) {
                failed(e);
            }
        }
    }
}
