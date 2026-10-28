package soloMapling.site.auth;

import client.DefaultDates;
import soloMapling.ArtificialPlayer.BotStore;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 105: what invite codes and site sign-up / sign-in need from the database. {@link Jdbc} is the real one
 * ({@code invite_codes}, migration 036, plus {@code accounts}); tests use an in-memory one.
 */
public interface AuthStore {

    /** The world players are on: Bera is world id 1 on live and TEST, never 0 (AGENTS.md). */
    int MAIN_WORLD = 1;
    /** An account's characters on {@link #MAIN_WORLD}, bots left out, oldest first. */
    String CHARACTERS_SQL = "SELECT name FROM characters WHERE accountid = ? AND world = ? AND id < ? ORDER BY id";


    /** {@link #createAccountWithInvite}: the code was used, revoked or expired before this sign-up got it. */
    int CODE_GONE = -1;
    /** {@link #createAccountWithInvite}: an account with this name already exists. */
    int NAME_TAKEN = -2;

    /**
     * One row of {@code accounts}, as sign-in needs it. tempbanUntil: epoch ms, 0 when none. botAccount: the account
     * owns the bot template character ({@code -Dsolomapling.template-id}) or a bot-range character (id at or above
     * {@code BOT_ID_BASE}).
     */
    record Account(int id, String name, String passhash, boolean banned, long tempbanUntil, boolean botAccount) {
        public Account(int id, String name, String passhash, boolean banned, long tempbanUntil) {
            this(id, name, passhash, banned, tempbanUntil, false);
        }
    }

    void insertInvite(InviteCodes.Invite invite) throws SQLException;

    /** Null when no such code. */
    InviteCodes.Invite findInvite(String code) throws SQLException;

    /** Codes made with {@code @invite} by this account after {@code since} (epoch ms), revoked ones included. */
    default int countSelfServeInvitesSince(int accountId, long since) throws SQLException {
        return 0;
    }

    /** The highest level among the account's own (non-bot) characters, 0 when none. */
    default int highestCharacterLevel(int accountId) throws SQLException {
        return 0;
    }

    /** Every code the account made, newest first, whichever command made it. */
    default List<InviteCodes.Invite> invitesMadeBy(int accountId, int limit) throws SQLException {
        return List.of();
    }

    /** The newest codes first. */
    List<InviteCodes.Invite> recentInvites(int limit) throws SQLException;

    /** Revokes an unused, unrevoked code; false when there is no such open (or expired) code. */
    boolean revokeInvite(String code, String by, long now) throws SQLException;

    boolean accountNameTaken(String name) throws SQLException;

    /**
     * In one transaction: consumes the code (only while unused, unrevoked and unexpired at {@code now}) and creates
     * the account exactly as the login screen's automatic registration does. Returns the new account id, or
     * {@link #CODE_GONE} / {@link #NAME_TAKEN} with nothing changed.
     */
    int createAccountWithInvite(String code, String name, String passhash, String ipHash, long now) throws SQLException;

    /** Null when no such account. */
    Account findAccount(String name) throws SQLException;

    /** Stores an upgraded (bcrypt) hash, as the login screen does after a legacy match. */
    void upgradeHash(int accountId, String passhash) throws SQLException;

    /**
     * What a site session needs about a signed-in account: its highest GM level over its characters (as the version
     * gate reads it) and its characters on the main world (not bots), oldest first, as {@code @weblogin} sends them.
     */
    Profile profile(int accountId) throws SQLException;

    record Profile(int gmLevel, List<String> characters) {
    }

    /** The game database, through the server's connection pool. */
    final class Jdbc implements AuthStore {

        static final String COLUMNS = "code, created_at, created_by_account, created_by_name, note, expires_at,"
                + " used_by_account, used_by_name, used_at, used_ip_hash, revoked_at, revoked_by, created_by_char, self_serve";
        static final String INSERT = "INSERT INTO invite_codes (code, created_at, created_by_account, created_by_name,"
                + " note, expires_at, created_by_char, self_serve) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        static final String COUNT_SELF_SERVE = "SELECT COUNT(*) FROM invite_codes WHERE created_by_account = ?"
                + " AND self_serve = 1 AND created_at > ?";
        static final String HIGHEST_LEVEL = "SELECT COALESCE(MAX(level), 0) FROM characters WHERE accountid = ?"
                + " AND id < ?";
        // Who invited the new account: copied from the code in the sign-up transaction (staff only).
        static final String MARK_INVITER = "UPDATE accounts a JOIN invite_codes i ON i.code = ? SET"
                + " a.invited_by_account = i.created_by_account, a.invited_by_char = i.created_by_char WHERE a.id = ?";
        static final String CONSUME = "UPDATE invite_codes SET used_at = ?, used_ip_hash = ? WHERE code = ?"
                + " AND used_at = 0 AND revoked_at = 0 AND (expires_at = 0 OR expires_at > ?)";
        static final String MARK_USER = "UPDATE invite_codes SET used_by_account = ?, used_by_name = ? WHERE code = ?";
        static final String REVOKE = "UPDATE invite_codes SET revoked_at = ?, revoked_by = ? WHERE code = ?"
                + " AND used_at = 0 AND revoked_at = 0";
        static final String FIND_ACCOUNT = "SELECT a.id, a.name, a.password, a.banned, a.tempban, EXISTS (SELECT 1"
                + " FROM characters c WHERE c.accountid = a.id AND (c.id >= ? OR c.id = ?)) AS bot"
                + " FROM accounts a WHERE a.name = ?";
        // The same insert as LoginPasswordHandler's automatic registration.
        static final String INSERT_ACCOUNT = "INSERT INTO accounts (name, password, birthday, tempban) VALUES (?, ?, ?, ?)";

