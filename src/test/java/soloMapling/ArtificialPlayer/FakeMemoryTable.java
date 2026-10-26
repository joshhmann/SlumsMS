package soloMapling.ArtificialPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The botmemory table for tests, as rows of column -> value behind a mocked JDBC pool. {@code legacy} plays a
 * database without migration 030 (its columns are unknown: MySQL error 1054).
 */
final class FakeMemoryTable {

    final List<Map<String, Object>> rows = new ArrayList<>();
    final AtomicLong nextId = new AtomicLong(1);
    /** How many times the pair was read from the "database" (cache misses). */
    final AtomicInteger pairReads = new AtomicInteger();
    final boolean legacy;

    FakeMemoryTable(boolean legacy) {
        this.legacy = legacy;
    }

    void install() {
        BotMemory.useConnectionSourceForTest(() -> {
            Connection con = mock(Connection.class);
            when(con.prepareStatement(anyString())).thenAnswer(c -> statement(c.getArgument(0)));
            when(con.prepareStatement(anyString(), anyInt())).thenAnswer(c -> statement(c.getArgument(0)));
            return con;
        });
    }

    private static SQLException unknownColumn() {
        return new SQLException("Unknown column 'Kind' in 'field list'", "42S22", 1054);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private PreparedStatement statement(String sql) throws Exception {
        boolean kinds = sql.equals(BotMemory.INSERT_KINDS) || sql.equals(BotMemory.PROBE_KINDS)
                || sql.equals(BotMemory.KIND_COUNTS) || sql.equals(BotMemory.PRUNE_LIGHT) || sql.equals(BotMemory.PRUNE_HEAVY)
                || sql.equals(BotMemory.FOLLOWED_UP);
        if (legacy && kinds) {
            throw unknownColumn();
        }
        Map<Integer, Object> bound = new HashMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setLong(anyInt(), anyLong());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setString(anyInt(), anyString());
        long[] lastId = {0};
        when(ps.executeUpdate()).thenAnswer(c -> {
            synchronized (rows) {
                if (sql.equals(BotMemory.INSERT) || sql.equals(BotMemory.INSERT_KINDS)) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("Id", lastId[0] = nextId.getAndIncrement());
                    row.put("BotId", bound.get(1));
                    row.put("AboutCharacterId", bound.get(2));
                    row.put("Note", bound.get(3));
                    row.put("Source", bound.get(4));
                    row.put("FromBotId", bound.get(5));
                    row.put("Hops", bound.get(6));
                    row.put("CreatedAt", bound.get(7));
                    row.put("LastUsedAt", bound.get(8));
                    boolean k = sql.equals(BotMemory.INSERT_KINDS);
                    row.put("Kind", k ? bound.get(9) : "NOTE");
                    row.put("Weight", k ? bound.get(10) : 1);
                    row.put("Tag", k ? bound.get(11) : "");
                    row.put("FollowedUpAt", k ? bound.get(12) : 0L);
                    rows.add(row);
                    return 1;
                }
                if (sql.equals(BotMemory.DELETE)) {
                    rows.removeIf(r -> r.get("Id").equals(bound.get(1)));
                    return 1;
                }
                if (sql.equals(BotMemory.DELETE_PAIR)) {
                    int before = rows.size();
                    rows.removeIf(r -> r.get("BotId").equals(bound.get(1)) && r.get("AboutCharacterId").equals(bound.get(2)));
                    return before - rows.size();
                }
                if (sql.equals(BotMemory.TOUCH) || sql.equals(BotMemory.FOLLOWED_UP)) {
                    String col = sql.equals(BotMemory.TOUCH) ? "LastUsedAt" : "FollowedUpAt";
                    rows.stream().filter(r -> r.get("Id").equals(bound.get(2))).forEach(r -> r.put(col, bound.get(1)));
                    return 1;
                }
                if (sql.equals(BotMemory.PRUNE_HEARSAY) || sql.equals(BotMemory.PRUNE_LIGHT)
                        || sql.equals(BotMemory.PRUNE_HEAVY) || sql.equals(BotMemory.PRUNE_LEGACY)) {
                    long created = num(bound.get(1));
                    long used = num(bound.get(2));
                    int before = rows.size();
                    rows.removeIf(r -> {
                        boolean hearsay = "HEARSAY".equals(r.get("Source"));
                        int weight = (int) num(r.get("Weight"));
                        boolean cls = sql.equals(BotMemory.PRUNE_HEARSAY) ? hearsay
                                : sql.equals(BotMemory.PRUNE_LIGHT) ? !hearsay && weight < 7
                                : sql.equals(BotMemory.PRUNE_HEAVY) ? !hearsay && weight >= 7 : !hearsay;
                        return cls && num(r.get("CreatedAt")) < created && num(r.get("LastUsedAt")) < used;
                    });
                    return before - rows.size();
                }
                if (sql.startsWith("DELETE FROM botmemory WHERE AboutCharacterId = ?")) {
                    int before = rows.size();
                    rows.removeIf(r -> r.get("AboutCharacterId").equals(bound.get(1)) && ("HEARSAY".equals(r.get("Source"))
                            || bound.entrySet().stream().anyMatch(e -> e.getKey() > 1 && e.getValue().equals(r.get("Note")))));
                    return before - rows.size();
                }
                return 1;
            }
        });
        when(ps.getGeneratedKeys()).thenAnswer(c -> {
            ResultSet keys = mock(ResultSet.class);
            boolean[] given = {false};
            when(keys.next()).thenAnswer(n -> !given[0] && (given[0] = true));
            when(keys.getLong(1)).thenAnswer(n -> lastId[0]);
            return keys;
        });
        when(ps.executeQuery()).thenAnswer(c -> {
            List<Map<String, Object>> found;
            synchronized (rows) {
                if (sql.equals(BotMemory.PROBE_KINDS)) {
                    found = List.of();
                } else if (sql.equals(BotMemory.KIND_COUNTS)) {
                    Map<String, Integer> counts = new HashMap<>();
                    rows.stream().filter(r -> r.get("AboutCharacterId").equals(bound.get(1)) && "DIRECT".equals(r.get("Source")))
                            .forEach(r -> counts.merge((String) r.get("Kind"), 1, Integer::sum));
                    found = counts.entrySet().stream().map(e -> Map.<String, Object>of("Kind", e.getKey(), "N", e.getValue()))
                            .toList();
                } else if (sql.equals(BotMemory.RECENT_OF_BOT)) {
                    found = rows.stream()
                            .filter(r -> r.get("BotId").equals(bound.get(1)) && ((Number) r.get("Hops")).intValue() < (int) bound.get(2))
                            .sorted((x, y) -> Long.compare(num(y.get("CreatedAt")), num(x.get("CreatedAt"))))
                            .limit((int) bound.get(3)).map(HashMap::new).map(m -> (Map<String, Object>) m).toList();
                } else {
                    pairReads.incrementAndGet();
                    found = rows.stream()
                            .filter(r -> r.get("BotId").equals(bound.get(1)) && r.get("AboutCharacterId").equals(bound.get(2)))
                            .map(HashMap::new).map(m -> (Map<String, Object>) m).toList();
                }
            }
            Iterator<Map<String, Object>> it = found.iterator();
            ResultSet rs = mock(ResultSet.class);
            Map<String, Object>[] current = new Map[1];
            when(rs.next()).thenAnswer(n -> it.hasNext() && (current[0] = it.next()) != null);
            when(rs.getLong(anyString())).thenAnswer(n -> num(current[0].get(n.getArgument(0))));
            when(rs.getInt(anyString())).thenAnswer(n -> (int) num(current[0].get(n.getArgument(0))));
            when(rs.getString(anyString())).thenAnswer(n -> {
                String col = n.getArgument(0);
                if (legacy && (col.equals("Kind") || col.equals("Tag"))) {
                    throw unknownColumn();
                }
                return (String) current[0].get(col);
            });
            return rs;
        });
        return ps;
    }
}
