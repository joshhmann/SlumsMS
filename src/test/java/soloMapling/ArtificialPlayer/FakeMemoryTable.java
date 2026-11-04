package soloMapling.ArtificialPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyDouble;
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
public final class FakeMemoryTable {

    public final List<Map<String, Object>> rows = new ArrayList<>();
    final AtomicLong nextId = new AtomicLong(1);
    /** How many times the pair was read from the "database" (cache misses). */
    final AtomicInteger pairReads = new AtomicInteger();
    /** How many batched {@code BotId IN (...)} reads were issued. */
    final AtomicInteger batchReads = new AtomicInteger();
    final boolean legacy;
    /** Migration 030 applied but not 063: the social columns are unknown (MySQL error 1054). */
    boolean withoutSocial;

    public FakeMemoryTable(boolean legacy) {
        this.legacy = legacy;
    }

    /** A database with migration 030 but not 063. */
    static FakeMemoryTable without060() {
        FakeMemoryTable t = new FakeMemoryTable(false);
        t.withoutSocial = true;
        return t;
    }

    /** Puts the real pool back (and forgets every cached note). */
    public static void uninstall() {
        BotMemory.useConnectionSourceForTest(null);
    }

    public void install() {
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

    /** The {@code ?} count inside the batched recent-notes read's {@code IN (...)} list (0 for any other statement). */
    static int inListSize(String sql) {
        int from = sql.indexOf("BotId IN (");
        if (from < 0) {
            return 0;
        }
        from += "BotId IN (".length();
        int to = sql.indexOf(')', from);
        int n = 0;
        for (int i = from; i < to; i++) {
            if (sql.charAt(i) == '?') {
                n++;
            }
        }
        return n;
    }

    private PreparedStatement statement(String sql) throws Exception {
        boolean kinds = sql.equals(BotMemory.INSERT_KINDS) || sql.equals(BotMemory.PROBE_KINDS)
                || sql.equals(BotMemory.KIND_COUNTS) || sql.equals(BotMemory.PRUNE_LIGHT) || sql.equals(BotMemory.PRUNE_HEAVY)
                || sql.equals(BotMemory.FOLLOWED_UP) || sql.equals(BotMemory.MERGE_KINDS)
                || sql.startsWith("SELECT Kind, COUNT(*) AS N FROM botmemory WHERE AboutCharacterId = ? AND Source = 'DIRECT' AND BotId IN");
        boolean socialSql = sql.equals(BotMemory.INSERT_SOCIAL) || sql.equals(BotMemory.PROBE_SOCIAL)
                || sql.equals(BotMemory.MERGE_SOCIAL);
        if ((legacy && (kinds || socialSql)) || (withoutSocial && socialSql)) {
            throw unknownColumn();
        }
        Map<Integer, Object> bound = new HashMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setLong(anyInt(), anyLong());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setString(anyInt(), anyString());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setDouble(anyInt(), anyDouble());
        doAnswer(c -> bound.put(c.getArgument(0), null)).when(ps).setNull(anyInt(), anyInt());
        long[] lastId = {0};
        when(ps.executeUpdate()).thenAnswer(c -> {
            synchronized (rows) {
                if (sql.equals(BotMemory.INSERT) || sql.equals(BotMemory.INSERT_KINDS)
                        || sql.equals(BotMemory.INSERT_SOCIAL)) {
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
                    boolean k = !sql.equals(BotMemory.INSERT);
                    row.put("Kind", k ? bound.get(9) : "NOTE");
                    row.put("Weight", k ? bound.get(10) : 1);
                    row.put("Tag", k ? bound.get(11) : "");
                    row.put("FollowedUpAt", k ? bound.get(12) : 0L);
                    if (sql.equals(BotMemory.INSERT_SOCIAL)) {
                        row.put("SubjectType", bound.get(13));
                        row.put("Location", bound.get(14));
                        row.put("Confidence", bound.get(15));
                        row.put("Valence", bound.get(16));
                        row.put("Provenance", bound.get(17));
                        row.put("OtherParticipant", bound.get(18));
                    }
                    rows.add(row);
                    return 1;
                }
                if (sql.equals(BotMemory.MERGE_PLAIN) || sql.equals(BotMemory.MERGE_KINDS)
                        || sql.equals(BotMemory.MERGE_SOCIAL)) {
                    int idAt = sql.equals(BotMemory.MERGE_SOCIAL) ? 8 : sql.equals(BotMemory.MERGE_KINDS) ? 4 : 3;
                    rows.stream().filter(r -> r.get("Id").equals(bound.get(idAt))).forEach(r -> {
                        r.put("Note", bound.get(1));
                        r.put("CreatedAt", bound.get(2));
                        if (idAt >= 4) {
                            r.put("Weight", bound.get(3));
                        }
                        if (idAt == 8) {
                            r.put("Location", bound.get(4));
                            r.put("Confidence", bound.get(5));
                            r.put("Valence", bound.get(6));
                            r.put("OtherParticipant", bound.get(7));
                        }
                    });
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
                    boolean scams = sql.contains("Kind = 'SCAM'");
                    rows.removeIf(r -> r.get("AboutCharacterId").equals(bound.get(1)) && ("HEARSAY".equals(r.get("Source"))
                            || (scams && "SCAM".equals(r.get("Kind")))
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
                if (sql.equals(BotMemory.PROBE_KINDS) || sql.equals(BotMemory.PROBE_SOCIAL)) {
                    found = List.of();
                } else if (sql.startsWith("SELECT Kind, COUNT(*) AS N FROM botmemory WHERE AboutCharacterId = ? AND Source = 'DIRECT' AND BotId IN")) {
                    Set<Integer> circle = new HashSet<>();
                    for (int i = 2; i <= inListSize(sql) + 1; i++) {
                        circle.add(((Number) bound.get(i)).intValue());
                    }
                    Map<String, Integer> counts = new HashMap<>();
                    rows.stream().filter(r -> r.get("AboutCharacterId").equals(bound.get(1)) && "DIRECT".equals(r.get("Source"))
                            && circle.contains(((Number) r.get("BotId")).intValue()))
                            .forEach(r -> counts.merge((String) r.get("Kind"), 1, Integer::sum));
                    found = counts.entrySet().stream().map(e -> Map.<String, Object>of("Kind", e.getKey(), "N", e.getValue()))
                            .toList();
                } else if (sql.equals(BotMemory.KIND_COUNTS)) {
                    Map<String, Integer> counts = new HashMap<>();
                    rows.stream().filter(r -> r.get("AboutCharacterId").equals(bound.get(1)) && "DIRECT".equals(r.get("Source")))
                            .forEach(r -> counts.merge((String) r.get("Kind"), 1, Integer::sum));
                    found = counts.entrySet().stream().map(e -> Map.<String, Object>of("Kind", e.getKey(), "N", e.getValue()))
                            .toList();
                } else if (inListSize(sql) > 0) {
                    batchReads.incrementAndGet();
                    int n = inListSize(sql);
                    Set<Integer> ids = new HashSet<>();
                    for (int i = 1; i <= n; i++) {
                        ids.add(((Number) bound.get(i)).intValue());
                    }
                    int hops = (int) num(bound.get(n + 1));
                    found = rows.stream()
                            .filter(r -> ids.contains(((Number) r.get("BotId")).intValue()) && num(r.get("Hops")) < hops)
                            .sorted((x, y) -> {
                                int byBot = Integer.compare(((Number) x.get("BotId")).intValue(),
                                        ((Number) y.get("BotId")).intValue());
                                return byBot != 0 ? byBot : Long.compare(num(y.get("CreatedAt")), num(x.get("CreatedAt")));
                            })
                            .map(HashMap::new).map(m -> (Map<String, Object>) m).toList();
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
            boolean[] wasNull = {false};
            when(rs.next()).thenAnswer(n -> it.hasNext() && (current[0] = it.next()) != null);
            when(rs.wasNull()).thenAnswer(n -> wasNull[0]);
            when(rs.getLong(anyString())).thenAnswer(n -> num(current[0].get(n.getArgument(0))));
            when(rs.getInt(anyString())).thenAnswer(n -> {
                Object v = current[0].get(n.getArgument(0));
                wasNull[0] = v == null;
                return (int) num(v);
            });
            when(rs.getDouble(anyString())).thenAnswer(n -> {
                Object v = current[0].get(n.getArgument(0));
                wasNull[0] = v == null;
                return v == null ? 0.0 : ((Number) v).doubleValue();
            });
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
