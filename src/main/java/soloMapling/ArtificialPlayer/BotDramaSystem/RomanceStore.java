package soloMapling.ArtificialPlayer.BotDramaSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotDbGate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where player-bot romances and {@code @romance on} live (migration 015: {@code botromance}, {@code botromanceopt}).
 * Small: a row per player and bot they dated, an opt-in row per player who turned romance on. Read once at boot, written
 * on change, always off the game thread. Without the migration the JDBC store logs once and keeps nothing: romance then
 * lives in memory until a restart (a real marriage's stock rows stay, and the bot spouse is found again from them).
 */
public interface RomanceStore {

    /** Every row; null when the table could not be read (empty when the migration is not applied). */
    List<Romance> loadAll();

    void save(Romance r);

    void delete(int charId, int botId);

    /** Every character with {@code @romance on}; null when it could not be read. */
    Set<Integer> loadOptedIn();

    void setOptedIn(int charId, boolean in, long now);

    final class Jdbc implements RomanceStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String ALL = "SELECT CharacterId, BotId, Stage, StartedAt, TogetherAt, EndedAt, RingItemId, BotRingId"
                + " FROM botromance";
        static final String UPSERT = "INSERT INTO botromance (CharacterId, BotId, Stage, StartedAt, TogetherAt, EndedAt,"
                + " RingItemId, BotRingId) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE Stage = VALUES(Stage),"
                + " StartedAt = VALUES(StartedAt), TogetherAt = VALUES(TogetherAt), EndedAt = VALUES(EndedAt),"
                + " RingItemId = VALUES(RingItemId), BotRingId = VALUES(BotRingId)";
        static final String DELETE = "DELETE FROM botromance WHERE CharacterId = ? AND BotId = ?";
        static final String OPT_ALL = "SELECT CharacterId FROM botromanceopt";
        static final String OPT_IN = "INSERT INTO botromanceopt (CharacterId, OptedInAt) VALUES (?, ?) ON DUPLICATE KEY"
                + " UPDATE OptedInAt = VALUES(OptedInAt)";
        static final String OPT_OUT = "DELETE FROM botromanceopt WHERE CharacterId = ?";

        private volatile boolean warned;

        private void missing(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Bot romance is not stored (is migration 015-bot-romance applied?); keeping it in memory", e);
            } else {
                log.debug("Bot romance store", e);
            }
        }

        static boolean noTable(SQLException e) {
            return e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState());
        }

        @Override
        public List<Romance> loadAll() {
            List<Romance> out = new ArrayList<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(ALL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Romance.Stage stage = Romance.Stage.parse(rs.getString("Stage"));
                    if (stage != null) {
                        out.add(new Romance(rs.getInt("CharacterId"), rs.getInt("BotId"), stage, rs.getLong("StartedAt"),
                                rs.getLong("TogetherAt"), rs.getLong("EndedAt"), rs.getInt("RingItemId"),
                                rs.getInt("BotRingId"), false));
                    }
                }
                return out;
            } catch (SQLException e) {
                missing(e);
                return noTable(e) ? out : null;
            }
        }

        @Override
        public void save(Romance r) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(UPSERT)) {
                ps.setInt(1, r.charId());
                ps.setInt(2, r.botId());
                ps.setString(3, r.stage().name());
                ps.setLong(4, r.startedAt());
                ps.setLong(5, r.togetherAt());
                ps.setLong(6, r.endedAt());
                ps.setInt(7, r.ringItemId());
                ps.setInt(8, r.botRingId());
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public void delete(int charId, int botId) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(DELETE)) {
                ps.setInt(1, charId);
                ps.setInt(2, botId);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public Set<Integer> loadOptedIn() {
            Set<Integer> out = new HashSet<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(OPT_ALL);
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
        public void setOptedIn(int charId, boolean in, long now) {
            try (Connection con = BotDbGate.connection();
                 PreparedStatement ps = con.prepareStatement(in ? OPT_IN : OPT_OUT)) {
                ps.setInt(1, charId);
                if (in) {
                    ps.setLong(2, now);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }
    }

    /** In memory: tests, and a server without the migration. */
    final class Memory implements RomanceStore {
        final Map<Long, Romance> rows = new ConcurrentHashMap<>();
        final Set<Integer> in = ConcurrentHashMap.newKeySet();
        volatile boolean failing;

        static long key(int charId, int botId) {
            return ((long) charId << 32) | (botId & 0xffffffffL);
        }

        @Override
        public List<Romance> loadAll() {
            return failing ? null : new ArrayList<>(rows.values());
        }

        @Override
        public void save(Romance r) {
            rows.put(key(r.charId(), r.botId()), r);
        }

        @Override
        public void delete(int charId, int botId) {
            rows.remove(key(charId, botId));
        }

        @Override
        public Set<Integer> loadOptedIn() {
            return failing ? null : new HashSet<>(in);
        }

        @Override
        public void setOptedIn(int charId, boolean on, long now) {
            if (on) {
                in.add(charId);
            } else {
                in.remove(charId);
            }
        }
    }
}
