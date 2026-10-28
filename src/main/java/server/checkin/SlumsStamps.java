package server.checkin;

import client.Character;
import client.Client;
import client.SyntheticClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.site.SiteFeed;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Slums Stamps (ticket 111, owner 2026-10-27): one balance per account ({@code accounts.stamps}, migration 039),
 * shared by its characters and never tradeable. Earned only through the Daily Check-In (1 a claimed day, +5 more on
 * days 7, 14, 21 and 28: the {@code stamps} of each day in the reward table), credited in the same transaction that
 * records the claim ({@link DailyCheckin}). Spent at Rooty's Donor sections. Staff (an account with any GM
 * character), auto-QA accounts and bots never earn; bots never spend.
 *
 * <p>Every change is one conditional SQL statement, so two channels (or a claim and a purchase) can never spend the
 * same stamp twice or push a balance below zero.
 */
public final class SlumsStamps {

    private static final Logger log = LoggerFactory.getLogger(SlumsStamps.class);

    /** What a converted Billion Coin or vote point became (migration 039): the price of one Donor NX cosmetic. */
    public static final int PER_BILLION_COIN = 6;
    public static final int PER_VOTE_POINT = 6;
    /** The private outbox file for the site's account page (board outbox, never the public export). */
    public static final String PRIVATE_FILE = "stamps.json";

    /** The balance store; a seam for tests. */
    interface Store {
        int balance(int accountId) throws SQLException;

        /** Adds {@code n} (> 0). */
        void add(int accountId, int n) throws SQLException;

        /** Takes {@code n} (> 0) only when the balance covers it; false (nothing taken) otherwise. */
        boolean spend(int accountId, int n) throws SQLException;
    }

    /** What a purchase did. */
    public enum Buy { BOUGHT, SHORT, NO_ROOM, REFUSED }

    private static volatile Store store = new DatabaseStore();
    /** Accounts the auto-QA check lets earn while it runs (t-ss-1); empty otherwise. */
    private static final Set<Integer> qaEarners = ConcurrentHashMap.newKeySet();

    private SlumsStamps() {
    }

    /**
     * Whether this character's claims may earn stamps, as far as the character itself shows: a real player (not a bot),
     * not a GM character, not an auto-QA pretend player or account. The database store also refuses an account with
     * any GM character ({@link DailyCheckin}'s claim transaction), so a staff member's alt earns nothing either.
     */
    public static boolean earns(Character chr) {
        if (!DailyCheckin.isPlayer(chr)) {
            return false;
        }
        Client c = chr.getClient();
        if (c != null && qaEarners.contains(c.getAccID())) {
            return true;
        }
        if (chr.gmLevel() > 0 || c instanceof SyntheticClient || SiteFeed.isQaName(chr.getName())) {
            return false;
        }
        return c == null || !SiteFeed.isQaName(c.getAccountName());
    }

    /** Whether the claim transaction should skip its staff-account check (only for the auto-QA check's account). */
    static boolean qaEarner(int accountId) {
        return qaEarners.contains(accountId);
    }

    public static int balance(int accountId) {
        try {
            return store.balance(accountId);
        } catch (SQLException e) {
            log.warn("Could not read the Slums Stamps of account {}", accountId, e);
            return 0;
        }
    }

    /** A GM grant (!givestamps) or a test; {@code n} > 0. */
    public static boolean add(int accountId, int n) {
        if (n <= 0) {
            return false;
        }
        try {
            store.add(accountId, n);
            return true;
        } catch (SQLException e) {
            log.warn("Could not add {} Slums Stamps to account {}", n, accountId, e);
            return false;
        }
    }

    /**
     * Buys one item for {@code price} stamps: the item must fit ({@code canHold}), then the stamps are taken in one
     * conditional update, and only then is the item handed over ({@code give}, false when it did not land: the
     * stamps go back). Bots and non-players are refused. Never gives without taking, never takes without giving.
     */
    public static Buy buy(Character chr, int price, BooleanSupplier canHold, BooleanSupplier give) {
        if (!DailyCheckin.isPlayer(chr) || price <= 0 || chr.getClient() == null) {
            return Buy.REFUSED;
        }
        int acc = chr.getClient().getAccID();
        if (!canHold.getAsBoolean()) {
            return Buy.NO_ROOM;
        }
        try {
            if (!store.spend(acc, price)) {
                return Buy.SHORT;
            }
        } catch (SQLException e) {
            log.warn("Could not take {} Slums Stamps from account {}", price, acc, e);
            return Buy.REFUSED;
        }
        boolean given;
        try {
            given = give.getAsBoolean();
        } catch (RuntimeException e) {
            log.warn("A Slums Stamps purchase by {} failed to deliver", chr.getName(), e);
            given = false;
        }
        if (!given) {
            add(acc, price);   // refund
            return Buy.NO_ROOM;
        }
        log.info("{} (account {}) spent {} Slums Stamps", chr.getName(), acc, price);
        return Buy.BOUGHT;
    }

