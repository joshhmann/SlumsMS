package soloMapling.site.auth;

import client.AccountPasswords;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 107: GM-approved password resets: no probing, the secret, approve / deny / expiry, single use, the rules. */
class PasswordResetsTest {

    static final long NOW = 1_800_000_000_000L;
    static final long HOUR = TimeUnit.HOURS.toMillis(1);

    final MemoryAuthStore accounts = new MemoryAuthStore();
    final MemoryResetStore store = new MemoryResetStore();
    final AtomicLong clock = new AtomicLong(NOW);
    final Set<Integer> revoked = new HashSet<>();
    final List<String> notices = new ArrayList<>();
    PasswordResets resets;
    boolean bcrypt;
    int minPass;

    @BeforeEach
    void setUp() {
        bcrypt = YamlConfig.config.server.BCRYPT_MIGRATION;
        minPass = YamlConfig.config.server.AUTO_REGISTER_MIN_PASSWORD_LENGTH;
        YamlConfig.config.server.BCRYPT_MIGRATION = false;
        YamlConfig.config.server.AUTO_REGISTER_MIN_PASSWORD_LENGTH = 6;
        SiteAccounts site = new SiteAccounts(accounts, clock::get, n -> false, revoked::contains);
        resets = new PasswordResets(store, accounts, site, clock::get, Runnable::run, notices::add);
        accounts.put(new AuthStore.Account(7, "Forgetful", AccountPasswords.hashForNewAccount("oldpass1"), false, 0));
    }

    @AfterEach
    void tearDown() {
        YamlConfig.config.server.BCRYPT_MIGRATION = bcrypt;
        YamlConfig.config.server.AUTO_REGISTER_MIN_PASSWORD_LENGTH = minPass;
    }

    PasswordResets.Ticket ask(String user) {
        return resets.request(user, "bob#1234", "iphash" + user);
    }

    // ── no probing ──────────────────────────────────────────────────────────────────────────────

    @Test
    void everyNameGetsTheSameShapeAndOnlyRealOnesAreStored() {
        accounts.put(new AuthStore.Account(8, "Banned", "x", true, 0));
        accounts.put(new AuthStore.Account(9, "Tempbanned", "x", false, NOW + HOUR));
        accounts.put(new AuthStore.Account(10, "Botowner", "x", false, 0, true));
        accounts.put(new AuthStore.Account(11, "SMBotBase", "x", false, 0));
        accounts.put(new AuthStore.Account(soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 1, "Botrange", "x", false, 0));
        accounts.put(new AuthStore.Account(12, "Revoked", "x", false, 0));
        revoked.add(12);
        for (String name : List.of("Forgetful", "Nobodyhere", "Banned", "Tempbanned", "Botowner", "SMBotBase", "Botrange", "Revoked")) {
            PasswordResets.Ticket t = ask(name);
            assertTrue(t.ok(), name);
            assertNull(t.error(), name);
            assertTrue(t.requestId().matches("[0-9a-f]{32}"), name);
            assertEquals(43, t.secret().length(), name);
            assertTrue(t.secret().matches("[A-Za-z0-9_-]+"), name);
        }
        assertEquals(1, store.rows.size(), "only the real, resettable account");
        assertEquals(7, store.rows.values().iterator().next().accountId());
        assertEquals(1, notices.size());
    }

    @Test
    void theRandomAnswerForAnUnknownNameNeverWorks() {
        PasswordResets.Ticket t = ask("Nobodyhere");
        assertEquals(PasswordResets.State.EXPIRED, resets.status(t.requestId(), t.secret(), "ip"));
        SiteAccounts.Result d = resets.complete(t.requestId(), t.secret(), "newpass1", "ip");
        assertFalse(d.ok());
        assertEquals(PasswordResets.EXPIRED, d.error());
    }

    @Test
    void theSecretIsStoredOnlyAsItsHash() {
        PasswordResets.Ticket t = ask("Forgetful");
        ResetStore.Request r = store.find(t.requestId());
        assertNotEquals(t.secret(), r.secretHash());
        assertFalse(r.toString().contains(t.secret()));
        assertEquals(PasswordResets.hash(t.secret()), r.secretHash());
        assertEquals(64, r.secretHash().length());
        assertEquals("bob#1234", r.contact());
        assertEquals("iphashForgetful", r.ipHash());
    }

