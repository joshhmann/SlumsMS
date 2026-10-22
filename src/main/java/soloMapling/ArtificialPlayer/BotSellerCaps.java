package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 56A: what bots may pay real players. {@code FMBot} buys from any shop in the FM rooms, and bots hold far
 * more mesos than players, so without a limit every player shop is an NPC that buys anything at market value.
 * <ul>
 *   <li><b>Daily cap per seller account.</b> Mesos bots together pay one real player's account (its player shops
 *   and hired merchants) in one server day. Once reached, bots simply skip that seller's items until the next
 *   day. The count survives a restart: it lives in table {@code botyield} (migration 006) under keys from
 *   {@link #STORE_KEY_BASE} up, a range bot ids never reach, so no new migration is needed.</li>
 *   <li><b>Price ceiling.</b> A bot pays a real player at most {@code maxRatio} x the item's static market value
 *   (bots' own shops keep {@code FMBot}'s 1.2x limit).</li>
 * </ul>
 * Bot-owned shops are not metered: that is bots trading among themselves.
 * <p>
 * Tuned with JVM system properties (config.yaml is strict): {@code -Dsolomapling.botBuyFromPlayerDailyCap}
 * (default {@value #DEFAULT_DAILY_CAP}; 0 stops bots buying from players), {@code
 * -Dsolomapling.botBuyFromPlayerMaxRatio} (default {@value #DEFAULT_MAX_RATIO}).
 */
public final class BotSellerCaps {

    private static final Logger log = LoggerFactory.getLogger(BotSellerCaps.class);

    static final long DEFAULT_DAILY_CAP = 5_000_000;
    static final double DEFAULT_MAX_RATIO = 1.0;
    /** botyield.BotId for a seller account is this + the account id. Bot ids stay far below it. */
    static final int STORE_KEY_BASE = 2_000_000_000;

    record Limits(long dailyCap, double maxRatio) {
        static Limits fromSystemProperties() {
            long cap = DEFAULT_DAILY_CAP;
            double ratio = DEFAULT_MAX_RATIO;
            try {
                String v = System.getProperty("solomapling.botBuyFromPlayerDailyCap");
                if (v != null && !v.isBlank()) {
                    cap = Math.max(0, Long.parseLong(v.trim().replace("_", "")));
                }
                v = System.getProperty("solomapling.botBuyFromPlayerMaxRatio");
                if (v != null && !v.isBlank()) {
                    ratio = Math.max(0, Double.parseDouble(v.trim()));
                }
            } catch (NumberFormatException e) {
                log.warn("Ignoring a bad -Dsolomapling.botBuyFromPlayer* value: {}", e.getMessage());
            }
            return new Limits(cap, ratio);
        }
    }

    // One seller account's day. Its own monitor guards it.
    static final class Daily {
        final int accountId;
        volatile String seller;   // read-out only
        LocalDate day;
        long mesos;
        int refused;
        boolean logged;
        boolean dirty;

        Daily(int accountId, LocalDate day) {
            this.accountId = accountId;
            this.day = day;
        }

        void rollTo(LocalDate today) {
            if (!today.equals(day)) {
                day = today;
                mesos = 0;
                refused = 0;
                logged = false;
                dirty = true;
            }
        }
    }

    private static volatile Limits limits = Limits.fromSystemProperties();
    private static final Map<Integer, Daily> dailies = new ConcurrentHashMap<>();
    // A character's account never changes: cached so an offline merchant owner costs one query per boot.
    private static final Map<Integer, Integer> accountOfCharacter = new ConcurrentHashMap<>();

    private BotSellerCaps() {
    }

    /** True when the item's price is within the ceiling a bot pays a real player. */
    public static boolean withinPriceCeiling(long askingPrice, Integer marketValue) {
        return marketValue != null && marketValue > 0 && askingPrice > 0
                && askingPrice <= marketValue * limits.maxRatio() + 1e-9;
    }

    /**
     * Reserves {@code price} mesos of a real seller account's daily cap for one bot purchase. False when the cap
     * has no room: the bot must not buy. Give it back with {@link #release} if the purchase then fails.
     */
    public static boolean tryReserve(int sellerAccountId, String sellerName, long price) {
        if (price <= 0) {
            return true;
        }
        LocalDate today = BotYieldCaps.today();
        BotYieldStore.ensureLoaded(today);
        Daily daily = dailies.computeIfAbsent(sellerAccountId, id -> new Daily(id, today));
        if (sellerName != null) {
            daily.seller = sellerName;
        }
        long cap = limits.dailyCap();
        synchronized (daily) {
            daily.rollTo(today);
            if (daily.mesos + price > cap) {
                daily.refused++;
                if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.FM_BUY)) {
                    soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.FM_BUY, "cap refused " + price + " for seller "
                            + daily.seller + " (account " + sellerAccountId + "): " + daily.mesos + " of " + cap
                            + " paid today, " + daily.refused + " refused");
                }
                if (!daily.logged) {
                    daily.logged = true;
                    log.info("Bot buy cap reached for seller {} (account {}): {} of {} mesos paid today; bots stop"
                            + " buying from them until tomorrow", daily.seller, sellerAccountId, daily.mesos, cap);
                }
                return false;
            }
            daily.mesos += price;
            daily.dirty = true;
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.FM_BUY)) {
                soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.FM_BUY, "cap reserved " + price + " for seller "
                        + daily.seller + " (account " + sellerAccountId + "): " + daily.mesos + " of " + cap
                        + " paid today");
            }
            return true;
        }
    }

    /**
     * 1.5.0 rc3 review: {@link #tryReserve} and then the world's daily budget for bots buying from players
     * ({@link soloMapling.Economy.EconomyMeters#tryBotBuy}); when the budget has no room the seller's reservation is
     * given back and nothing is held. Undo both with {@link #releaseWithWorldBudget}.
     */
    public static boolean tryReserveWithWorldBudget(int sellerAccountId, String sellerName, long price) {
        if (!tryReserve(sellerAccountId, sellerName, price)) {
            return false;
        }
        if (!soloMapling.Economy.EconomyMeters.tryBotBuy(price)) {
            release(sellerAccountId, price);
            return false;
        }
        return true;
    }

    public static void releaseWithWorldBudget(int sellerAccountId, long price) {
        release(sellerAccountId, price);
        soloMapling.Economy.EconomyMeters.releaseBotBuy(price);
    }

    public static void release(int sellerAccountId, long price) {
        Daily daily = dailies.get(sellerAccountId);
        if (daily != null && price > 0) {
            synchronized (daily) {
                daily.mesos = Math.max(0, daily.mesos - price);
                daily.dirty = true;
            }
        }
    }

    /** The account a character belongs to, or -1 when it cannot be found (then bots do not buy). */
    public static int accountOf(int characterId) {
        Integer cached = accountOfCharacter.get(characterId);
        if (cached != null) {
            return cached;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT accountid FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int account = rs.getInt(1);
                    accountOfCharacter.put(characterId, account);
                    return account;
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("No account for character {}", characterId, e);
        }
        return -1;
    }

    public static void rememberAccount(int characterId, int accountId) {
        accountOfCharacter.put(characterId, accountId);
    }

    // ---- persistence (BotYieldStore, table botyield) ----

    static void restore(int accountId, LocalDate day, long mesos) {
        Daily daily = dailies.computeIfAbsent(accountId, id -> new Daily(id, day));
        synchronized (daily) {
            daily.rollTo(day);
            daily.mesos += mesos;
        }
    }

    /** Changed counts as (account id, day, mesos), marked clean. */
    static List<long[]> drainDirty() {
        List<long[]> out = new ArrayList<>();
        for (Daily daily : dailies.values()) {
            synchronized (daily) {
                if (daily.dirty) {
                    daily.dirty = false;
                    out.add(new long[]{daily.accountId, daily.day.toEpochDay(), daily.mesos});
                }
            }
        }
        return out;
    }

    static void markDirty(List<long[]> rows) {
        for (long[] row : rows) {
            Daily daily = dailies.get((int) row[0]);
            if (daily != null) {
                synchronized (daily) {
                    daily.dirty = true;
                }
            }
        }
    }

    // ---- GM read-out (!botyield) ----

    static List<String> report(int top) {
        Limits limits = BotSellerCaps.limits;
        List<String> lines = new ArrayList<>();
        LocalDate today = BotYieldCaps.today();
        List<Daily> sellers = new ArrayList<>(dailies.values());
        sellers.removeIf(d -> !today.equals(d.day));
        sellers.sort(Comparator.comparingLong((Daily d) -> d.mesos).reversed());
        lines.add(String.format("Bots buying from players: cap %,d mesos per seller account per day, price <= %.2fx"
                + " market value. Paid today:", limits.dailyCap(), limits.maxRatio()));
        for (Daily d : sellers.subList(0, Math.min(top, sellers.size()))) {
            lines.add(String.format("  %s (account %d): %,d mesos, %d buys refused", d.seller == null ? "?" : d.seller,
                    d.accountId, d.mesos, d.refused));
        }
        return lines;
    }

    /** One seller account's day, for !qa fm-1: paid, cap, refused. */
    public static String status(int accountId) {
        Daily daily = dailies.get(accountId);
        long cap = limits.dailyCap();
        if (daily == null) {
            return "account " + accountId + ": 0 of " + cap + " paid today";
        }
        synchronized (daily) {
            daily.rollTo(BotYieldCaps.today());
            return "account " + accountId + ": " + daily.mesos + " of " + cap + " paid today, " + daily.refused
                    + " buys refused";
        }
    }

    // ---- test seams ----

    static void setForTest(Limits newLimits) {
        limits = newLimits == null ? Limits.fromSystemProperties() : newLimits;
    }

    static void clearForTest() {
        dailies.clear();
        accountOfCharacter.clear();
    }

    static long paidTodayForTest(int accountId) {
        Daily daily = dailies.get(accountId);
        return daily == null ? 0 : daily.mesos;
    }
}
