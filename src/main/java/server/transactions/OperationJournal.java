package server.transactions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The operation journal (RFC 2.9, migration 066). The connection methods never commit: the caller's transaction
 * decides, so a journal row and the effect it describes commit together.
 *
 * <p>States: STARTED (claimed, effect not yet known to be durable), APPLIED (the effect committed with this row's
 * flip), ROLLED_BACK. For BUYBACK a STARTED row at boot means the character save that carries the grant never
 * committed, so the database still shows no item or meso change and boot only has to clear the claim flag.
 */
public final class OperationJournal {
    private static final Logger log = LoggerFactory.getLogger(OperationJournal.class);

    public static final String BUYBACK = "BUYBACK";

    private OperationJournal() {
    }

    public static long start(Connection con, String kind, int actorId, long refId, String deltasJson) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO operation_journal (kind, actor_id, ref_id, deltas, state) VALUES (?, ?, ?, ?, 'STARTED')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setInt(2, actorId);
            ps.setLong(3, refId);
            ps.setString(4, deltasJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("operation_journal insert returned no id");
    }

    /** STARTED to APPLIED; throws if the row is not STARTED, so the enclosing transaction rolls back. */
    public static void markApplied(Connection con, long id) throws SQLException {
        transition(con, id, "APPLIED", null);
    }

    /** STARTED to ROLLED_BACK; throws if the row is not STARTED. */
    public static void markRolledBack(Connection con, long id, String note) throws SQLException {
        transition(con, id, "ROLLED_BACK", note);
    }

    private static void transition(Connection con, long id, String state, String note) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE operation_journal SET state = ?, note = ? WHERE id = ? AND state = 'STARTED'")) {
            ps.setString(1, state);
            ps.setString(2, note);
            ps.setLong(3, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("journal row " + id + " is not STARTED, cannot move it to " + state);
            }
        }
    }

    /** Boot: settle every STARTED row on the game database. Call before any player or bot can log in. */
    public static void reconcile() {
        reconcile(new JdbcJournalStore());
    }

    public static void reconcile(JournalStore store) {
        List<JournalStore.OpenRow> rows;
        try {
            rows = store.openRows();
        } catch (SQLException e) {
            log.error("Journal reconcile: could not read open rows (is migration 066 applied?)", e);
            return;
        }
        int rolledBack = 0, failed = 0, skipped = 0;
        for (JournalStore.OpenRow row : rows) {
            if (!BUYBACK.equals(row.kind())) {
                log.warn("Journal reconcile: row {} has unknown kind '{}' (actor {}, ref {}), left alone",
                        row.id(), row.kind(), row.actorId(), row.refId());
                skipped++;
                continue;
            }
            try {
                store.releaseBuyback(row.id(), (int) row.refId(), "boot reconcile");
                log.info("Journal reconcile: BUYBACK row {} (actor {}, transaction {}) was never saved, claim released",
                        row.id(), row.actorId(), row.refId());
                rolledBack++;
            } catch (SQLException e) {
                log.error("Journal reconcile: BUYBACK row {} could not be settled, left STARTED", row.id(), e);
                failed++;
            }
        }
        log.info("Journal reconcile: {} open rows, {} rolled back, {} failed, {} left alone",
                rows.size(), rolledBack, failed, skipped);
    }
}
