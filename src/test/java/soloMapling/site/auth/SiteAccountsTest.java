package soloMapling.site.auth;

import client.AccountPasswords;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 105: invite sign-up and password sign-in for the site, with their limits. */
class SiteAccountsTest {

    static final long NOW = 1_800_000_000_000L;
    static final String CODE = "ABCDEFGHJK";

    final MemoryAuthStore store = new MemoryAuthStore();
    final AtomicLong clock = new AtomicLong(NOW);
    final Set<Integer> revoked = new java.util.HashSet<>();
    SiteAccounts accounts;
    boolean bcrypt;
    int minPass, cooldown;

    @BeforeEach
    void setUp() {
        var server = YamlConfig.config.server;
        bcrypt = server.BCRYPT_MIGRATION;
        minPass = server.AUTO_REGISTER_MIN_PASSWORD_LENGTH;
        cooldown = server.AUTO_REGISTER_IP_COOLDOWN_MINUTES;
        server.BCRYPT_MIGRATION = false;   // SHA-512 keeps the tests fast; one test covers bcrypt
        server.AUTO_REGISTER_MIN_PASSWORD_LENGTH = 6;
        server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = 0;
        accounts = new SiteAccounts(store, clock::get, name -> name.equalsIgnoreCase("Bottyboy"), revoked::contains);
    }

    @AfterEach
    void tearDown() {
        var server = YamlConfig.config.server;
        server.BCRYPT_MIGRATION = bcrypt;
        server.AUTO_REGISTER_MIN_PASSWORD_LENGTH = minPass;
        server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = cooldown;
    }

    void invite(String code, long expiresAt) {
        store.insertInvite(InviteCodes.Invite.fresh(code, NOW, 1, "Gm", "", expiresAt));
    }

