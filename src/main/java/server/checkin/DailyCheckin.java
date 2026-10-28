package server.checkin;

import client.Character;
import client.Client;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.SoloMaplingConstants;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily Check-In: one 28-day login streak per account (table {@code daily_checkin}, migration 004), opened from the
 * login push, {@code @daily} or the window. The server is the source of truth: the client only asks to open the
 * window or to claim a day. A claim is checked (claimable day, inventory room), recorded, and only then rewarded, so
 * a failed write or a full inventory never costs the player a day or a reward.
 */
public final class DailyCheckin {

    private static final Logger log = LoggerFactory.getLogger(DailyCheckin.class);

    static final long MIN_REQUEST_INTERVAL_MS = 1_000;

    interface Store {
        DailyCheckinStreak load(int accountId) throws SQLException;

        void save(int accountId, DailyCheckinStreak streak) throws SQLException;

        /**
         * Records a claim only if the stored streak is still {@code before} (so two channels, or two requests, that
         * read the same streak cannot both claim), and credits {@code stamps} Slums Stamps in the same transaction.
         * Returns the stamps credited (0 for a staff account), or -1 when the streak had changed and nothing was
         * written. The default suits test stores that only record streaks.
         */
        default int recordClaim(int accountId, DailyCheckinStreak before, DailyCheckinStreak after, int stamps,
                                boolean skipStaffCheck) throws SQLException {
            save(accountId, after);
            return stamps;
        }
    }

    /** What happened to a claim. */
    public enum ClaimResult { CLAIMED, NOT_CLAIMABLE, NO_ROOM, NOT_SAVED }

    private static final Map<Integer, Long> lastRequestByAccount = new ConcurrentHashMap<>();
    private static final Map<Integer, Object> accountLocks = new ConcurrentHashMap<>();
    private static volatile Store store = new DatabaseStore();

    private DailyCheckin() {
    }

    public static boolean enabled() {
        return YamlConfig.config.server.DAILY_CHECKIN_ENABLED;
    }

    public static boolean eligible(Character chr) {
        return isPlayer(chr) && enabled() && chr.getLevel() >= YamlConfig.config.server.DAILY_CHECKIN_MIN_LEVEL;
    }

    /**
     * A real player: never a bot (a BotClient, or an id at or above the bot id base) nor the console pseudo-character.
     * Bots get no window, no login push and no claims, so they never receive NX, items or slots.
     */
    static boolean isPlayer(Character chr) {
        return chr != null && !BotHelpers.isBot(chr)
                && chr.getId() < SoloMaplingConstants.GameConstants.BOT_BASE_ID
                && chr.getId() != SoloMaplingConstants.GameConstants.CONSOLE_BOT_ID;
    }

    /** Pushes the window at login when a reward is waiting. */
    public static void onLogin(Client c) {
        Character chr = c.getPlayer();
        if (!eligible(chr)) {
            return;
        }
        try {
            DailyCheckinStreak streak = store.load(c.getAccID());
            long now = System.currentTimeMillis();
            if (streak.claimableDay(now) > 0) {
                c.sendPacket(snapshot(streak, now, 0));
            }
        } catch (SQLException e) {
            log.warn("Could not read the check-in streak of account {}", c.getAccID(), e);
        }
    }

    /** {@code @daily}: shows the window, and the remaining wait when nothing is claimable. */
    public static void open(Client c) {
        Character chr = c.getPlayer();
        if (!isPlayer(chr)) {
            return;
        }
        if (!enabled()) {
            chr.dropMessage(6, "Daily Check-In is not available right now.");
            return;
        }
        if (!eligible(chr)) {
            chr.dropMessage(6, "Daily Check-In unlocks at Level " + YamlConfig.config.server.DAILY_CHECKIN_MIN_LEVEL + ".");
            return;
        }
        try {
            DailyCheckinStreak streak = store.load(c.getAccID());
            long now = System.currentTimeMillis();
            c.sendPacket(snapshot(streak, now, 0));
            long secs = streak.cooldownSeconds(now);
            if (secs > 0) {
                chr.dropMessage(6, "Daily Check-In: your next reward unlocks in " + secs / 3600 + "h " + secs % 3600 / 60 + "m.");
            }
        } catch (SQLException e) {
            log.warn("Could not read the check-in streak of account {}", c.getAccID(), e);
        }
    }

