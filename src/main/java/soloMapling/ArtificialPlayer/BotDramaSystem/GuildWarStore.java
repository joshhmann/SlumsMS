package soloMapling.ArtificialPlayer.BotDramaSystem;

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guild wars and alliances (1.5.0 bot drama, {@code botguildwar}, migration 016). A war lasts a week, so it is kept
 * across restarts with its running score; an alliance until it ends (or is betrayed). Read once at boot, written when
 * one starts or ends and every few minutes while a score moves. Without the migration everything lives in memory.
 */
public interface GuildWarStore {

    enum Kind { WAR, ALLIANCE }

    enum State {
        ACTIVE, ENDED, PEACE, BETRAYED;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One war or alliance between guild {@code a} (always a bot guild: the one that declared) and {@code b} (a bot guild,
     * or a player guild when {@code bPlayer}). Times are epoch ms.
     */
    record War(long id, Kind kind, int a, int b, boolean bPlayer, State state, long startedAt, long endsAt, int scoreA,
               int scoreB, long endedAt, boolean staged) {

        War withId(long newId) {
            return new War(newId, kind, a, b, bPlayer, state, startedAt, endsAt, scoreA, scoreB, endedAt, staged);
        }

        War scored(int sa, int sb) {
            return new War(id, kind, a, b, bPlayer, state, startedAt, endsAt, sa, sb, endedAt, staged);
        }

        War ended(State s, long at) {
            return new War(id, kind, a, b, bPlayer, s, startedAt, endsAt, scoreA, scoreB, at, staged);
        }

        public boolean active() {
            return state == State.ACTIVE;
        }

        public boolean involves(int guildId) {
            return a == guildId || b == guildId;
        }

        public int other(int guildId) {
            return a == guildId ? b : a;
        }
    }

    /** Active wars and alliances, and those that ended in the last two weeks; null when the table could not be read. */
    List<War> loadRecent(long since);

    /** The new row's id, or 0 when it could not be stored. */
    long insert(War w);

    void update(War w);

    final class Jdbc implements GuildWarStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String SELECT = "SELECT WarId, Kind, GuildA, GuildB, BPlayer, State, StartedAt, EndsAt, ScoreA, ScoreB,"
                + " EndedAt FROM botguildwar WHERE State = 'ACTIVE' OR EndedAt >= ?";
        static final String INSERT = "INSERT INTO botguildwar (Kind, GuildA, GuildB, BPlayer, State, StartedAt, EndsAt,"
                + " ScoreA, ScoreB, EndedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        static final String UPDATE = "UPDATE botguildwar SET State = ?, ScoreA = ?, ScoreB = ?, EndedAt = ?, EndsAt = ?"
                + " WHERE WarId = ?";

        private volatile boolean warned;

        private void missing(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Guild wars are not stored (is migration 016-bot-guild-wars applied?); keeping them in memory", e);
            } else {
                log.debug("Guild war store", e);
            }
        }

        @Override
        public List<War> loadRecent(long since) {
            List<War> out = new ArrayList<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(SELECT)) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            out.add(new War(rs.getLong("WarId"), Kind.valueOf(rs.getString("Kind")), rs.getInt("GuildA"),
                                    rs.getInt("GuildB"), rs.getBoolean("BPlayer"), State.valueOf(rs.getString("State")),
                                    rs.getLong("StartedAt"), rs.getLong("EndsAt"), rs.getInt("ScoreA"),
                                    rs.getInt("ScoreB"), rs.getLong("EndedAt"), false));
                        } catch (IllegalArgumentException bad) {
                            log.debug("guild war row", bad);
                        }
                    }
                }
                return out;
            } catch (SQLException e) {
                missing(e);
                return RomanceStore.Jdbc.noTable(e) ? out : null;
            }
        }

        @Override
        public long insert(War w) {
            try (Connection con = BotDbGate.connection();
                 PreparedStatement ps = con.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, w.kind().name());
                ps.setInt(2, w.a());
                ps.setInt(3, w.b());
                ps.setBoolean(4, w.bPlayer());
                ps.setString(5, w.state().name());
                ps.setLong(6, w.startedAt());
                ps.setLong(7, w.endsAt());
                ps.setInt(8, w.scoreA());
                ps.setInt(9, w.scoreB());
                ps.setLong(10, w.endedAt());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (SQLException e) {
                missing(e);
                return 0;
            }
        }

        @Override
        public void update(War w) {
            if (w.id() <= 0) {
                return;
            }
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(UPDATE)) {
                ps.setString(1, w.state().name());
                ps.setInt(2, w.scoreA());
                ps.setInt(3, w.scoreB());
                ps.setLong(4, w.endedAt());
                ps.setLong(5, w.endsAt());
                ps.setLong(6, w.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }
    }

    final class Memory implements GuildWarStore {
        final Map<Long, War> rows = new ConcurrentHashMap<>();
        final AtomicLong ids = new AtomicLong();
        volatile boolean failing;

        @Override
        public List<War> loadRecent(long since) {
            if (failing) {
                return null;
            }
            List<War> out = new ArrayList<>();
            for (War w : rows.values()) {
                if (w.active() || w.endedAt() >= since) {
                    out.add(w);
                }
            }
            return out;
        }

        @Override
        public long insert(War w) {
            long id = ids.incrementAndGet();
            rows.put(id, w.withId(id));
            return id;
        }

        @Override
        public void update(War w) {
            if (w.id() > 0) {
                rows.put(w.id(), w);
            }
        }
    }
}
