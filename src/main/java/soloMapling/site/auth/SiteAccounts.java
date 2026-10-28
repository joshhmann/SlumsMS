package soloMapling.site.auth;

import client.AccountPasswords;
import client.ReservedNames;
import client.command.commands.gm0.WebLoginCommand;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotStore;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Ticket 105: account sign-up with an invite code and sign-in with the game password, for the site's login service
 * (through {@link SiteAuthApi}). Accounts are created and passwords checked exactly as the login screen does
 * ({@link AccountPasswords}, the automatic-registration insert, {@link ReservedNames}).
 * <p>
 * Errors are generic: every unusable code is {@code BAD_CODE}; a wrong name, wrong password, banned account, bot
 * account or revoked site sign-in is {@code WRONG_LOGIN}. Sign-in compares hashes only (bcrypt, SHA-1, SHA-512), never
 * plain text, and refuses banned and bot accounts and passwords over 12 characters before comparing anything. Limits: per ipHash and overall request rates, a failed-code cap per
 * ipHash, the login screen's sign-up cooldown per ipHash, and 5 failed sign-ins per account name in 15 minutes lock
 * that name's sign-in for 15 minutes (names that don't exist are counted too, so a lock reveals nothing).
 * Never logs a password; sign-ups are logged with code, account and ipHash, failed sign-ins as a count only.
 */
public final class SiteAccounts {

    private static final Logger log = LoggerFactory.getLogger(SiteAccounts.class);

    public static final String BAD_CODE = "BAD_CODE";
    public static final String NAME_TAKEN = "NAME_TAKEN";
    public static final String BAD_INPUT = "BAD_INPUT";
    public static final String WRONG_LOGIN = "WRONG_LOGIN";
    public static final String LOCKED = "LOCKED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** Account name: 4-12 letters or digits. */
    static final Pattern USER_CHARS = Pattern.compile("[A-Za-z0-9]+");
    static final int USER_MIN = 4, USER_MAX = 12;
    /** Password: AUTO_REGISTER_MIN_PASSWORD_LENGTH (6) to 12 printable ASCII characters, no spaces. */
    static final Pattern PASS_CHARS = Pattern.compile("[\\x21-\\x7E]+");
    static final int PASS_MAX = 12;
    /** Longest name or password sign-in will even look up (existing accounts may predate the sign-up rules). */
    static final int VERIFY_MAX = 64;
    /** Longest password sign-in compares (the site's sign-in limit); longer is a wrong password, never hashed. */
    static final int VERIFY_PASS_MAX = 64;
    /** The bot template's account (RankingLoginTask): never signs in, whatever its password column holds. */
    static final String BOT_TEMPLATE_ACCOUNT = "SMBotBase";

    static final int PER_IP_PER_MINUTE = 10;
    static final int GLOBAL_PER_MINUTE = 120;
    static final int BAD_CODES_PER_IP_PER_HOUR = 10;
    static final int LOCK_FAILS = 5;
    static final long LOCK_WINDOW_MS = TimeUnit.MINUTES.toMillis(15);
    static final long LOCK_MS = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_TRACKED = 20_000;

    /** What {@code /signup} and {@code /verify} answer. On success error is null; rule is set only with BAD_INPUT. */
    public record Result(boolean ok, int accountId, String account, int gmLevel, List<String> characters,
                         String error, String rule) {
        static Result fail(String error) {
            return new Result(false, 0, null, 0, List.of(), error, null);
        }

        static Result badInput(String rule) {
            return new Result(false, 0, null, 0, List.of(), BAD_INPUT, rule);
        }

        public boolean gm() {
            return gmLevel > 0;
        }
    }

    private final AuthStore store;
    private final LongSupplier clock;
    private final Predicate<String> botName;
    private final IntPredicate siteRevoked;
    private final Window global = new Window();
    private final Map<String, Window> perIp = new ConcurrentHashMap<>();
    private final Map<String, Window> badCodes = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSignupByIp = new ConcurrentHashMap<>();
    private final Map<String, Window> fails = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final ReentrantLock signupLock = new ReentrantLock();
    private final AtomicLong failedSignIns = new AtomicLong();
    private final Window lockouts = new Window();
    private static volatile SiteAccounts current;
    private volatile String dummyHash;

    public SiteAccounts(AuthStore store, LongSupplier clock, Predicate<String> botName, IntPredicate siteRevoked) {
        this.store = store;
        this.clock = clock;
        this.botName = botName;
        this.siteRevoked = siteRevoked;
    }

    /** The game's: the database, the bot name pool (when bots run) and {@code @weblogin}'s revoked list. */
    public static SiteAccounts forGame() {
        return current = new SiteAccounts(new AuthStore.Jdbc(), System::currentTimeMillis,
                name -> soloMapling.server.MapleRootSolo.isEnabled()
                        && soloMapling.ArtificialPlayer.BotNames.heldByBot(name),
                accountId -> {
                    Path f = revokedFile(System.getProperty("slums.auth.revoked"), System.getProperty("slums.weblogin.dir"));
                    return f != null && WebLoginCommand.isRevoked(f.getParent(), f.getFileName().toString(), accountId);
                });
    }

