package soloMapling.ArtificialPlayer.BotCoupleSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotDbGate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where bot couples live ({@code botcouple}, migration 012). A handful of rows: all read once, written on change.
 * Without the migration the JDBC store logs once and keeps nothing, so couples last until a restart.
 */
public interface CoupleStore {

    /** Every stored couple; null when the table could not be read (empty when the migration is not applied). */
    List<BotCouples.Couple> loadAll();

    /**
     * Stores a new couple; its id. 0 when it could not be stored (a memory-only id is used then), {@link #TAKEN} when
     * either bot is already in a stored couple (in either column): then there is no couple.
     */
    long insert(BotCouples.Couple couple);

    long TAKEN = -1;

    void update(BotCouples.Couple couple);

    void delete(long id);

    final class Jdbc implements CoupleStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String SELECT = "SELECT * FROM botcouple";
        /**
         * The table's unique keys hold per column only (BotA, BotB), so a bot could be BotA of one row and BotB of
         * another: the insert checks both columns itself, in the same statement.
         */
        static final String INSERT = "INSERT INTO botcouple (BotA, BotB, Stage, FormedAt, MarriedAt, LastNoteAt) "
                + "SELECT ?, ?, ?, ?, ?, ? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM botcouple "
                + "WHERE BotA IN (?, ?) OR BotB IN (?, ?))";
        static final String UPDATE = "UPDATE botcouple SET Stage = ?, MarriedAt = ?, LastNoteAt = ? WHERE CoupleId = ?";
        static final String DELETE = "DELETE FROM botcouple WHERE CoupleId = ?";

        private volatile boolean warned;

        private void missing(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Bot couples are not stored (is migration 012-bot-couples applied?); keeping them in memory", e);
            } else {
                log.debug("Bot couple store", e);
            }
        }

        @Override
        public List<BotCouples.Couple> loadAll() {
            List<BotCouples.Couple> out = new ArrayList<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(SELECT);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BotCouples.Stage stage;
                    try {
                        stage = BotCouples.Stage.valueOf(rs.getString("Stage"));
                    } catch (IllegalArgumentException e) {
                        stage = BotCouples.Stage.DATING;
                    }
                    out.add(new BotCouples.Couple(rs.getLong("CoupleId"), rs.getInt("BotA"), rs.getInt("BotB"), stage,
                            rs.getLong("FormedAt"), rs.getLong("MarriedAt"), rs.getLong("LastNoteAt")));
                }
            } catch (SQLException e) {
                missing(e);
                return noTable(e) ? out : null; // no migration: memory only; anything else: not read, try again
            }
            return out;
        }

        /** MySQL's "table doesn't exist". */
        static boolean noTable(SQLException e) {
            return e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState());
        }

        @Override
        public long insert(BotCouples.Couple c) {
            try (Connection con = BotDbGate.connection();
                 PreparedStatement ps = con.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, c.a());
                ps.setInt(2, c.b());
                ps.setString(3, c.stage().name());
                ps.setLong(4, c.formedAt());
                ps.setLong(5, c.marriedAt());
                ps.setLong(6, c.lastNoteAt());
                ps.setInt(7, c.a());
                ps.setInt(8, c.b());
                ps.setInt(9, c.a());
                ps.setInt(10, c.b());
                if (ps.executeUpdate() == 0) {
                    return TAKEN;
                }
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys != null && keys.next() ? keys.getLong(1) : 0;
                }
            } catch (SQLException e) {
                missing(e);
                return e.getErrorCode() == 1062 ? TAKEN : 0; // 1062: a unique key - one of them is in a couple
            }
        }

        @Override
        public void update(BotCouples.Couple c) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(UPDATE)) {
                ps.setString(1, c.stage().name());
                ps.setLong(2, c.marriedAt());
                ps.setLong(3, c.lastNoteAt());
                ps.setLong(4, c.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public void delete(long id) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(DELETE)) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }
    }

    /** In memory: tests, and staged (GM) couples of on-demand bots, which are never stored. */
    final class Memory implements CoupleStore {
        final Map<Long, BotCouples.Couple> rows = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        /** Tests: reads fail (a database that is down at boot). */
        volatile boolean failing;

        @Override
        public List<BotCouples.Couple> loadAll() {
            return failing ? null : new ArrayList<>(rows.values());
        }

        @Override
        public synchronized long insert(BotCouples.Couple c) {
            for (BotCouples.Couple r : rows.values()) {
                if (r.has(c.a()) || r.has(c.b())) {
                    return TAKEN; // as the table's insert: both columns
                }
            }
            long id = ids.incrementAndGet();
            rows.put(id, c.withId(id));
            return id;
        }

        @Override
        public void update(BotCouples.Couple c) {
            rows.put(c.id(), c);
        }

        @Override
        public void delete(long id) {
            rows.remove(id);
        }
    }
}