    /** A request from the window: {@code claimDay} 0 = just show it. Throttled per account. */
    public static void handleRequest(Client c, int claimDay) {
        Character chr = c.getPlayer();
        if (!eligible(chr)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastRequestByAccount.put(c.getAccID(), now);
        if (last != null && now - last < MIN_REQUEST_INTERVAL_MS) {
            return;
        }
        try {
            DailyCheckinStreak streak;
            int justClaimed = 0;
            synchronized (accountLocks.computeIfAbsent(c.getAccID(), k -> new Object())) {
                streak = store.load(c.getAccID());
                if (claimDay > 0) {
                    ClaimResult result = claim(chr, c.getAccID(), streak, claimDay, now);
                    if (result == ClaimResult.CLAIMED) {
                        streak = streak.claimed(claimDay, now);
                        justClaimed = claimDay;
                    } else if (result == ClaimResult.NO_ROOM) {
                        chr.dropMessage(1, "Make room in your inventory to claim today's reward.");
                    }
                }
            }
            c.sendPacket(snapshot(streak, now, justClaimed));
        } catch (SQLException e) {
            log.warn("Could not read the check-in streak of account {}", c.getAccID(), e);
        }
    }

    static ClaimResult claim(Character chr, int accountId, DailyCheckinStreak streak, int day, long now) {
        if (!isPlayer(chr)) {
            return ClaimResult.NOT_CLAIMABLE;
        }
        if (day < 1 || day != streak.claimableDay(now)) {
            log.info("Check-in claim refused for {}: day {} (claimable {})", chr.getName(), day, streak.claimableDay(now));
            return ClaimResult.NOT_CLAIMABLE;
        }
        if (!DailyCheckinRewards.canReceive(chr, day)) {
            return ClaimResult.NO_ROOM;
        }
        DailyCheckinRewards.Reward reward = DailyCheckinRewards.of(day);
        int stamps = SlumsStamps.earns(chr) ? reward.stamps() : 0;
        int credited;
        try {
            credited = store.recordClaim(accountId, streak, streak.claimed(day, now), stamps,
                    SlumsStamps.qaEarner(accountId));
        } catch (SQLException e) {
            log.warn("Could not record the check-in claim of account {}", accountId, e);
            return ClaimResult.NOT_SAVED;
        }
        if (credited < 0) {
            log.info("Check-in claim refused for {}: day {} was claimed elsewhere first", chr.getName(), day);
            return ClaimResult.NOT_CLAIMABLE;
        }
        DailyCheckinRewards.grant(chr, day);
        if (credited > 0) {
            chr.yellowMessage(SlumsStamps.claimLine(credited, day));
        }
        log.info("{} claimed Daily Check-In day {} (+{} stamps)", chr.getName(), day, credited);
        soloMapling.achievements.Achievements.onCheckin(chr, day); // 1.6.1 ticket 93
        return ClaimResult.CLAIMED;
    }

    static net.packet.Packet snapshot(DailyCheckinStreak streak, long now, int justClaimed) {
        DailyCheckinStreak shown = streak.normalized(now);
        return DailyCheckinPackets.snapshot(streak.viewDay(now), shown.claimedMask(), justClaimed);
    }

    private static final class DatabaseStore implements Store {
        @Override
        public DailyCheckinStreak load(int accountId) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT day, claimed_mask, last_claim FROM daily_checkin WHERE account_id = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new DailyCheckinStreak(rs.getInt(1), rs.getInt(2), rs.getLong(3)) : DailyCheckinStreak.NEW;
                }
            }
        }

        @Override
        public void save(int accountId, DailyCheckinStreak streak) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "INSERT INTO daily_checkin (account_id, day, claimed_mask, last_claim) VALUES (?, ?, ?, ?) "
                                 + "ON DUPLICATE KEY UPDATE day = VALUES(day), claimed_mask = VALUES(claimed_mask), "
                                 + "last_claim = VALUES(last_claim)")) {
                ps.setInt(1, accountId);
                ps.setInt(2, streak.day());
                ps.setInt(3, streak.claimedMask());
                ps.setLong(4, streak.lastClaim());
                ps.executeUpdate();
            }
        }

        @Override
        public int recordClaim(int accountId, DailyCheckinStreak before, DailyCheckinStreak after, int stamps,
                               boolean skipStaffCheck) throws SQLException {
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean auto = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    int rows;
                    if (before.lastClaim() <= 0) {
                        // never claimed: only the first of two racing claims gets the row
                        try (PreparedStatement ps = con.prepareStatement("INSERT IGNORE INTO daily_checkin "
                                + "(account_id, day, claimed_mask, last_claim) VALUES (?, ?, ?, ?)")) {
                            ps.setInt(1, accountId);
                            ps.setInt(2, after.day());
                            ps.setInt(3, after.claimedMask());
                            ps.setLong(4, after.lastClaim());
                            rows = ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = con.prepareStatement("UPDATE daily_checkin SET day = ?, "
                                + "claimed_mask = ?, last_claim = ? WHERE account_id = ? AND last_claim = ?")) {
                            ps.setInt(1, after.day());
                            ps.setInt(2, after.claimedMask());
                            ps.setLong(3, after.lastClaim());
                            ps.setInt(4, accountId);
                            ps.setLong(5, before.lastClaim());
                            rows = ps.executeUpdate();
                        }
                    }
                    if (rows != 1) {
                        con.rollback();
                        return -1;
                    }
                    int credited = 0;
                    if (stamps > 0) {
                        try (PreparedStatement ps = con.prepareStatement(SlumsStamps.CREDIT_SQL)) {
                            ps.setInt(1, stamps);
                            ps.setInt(2, accountId);
                            ps.setInt(3, skipStaffCheck ? 1 : 0);
                            ps.setInt(4, accountId);
                            credited = ps.executeUpdate() == 1 ? stamps : 0;
                        }
                    }
                    con.commit();
                    return credited;
                } catch (SQLException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(auto);
                }
            }
        }
    }

    /** An account's streak as stored ({@code @stamps}); null when it cannot be read. */
    public static DailyCheckinStreak streakOf(int accountId) {
        try {
            return store.load(accountId);
        } catch (SQLException e) {
            log.warn("Could not read the check-in streak of account {}", accountId, e);
            return null;
        }
    }

    /** Auto-QA t-ck-1: an account's streak as stored. */
    public static DailyCheckinStreak streakForQa(int accountId) throws SQLException {
        return store.load(accountId);
    }

    /**
     * Auto-QA t-ck-1: moves the QA account's last claim back past the 24-hour cooldown (the day and mask kept, so the
     * streak continues), so a nightly run can claim again. The caller only does this for its own pretend player.
     */
    public static DailyCheckinStreak rewindForQa(int accountId, long now) throws SQLException {
        DailyCheckinStreak s = store.load(accountId);
        if (s.lastClaim() > 0 && now - s.lastClaim() < DailyCheckinStreak.PERIOD_MS) {
            s = new DailyCheckinStreak(s.day(), s.claimedMask(), now - DailyCheckinStreak.PERIOD_MS - 60_000);
            store.save(accountId, s);
        }
        lastRequestByAccount.remove(accountId);
        return s;
    }

    /** Test seam: a fake store (null restores the database) and a clean throttle. */
    static void useForTest(Store testStore) {
        store = testStore == null ? new DatabaseStore() : testStore;
        lastRequestByAccount.clear();
    }
}
