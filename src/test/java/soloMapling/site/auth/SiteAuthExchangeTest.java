package soloMapling.site.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import config.YamlConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.ExchangeSite;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.NxGuard;
import soloMapling.Economy.exchange.SiteReauth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 b005 end to end: site -> the game's auth API -> the exchange. A stolen cookie (a valid account id and a fresh
 * {@code siteAuthAt}) gets REAUTH until the game itself has seen the account's password, and a sell needs the password
 * typed for that order through {@code POST /exchange/approve}. Wrong passwords, another account's password and a
 * missing approval are all refused; the right password places the order.
 */
class SiteAuthExchangeTest {

    static final String SECRET = "s3cret-for-the-test-only-0123456789";
    static final String PASSWORD = "Hunt3r!x";
    static final ObjectMapper JSON = new ObjectMapper();
    static final long H = 3_600_000L, NX0 = 100_000;

    final MemoryAuthStore auth = new MemoryAuthStore();
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final MemoryExchangeStore store = new MemoryExchangeStore();
    SiteAuthApi api;
    Exchange ex;
    int owner, other, req;
    boolean bcrypt;
    int cooldown;

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("site-auth-exchange-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    final Capture capture = new Capture();
    Logger apiLog;
    Level apiLevel;

    @BeforeEach
    void setUp() throws Exception {
        bcrypt = YamlConfig.config.server.BCRYPT_MIGRATION;
        cooldown = YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES;
        YamlConfig.config.server.BCRYPT_MIGRATION = false;
        YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = 0;
        api = new SiteAuthApi(SECRET.getBytes(StandardCharsets.UTF_8),
                new SiteAccounts(auth, clock::get, n -> false, id -> false));
        owner = signup("ABCDEFGHJK", "Kestrel", PASSWORD);
        other = signup("BCDEFGHJKL", "Magpie", "Thief!pw9");

        store.addAccount(owner, NX0);
        store.addCharacter(500, owner, 1_000_000_000L);
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                1000, 100_000, 0, TimeUnit.HOURS.toMillis(72), 3, d.regularSpreadBps(), d.regularNx(),
                d.rateWindowMs(), 1, d.pollSeconds(), 0, 60_000);
        ex = new Exchange(store, c, store::online, store.finder(), clock::get);
        assertTrue(ex.recover().isEmpty());
        ex.guard(NxGuard.defaults());
        ex.siteReauth(SiteReauth.defaults());
        ex.signIns(new SiteReauth.SignIns());
        api.exchangeHook(new SiteAuthApi.ExchangeHook() {
            @Override
            public void signedIn(long account) {
                ex.signIns().record(account, clock.get());
            }

            @Override
            public Exchange.Response approve(long account, String token) {
                return ex.approve(account, token);
            }
        });

        capture.start();
        apiLog = (Logger) LogManager.getLogger(SiteAuthApi.class);
        apiLevel = apiLog.getLevel();
        Configurator.setLevel(apiLog.getName(), Level.ALL);
        apiLog.addAppender(capture);
    }

    @AfterEach
    void tearDown() {
        apiLog.removeAppender(capture);
        Configurator.setLevel(apiLog.getName(), apiLevel);
        capture.stop();
        YamlConfig.config.server.BCRYPT_MIGRATION = bcrypt;
        YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = cooldown;
    }

    int signup(String code, String user, String pass) throws Exception {
        auth.insertInvite(InviteCodes.Invite.fresh(code, clock.get(), 1, "Gm", "", 0));
        var r = api.handle("POST", "/signup", SECRET, JSON.writeValueAsBytes(Map.of("code", code, "user", user,
                "pass", pass, "ipHash", "h-" + user)));
        assertEquals(true, r.body().get("ok"), r.body().toString());
        return ((Number) r.body().get("account_id")).intValue();
    }

    SiteAuthApi.Response call(String path, Map<String, Object> body) throws Exception {
        return api.handle("POST", path, SECRET, JSON.writeValueAsBytes(body));
    }

    /** What the market service writes for the session (account id and the cookie's sign-in time). */
    Exchange.Response site(String body) {
        clock.addAndGet(11_000);
        ExchangeSite.Request r = ExchangeSite.requests("[{\"id\":\"r" + (++req) + "\",\"account\":" + owner + ","
                + body + ",\"siteAuthAt\":" + (clock.get() - H) + ",\"createdAt\":\""
                + Instant.ofEpochMilli(clock.get()) + "\"}]").get(0);
        return ExchangeSite.answer(ex, r, clock.get());
    }

    Exchange.Response previewSell() {
        return site("\"type\":\"preview\",\"character\":500,\"side\":\"SELL\",\"price\":5000000,\"nx\":50000");
    }

