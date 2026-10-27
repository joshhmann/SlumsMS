package soloMapling.site.auth;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 107: what GM-approved password resets need from the database. {@link Jdbc} is the real one
 * ({@code password_resets}, migration 037, plus the password column of {@code accounts}); tests use an in-memory one.
 */
public interface ResetStore {

    String PENDING = "pending", APPROVED = "approved", DENIED = "denied", USED = "used", REPLACED = "replaced";

    /**
     * One row of {@code password_resets}. secretHash: SHA-256 hex of the browser's secret (never the secret). Times are
     * epoch ms, 0 when not set.
     */
    record Request(String id, int accountId, String accountName, String secretHash, String contact, String ipHash,
                   long createdAt, String state, long decidedAt, String decidedBy, long usedAt) {

        public static Request fresh(String id, int accountId, String accountName, String secretHash, String contact,
                                    String ipHash, long now) {
            return new Request(id, accountId, accountName, secretHash, contact, ipHash, now, PENDING, 0, "", 0);
        }
    }

    /** In one transaction: the account's pending and approved (unused) requests become replaced, then this one is added. */
    void insertReplacing(Request r) throws SQLException;

    /** Null when no such request. */
    Request find(String id) throws SQLException;

    /** The account's newest request in one of {@code states} made after {@code since}, or null. Name ignores case. */
    Request newestFor(String accountName, long since, String... states) throws SQLException;

    /** Requests in {@code state} made after {@code since}, oldest first. */
    List<Request> withState(String state, long since) throws SQLException;

    /** The newest requests, any state. */
    List<Request> recent(int limit) throws SQLException;

    /** Moves a request from {@code from} to {@code to} (approve or deny); false when it was no longer in {@code from}. */
    boolean decide(String id, String from, String to, String by, long now) throws SQLException;

    /**
     * In one transaction: marks the request used, only while approved, made after {@code createdAfter} and approved
     * after {@code decidedAfter}, and then sets the account's password to {@code passhash}. False with nothing changed
     * when the request was no longer usable.
     */
    boolean complete(String id, int accountId, String passhash, String ipHash, long now, long createdAfter,
                     long decidedAfter) throws SQLException;

    /** The game database, through the server's connection pool. */
    final class Jdbc implements ResetStore {

        static final String COLUMNS = "request_id, account_id, account_name, secret_hash, contact, ip_hash, created_at,"
                + " state, decided_at, decided_by, used_at";
        static final String REPLACE = "UPDATE password_resets SET state = 'replaced' WHERE account_id = ?"
                + " AND state IN ('pending', 'approved')";
        static final String INSERT = "INSERT INTO password_resets (request_id, account_id, account_name, secret_hash,"
                + " contact, ip_hash, created_at, state) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')";
        static final String DECIDE = "UPDATE password_resets SET state = ?, decided_at = ?, decided_by = ?"
                + " WHERE request_id = ? AND state = ?";
        static final String USE = "UPDATE password_resets SET state = 'used', used_at = ?, used_ip_hash = ?"
                + " WHERE request_id = ? AND account_id = ? AND state = 'approved' AND created_at > ? AND decided_at > ?";
        static final String SET_PASSWORD = "UPDATE accounts SET password = ? WHERE id = ?";

        @Override
        public void insertReplacing(Request r) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean autoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = con.prepareStatement(REPLACE)) {
                        ps.setInt(1, r.accountId());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = con.prepareStatement(INSERT)) {
                        ps.setString(1, r.id());
                        ps.setInt(2, r.accountId());
                        ps.setString(3, r.accountName());
                        ps.setString(4, r.secretHash());
                        ps.setString(5, r.contact());
                        ps.setString(6, r.ipHash());
                        ps.setLong(7, r.createdAt());
                        ps.executeUpdate();
                    }
                    con.commit();
                } catch (SQLException | RuntimeException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(autoCommit);
                }
            }
        }

        @Override
        public Request find(String id) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT " + COLUMNS + " FROM password_resets WHERE request_id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        }

        @Override
        public Request newestFor(String accountName, long since, String... states) throws SQLException {
            String in = String.join(", ", java.util.Collections.nCopies(states.length, "?"));
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT " + COLUMNS + " FROM password_resets"
                         + " WHERE account_name = ? AND created_at > ? AND state IN (" + in + ")"
                         + " ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, accountName);
                ps.setLong(2, since);
                for (int i = 0; i < states.length; i++) {
                    ps.setString(3 + i, states[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        }

        @Override
        public List<Request> withState(String state, long since) throws SQLException {
            return list("SELECT " + COLUMNS + " FROM password_resets WHERE state = ? AND created_at > ?"
                    + " ORDER BY created_at LIMIT 200", ps -> {
                ps.setString(1, state);
                ps.setLong(2, since);
            });
        }

        @Override
        public List<Request> recent(int limit) throws SQLException {
            return list("SELECT " + COLUMNS + " FROM password_resets ORDER BY created_at DESC LIMIT ?",
                    ps -> ps.setInt(1, limit));
        }

        @Override
        public boolean decide(String id, String from, String to, String by, long now) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(DECIDE)) {
                ps.setString(1, to);
                ps.setLong(2, now);
                ps.setString(3, by);
                ps.setString(4, id);
                ps.setString(5, from);
                return ps.executeUpdate() == 1;
            }
        }

        @Override
        public boolean complete(String id, int accountId, String passhash, String ipHash, long now, long createdAfter,
                                long decidedAfter) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean autoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    // The conditional update takes the row lock: a second completion waits, then finds it used.
                    try (PreparedStatement ps = con.prepareStatement(USE)) {
                        ps.setLong(1, now);
                        ps.setString(2, ipHash);
                        ps.setString(3, id);
                        ps.setInt(4, accountId);
                        ps.setLong(5, createdAfter);
                        ps.setLong(6, decidedAfter);
                        if (ps.executeUpdate() != 1) {
                            con.rollback();
                            return false;
                        }
                    }
                    try (PreparedStatement ps = con.prepareStatement(SET_PASSWORD)) {
                        ps.setString(1, passhash);
                        ps.setInt(2, accountId);
                        if (ps.executeUpdate() != 1) {
                            con.rollback();
                            return false;
                        }
                    }
                    con.commit();
                    return true;
                } catch (SQLException | RuntimeException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(autoCommit);
                }
            }
        }

        private interface Binder {
            void bind(PreparedStatement ps) throws SQLException;
        }

        private static List<Request> list(String sql, Binder binder) throws SQLException {
            List<Request> out = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs));
                    }
                }
            }
            return out;
        }

        private static Request read(ResultSet rs) throws SQLException {
            return new Request(rs.getString("request_id"), rs.getInt("account_id"), rs.getString("account_name"),
                    rs.getString("secret_hash"), rs.getString("contact"), rs.getString("ip_hash"),
                    rs.getLong("created_at"), rs.getString("state"), rs.getLong("decided_at"),
                    rs.getString("decided_by"), rs.getLong("used_at"));
        }
    }
}
