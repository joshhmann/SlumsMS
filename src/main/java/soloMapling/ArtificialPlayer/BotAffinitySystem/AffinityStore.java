package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotDbGate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where ties and {@code @botdrama} opt-outs live (migration 011: {@code botaffinity}, {@code botdrama}). Everything
 * is read once when a player logs in and written only when something changes, always off the game thread.
 * Without the migration the JDBC store logs once and keeps nothing: affinity still works in memory until a restart.
 */
public interface AffinityStore {

    /** Every tie any bot holds about this player. */
    List<Tie> load(int charId);

    void save(Tie tie);

    void delete(int charId, int botId);

    boolean optedOut(int charId);

    void setOptedOut(int charId, boolean optedOut, long now);

    /**
     * Every character with {@code @botdrama off}, read once at boot (and again until a read works). Null when the
     * table could not be read; an empty set when it holds nobody (or the migration is not applied).
     */
    Set<Integer> loadOptedOut();

    /**
     * The per-player daily bot-defame claim: true when this call claimed the day that starts at {@code dayStart} (no
     * bot defamed the player since then), false when it was taken already, null when the store could not answer.
     * Atomic in the store: two claims of one day never both succeed.
     */
    Boolean claimDefame(int charId, long dayStart, long now);

    /**
     * {@code botaffinity} row that holds a player's last bot defame ({@link #claimDefame}) instead of a tie: a bot id
     * no bot can have (character ids are signed ints, so a bot's id is at most about 1.15 billion). Never loaded as a tie.
     */
    long DEFAME_MARK = 4_000_000_000L;

    /** The real store: the server's database through the bots' share of the pool. */
    final class Jdbc implements AffinityStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String SELECT = "SELECT BotId, Score, Kind, Hostile, UpdatedAt FROM botaffinity WHERE CharacterId = ?"
                + " AND BotId < " + DEFAME_MARK;
        static final String DRAMA_ALL = "SELECT CharacterId FROM botdrama";
        static final String DEFAME_ROLL = "UPDATE botaffinity SET UpdatedAt = ? WHERE BotId = " + DEFAME_MARK
                + " AND CharacterId = ? AND UpdatedAt < ?";
        static final String DEFAME_FIRST = "INSERT IGNORE INTO botaffinity (BotId, CharacterId, Score, Kind, Hostile,"
                + " UpdatedAt) VALUES (" + DEFAME_MARK + ", ?, 0, 0, 0, ?)";
        static final String UPSERT = "INSERT INTO botaffinity (BotId, CharacterId, Score, Kind, Hostile, UpdatedAt) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE Score = VALUES(Score), Kind = VALUES(Kind), "
                + "Hostile = VALUES(Hostile), UpdatedAt = VALUES(UpdatedAt)";
        static final String DELETE = "DELETE FROM botaffinity WHERE CharacterId = ? AND BotId = ?";
        static final String DRAMA_GET = "SELECT 1 FROM botdrama WHERE CharacterId = ?";
        static final String DRAMA_OFF = "INSERT INTO botdrama (CharacterId, OptedOutAt) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE OptedOutAt = VALUES(OptedOutAt)";
        static final String DRAMA_ON = "DELETE FROM botdrama WHERE CharacterId = ?";

        private volatile boolean warned;

        private Connection connection() throws SQLException {
            return BotDbGate.connection();
        }