        @Override
        public void insertInvite(InviteCodes.Invite i) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(INSERT)) {
                ps.setString(1, i.code());
                ps.setLong(2, i.createdAt());
                ps.setInt(3, i.createdByAccount());
                ps.setString(4, i.createdByName());
                ps.setString(5, i.note());
                ps.setLong(6, i.expiresAt());
                ps.setInt(7, i.createdByChar());
                ps.setBoolean(8, i.selfServe());
                ps.executeUpdate();
            }
        }

        @Override
        public int countSelfServeInvitesSince(int accountId, long since) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(COUNT_SELF_SERVE)) {
                ps.setInt(1, accountId);
                ps.setLong(2, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        @Override
        public int highestCharacterLevel(int accountId) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(HIGHEST_LEVEL)) {
                ps.setInt(1, accountId);
                ps.setInt(2, BotStore.BOT_ID_BASE);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        @Override
        public List<InviteCodes.Invite> invitesMadeBy(int accountId, int limit) throws SQLException {
            List<InviteCodes.Invite> out = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT " + COLUMNS
                         + " FROM invite_codes WHERE created_by_account = ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setInt(1, accountId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs));
                    }
                }
            }
            return out;
        }

        @Override
        public InviteCodes.Invite findInvite(String code) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT " + COLUMNS + " FROM invite_codes WHERE code = ?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        }

        @Override
        public List<InviteCodes.Invite> recentInvites(int limit) throws SQLException {
            List<InviteCodes.Invite> out = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT " + COLUMNS
                         + " FROM invite_codes ORDER BY created_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs));
                    }
                }
            }
            return out;
        }

        private static InviteCodes.Invite read(ResultSet rs) throws SQLException {
            return new InviteCodes.Invite(rs.getString("code"), rs.getLong("created_at"),
                    rs.getInt("created_by_account"), rs.getString("created_by_name"), rs.getString("note"),
                    rs.getLong("expires_at"), rs.getInt("used_by_account"), rs.getString("used_by_name"),
                    rs.getLong("used_at"), rs.getLong("revoked_at"), rs.getString("revoked_by"),
                    rs.getInt("created_by_char"), rs.getBoolean("self_serve"));
        }

        @Override
        public boolean revokeInvite(String code, String by, long now) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(REVOKE)) {
                ps.setLong(1, now);
                ps.setString(2, by);
                ps.setString(3, code);
                return ps.executeUpdate() == 1;
            }
        }

        @Override
        public boolean accountNameTaken(String name) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT 1 FROM accounts WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        @Override
        public int createAccountWithInvite(String code, String name, String passhash, String ipHash, long now)
                throws SQLException {
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean autoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    // The conditional update takes the code's row lock: a second sign-up with the same code waits
                    // here, then finds it used and changes nothing.
                    try (PreparedStatement ps = con.prepareStatement(CONSUME)) {
                        ps.setLong(1, now);
                        ps.setString(2, ipHash);
                        ps.setString(3, code);
                        ps.setLong(4, now);
                        if (ps.executeUpdate() != 1) {
                            con.rollback();
                            return CODE_GONE;
                        }
                    }
                    try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM accounts WHERE name = ? FOR UPDATE")) {
                        ps.setString(1, name);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                con.rollback();
                                return NAME_TAKEN;
                            }
                        }
                    }
                    int id;
                    try (PreparedStatement ps = con.prepareStatement(INSERT_ACCOUNT, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, name);
                        ps.setString(2, passhash);
                        ps.setDate(3, Date.valueOf(DefaultDates.getBirthday()));
                        ps.setTimestamp(4, Timestamp.valueOf(DefaultDates.getTempban()));
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            rs.next();
                            id = rs.getInt(1);
                        }
                    }
                    try (PreparedStatement ps = con.prepareStatement(MARK_USER)) {
                        ps.setInt(1, id);
                        ps.setString(2, name);
                        ps.setString(3, code);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = con.prepareStatement(MARK_INVITER)) {
                        ps.setString(1, code);
                        ps.setInt(2, id);
                        ps.executeUpdate();
                    }
                    con.commit();
                    return id;
                } catch (SQLException | RuntimeException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(autoCommit);
                }
            }
        }

        @Override
        public Account findAccount(String name) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         FIND_ACCOUNT)) {
                ps.setInt(1, BotStore.BOT_ID_BASE);
                ps.setInt(2, Integer.getInteger("solomapling.template-id", 0));
                ps.setString(3, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Timestamp tempban = rs.getTimestamp("tempban");
                    return new Account(rs.getInt("id"), rs.getString("name"), rs.getString("password"),
                            rs.getByte("banned") == 1, tempban == null ? 0 : tempban.getTime(), rs.getBoolean("bot"));
                }
            }
        }

        @Override
        public void upgradeHash(int accountId, String passhash) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE accounts SET password = ? WHERE id = ?")) {
                ps.setString(1, passhash);
                ps.setInt(2, accountId);
                ps.executeUpdate();
            }
        }

        @Override
        public Profile profile(int accountId) throws SQLException {
            int gm = 0;
            List<String> names = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection()) {
                try (PreparedStatement ps = con.prepareStatement("SELECT MAX(gm) FROM characters WHERE accountid = ?")) {
                    ps.setInt(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            gm = rs.getInt(1);
                        }
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(CHARACTERS_SQL)) {
                    ps.setInt(1, accountId);
                    ps.setInt(2, MAIN_WORLD);
                    ps.setInt(3, BotStore.BOT_ID_BASE);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            names.add(rs.getString(1));
                        }
                    }
                }
            }
            return new Profile(gm, names);
        }
    }
}
