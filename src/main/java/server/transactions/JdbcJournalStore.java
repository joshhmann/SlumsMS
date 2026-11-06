package server.transactions;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** {@link JournalStore} on the game database. */
public class JdbcJournalStore implements JournalStore {

    @Override
    public long claimBuyback(int playerId, int transactionId, String deltasJson) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE transactions SET buyback_used = 1 WHERE transaction_id = ? AND buyback_used = 0")) {
                    ps.setInt(1, transactionId);
                    if (ps.executeUpdate() == 0) {
                        con.rollback();
                        return -1;
                    }
                }
                long id = OperationJournal.start(con, OperationJournal.BUYBACK, playerId, transactionId, deltasJson);
                con.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    @Override
    public void releaseBuyback(long journalId, int transactionId, String note) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE transactions SET buyback_used = 0 WHERE transaction_id = ?")) {
                    ps.setInt(1, transactionId);
                    ps.executeUpdate();
                }
                OperationJournal.markRolledBack(con, journalId, note);
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    @Override
    public List<OpenRow> openRows() throws SQLException {
        List<OpenRow> rows = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, kind, actor_id, ref_id FROM operation_journal WHERE state = 'STARTED' ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new OpenRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)));
            }
        }
        return rows;
    }
}