    @Test
    void theRefusalsAndTheSuccessThroughTheAuthApi() throws Exception {
        // signups count as password checks: start from a game that has not seen this account's password
        ex.signIns(new SiteReauth.SignIns());
        // 1. a stolen cookie alone: REAUTH, nothing previewed
        assertEquals("REAUTH", previewSell().code());

        // 2. the owner signs in on the site: the login service asks the game's /verify, which records it
        var in = call("/verify", Map.of("user", "Kestrel", "pass", PASSWORD, "ipHash", "h1"));
        assertEquals(true, in.body().get("ok"));
        Exchange.Response p = previewSell();
        assertTrue(p.ok(), p.toString());
        assertEquals(true, p.data().get("passwordRequired"));
        String token = (String) p.data().get("token");

        // 3. a confirm without the password (what a thief riding the fresh session can send)
        Exchange.Response c = site("\"type\":\"confirm\",\"token\":\"" + token + "\"");
        assertEquals("PASSWORD_REQUIRED", c.code());
        assertEquals(NX0, store.nx(owner));

        // 4. wrong password, another account's right password, a bad token: refused, nothing approved
        var wrong = call("/exchange/approve", Map.of("account_id", owner, "user", "Kestrel", "pass", "guess",
                "token", token, "ipHash", "h2"));
        assertEquals(Map.of("ok", false, "error", "WRONG_LOGIN"), wrong.body());
        var others = call("/exchange/approve", Map.of("account_id", owner, "user", "Magpie", "pass", "Thief!pw9",
                "token", token, "ipHash", "h2"));
        assertEquals(Map.of("ok", false, "error", "WRONG_LOGIN"), others.body());
        var badToken = call("/exchange/approve", Map.of("account_id", owner, "user", "Kestrel", "pass", PASSWORD,
                "token", "not-hex", "ipHash", "h2"));
        assertEquals(Map.of("ok", false, "error", "BAD_INPUT"), badToken.body());
        var otherToken = call("/exchange/approve", Map.of("account_id", other, "user", "Magpie", "pass", "Thief!pw9",
                "token", token, "ipHash", "h3"));
        assertEquals(Map.of("ok", false, "error", "TOKEN_EXPIRED"), otherToken.body(), "a token is its account's");
        assertEquals("PASSWORD_REQUIRED", site("\"type\":\"confirm\",\"token\":\"" + token + "\"").code());

        // 5. the owner types the password on the confirm page: approved, then the confirm places the sell
        var ok = call("/exchange/approve", Map.of("account_id", owner, "user", "Kestrel", "pass", PASSWORD,
                "token", token, "ipHash", "h1"));
        assertEquals(true, ok.body().get("ok"), ok.body().toString());
        assertEquals(token, ok.body().get("token"));
        assertTrue(ok.body().get("expires_at") instanceof String);
        Exchange.Response placed = site("\"type\":\"confirm\",\"token\":\"" + token + "\"");
        assertTrue(placed.ok(), placed.toString());
        assertEquals(NX0 - 50_000, store.nx(owner));

        // nothing secret in the auth API's log
        assertFalse(capture.lines.isEmpty());
        for (String line : capture.lines) {
            assertFalse(line.contains(PASSWORD) || line.contains("Thief!pw9") || line.contains(token), line);
        }
    }

    @Test
    void approveIsRateLimitedAndLocksLikeASignIn() throws Exception {
        ex.signIns().record(owner, clock.get());
        String token = (String) previewSell().data().get("token");
        for (int i = 0; i < 5; i++) {
            call("/exchange/approve", Map.of("account_id", owner, "user", "Kestrel", "pass", "guess" + i,
                    "token", token, "ipHash", "h" + i));
        }
        var locked = call("/exchange/approve", Map.of("account_id", owner, "user", "Kestrel", "pass", PASSWORD,
                "token", token, "ipHash", "h9"));
        assertEquals("LOCKED", locked.body().get("error"), "5 wrong passwords lock the name, as a sign-in does");
        assertEquals("PASSWORD_REQUIRED", site("\"type\":\"confirm\",\"token\":\"" + token + "\"").code());
    }

    @Test
    void anOldGameSignInIsNotEnough() throws Exception {
        call("/verify", Map.of("user", "Kestrel", "pass", PASSWORD, "ipHash", "h1"));
        clock.addAndGet(13 * H);
        assertEquals("REAUTH", previewSell().code(), "13 hours later the player signs in again");
        call("/verify", Map.of("user", "Kestrel", "pass", PASSWORD, "ipHash", "h1"));
        assertTrue(previewSell().ok());
    }

    @Test
    void theRouteIsBehindTheSecretAndOffWithoutTheExchange() throws Exception {
        byte[] b = JSON.writeValueAsBytes(Map.of("account_id", owner, "user", "Kestrel", "pass", PASSWORD,
                "token", "deadbeefdeadbeef"));
        assertEquals(401, api.handle("POST", "/exchange/approve", "wrong", b).status());
        assertEquals(405, api.handle("GET", "/exchange/approve", SECRET, b).status());
        api.exchangeHook(null); // the game's hook: no running exchange in a test
        var off = api.handle("POST", "/exchange/approve", SECRET, b);
        assertEquals("UNAVAILABLE", off.body().get("error"));
    }
}