    // ── the secret ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aWrongOrMissingSecretLooksExpired() throws Exception {
        PasswordResets.Ticket t = ask("Forgetful");
        assertEquals(PasswordResets.State.PENDING, resets.status(t.requestId(), t.secret(), "ip"));
        PasswordResets.approve(store, "Forgetful", "Gm", NOW);
        assertEquals(PasswordResets.State.APPROVED, resets.status(t.requestId(), t.secret(), "ip"));
        String other = PasswordResets.newSecret();
        assertEquals(PasswordResets.State.EXPIRED, resets.status(t.requestId(), other, "ip"));
        assertEquals(PasswordResets.State.EXPIRED, resets.status(t.requestId(), null, "ip"));
        assertEquals(PasswordResets.State.EXPIRED, resets.status(t.requestId(), "", "ip"));
        assertEquals(PasswordResets.State.EXPIRED, resets.status("not-an-id", t.secret(), "ip"));
        assertEquals(PasswordResets.State.EXPIRED, resets.status(null, t.secret(), "ip"));
        assertEquals(PasswordResets.EXPIRED, resets.complete(t.requestId(), other, "newpass1", "ip").error());
        assertTrue(store.passwords.isEmpty(), "nothing set without the secret");
    }

    // ── approve, deny, expiry, single use ─────────────────────────────────────────────────────────

    @Test
    void approvedThenCompletedOnceSetsTheHashedPasswordAndSignsIn() throws Exception {
        accounts.profiles.put(7, new AuthStore.Profile(0, List.of("Slum")));
        PasswordResets.Ticket t = ask("Forgetful");
        assertEquals(PasswordResets.NOT_APPROVED, resets.complete(t.requestId(), t.secret(), "newpass1", "ip").error(),
                "not before a GM approves");
        assertTrue(PasswordResets.approve(store, "forgetful", "Gm", NOW + HOUR).startsWith("Approved"));
        clock.set(NOW + HOUR + 1000);
        SiteAccounts.Result d = resets.complete(t.requestId(), t.secret(), "newpass1", "ip");
        assertTrue(d.ok(), d.toString());
        assertEquals(7, d.accountId());
        assertEquals("Forgetful", d.account());
        assertEquals(List.of("Slum"), d.characters());
        String hash = store.passwords.get(7);
        assertNotEquals("newpass1", hash);
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("newpass1", hash));
        assertEquals(ResetStore.USED, store.find(t.requestId()).state());

        SiteAccounts.Result again = resets.complete(t.requestId(), t.secret(), "another1", "ip");
        assertFalse(again.ok(), "only once");
        assertEquals(PasswordResets.USED, again.error());
        assertEquals(PasswordResets.State.EXPIRED, resets.status(t.requestId(), t.secret(), "ip"));
    }

    @Test
    void bcryptWhenTheServerMigratesToIt() throws Exception {
        YamlConfig.config.server.BCRYPT_MIGRATION = true;
        PasswordResets.Ticket t = ask("Forgetful");
        PasswordResets.approve(store, "Forgetful", "Gm", NOW);
        assertTrue(resets.complete(t.requestId(), t.secret(), "newpass1", "ip").ok());
        assertEquals(AccountPasswords.Match.BCRYPT, AccountPasswords.check("newpass1", store.passwords.get(7)));
    }

    @Test
    void deniedStaysDeniedAndCannotComplete() throws Exception {
        PasswordResets.Ticket t = ask("Forgetful");
        assertTrue(PasswordResets.deny(store, "Forgetful", "Gm", NOW).startsWith("Denied"));
        assertEquals(PasswordResets.State.DENIED, resets.status(t.requestId(), t.secret(), "ip"));
        assertEquals(PasswordResets.NOT_APPROVED, resets.complete(t.requestId(), t.secret(), "newpass1", "ip").error());
        assertTrue(PasswordResets.approve(store, "Forgetful", "Gm", NOW).startsWith("No pending"), "denied can't be approved");
        assertEquals("Gm", store.find(t.requestId()).decidedBy());
    }

    @Test
    void aGmCanDenyAnApprovalBeforeItIsUsed() throws Exception {
        PasswordResets.Ticket t = ask("Forgetful");
        PasswordResets.approve(store, "Forgetful", "Gm", NOW);
        assertTrue(PasswordResets.deny(store, "Forgetful", "Gm2", NOW + 1).startsWith("Denied"));
        assertFalse(resets.complete(t.requestId(), t.secret(), "newpass1", "ip").ok());
    }

    @Test
    void pendingExpiresAfter24Hours() throws Exception {
        PasswordResets.Ticket t = ask("Forgetful");
        clock.set(NOW + 24 * HOUR);
        assertEquals(PasswordResets.State.EXPIRED, resets.status(t.requestId(), t.secret(), "ip"));
        assertTrue(PasswordResets.approve(store, "Forgetful", "Gm", NOW + 24 * HOUR).startsWith("No pending"),
                "too old to approve");
        assertTrue(PasswordResets.list(store, NOW + 24 * HOUR).get(0).startsWith("No pending"));
    }

    @Test
    void anApprovalLastsTwoHoursAndNeverPastTheDay() throws Exception {
        PasswordResets.Ticket t = ask("Forgetful");
        PasswordResets.approve(store, "Forgetful", "Gm", NOW + HOUR);
        clock.set(NOW + 3 * HOUR);
        assertEquals(PasswordResets.State.EXPIRED, resets.status(t.requestId(), t.secret(), "ip"));
        assertEquals(PasswordResets.EXPIRED, resets.complete(t.requestId(), t.secret(), "newpass1", "ip").error());

        clock.set(NOW + 30 * HOUR);
        PasswordResets.Ticket late = ask("Forgetful");
        PasswordResets.approve(store, "Forgetful", "Gm", NOW + 53 * HOUR + HOUR / 2);
        clock.set(NOW + 54 * HOUR + 1);   // 30 min into the approval, but the request is 24 h old
        assertEquals(PasswordResets.State.EXPIRED, resets.status(late.requestId(), late.secret(), "ip"));
        assertTrue(store.passwords.isEmpty());
    }

    @Test
    void aNewRequestReplacesTheOldOne() throws Exception {
        PasswordResets.Ticket first = ask("Forgetful");
        PasswordResets.approve(store, "Forgetful", "Gm", NOW);
        clock.addAndGet(1000);
        PasswordResets.Ticket second = ask("Forgetful");
        assertEquals(PasswordResets.State.EXPIRED, resets.status(first.requestId(), first.secret(), "ip"));
        assertEquals(PasswordResets.State.PENDING, resets.status(second.requestId(), second.secret(), "ip"));
        assertEquals(1, PasswordResets.list(store, clock.get()).size());
    }

    @Test
    void anAccountBannedAfterApprovalCannotComplete() throws Exception {
        PasswordResets.Ticket t = ask("Forgetful");
        PasswordResets.approve(store, "Forgetful", "Gm", NOW);
        accounts.put(new AuthStore.Account(7, "Forgetful", "x", true, 0));
        assertFalse(resets.complete(t.requestId(), t.secret(), "newpass1", "ip").ok());
        assertTrue(store.passwords.isEmpty());
    }

    // ── rules and limits ────────────────────────────────────────────────────────────────────────

    @Test
    void theNewPasswordFollowsTheSignUpRules() throws Exception {
        PasswordResets.Ticket t = ask("Forgetful");
        PasswordResets.approve(store, "Forgetful", "Gm", NOW);
        assertEquals("pass_length", resets.complete(t.requestId(), t.secret(), "short", "ip").rule());
        assertEquals("pass_length", resets.complete(t.requestId(), t.secret(), "thirteenchars", "ip").rule());
        assertEquals("pass_chars", resets.complete(t.requestId(), t.secret(), "has space", "ip").rule());
        assertEquals(SiteAccounts.BAD_INPUT, resets.complete(t.requestId(), t.secret(), null, "ip").error());
        assertTrue(resets.complete(t.requestId(), t.secret(), "!Pass~word1", "ip").ok(), "still usable");
    }

    @Test
    void theContactIsCleanedAndPg13() {
        assertEquals("bob#1234", PasswordResets.cleanContact("  bob#1234 \n"));
        assertEquals("bob 1234", PasswordResets.cleanContact("bob\t\t1234\u0007"));
        assertEquals("bob", PasswordResets.cleanContact("bob😀"), "emoji dropped");
        assertNull(PasswordResets.cleanContact(""));
        assertNull(PasswordResets.cleanContact(null));
        assertEquals(64, PasswordResets.cleanContact("x".repeat(64)).length());
        assertNull(PasswordResets.cleanContact("x".repeat(65)));
        assertEquals("contact_length", resets.request("Forgetful", "   ", "ip").rule());
        assertEquals("contact_length", resets.request("Forgetful", "x".repeat(65), "ip").rule());
        assertEquals("contact_words", resets.request("Forgetful", "kys loser", "ip").rule());
        assertEquals("user_length", resets.request("", "bob", "ip").rule());
        assertEquals("user_length", resets.request("x".repeat(65), "bob", "ip").rule());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void requestsAreRateLimitedPerIpAndOverall() {
        for (int i = 0; i < PasswordResets.REQUESTS_PER_IP_PER_HOUR; i++) {
            assertTrue(resets.request("Nobody" + i, "bob", "flood").ok());
        }
        assertEquals(SiteAccounts.RATE_LIMITED, resets.request("Forgetful", "bob", "flood").error());
        assertTrue(resets.request("Forgetful", "bob", "other").ok(), "another ipHash still gets through");
        for (int i = 0; i < PasswordResets.REQUESTS_PER_HOUR; i++) {
            resets.request("Nobody", "bob", "ip" + i);
        }
        assertEquals(SiteAccounts.RATE_LIMITED, resets.request("Nobody", "bob", "fresh").error(), "global cap");
        clock.addAndGet(HOUR);
        assertTrue(resets.request("Nobody", "bob", "flood").ok(), "the window slides");
    }

    @Test
    void statusAndCompleteAreRateLimited() {
        for (int i = 0; i < PasswordResets.STATUS_PER_IP_PER_MINUTE; i++) {
            assertNotNull(resets.status("x", "y", "poll"));
        }
        assertNull(resets.status("x", "y", "poll"));
        for (int i = 0; i < PasswordResets.COMPLETES_PER_IP_PER_HOUR; i++) {
            assertNotEquals(SiteAccounts.RATE_LIMITED, resets.complete("x", "y", "newpass1", "c").error());
        }
        assertEquals(SiteAccounts.RATE_LIMITED, resets.complete("x", "y", "newpass1", "c").error());
    }

    // ── GM side ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void gmsAreToldAndTheListShowsAccountTimeContactAndIpPrefix() throws Exception {
        accounts.profiles.put(7, new AuthStore.Profile(4, List.of()));
        ask("Forgetful");
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("Forgetful") && notices.get(0).contains("bob#1234")
                && notices.get(0).contains("GM level 4"), notices.get(0));
        List<String> lines = PasswordResets.list(store, NOW + HOUR);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("Forgetful  "), line);
        assertTrue(line.contains(" PT") && line.contains("contact: bob#1234") && line.contains("ip iphash  ("), line);
        assertTrue(line.contains("expires in 23h"), line);
        assertFalse(line.contains("iphashForgetful"), "only the first characters of the ipHash");
    }

    @Test
    void dashboardStates() {
        assertEquals("pending", PasswordResets.dashboardState("pending", NOW, 0, NOW + HOUR));
        assertEquals("expired", PasswordResets.dashboardState("pending", NOW, 0, NOW + 25 * HOUR));
        assertEquals("approved", PasswordResets.dashboardState("approved", NOW, NOW, NOW + HOUR));
        assertEquals("expired", PasswordResets.dashboardState("approved", NOW, NOW, NOW + 3 * HOUR));
        assertEquals("used", PasswordResets.dashboardState("used", NOW, NOW, NOW + 3 * HOUR));
        assertEquals("denied", PasswordResets.dashboardState("denied", NOW, NOW, NOW + 30 * HOUR));
        assertEquals("replaced", PasswordResets.dashboardState("replaced", NOW, 0, NOW));
    }

    @Test
    void migration037IsIdempotentLedgeredAndMatchesTheStore() throws Exception {
        Path dir = Path.of("database/migrations");
        String id = "037-password-resets";
        String sql = Files.readString(dir.resolve(id + ".sql"));
        String rollback = Files.readString(dir.resolve(id + ".rollback.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS password_resets"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + id + "')"));
        assertTrue(rollback.contains("DROP TABLE IF EXISTS password_resets") && rollback.contains("migration_id='" + id + "'"));
        for (String col : ResetStore.Jdbc.COLUMNS.split(",\\s*")) {
            assertTrue(sql.contains(col + " "), col);
        }
        assertTrue(sql.contains("used_ip_hash "));
        assertFalse(java.util.regex.Pattern.compile("(?m)^\\s*(secret|password)\\s").matcher(sql).find(),
                "only secret_hash, never the secret or a password");
        // Single use: completing only matches an approved, fresh request.
        assertTrue(ResetStore.Jdbc.USE.contains("state = 'approved' AND created_at > ? AND decided_at > ?"));
        assertTrue(ResetStore.Jdbc.DECIDE.contains("AND state = ?"));
    }
}