    /** "+1 Slums Stamp, day 4/28" / "+6 Slums Stamps, day 7/28": the claim's yellow line. */
    static String claimLine(int stamps, int day) {
        return "+" + stamps + " Slums Stamp" + (stamps == 1 ? "" : "s") + ", day " + day + "/"
                + DailyCheckinStreak.CYCLE_DAYS;
    }

    /** The next bonus day (7, 14, 21 or 28) after {@code day} (0..28); 7 after a finished cycle. */
    static int nextBonusDay(int day) {
        int next = (day / 7 + 1) * 7;
        return next > DailyCheckinStreak.CYCLE_DAYS ? 7 : next;
    }

    /** The {@code @stamps} lines. */
    public static String[] statusLines(Character chr, DailyCheckinStreak streak, long now) {
        int acc = chr.getClient().getAccID();
        int bal = balance(acc);
        DailyCheckinStreak s = streak.normalized(now);
        int claimable = streak.claimableDay(now);
        int day = s.day();
        long secs = streak.cooldownSeconds(now);
        String when = claimable > 0 ? "claimable now (@daily)"
                : "next claim in " + secs / 3600 + "h " + secs % 3600 / 60 + "m";
        int bonus = nextBonusDay(day);
        DailyCheckinRewards.Reward r = DailyCheckinRewards.of(bonus);
        String bonusLine = "Next bonus: day " + bonus + " (+" + (r == null ? 6 : r.stamps()) + ")";
        String first = "Slums Stamps: " + bal + (earns(chr) ? "" : " (staff and QA accounts don't earn stamps)");
        return new String[]{first, "Check-In: day " + day + "/" + DailyCheckinStreak.CYCLE_DAYS + ", " + when + ". "
                + bonusLine + "."};
    }

    // ── the site export ─────────────────────────────────────────────────────────────────────────

    /**
     * {@value #PRIVATE_FILE}: {@code {"generated": "<ISO UTC>", "accounts": {"<account name>": {"id": n, "stamps": n}}}}
     * for every account holding stamps, for the site's account page (keyed by the login name the site signs in
     * with). Private: the board outbox only. Pure.
     */
    public static Map<String, Object> privateFile(Map<String, int[]> rows, long now) {
        Map<String, Object> accounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        rows.forEach((name, v) -> {
            if (name != null && !name.isBlank() && v[1] > 0 && !SiteFeed.isQaName(name)) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", v[0]);
                a.put("stamps", v[1]);
                accounts.put(name, a);
            }
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generated", java.time.Instant.ofEpochMilli(now).toString());
        m.put("accounts", new LinkedHashMap<>(accounts));
        return m;
    }

    /** The private file from the database; null when the column cannot be read (migration 039 not applied). */
    public static Map<String, Object> privateFileFromDb(long now) {
        Map<String, int[]> rows = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, name, stamps FROM accounts WHERE stamps > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.put(rs.getString(2), new int[]{rs.getInt(1), rs.getInt(3)});
            }
        } catch (SQLException e) {
            log.debug("Slums Stamps export not built: {}", e.getMessage());
            return null;
        }
        return privateFile(rows, now);
    }

    // ── the database ────────────────────────────────────────────────────────────────────────────

    private static final class DatabaseStore implements Store {
        @Override
        public int balance(int accountId) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT stamps FROM accounts WHERE id = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        @Override
        public void add(int accountId, int n) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE accounts SET stamps = stamps + ? WHERE id = ?")) {
                ps.setInt(1, n);
                ps.setInt(2, accountId);
                ps.executeUpdate();
            }
        }

        @Override
        public boolean spend(int accountId, int n) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(SPEND_SQL)) {
                ps.setInt(1, n);
                ps.setInt(2, accountId);
                ps.setInt(3, n);
                return ps.executeUpdate() == 1;
            }
        }
    }

    static final String SPEND_SQL = "UPDATE accounts SET stamps = stamps - ? WHERE id = ? AND stamps >= ?";
    /**
     * The claim's credit, inside the claim transaction: nothing for an account with any GM character or an auto-QA
     * account name (params: n, account id, 1 = skip the staff check (t-ss-1 only), account id).
     */
    static final String CREDIT_SQL = "UPDATE accounts SET stamps = stamps + ? WHERE id = ? AND (? = 1 OR ("
            + "NOT EXISTS (SELECT 1 FROM characters WHERE accountid = ? AND gm > 0) AND name NOT LIKE 'autoqa%'))";

    // ── auto-QA and test seams ──────────────────────────────────────────────────────────────────

    /** t-ss-1: let this auto-QA account earn while the check runs (and stop again). */
    public static void qaEarnerForCheck(int accountId, boolean on) {
        if (on) {
            qaEarners.add(accountId);
        } else {
            qaEarners.remove(accountId);
        }
    }

    /** t-ss-1: put the QA account's balance back to what it was before the check. */
    public static void restoreForQa(int accountId, int balance) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET stamps = ? WHERE id = ?")) {
            ps.setInt(1, Math.max(0, balance));
            ps.setInt(2, accountId);
            ps.executeUpdate();
        }
    }

    static void useForTest(Store testStore) {
        store = testStore == null ? new DatabaseStore() : testStore;
        qaEarners.clear();
    }
}
