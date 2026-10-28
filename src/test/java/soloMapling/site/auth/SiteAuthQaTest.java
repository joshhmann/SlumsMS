package soloMapling.site.auth;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 t-au-1: auto-QA calls the running API's routes in-process, never with the secret; HTTP still needs it. */
class SiteAuthQaTest {

    static final String SECRET = "s3cret-for-the-test-only-0123456789";
    final MemoryAuthStore store = new MemoryAuthStore();
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    SiteAuthApi api;
    int cooldown;

    @BeforeEach
    void setUp() {
        cooldown = YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES;
        YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = 0;
        api = new SiteAuthApi(SECRET.getBytes(StandardCharsets.UTF_8),
                new SiteAccounts(store, clock::get, n -> false, id -> false));
    }

    @AfterEach
    void tearDown() {
        SiteAuthApi.stop();
        System.clearProperty(soloMapling.autoqa.AutoQa.PROPERTY);
        YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = cooldown;
    }

    @Test
    void notListeningSaysWhy() {
        SiteAuthApi.stop();
        assertNotNull(SiteAuthQa.notListening());
        assertEquals(-1, SiteAuthQa.statusWithoutSecret());
    }

    @Test
    void theRoutesRunInProcessOnlyWithAutoQaOnAndHttpStillWantsTheSecret() throws Exception {
        SiteAuthApi.startForTest(api);
        assertNull(SiteAuthQa.notListening());
        assertTrue(SiteAuthQa.port() > 0);
        assertEquals(401, SiteAuthQa.statusWithoutSecret(), "loopback HTTP without the header is refused");
        assertThrows(IllegalStateException.class, () -> SiteAuthQa.call("/verify", Map.of("user", "x", "pass", "y")),
                "not without auto-QA");
        System.setProperty(soloMapling.autoqa.AutoQa.PROPERTY, "true");
        store.insertInvite(InviteCodes.Invite.fresh("ABCDEFGHJK", clock.get(), 1, "Gm", "", 0));
        SiteAuthQa.Answer up = SiteAuthQa.call("/signup",
                Map.of("code", "ABCDE-FGHJK", "user", "qaau123456", "pass", "Qa1!123456", "ipHash", "autoqa"));
        assertTrue(up.ok(), up.body().toString());
        SiteAuthQa.Answer in = SiteAuthQa.call("/verify", Map.of("user", "qaau123456", "pass", "Qa1!123456", "ipHash", "autoqa"));
        assertTrue(in.ok(), in.body().toString());
        SiteAuthQa.Answer wrong = SiteAuthQa.call("/verify", Map.of("user", "qaau123456", "pass", "nope", "ipHash", "autoqa"));
        assertEquals("WRONG_LOGIN", wrong.error());
        assertEquals(404, SiteAuthQa.call("/admin", Map.of()).status(), "the route checks still apply");
        assertEquals("UNAVAILABLE", SiteAuthQa.call("/reset/request", Map.of("user", "qaau123456", "contact", "x")).error(),
                "no reset service in this instance");
    }

    @Test
    void routeAnswersAsHandleDoesWithTheSecret() throws Exception {
        byte[] b = "{\"user\":\"x\",\"pass\":\"y\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(api.handle("POST", "/verify", SECRET, b).body(), api.route("POST", "/verify", b).body());
        assertEquals(401, api.handle("POST", "/verify", null, b).status());
    }

    @Test
    void tIv2FakeAccountsAreOnlyTheMadeUpOnesAndInvitesGoToTheRealStore() {
        SiteAuthQa.FakeAccountStore fake = new SiteAuthQa.FakeAccountStore(store, java.util.List.of(
                new SiteAuthQa.FakeAccount(-5, "qaplayer", 0, 40, false),
                new SiteAuthQa.FakeAccount(-6, "qagm", 3, 1, false)));
        store.put(new AuthStore.Account(7, "Real", "h", false, 0));
        SiteAccounts accounts = new SiteAccounts(fake, clock::get, n -> false, id -> false);
        SiteInvites si = new SiteInvites(fake, clock::get, accounts, true);
        assertEquals("REFUSED", new SiteInvites(fake, clock::get, accounts).create(-5, null, null).get("error"),
                "the real routes never take a negative id");
        assertEquals("REFUSED", si.create(7, null, null).get("error"), "a real account is unknown to the QA store");
        assertEquals(true, si.create(-5, null, null).get("ok"));
        assertEquals(true, si.create(-6, 2, "n").get("gm"));
        assertEquals(2, store.invites.size(), "codes land in the real invite store");
        assertThrows(IllegalArgumentException.class, () -> {
            System.setProperty(soloMapling.autoqa.AutoQa.PROPERTY, "true");
            SiteAuthQa.callInvitesAs(java.util.List.of(new SiteAuthQa.FakeAccount(3, "x", 0, 0, false)), "/invite/list", Map.of());
        });
    }
}
