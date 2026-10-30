package soloMapling.Economy.exchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.2 b005 (S2, "a stolen site cookie can dump an account's NX"): the game's own proof that a person typed the
 * account's password, so the site's cookie alone is never enough to move an account's NX, items or a large sum of
 * mesos from the website. Two parts, both checked in the game, never trusting the site's word:
 * <ol>
 *   <li><b>Game-side sign-in age</b> ({@link #gameSignIn}): the game remembers when each account last passed a real
 *   password check through its own auth API ({@code /verify}, {@code /signup}, {@code /reset/complete} and
 *   {@code /exchange/approve}; ticket 105). A site request that acts for the account (preview, confirm, cancel, mine)
 *   needs one within {@link NxGuard#siteAuthMaxMs} (12 h), else REAUTH. The site's own {@code siteAuthAt} is still
 *   checked too. In memory: after a game restart players sign in on the site once more before trading there.</li>
 *   <li><b>Password step-up</b> ({@link #stepUp}): a site confirm that is big or risky needs the password typed for
 *   that very order: the site sends it with the preview token to {@code POST /exchange/approve} on the auth API, the
 *   game checks it and marks that token approved, and only then does the confirm place it (else
 *   PASSWORD_REQUIRED). Big or risky = every SELL (NX or items leave the account) and any BUY once the account's
 *   site buys confirmed without a password in the last {@link #FREE_WINDOW_MS} (12 h) would pass
 *   {@link #freeBuyMesos} mesos, fee reserve included. This is not a cap: with the password any order goes through
 *   (owner 2026-10-29: no daily NX caps).</li>
 * </ol>
 * JVM properties ({@code config.yaml} is strict):
 * <pre>
 *   -Dslums.exchange.siteAuthGame=true        the game-side sign-in age (false: only the site's siteAuthAt)
 *   -Dslums.exchange.stepUp=true              the password step-up
 *   -Dslums.exchange.stepUpFreeMesos=10000000 site buys without a password, per account per 12 h (0: every buy asks)
 * </pre>
 * The in-game {@code @nx} needs neither: the player typed the password at the game's login.
 */
public record SiteReauth(boolean gameSignIn, boolean stepUp, long freeBuyMesos) {

    /** How far back the password-free site buys are added up. */
    public static final long FREE_WINDOW_MS = TimeUnit.HOURS.toMillis(12);

    public SiteReauth {
        freeBuyMesos = Math.max(0, freeBuyMesos);
    }

    public static SiteReauth defaults() {
        return new SiteReauth(true, true, 10_000_000L);
    }

    /** Both parts off (tests of other things; the site's siteAuthAt check is NxGuard's). */
    public static final SiteReauth OFF = new SiteReauth(false, false, 0);

    public static SiteReauth fromSystem() {
        SiteReauth d = defaults();
        return new SiteReauth(bool("siteAuthGame", d.gameSignIn), bool("stepUp", d.stepUp),
                num("stepUpFreeMesos", d.freeBuyMesos));
    }

    private static boolean bool(String key, boolean dflt) {
        String v = System.getProperty("slums.exchange." + key);
        return v == null || v.isBlank() ? dflt : Boolean.parseBoolean(v.trim());
    }

    private static long num(String key, long dflt) {
        String v = System.getProperty("slums.exchange." + key);
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * When each account last passed a password check through the game's auth API (epoch ms). Thread safe; in memory.
     * {@link #GAME} is the one the running server's auth API writes and its exchange reads.
     */
    public static final class SignIns {
        private static final int MAX = 50_000;
        private final Map<Long, Long> last = new ConcurrentHashMap<>();

        /** A password check for {@code account} passed at {@code at}. */
        public void record(long account, long at) {
            if (account <= 0) {
                return;
            }
            last.merge(account, at, Math::max);
            if (last.size() > MAX) {
                long cut = at - TimeUnit.DAYS.toMillis(1);
                last.values().removeIf(t -> t < cut);
            }
        }

        /** The account's last password check, or null when none since the game started. */
        public Long last(long account) {
            return last.get(account);
        }

        /** Forgets one account (a ban or a password reset elsewhere could call this). */
        public void forget(long account) {
            last.remove(account);
        }
    }

    /** The running game's sign-ins. */
    public static final SignIns GAME = new SignIns();
}
