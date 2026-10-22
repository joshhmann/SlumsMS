package soloMapling.ArtificialPlayer.BotGuildSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotDbGate;
import soloMapling.ArtificialPlayer.BotStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where bot guilds live: the stock {@code guilds} row (so the client, the guild list and the ranking NPC see them),
 * plus {@code botguild} and {@code botguildmember} (migration 014). Read once at start, written on change. Without
 * the migration the JDBC store says so ({@link #available}) and keeps nothing: no guild is founded and a bot that
 * joins a player's guild is a member until a restart.
 */
public interface GuildStore {

    /** A bot-run guild: its stock row's name and leader plus how it behaves. */
    record GuildRow(int guildId, String name, int leaderId, GuildRules.Vibe vibe, int rivalId, int targetSize,
                    long createdAt) {
        GuildRow withRival(int rival) {
            return new GuildRow(guildId, name, leaderId, vibe, rival, targetSize, createdAt);
        }

        GuildRow withLeader(int leader) {
            return new GuildRow(guildId, name, leader, vibe, rivalId, targetSize, createdAt);
        }
    }

    /** A bot in a guild (any guild: bot-run or a player's). {@code charId} = BOT_ID_BASE + BotId. */
    record MemberRow(int charId, int guildId, int rank, long joinedAt, long attentionAt, String name, int level,
                     int job) {
        MemberRow withRank(int newRank) {
            return new MemberRow(charId, guildId, newRank, joinedAt, attentionAt, name, level, job);
        }

        MemberRow attended(long at) {
            return new MemberRow(charId, guildId, rank, joinedAt, at, name, level, job);
        }
    }

    /** Whether migration 014 is applied (the tables exist). */
    boolean available();

    /** Every bot-run guild; null when the table could not be read (empty when the migration is not applied). */
    List<GuildRow> loadGuilds();

    /** Every bot membership; null when the table could not be read (empty when the migration is not applied). */
    List<MemberRow> loadMembers();

    /** A stored bot, for founding. */
    record StoredBot(int botId, String name, int level, int job) {
    }

    /** Every bot in the bot store (name, level, job), for founding. */
    List<StoredBot> loadBots();

    /**
     * Writes a new guild: its stock row and its {@code botguild} row. The new guild id, or 0 when the name is taken
     * or it could not be stored.
     */
    int found(GuildPlan.Spec spec, int leaderCharId, int capacity, long now);

    void setRival(int guildId, int rivalId);

    void saveMember(MemberRow row);

    void deleteMember(int charId);

    /** The guild's stored GP, or -1 when it cannot be read. */
    int gp(int guildId);

    /** Sets the guild's stored GP to {@code value} if it still is {@code expected}; true when it did. */
    boolean replaceGp(int guildId, int expected, int value);

    final class Jdbc implements GuildStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String GUILDS = "SELECT b.GuildId, b.Vibe, b.RivalId, b.TargetSize, b.CreatedAt, g.name, g.leader "
                + "FROM botguild b JOIN guilds g ON g.guildid = b.GuildId";
        static final String MEMBERS = "SELECT m.BotId, m.GuildId, m.GuildRank, m.JoinedAt, m.AttentionAt, s.Name, "
                + "s.Level, s.Job FROM botguildmember m LEFT JOIN botstore s ON s.BotId = m.BotId";
        static final String BOTS = "SELECT BotId, Name, Level, Job FROM botstore";
        static final String NAME_TAKEN = "SELECT guildid FROM guilds WHERE name = ?";
        static final String INSERT_GUILD = "INSERT INTO guilds (leader, name, signature, capacity, logo, logoColor, "
                + "logoBG, logoBGColor, notice, GP) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        static final String INSERT_BOTGUILD = "INSERT INTO botguild (GuildId, Vibe, RivalId, TargetSize, CreatedAt) "
                + "VALUES (?, ?, 0, ?, ?)";
        static final String RIVAL = "UPDATE botguild SET RivalId = ? WHERE GuildId = ?";
        static final String SAVE_MEMBER = "INSERT INTO botguildmember (BotId, GuildId, GuildRank, JoinedAt, AttentionAt) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE GuildId = VALUES(GuildId), "
                + "GuildRank = VALUES(GuildRank), JoinedAt = VALUES(JoinedAt), AttentionAt = VALUES(AttentionAt)";
        static final String DELETE_MEMBER = "DELETE FROM botguildmember WHERE BotId = ?";

        private volatile Boolean available;
        private volatile boolean warned;

        private void missing(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Bot guilds are not stored (is migration 014-bot-guilds applied?)", e);
            } else {
                log.debug("Bot guild store", e);
            }
        }

        @Override
        public boolean available() {
            return Boolean.TRUE.equals(probe());
        }

        /**
         * True when the tables exist, false when the migration is not applied (remembered), null when the database
         * could not be asked (asked again next time: a failed read at boot is not "no migration").
         */
        private Boolean probe() {
            Boolean a = available;
            if (a != null) {
                return a;
            }
            try (Connection con = BotDbGate.connection(); PreparedStatement ps =
                    con.prepareStatement("SELECT 1 FROM botguild LIMIT 1"); ResultSet rs = ps.executeQuery()) {
                available = true;
            } catch (SQLException e) {
                missing(e);
                if (!noTable(e)) {
                    return null;
                }
                available = false;
            }
            return available;
        }

        /** MySQL's "table doesn't exist". */
        static boolean noTable(SQLException e) {
            return e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState());
        }

        @Override
        public List<GuildRow> loadGuilds() {
            List<GuildRow> out = new ArrayList<>();
            Boolean a = probe();
            if (a == null) {
                return null;
            }
            if (!a) {
                return out;
            }
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(GUILDS);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GuildRow(rs.getInt("GuildId"), rs.getString("name"), rs.getInt("leader"),
                            GuildRules.Vibe.parse(rs.getString("Vibe")), rs.getInt("RivalId"), rs.getInt("TargetSize"),
                            rs.getLong("CreatedAt")));
                }
            } catch (SQLException e) {
                missing(e);
                return null;
            }
            return out;
        }

        @Override
        public List<MemberRow> loadMembers() {
            List<MemberRow> out = new ArrayList<>();
            Boolean a = probe();
            if (a == null) {
                return null;
            }
            if (!a) {
                return out;
            }
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(MEMBERS);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("Name");
                    if (name == null) {
                        continue; // the bot is gone from the store
                    }
                    out.add(new MemberRow(BotStore.BOT_ID_BASE + rs.getInt("BotId"), rs.getInt("GuildId"),
                            rs.getInt("GuildRank"), rs.getLong("JoinedAt"), rs.getLong("AttentionAt"), name,
                            rs.getInt("Level"), rs.getInt("Job")));
                }
            } catch (SQLException e) {
                missing(e);
                return null;
            }
            return out;
        }

        @Override
        public List<StoredBot> loadBots() {
            List<StoredBot> out = new ArrayList<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(BOTS);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredBot(rs.getInt("BotId"), rs.getString("Name"), rs.getInt("Level"), rs.getInt("Job")));
                }
            } catch (SQLException e) {
                missing(e);
            }
            return out;
        }

        @Override
        public int found(GuildPlan.Spec spec, int leaderCharId, int capacity, long now) {
            try (Connection con = BotDbGate.connection()) {
                try (PreparedStatement ps = con.prepareStatement(NAME_TAKEN)) {
                    ps.setString(1, spec.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            log.info("Bot guild {} not founded: a guild of that name exists", spec.name());
                            return 0;
                        }
                    }
                }
                int id;
                try (PreparedStatement ps = con.prepareStatement(INSERT_GUILD, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, leaderCharId);
                    ps.setString(2, spec.name());
                    ps.setInt(3, (int) now);
                    ps.setInt(4, capacity);
                    ps.setInt(5, spec.logo());
                    ps.setInt(6, spec.logoColor());
                    ps.setInt(7, spec.logoBG());
                    ps.setInt(8, spec.logoBGColor());
                    ps.setString(9, spec.notice());
                    ps.setInt(10, GuildRules.seedGp(spec.size())); // some history, small enough to pass
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys == null || !keys.next()) {
                            return 0;
                        }
                        id = keys.getInt(1);
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(INSERT_BOTGUILD)) {
                    ps.setInt(1, id);
                    ps.setString(2, spec.vibe().label());
                    ps.setInt(3, spec.size());
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                return id;
            } catch (SQLException e) {
                missing(e);
                return 0;
            }
        }

        @Override
        public void setRival(int guildId, int rivalId) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(RIVAL)) {
                ps.setInt(1, rivalId);
                ps.setInt(2, guildId);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public void saveMember(MemberRow row) {
            if (!available()) {
                return;
            }
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(SAVE_MEMBER)) {
                ps.setInt(1, row.charId() - BotStore.BOT_ID_BASE);
                ps.setInt(2, row.guildId());
                ps.setInt(3, row.rank());
                ps.setLong(4, row.joinedAt());
                ps.setLong(5, row.attentionAt());
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public int gp(int guildId) {
            try (Connection con = BotDbGate.connection();
                 PreparedStatement ps = con.prepareStatement("SELECT GP FROM guilds WHERE guildid = ?")) {
                ps.setInt(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            } catch (SQLException e) {
                missing(e);
                return -1;
            }
        }

        @Override
        public boolean replaceGp(int guildId, int expected, int value) {
            try (Connection con = BotDbGate.connection();
                 PreparedStatement ps = con.prepareStatement("UPDATE guilds SET GP = ? WHERE guildid = ? AND GP = ?")) {
                ps.setInt(1, value);
                ps.setInt(2, guildId);
                ps.setInt(3, expected);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                missing(e);
                return false;
            }
        }

        @Override
        public void deleteMember(int charId) {
            if (!available()) {
                return;
            }
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(DELETE_MEMBER)) {
                ps.setInt(1, charId - BotStore.BOT_ID_BASE);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }
    }

    /** In memory: tests. */
    final class Memory implements GuildStore {
        final Map<Integer, GuildRow> guilds = new ConcurrentHashMap<>();
        final Map<Integer, MemberRow> members = new ConcurrentHashMap<>();
        final Map<Integer, Integer> gp = new ConcurrentHashMap<>();
        final List<StoredBot> bots = new ArrayList<>();
        private final AtomicInteger ids = new AtomicInteger(500);
        boolean available = true;
        /** Tests: reads fail (a database that is down at boot). */
        volatile boolean failing;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<GuildRow> loadGuilds() {
            return failing ? null : new ArrayList<>(guilds.values());
        }

        @Override
        public List<MemberRow> loadMembers() {
            return failing ? null : new ArrayList<>(members.values());
        }

        @Override
        public List<StoredBot> loadBots() {
            return new ArrayList<>(bots);
        }

        @Override
        public int found(GuildPlan.Spec spec, int leaderCharId, int capacity, long now) {
            for (GuildRow g : guilds.values()) {
                if (g.name().equalsIgnoreCase(spec.name())) {
                    return 0;
                }
            }
            int id = ids.incrementAndGet();
            guilds.put(id, new GuildRow(id, spec.name(), leaderCharId, spec.vibe(), 0, spec.size(), now));
            gp.put(id, GuildRules.seedGp(spec.size()));
            return id;
        }

        @Override
        public int gp(int guildId) {
            return gp.getOrDefault(guildId, -1);
        }

        @Override
        public boolean replaceGp(int guildId, int expected, int value) {
            return gp.replace(guildId, expected, value);
        }

        @Override
        public void setRival(int guildId, int rivalId) {
            GuildRow g = guilds.get(guildId);
            if (g != null) {
                guilds.put(guildId, g.withRival(rivalId));
            }
        }

        @Override
        public void saveMember(MemberRow row) {
            members.put(row.charId(), row);
        }

        @Override
        public void deleteMember(int charId) {
            members.remove(charId);
        }
    }
}
