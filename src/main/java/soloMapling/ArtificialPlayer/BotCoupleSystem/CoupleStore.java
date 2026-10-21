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

    List<BotCouples.Couple> loadAll();

    /** Stores a new couple; its id (0 when it could not be stored, a memory-only id is used then). */
    long insert(BotCouples.Couple couple);

    void update(BotCouples.Couple couple);

    void delete(long id);

    final class Jdbc implements CoupleStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String SELECT = "SELECT * FROM botcouple";
        static final String INSERT = "INSERT INTO botcouple (BotA, BotB, Stage, FormedAt, MarriedAt, LastNoteAt) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
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
            }
            return out;
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
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys != null && keys.next() ? keys.getLong(1) : 0;
                }
            } catch (SQLException e) {
                missing(e);
                return 0;
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

        @Override
        public List<BotCouples.Couple> loadAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public long insert(BotCouples.Couple c) {
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
