package soloMapling.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.site.auth.PasswordResets;
import soloMapling.site.auth.SiteAccounts;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The dashboard's "Accounts" panel (ticket 105 follow-up): real account totals, invite code counts, the latest sign-ups
 * and the sign-in lockouts of the last day. Read on the exporter's own thread through the DB pool with a few cheap
 * queries (the accounts table, and the tiny {@code invite_codes}). Exports names, times, the first 4 characters of an
 * invite code, who made it and its note, and counts. Never a password, hash, IP, email or full code: the queries select
 * only the columns below, and {@link #build} is the only thing that shapes the output.
 * <p>
 * Ticket 107 adds the password reset requests ({@code password_resets}, migration 037): counts by state and the latest
 * 10 with account, time, state, the GM who decided and the contact the player left. Never the secret hash or ipHash,
 * and never the decoy rows kept for names that are not resettable accounts (account_id 0).
 */
final class AccountsExport {

    private static final Logger log = LoggerFactory.getLogger(AccountsExport.class);
    static final int LATEST = 20;
    static final int CODE_PREFIX = 4;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter PT = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT)
            .withZone(ZoneId.of("America/Los_Angeles"));

    /** A real account is not a bot's, the bot template's, or an auto-QA observer's. */
    static final String REAL = "a.name <> 'SMBotBase' AND a.name NOT LIKE 'autoqa%' AND NOT EXISTS (SELECT 1 FROM characters c"
            + " WHERE c.accountid = a.id AND (c.id >= ? OR c.id = ?))";
    static final String TOTALS = "SELECT COUNT(*), COALESCE(SUM(a.createdat >= FROM_UNIXTIME(?)), 0),"
            + " COALESCE(SUM(a.createdat >= FROM_UNIXTIME(?)), 0) FROM accounts a WHERE " + REAL;
    static final String SIGNUPS = "SELECT a.name, UNIX_TIMESTAMP(a.createdat), i.code, i.created_by_name, i.note"
            + " FROM accounts a LEFT JOIN invite_codes i ON i.used_by_account = a.id WHERE " + REAL
            + " ORDER BY a.createdat DESC, a.id DESC LIMIT " + LATEST;
    static final String SIGNUPS_NO_INVITES = "SELECT a.name, UNIX_TIMESTAMP(a.createdat), NULL, NULL, NULL"
            + " FROM accounts a WHERE " + REAL + " ORDER BY a.createdat DESC, a.id DESC LIMIT " + LATEST;
    static final String INVITES = "SELECT COALESCE(SUM(revoked_at > 0), 0), COALESCE(SUM(revoked_at = 0 AND used_at > 0), 0),"
            + " COALESCE(SUM(revoked_at = 0 AND used_at = 0 AND expires_at > 0 AND expires_at <= ?), 0),"
            + " COALESCE(SUM(revoked_at = 0 AND used_at = 0 AND (expires_at = 0 OR expires_at > ?)), 0) FROM invite_codes";

    static final int LATEST_RESETS = 10;
    static final String RESET_COUNTS = "SELECT COALESCE(SUM(state = 'pending' AND created_at > ?), 0),"
            + " COALESCE(SUM(state IN ('approved', 'used')), 0), COALESCE(SUM(state = 'used'), 0),"
            + " COALESCE(SUM(state = 'denied'), 0) FROM password_resets WHERE account_id > 0";
    static final String RESET_LATEST = "SELECT account_name, created_at, state, decided_by, contact, decided_at"
            + " FROM password_resets WHERE account_id > 0 ORDER BY created_at DESC LIMIT " + LATEST_RESETS;

    private AccountsExport() {
    }

    record Totals(long accounts, long week, long today) {
    }

    record Invites(long active, long used, long expired, long revoked) {
    }

    /** One sign-up. {@code code} is the whole code here but never leaves {@link #build} longer than 4 characters. */
    record Signup(String name, long at, String code, String madeBy, String note) {
    }

    /** Reset requests: pending (open, under 24 h), approved (ever, including used), used, denied. */
    record ResetCounts(long pending, long approved, long used, long denied) {
    }

    /** One reset request; state is as stored (pending, approved, denied, used, replaced). */
    record Reset(String account, long at, String state, String decidedBy, String contact, long decidedAt) {
    }

    /** What the database said; a null part means that query failed or the table is not there. */
    record Data(Totals totals, Invites invites, List<Signup> signups, ResetCounts resetCounts, List<Reset> resets) {
        Data(Totals totals, Invites invites, List<Signup> signups) {
            this(totals, invites, signups, null, null);
        }
    }

    /** The panel as JSON-ready maps. {@code lockouts} is the in-memory count of the last 24 hours. */
    static Map<String, Object> build(long now, Data data, long lockouts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now / 1000);
        Totals t = data.totals();
        if (t != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accounts", t.accounts());
            m.put("week", t.week());
            m.put("today", t.today());
            out.put("totals", m);
        }
        Invites i = data.invites();
        if (i != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("active", i.active());
            m.put("used", i.used());
            m.put("expired", i.expired());
            m.put("revoked", i.revoked());
            out.put("invites", m);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Signup s : data.signups() == null ? List.<Signup>of() : data.signups()) {
            Map<String, Object> r = new LinkedHashMap<>();
            Instant at = Instant.ofEpochMilli(s.at());
            r.put("utc", UTC.format(at));
            r.put("pt", PT.format(at));
            r.put("name", s.name());
            boolean invited = s.code() != null && !s.code().isEmpty();
            r.put("invite", invited ? s.code().substring(0, Math.min(CODE_PREFIX, s.code().length())) + "…" : null);
            r.put("madeBy", invited ? s.madeBy() : null);
            r.put("note", invited ? s.note() : null);
            r.put("autoRegister", !invited);
            rows.add(r);
        }
        out.put("signups", rows);
        out.put("lockouts24h", lockouts);
        ResetCounts rc = data.resetCounts();
        if (rc != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pending", rc.pending());
            m.put("approved", rc.approved());
            m.put("used", rc.used());
            m.put("denied", rc.denied());
            List<Map<String, Object>> latest = new ArrayList<>();
            for (Reset r : data.resets() == null ? List.<Reset>of() : data.resets()) {
                Map<String, Object> row = new LinkedHashMap<>();
                Instant at = Instant.ofEpochMilli(r.at());
                row.put("utc", UTC.format(at));
                row.put("pt", PT.format(at));
                row.put("account", r.account());
                row.put("state", PasswordResets.dashboardState(r.state(), r.at(), r.decidedAt(), now));
                row.put("decidedBy", r.decidedBy() == null || r.decidedBy().isEmpty() ? null : r.decidedBy());
                row.put("contact", r.contact());
                latest.add(row);
            }
            m.put("latest", latest);
            out.put("resets", m);
        }
        return out;
    }

    /** Reads the database and the lockout count; never throws. A failed query only leaves its part out. */
    static Map<String, Object> read(long now) {
        Totals totals = null;
        Invites invites = null;
        List<Signup> signups = null;
        ResetCounts resetCounts = null;
        List<Reset> resets = null;
        try (Connection con = DatabaseConnection.getConnection()) {
            long dayStart = now - now % TimeUnit.DAYS.toMillis(1);
            try (PreparedStatement ps = con.prepareStatement(TOTALS)) {
                ps.setLong(1, (now - TimeUnit.DAYS.toMillis(7)) / 1000);
                ps.setLong(2, dayStart / 1000);
                realArgs(ps, 3);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totals = new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3));
                    }
                }
            } catch (SQLException e) {
                log.debug("Accounts totals not read", e);
            }
            try (PreparedStatement ps = con.prepareStatement(INVITES)) {
                ps.setLong(1, now);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        invites = new Invites(rs.getLong(4), rs.getLong(2), rs.getLong(3), rs.getLong(1));
                    }
                }
                signups = signups(con, SIGNUPS);
            } catch (SQLException e) {
                log.debug("Invite codes not read (migration 036 missing?)", e);
                try {
                    signups = signups(con, SIGNUPS_NO_INVITES);
                } catch (SQLException e2) {
                    log.debug("Sign-ups not read", e2);
                }
            }
            try (PreparedStatement ps = con.prepareStatement(RESET_COUNTS)) {
                ps.setLong(1, now - TimeUnit.DAYS.toMillis(1));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        resetCounts = new ResetCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
                    }
                }
                resets = new ArrayList<>();
                try (PreparedStatement latest = con.prepareStatement(RESET_LATEST);
                     ResultSet rs = latest.executeQuery()) {
                    while (rs.next()) {
                        resets.add(new Reset(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getLong(6)));
                    }
                }
            } catch (SQLException e) {
                log.debug("Password resets not read (migration 037 missing?)", e);
                resetCounts = null;
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("Accounts panel not read", e);
        }
        return build(now, new Data(totals, invites, signups, resetCounts, resets), SiteAccounts.lockoutsSince(now - TimeUnit.DAYS.toMillis(1), now));
    }

    private static void realArgs(PreparedStatement ps, int from) throws SQLException {
        ps.setInt(from, BotStore.BOT_ID_BASE);
        ps.setInt(from + 1, Integer.getInteger("solomapling.template-id", 0));
    }

    private static List<Signup> signups(Connection con, String sql) throws SQLException {
        List<Signup> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            realArgs(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Signup(rs.getString(1), rs.getLong(2) * 1000, rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
        }
        return out;
    }
}