        private void missing(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Bot affinity is not stored (is migration 011-bot-affinity applied?); keeping it in memory", e);
            } else {
                log.debug("Bot affinity store", e);
            }
        }

        @Override
        public List<Tie> load(int charId) {
            List<Tie> ties = new ArrayList<>();
            try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(SELECT)) {
                ps.setInt(1, charId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ties.add(new Tie(rs.getInt("BotId"), charId, rs.getInt("Score"), rs.getInt("Kind"),
                                rs.getInt("Hostile"), rs.getLong("UpdatedAt")));
                    }
                }
            } catch (SQLException e) {
                missing(e);
            }
            return ties;
        }

        @Override
        public void save(Tie tie) {
            try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(UPSERT)) {
                ps.setInt(1, tie.botId);
                ps.setInt(2, tie.charId);
                ps.setInt(3, (int) Math.round(Math.max(-AffinityRules.MAX, Math.min(AffinityRules.MAX, tie.score))));
                ps.setInt(4, Math.min(999, tie.kind));
                ps.setInt(5, Math.min(999, tie.hostile));
                ps.setLong(6, tie.updatedAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public void delete(int charId, int botId) {
            try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(DELETE)) {
                ps.setInt(1, charId);
                ps.setInt(2, botId);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public boolean optedOut(int charId) {
            try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(DRAMA_GET)) {
                ps.setInt(1, charId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                missing(e);
                return false;
            }
        }

        @Override
        public Set<Integer> loadOptedOut() {
            Set<Integer> out = new java.util.HashSet<>();
            try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(DRAMA_ALL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
                return out;
            } catch (SQLException e) {
                missing(e);
                return noTable(e) ? out : null;
            }
        }

        @Override
        public Boolean claimDefame(int charId, long dayStart, long now) {
            try (Connection con = connection()) {
                try (PreparedStatement ps = con.prepareStatement(DEFAME_ROLL)) {
                    ps.setLong(1, now);
                    ps.setInt(2, charId);
                    ps.setLong(3, dayStart);
                    if (ps.executeUpdate() > 0) {
                        return true; // the last defame was before today: this one takes today
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(DEFAME_FIRST)) {
                    ps.setInt(1, charId);
                    ps.setLong(2, now);
                    return ps.executeUpdate() > 0; // 0: the row exists and is from today
                }
            } catch (SQLException e) {
                missing(e);
                return null;
            }
        }

        /** MySQL's "table doesn't exist": the migration is not applied, which is not a failure to retry. */
        static boolean noTable(SQLException e) {
            return e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState());
        }

        @Override
        public void setOptedOut(int charId, boolean optedOut, long now) {
            try (Connection con = connection();
                 PreparedStatement ps = con.prepareStatement(optedOut ? DRAMA_OFF : DRAMA_ON)) {
                ps.setInt(1, charId);
                if (optedOut) {
                    ps.setLong(2, now);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }
    }

    /** A store in memory: tests, and a server without a database. */
    class Memory implements AffinityStore {
        final Map<Long, Tie> rows = new ConcurrentHashMap<>();
        final Set<Integer> out = ConcurrentHashMap.newKeySet();

        private static long key(int charId, int botId) {
            return ((long) charId << 32) | (botId & 0xffffffffL);
        }

        @Override
        public List<Tie> load(int charId) {
            List<Tie> ties = new ArrayList<>();
            for (Tie t : rows.values()) {
                if (t.charId == charId) {
                    Tie copy = t.snapshot();
                    copy.score = Math.round(copy.score); // as the table stores it
                    copy.lastAt.clear();
                    ties.add(copy);
                }
            }
            return ties;
        }

        @Override
        public void save(Tie tie) {
            Tie copy = tie.snapshot();
            copy.dirty = false;
            rows.put(key(tie.charId, tie.botId), copy);
        }

        @Override
        public void delete(int charId, int botId) {
            rows.remove(key(charId, botId));
        }

        @Override
        public boolean optedOut(int charId) {
            return out.contains(charId);
        }

        @Override
        public void setOptedOut(int charId, boolean optedOut, long now) {
            if (optedOut) {
                out.add(charId);
            } else {
                out.remove(charId);
            }
        }

        final Map<Integer, Long> defamedAt = new ConcurrentHashMap<>();
        volatile boolean failing;

        @Override
        public Set<Integer> loadOptedOut() {
            return failing ? null : new java.util.HashSet<>(out);
        }

        @Override
        public Boolean claimDefame(int charId, long dayStart, long now) {
            if (failing) {
                return null;
            }
            boolean[] claimed = new boolean[1];
            defamedAt.compute(charId, (k, last) -> {
                if (last == null || last < dayStart) {
                    claimed[0] = true;
                    return now;
                }
                return last;
            });
            return claimed[0];
        }

        public int size() {
            return rows.size();
        }
    }
}
