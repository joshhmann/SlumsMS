package server.maps;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A recording JDBC stand-in for the merchant tests (P0-2): every connection logs its statements, its autocommit
 * switch and its commit/rollback, and a statement whose SQL contains {@link #failOn} throws like a dead database.
 * It models transactions only as far as the tests need: a "write" counts as durable only on a connection that
 * committed after it.
 */
public final class MerchantFakeDb {
    /** One log per connection handed out, in order. */
    public final List<Conn> conns = Collections.synchronizedList(new ArrayList<>());
    public volatile String failOn;

    public static final class Conn {
        public final List<String> sql = new ArrayList<>();
        boolean autoCommitOff;
        public int commits, rollbacks;
        /** statements that reached the database for good: at once on autocommit, else when the transaction commits. */
        public final List<String> durable = new ArrayList<>();
        final List<String> pending = new ArrayList<>();

        public boolean ran(String fragment) {
            return sql.stream().anyMatch(q -> q.contains(fragment));
        }

        public boolean committedAny(String fragment) {
            return durable.stream().anyMatch(q -> q.contains(fragment));
        }
    }

    public Connection open() {
        Conn log = new Conn();
        conns.add(log);
        Connection con = mock(Connection.class);
        try {
            when(con.prepareStatement(anyString())).thenAnswer(a -> statement(log, a.getArgument(0)));
            when(con.prepareStatement(anyString(), anyInt())).thenAnswer(a -> statement(log, a.getArgument(0)));
            doAnswer(a -> {
                log.autoCommitOff = !(Boolean) a.getArgument(0);
                return null;
            }).when(con).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
            doAnswer(a -> {
                log.commits++;
                log.durable.addAll(log.pending);
                log.pending.clear();
                return null;
            }).when(con).commit();
            doAnswer(a -> {
                log.rollbacks++;
                log.pending.clear();
                return null;
            }).when(con).rollback();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return con;
    }

    private PreparedStatement statement(Conn log, String sql) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeUpdate()).thenAnswer(a -> {
            String f = failOn;
            if (f != null && sql.contains(f)) {
                throw new SQLException("injected failure on: " + sql);
            }
            log.sql.add(sql);
            (log.autoCommitOff ? log.pending : log.durable).add(sql);
            return 1;
        });
        ResultSet keys = mock(ResultSet.class);
        when(keys.next()).thenReturn(true, false);
        when(keys.getInt(1)).thenReturn(1);
        when(keys.getLong(1)).thenReturn(1L);
        when(ps.getGeneratedKeys()).thenReturn(keys);
        return ps;
    }

    public int totalCommits() {
        return conns.stream().mapToInt(c -> c.commits).sum();
    }

    public boolean anyRan(String fragment) {
        return conns.stream().anyMatch(c -> c.ran(fragment));
    }

    public boolean anyCommitted(String fragment) {
        return conns.stream().anyMatch(c -> c.committedAny(fragment));
    }
}