    /**
     * The site's revoked list: {@code -Dslums.auth.revoked} (the file the login service reads:
     * /var/lib/slums-login/revoked.txt on live, /var/lib/slums-login-test/revoked.txt on TEST), else the old
     * {@code <slums.weblogin.dir>/revoked.txt}; null when neither is set. Pure.
     */
    static Path revokedFile(String explicit, String webloginDir) {
        if (explicit != null && !explicit.isBlank()) {
            Path f = Path.of(explicit.trim());
            return f.getParent() == null ? f.toAbsolutePath() : f;
        }
        return webloginDir == null || webloginDir.isBlank() ? null : Path.of(webloginDir).resolve("revoked.txt");
    }

    /** The sign-up rule a name/password pair breaks (user_length, user_chars, pass_length, pass_chars, reserved, bot_name), or null. */
    String brokenRule(String user, String pass) {
        if (user == null || user.length() < USER_MIN || user.length() > USER_MAX) {
            return "user_length";
        }
        if (!USER_CHARS.matcher(user).matches()) {
            return "user_chars";
        }
        String passRule = passRule(pass);
        if (passRule != null) {
            return passRule;
        }
        if (ReservedNames.isReservedAccountName(user)) {
            return "reserved";
        }
        if (botName.test(user)) {
            return "bot_name";
        }
        return null;
    }

    /** The password rule a new password breaks (pass_length, pass_chars), or null; sign-up and reset (ticket 107) share it. */
    static String passRule(String pass) {
        int min = Math.max(1, YamlConfig.config.server.AUTO_REGISTER_MIN_PASSWORD_LENGTH);
        if (pass == null || pass.length() < min || pass.length() > PASS_MAX) {
            return "pass_length";
        }
        if (!PASS_CHARS.matcher(pass).matches()) {
            return "pass_chars";
        }
        return null;
    }

    public Result signup(String typedCode, String user, String pass, String ipHash) {
        long now = clock.getAsLong();
        String ip = ipKey(ipHash);
        if (!allowRequest(ip, now)) {
            return Result.fail(RATE_LIMITED);
        }
        String rule = brokenRule(user, pass);
        if (rule != null) {
            return Result.badInput(rule);
        }
        Window bad = perIpWindow(badCodes, ip);
        if (bad.count(now, TimeUnit.HOURS.toMillis(1)) >= BAD_CODES_PER_IP_PER_HOUR) {
            return Result.fail(RATE_LIMITED);
        }
        String code = InviteCodes.normalize(typedCode);
        try {
            InviteCodes.Invite invite = code == null ? null : store.findInvite(code);
            if (invite == null || invite.status(now) != InviteCodes.Status.OPEN) {
                bad.add(now);
                return Result.fail(BAD_CODE);
            }
            if (store.accountNameTaken(user)) {
                return Result.fail(NAME_TAKEN);
            }
            Long last = lastSignupByIp.get(ip);
            long cooldown = TimeUnit.MINUTES.toMillis(YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES);
            if (last != null && now - last < cooldown) {
                return Result.fail(RATE_LIMITED);
            }
            String hash = AccountPasswords.hashForNewAccount(pass);
            int id;
            signupLock.lock();   // one sign-up at a time here; the code's row lock covers everything else
            try {
                id = store.createAccountWithInvite(code, user, hash, ip, now);
            } finally {
                signupLock.unlock();
            }
            if (id == AuthStore.CODE_GONE) {
                return Result.fail(BAD_CODE);
            }
            if (id == AuthStore.NAME_TAKEN) {
                return Result.fail(NAME_TAKEN);
            }
            lastSignupByIp.put(ip, now);
            prune(lastSignupByIp, now - cooldown);
            log.info("Site sign-up: account '{}' (id {}) with invite {} from ipHash {}", user, id,
                    InviteCodes.display(code), ip);
            return new Result(true, id, user, 0, List.of(), null, null);
        } catch (Exception e) {
            log.warn("Site sign-up failed on the server side", e);
            return Result.fail(UNAVAILABLE);
        }
    }