    // ── sign-up ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aCodeMakesOneAccountAndIsThenUsed() {
        invite(CODE, 0);
        SiteAccounts.Result r = accounts.signup("abcde-fghjk", "Newfriend", "hunter22", "ip1");
        assertTrue(r.ok(), r.toString());
        assertEquals("Newfriend", r.account());
        AuthStore.Account a = store.findAccount("newfriend");
        assertNotNull(a);
        assertNotEquals("hunter22", a.passhash(), "stored hashed");
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("hunter22", a.passhash()));
        InviteCodes.Invite used = store.findInvite(CODE);
        assertEquals(InviteCodes.Status.USED, used.status(NOW));
        assertEquals(a.id(), used.usedByAccount());

        assertEquals(SiteAccounts.BAD_CODE, accounts.signup(CODE, "Otherpal", "hunter22", "ip2").error(), "reuse refused");
        assertNull(store.findAccount("otherpal"));
    }

    @Test
    void expiredRevokedAndMadeUpCodesAreAllBadCode() {
        invite(CODE, NOW + 1000);
        clock.set(NOW + 1000);
        assertEquals(SiteAccounts.BAD_CODE, accounts.signup(CODE, "Newfriend", "hunter22", "ip").error());
        invite("BBBBBBBBBB", 0);
        assertTrue(store.revokeInvite("BBBBBBBBBB", "Gm", NOW));
        assertEquals(SiteAccounts.BAD_CODE, accounts.signup("BBBBBBBBBB", "Newfriend", "hunter22", "ip").error());
        assertEquals(SiteAccounts.BAD_CODE, accounts.signup("CCCCCCCCCC", "Newfriend", "hunter22", "ip").error());
        assertEquals(SiteAccounts.BAD_CODE, accounts.signup("nonsense", "Newfriend", "hunter22", "ip").error());
        assertTrue(store.accounts.isEmpty());
        assertFalse(store.revokeInvite("CCCCCCCCCC", "Gm", NOW), "nothing to revoke");
    }

    @Test
    void theSignUpRulesMatchTheGame() {
        invite(CODE, 0);
        assertEquals("user_length", accounts.signup(CODE, "abc", "hunter22", "ip").rule());
        assertEquals("user_length", accounts.signup(CODE, "abcdefghijklm", "hunter22", "ip").rule());
        assertEquals("user_chars", accounts.signup(CODE, "bad_name", "hunter22", "ip").rule());
        assertEquals("pass_length", accounts.signup(CODE, "Newfriend", "short", "ip").rule());
        assertEquals("pass_length", accounts.signup(CODE, "Newfriend", "thirteenchars", "ip").rule());
        assertEquals("pass_chars", accounts.signup(CODE, "Newfriend", "has space", "ip").rule());
        assertEquals("reserved", accounts.signup(CODE, "Admin01", "hunter22", "ip").rule(), "the login screen's staff-name rule");
        assertEquals("bot_name", accounts.signup(CODE, "BottyBoy", "hunter22", "ip").rule());
        assertEquals(SiteAccounts.BAD_INPUT, accounts.signup(CODE, "abc", "hunter22", "ip").error());
        assertEquals(InviteCodes.Status.OPEN, store.findInvite(CODE).status(NOW), "a refused sign-up keeps the code");

        store.put(new AuthStore.Account(7, "Taken", "x", false, 0));
        assertEquals(SiteAccounts.NAME_TAKEN, accounts.signup(CODE, "TAKEN", "hunter22", "ip").error());
        clock.addAndGet(60_000);   // past the per-minute cap
        assertTrue(accounts.signup(CODE, "Exactly12chr", "!Pass~word1", "ip").ok());
    }

    @Test
    void aBadCodeIsCheckedBeforeTheNameSoNamesDoNotLeak() {
        store.put(new AuthStore.Account(7, "Taken", "x", false, 0));
        assertEquals(SiteAccounts.BAD_CODE, accounts.signup("CCCCCCCCCC", "Taken", "hunter22", "ip").error());
    }

    @Test
    void bcryptWhenTheServerMigratesToIt() {
        YamlConfig.config.server.BCRYPT_MIGRATION = true;
        invite(CODE, 0);
        assertTrue(accounts.signup(CODE, "Newfriend", "hunter22", "ip").ok());
        assertEquals(AccountPasswords.Match.BCRYPT, AccountPasswords.check("hunter22", store.findAccount("Newfriend").passhash()));
    }

    @Test
    void twoSignUpsRacingForOneCodeMakeOneAccount() throws Exception {
        // A store that checks then acts with a pause in between: only the sign-up lock keeps this to one account.
        MemoryAuthStore racy = new MemoryAuthStore() {
            @Override
            public int createAccountWithInvite(String code, String name, String passhash, String ipHash, long now) {
                InviteCodes.Invite i = findInvite(code);
                if (i == null || i.status(now) != InviteCodes.Status.OPEN) {
                    return CODE_GONE;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                int id;
                synchronized (this) {
                    id = nextId++;
                    accounts.put(name.toLowerCase(), new Account(id, name, passhash, false, 0));
                    invites.put(code, new InviteCodes.Invite(code, i.createdAt(), 1, "Gm", "", 0, id, name, now, 0, ""));
                }
                return id;
            }
        };
        racy.insertInvite(InviteCodes.Invite.fresh(CODE, NOW, 1, "Gm", "", 0));
        SiteAccounts sa = new SiteAccounts(racy, clock::get, n -> false, id -> false);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SiteAccounts.Result>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String name = "Racer" + t;
            String ip = "ip" + t;
            results.add(pool.submit(() -> {
                go.await();
                return sa.signup(CODE, name, "hunter22", ip);
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<SiteAccounts.Result> f : results) {
            SiteAccounts.Result r = f.get(10, TimeUnit.SECONDS);
            if (r.ok()) {
                ok++;
            } else {
                assertEquals(SiteAccounts.BAD_CODE, r.error());
            }
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(1, racy.accounts.size());
    }

    @Test
    void signUpCooldownAndBadCodeCapPerIpHash() {
        YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = 1;
        invite(CODE, 0);
        invite("BBBBBBBBBB", 0);
        assertTrue(accounts.signup(CODE, "Newfriend", "hunter22", "ip").ok());
        assertEquals(SiteAccounts.RATE_LIMITED, accounts.signup("BBBBBBBBBB", "Second", "hunter22", "ip").error());
        assertTrue(accounts.signup("BBBBBBBBBB", "Second", "hunter22", "other").ok());

        for (int i = 0; i < SiteAccounts.BAD_CODES_PER_IP_PER_HOUR; i++) {
            clock.addAndGet(TimeUnit.MINUTES.toMillis(1));   // stay under the per-minute cap
            assertEquals(SiteAccounts.BAD_CODE, accounts.signup("CCCCCCCCCC", "Guesser", "hunter22", "g").error());
        }
        invite("DDDDDDDDDD", 0);
        clock.addAndGet(TimeUnit.MINUTES.toMillis(1));
        assertEquals(SiteAccounts.RATE_LIMITED, accounts.signup("DDDDDDDDDD", "Guesser", "hunter22", "g").error());
    }

    // ── sign-in ────────────────────────────────────────────────────────────────────────────────

    AuthStore.Account account(int id, String name, String pass, boolean banned, long tempban) {
        AuthStore.Account a = new AuthStore.Account(id, name, AccountPasswords.bcrypt(pass), banned, tempban);
        store.put(a);
        return a;
    }

    @Test
    void rightPasswordSignsInWithGmAndCharacters() {
        account(5, "Josh", "hunter22", false, 0);
        store.profiles.put(5, new AuthStore.Profile(6, List.of("Slum", "Alt")));
        SiteAccounts.Result r = accounts.verify("josh", "hunter22", "ip");
        assertTrue(r.ok(), r.toString());
        assertEquals(5, r.accountId());
        assertEquals("Josh", r.account(), "the stored spelling");
        assertTrue(r.gm(), "GM accounts may sign in");
        assertEquals(List.of("Slum", "Alt"), r.characters());
    }

    @Test
    void wrongPasswordUnknownNameBannedAndRevokedAreAllWrongLogin() {
        account(5, "Josh", "hunter22", false, 0);
        account(6, "Banned", "hunter22", true, 0);
        account(7, "Tempban", "hunter22", false, NOW + 60_000);
        account(8, "Revoked", "hunter22", false, 0);
        revoked.add(8);
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Josh", "nothunter", "ip").error());
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Nobody", "hunter22", "ip").error());
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Banned", "hunter22", "ip").error());
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Tempban", "hunter22", "ip").error());
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Revoked", "hunter22", "ip").error());
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Josh", null, "ip").error());
        clock.addAndGet(120_000);
        assertTrue(accounts.verify("Tempban", "hunter22", "ip2").ok(), "after the temp ban");
    }

    @Test
    void bannedBotAndTemplateAccountsAreRefusedBeforeAnyPasswordCheck() {
        // Live's bot template: plain-text marker, banned. Refused, and not even counted as a failed sign-in.
        store.put(new AuthStore.Account(3, "SMBotBase", "!bot-template-no-login", true, 0));
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("SMBotBase", "!bot-template-no-login", "ip").error());
        // Even unbanned with a real hash, the template account by name, a template/bot-owning account, and a
        // bot-range account id are refused.
        store.put(new AuthStore.Account(4, "smbotbase", AccountPasswords.bcrypt("hunter22"), false, 0));
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("SMBotBase", "hunter22", "ip").error());
        store.put(new AuthStore.Account(10, "Owner", AccountPasswords.bcrypt("hunter22"), false, 0, true));
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Owner", "hunter22", "ip").error());
        store.put(new AuthStore.Account(soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 5, "Botacct",
                AccountPasswords.bcrypt("hunter22"), false, 0));
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Botacct", "hunter22", "ip2").error());
        assertEquals(0, accounts.failedSignIns(), "refused before the password was compared");
        assertTrue(AuthStore.Jdbc.FIND_ACCOUNT.contains("c.id >= ? OR c.id = ?"), "bot range and template id");
    }

    @Test
    void aPlainTextStoredPasswordNeverMatches() {
        store.put(new AuthStore.Account(11, "Plainy", "hunter22", false, 0));
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Plainy", "hunter22", "ip").error());
        assertEquals(1, accounts.failedSignIns());
    }

    @Test
    void passwordsOverTwelveCharactersAreNeverCompared() {
        account(12, "Longpw", "thirteenchars", false, 0);
        assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Longpw", "thirteenchars", "ip").error());
        assertEquals(1, accounts.failedSignIns(), "counts as a wrong password");
    }

    @Test
    void legacyHashesSignInAndAreUpgradedLikeTheLoginScreen() {
        String sha512 = client.AccountPasswords.hashForNewAccount("hunter22");   // BCRYPT_MIGRATION off: SHA-512
        store.put(new AuthStore.Account(9, "Oldie", sha512, false, 0));
        assertTrue(accounts.verify("Oldie", "hunter22", "ip").ok());
        assertEquals(0, store.upgrades, "no upgrade while BCRYPT_MIGRATION is off");
        YamlConfig.config.server.BCRYPT_MIGRATION = true;
        assertTrue(accounts.verify("Oldie", "hunter22", "ip").ok());
        assertEquals(AccountPasswords.Match.BCRYPT, AccountPasswords.check("hunter22", store.findAccount("Oldie").passhash()));
    }

    @Test
    void fiveFailuresLockTheNameForFifteenMinutes() {
        account(5, "Josh", "hunter22", false, 0);
        for (int i = 0; i < SiteAccounts.LOCK_FAILS; i++) {
            assertEquals(SiteAccounts.WRONG_LOGIN, accounts.verify("Josh", "wrong" + i, "ip" + i).error());
        }
        assertEquals(SiteAccounts.LOCKED, accounts.verify("JOSH", "hunter22", "ipX").error(), "even the right password");
        assertEquals(5, accounts.failedSignIns());
        clock.addAndGet(SiteAccounts.LOCK_MS);
        assertTrue(accounts.verify("Josh", "hunter22", "ipX").ok());

        for (int i = 0; i < SiteAccounts.LOCK_FAILS; i++) {
            accounts.verify("Ghost", "wrong", "ip" + i);
        }
        assertEquals(SiteAccounts.LOCKED, accounts.verify("Ghost", "wrong", "ipY").error(), "unknown names lock the same way");
    }

    @Test
    void failuresSpreadOverMoreThanFifteenMinutesDoNotLock() {
        account(5, "Josh", "hunter22", false, 0);
        for (int i = 0; i < 8; i++) {
            accounts.verify("Josh", "wrong", "ip");
            clock.addAndGet(TimeUnit.MINUTES.toMillis(4));
        }
        assertTrue(accounts.verify("Josh", "hunter22", "ip").ok());
    }

    @Test
    void perIpHashAndGlobalRateLimits() {
        for (int i = 0; i < SiteAccounts.PER_IP_PER_MINUTE; i++) {
            assertNotEquals(SiteAccounts.RATE_LIMITED, accounts.verify("Nobody" + i, "x", "ip").error());
        }
        assertEquals(SiteAccounts.RATE_LIMITED, accounts.verify("Nobody", "x", "ip").error());
        assertNotEquals(SiteAccounts.RATE_LIMITED, accounts.verify("Nobody", "x", "other").error());
        clock.addAndGet(60_000);
        assertNotEquals(SiteAccounts.RATE_LIMITED, accounts.verify("Nobody", "x", "ip").error());

        clock.addAndGet(60_000);
        for (int i = 0; i < SiteAccounts.GLOBAL_PER_MINUTE; i++) {
            accounts.signup("x", "abc", "x", "many" + i);   // bad input: cheap, but counted
        }
        assertEquals(SiteAccounts.RATE_LIMITED, accounts.signup("x", "abc", "x", "fresh").error());
    }

    @Test
    void ipHashesAreBoundedAndSafe() {
        assertEquals("none", SiteAccounts.ipKey(null));
        assertEquals("abc123", SiteAccounts.ipKey("abc\n123"));
        assertEquals(128, SiteAccounts.ipKey("a".repeat(500)).length());
    }
}
