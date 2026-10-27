package soloMapling.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 nx-1: the nxcode / nxcode_items tables in memory, behind a mocked JDBC {@link Connection}, for exactly the
 * statements NXCodeManager and CouponCodeHandler run (AUTO_INCREMENT ids, the UNIQUE code, transactions, the
 * {@code retriever IS NULL} claim). Not a SQL engine: an unknown statement fails the test.
 */
public final class FakeNxDb {

    public final List<Map<String, Object>> codes = new ArrayList<>();
    public final List<Map<String, Object>> items = new ArrayList<>();
    private final AtomicInteger codeIds = new AtomicInteger();
    private final AtomicInteger itemIds = new AtomicInteger();
    /** Rows inserted inside the open transaction (removed on rollback). */
    private final List<Map<String, Object>> pending = new ArrayList<>();
    private boolean autoCommit = true;
    /** When set, a statement whose SQL contains this text throws. */
    public volatile String failOn;
    /** Runs just before the redeem's claim UPDATE (a second redeem landing in between). */
    public volatile Runnable beforeClaim;

    public Connection connection() throws SQLException {
        Connection con = mock(Connection.class);
        when(con.getAutoCommit()).thenAnswer(a -> autoCommit);
        doAnswer(a -> {
            autoCommit = a.getArgument(0);
            if (autoCommit) {
                pending.clear();
            }
            return null;
        }).when(con).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
        doAnswer(a -> {
            pending.clear();
            return null;
        }).when(con).commit();
        doAnswer(a -> {
            codes.removeAll(pending);
            items.removeAll(pending);
            pending.clear();
            return null;
        }).when(con).rollback();
        when(con.prepareStatement(anyString())).thenAnswer(a -> statement(a.getArgument(0)));
        when(con.prepareStatement(anyString(), anyInt())).thenAnswer(a -> statement(a.getArgument(0)));
        return con;
    }

    public Map<String, Object> code(String code) {
        for (Map<String, Object> r : codes) {
            if (code.equals(r.get("code"))) {
                return r;
            }
        }
        return null;
    }

    private PreparedStatement statement(String sql) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        Map<Integer, Object> p = new HashMap<>();
        doAnswer(a -> p.put(a.getArgument(0), a.getArgument(1))).when(ps).setString(anyInt(), anyString());
        doAnswer(a -> p.put(a.getArgument(0), a.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
        doAnswer(a -> p.put(a.getArgument(0), a.getArgument(1))).when(ps).setLong(anyInt(), anyLong());
        int[] lastKey = {0};
        when(ps.executeUpdate()).thenAnswer(a -> {
            check(sql);
            if (sql.startsWith("INSERT INTO nxcode (")) {
                if (code((String) p.get(1)) != null) {
                    throw new java.sql.SQLIntegrityConstraintViolationException("Duplicate entry for key 'code'");
                }
                Map<String, Object> row = new HashMap<>();
                row.put("id", codeIds.incrementAndGet());
                row.put("code", p.get(1));
                row.put("retriever", null);
                row.put("expiration", p.get(2));
                insert(codes, row);
                lastKey[0] = (int) row.get("id");
                return 1;
            }
            if (sql.startsWith("INSERT INTO nxcode_items (")) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", itemIds.incrementAndGet());
                row.put("codeid", p.get(1));
                row.put("type", p.get(2));
                row.put("item", p.get(3));
                row.put("quantity", p.get(4));
                insert(items, row);
                return 1;
            }
            if (sql.equals("UPDATE nxcode SET retriever = ? WHERE code = ? AND retriever IS NULL")) {
                Runnable hook = beforeClaim;
                if (hook != null) {
                    hook.run();
                }
                Map<String, Object> row = code((String) p.get(2));
                if (row == null || row.get("retriever") != null) {
                    return 0;
                }
                row.put("retriever", p.get(1));
                return 1;
            }
            throw new AssertionError("unexpected update: " + sql);
        });
        when(ps.getGeneratedKeys()).thenAnswer(a -> rows(List.of(Map.of("1", lastKey[0]))));
        when(ps.executeQuery()).thenAnswer(a -> {
            check(sql);
            if (sql.equals("SELECT * FROM nxcode WHERE code = ?")) {
                Map<String, Object> row = code((String) p.get(1));
                return rows(row == null ? List.of() : List.of(row));
            }
            if (sql.equals("SELECT * FROM nxcode_items WHERE codeid = ?")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> r : items) {
                    if (r.get("codeid").equals(p.get(1))) {
                        out.add(r);
                    }
                }
                return rows(out);
            }
            throw new AssertionError("unexpected query: " + sql);
        });
        return ps;
    }

    private void check(String sql) throws SQLException {
        String f = failOn;
        if (f != null && sql.contains(f)) {
            throw new SQLException("injected failure on " + f);
        }
    }

    private void insert(List<Map<String, Object>> table, Map<String, Object> row) {
        table.add(row);
        if (!autoCommit) {
            pending.add(row);
        }
    }

    private static ResultSet rows(List<Map<String, Object>> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] at = {-1};
        when(rs.next()).thenAnswer(a -> ++at[0] < rows.size());
        when(rs.getInt(anyInt())).thenAnswer(a -> (Integer) rows.get(at[0]).get(String.valueOf((int) a.getArgument(0))));
        when(rs.getInt(anyString())).thenAnswer(a -> (Integer) rows.get(at[0]).get((String) a.getArgument(0)));
        when(rs.getLong(anyString())).thenAnswer(a -> ((Number) rows.get(at[0]).get((String) a.getArgument(0))).longValue());
        when(rs.getString(anyString())).thenAnswer(a -> (String) rows.get(at[0]).get((String) a.getArgument(0)));
        return rs;
    }
}