    public Result verify(String user, String pass, String ipHash) {
        long now = clock.getAsLong();
        String ip = ipKey(ipHash);
        if (!allowRequest(ip, now)) {
            return Result.fail(RATE_LIMITED);
        }
        if (user == null || pass == null || user.isEmpty() || user.length() > VERIFY_MAX) {
            return Result.fail(WRONG_LOGIN);
        }
        String key = user.toLowerCase(Locale.ROOT);
        Long until = lockedUntil.get(key);
        if (until != null) {
            if (now < until) {
                return Result.fail(LOCKED);
            }
            lockedUntil.remove(key, until);
        }
        try {
            AuthStore.Account account = store.findAccount(user);
            if (account != null && refused(account, now)) {
                AccountPasswords.checkHashedOnly("-", dummyHash());   // about the time of a real check; nothing compared
                return Result.fail(WRONG_LOGIN);   // banned, bot or revoked: folded into WRONG_LOGIN (generic)
            }
            if (pass.length() > VERIFY_PASS_MAX) {
                return failed(key, now);   // anti-abuse bound only; bcrypt reads 72 bytes anyway
            }
            // The FULL password, as Client.login compares it: the game client's box takes more than 12 characters
            // (a 13-character account signs in there with all 13 and is refused with the first 12), and the stored
            // hash is of everything typed. Sign-up and reset still cap new passwords at PASS_MAX.
            // Hashes only: a plain-text stored value (the bot template's marker) never matches here.
            AccountPasswords.Match match = AccountPasswords.checkHashedOnly(pass,
                    account == null ? dummyHash() : account.passhash());
            if (account == null || match == AccountPasswords.Match.NONE) {
                return failed(key, now);
            }
            if (match == AccountPasswords.Match.LEGACY && YamlConfig.config.server.BCRYPT_MIGRATION) {
                store.upgradeHash(account.id(), AccountPasswords.bcrypt(pass));   // as the login screen does
            }
            fails.remove(key);
            AuthStore.Profile profile = store.profile(account.id());
            return new Result(true, account.id(), account.name(), profile.gmLevel(), profile.characters(), null, null);
        } catch (Exception e) {
            log.warn("Site sign-in failed on the server side", e);
            return Result.fail(UNAVAILABLE);
        }
    }

    /** Accounts site sign-in never opens, checked before any password comparison. */
    boolean refused(AuthStore.Account a, long now) {
        return a.banned() || a.tempbanUntil() > now || a.botAccount() || a.id() >= BotStore.BOT_ID_BASE
                || BOT_TEMPLATE_ACCOUNT.equalsIgnoreCase(a.name()) || siteRevoked.test(a.id());
    }

    private Result failed(String key, long now) {
        long total = failedSignIns.incrementAndGet();
        Window w = fails.computeIfAbsent(key, k -> new Window());
        w.add(now);
        if (w.count(now, LOCK_WINDOW_MS) >= LOCK_FAILS) {
            lockedUntil.put(key, now + LOCK_MS);
            lockouts.add(now);
            fails.remove(key);
            log.info("Site sign-in locked an account name for 15 min after {} failures ({} failed sign-ins since start)",
                    LOCK_FAILS, total);
        }
        if (fails.size() > MAX_TRACKED) {
            fails.entrySet().removeIf(e -> e.getValue().count(now, LOCK_WINDOW_MS) == 0);
            lockedUntil.entrySet().removeIf(e -> e.getValue() <= now);
        }
        return Result.fail(WRONG_LOGIN);
    }

    /** Lockouts in the last {@code windowMs} (the dashboard shows 24 h); in memory, so a restart starts it at 0. */
    public int lockouts(long now, long windowMs) {
        return lockouts.count(now, windowMs);
    }

    /** The running game's lockouts since {@code since}, 0 when site sign-in is off. */
    public static long lockoutsSince(long since, long now) {
        SiteAccounts s = current;
        return s == null ? 0 : s.lockouts(now, now - since);
    }

    public long failedSignIns() {
        return failedSignIns.get();
    }

    private boolean allowRequest(String ip, long now) {
        long minute = TimeUnit.MINUTES.toMillis(1);
        Window w = perIpWindow(perIp, ip);
        synchronized (global) {
            if (global.count(now, minute) >= GLOBAL_PER_MINUTE || w.count(now, minute) >= PER_IP_PER_MINUTE) {
                return false;
            }
            global.add(now);
            w.add(now);
            return true;
        }
    }

    private static Window perIpWindow(Map<String, Window> map, String ip) {
        if (map.size() > MAX_TRACKED) {
            map.clear();   // a flood of distinct ipHashes: forget them rather than grow (the global cap still holds)
        }
        return map.computeIfAbsent(ip, k -> new Window());
    }

    private static void prune(Map<String, Long> map, long olderThan) {
        if (map.size() > MAX_TRACKED) {
            map.values().removeIf(t -> t < olderThan);
        }
    }

    /** The login service's salted IP hash, or "none"; only safe characters and bounded length are kept. */
    static String ipKey(String ipHash) {
        if (ipHash == null || ipHash.isEmpty()) {
            return "none";
        }
        String s = ipHash.replaceAll("[^A-Za-z0-9_+/=:.-]", "");
        s = s.length() > 128 ? s.substring(0, 128) : s;
        return s.isEmpty() ? "none" : s;
    }

    // A real bcrypt hash to check against when the account does not exist, so the answer takes as long either way.
    private String dummyHash() {
        String h = dummyHash;
        if (h == null) {
            h = AccountPasswords.bcrypt(InviteCodes.newCode());
            dummyHash = h;
        }
        return h;
    }

    /** Event times in a sliding window. */
    static final class Window {
        private final ArrayDeque<Long> times = new ArrayDeque<>();

        synchronized void add(long now) {
            times.addLast(now);
            if (times.size() > 1000) {
                times.pollFirst();
            }
        }

        synchronized int count(long now, long window) {
            while (!times.isEmpty() && now - times.peekFirst() >= window) {
                times.pollFirst();
            }
            return times.size();
        }
    }
}
