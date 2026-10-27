package soloMapling.site.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 105: the loopback auth API: the shared secret, routes, generic answers, and nothing secret in the log. */
class SiteAuthApiTest {

    static final String SECRET = "s3cret-for-the-test-only-0123456789";
    static final String PASSWORD = "Hunt3r!x";
    static final ObjectMapper JSON = new ObjectMapper();

    final MemoryAuthStore store = new MemoryAuthStore();
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    SiteAuthApi api;
    boolean bcrypt;
    int cooldown;

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("site-auth-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage()
                    + (event.getThrown() == null ? "" : " " + event.getThrown()));
        }
    }

    final Capture capture = new Capture();
    final List<Logger> loggers = new ArrayList<>();
    final List<Level> levels = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bcrypt = YamlConfig.config.server.BCRYPT_MIGRATION;
        cooldown = YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES;
        YamlConfig.config.server.BCRYPT_MIGRATION = false;
        YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = 0;
        api = new SiteAuthApi(SECRET.getBytes(StandardCharsets.UTF_8),
                new SiteAccounts(store, clock::get, n -> false, id -> false));
        capture.start();
        for (Class<?> c : new Class<?>[]{SiteAuthApi.class, SiteAccounts.class, InviteCodes.class, PasswordResets.class}) {
            Logger l = (Logger) LogManager.getLogger(c);
            loggers.add(l);
            levels.add(l.getLevel());
            Configurator.setLevel(l.getName(), Level.ALL);
            l.addAppender(capture);
        }
    }

    @AfterEach
    void tearDown() {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).removeAppender(capture);
            Configurator.setLevel(loggers.get(i).getName(), levels.get(i));
        }
        capture.stop();
        YamlConfig.config.server.BCRYPT_MIGRATION = bcrypt;
        YamlConfig.config.server.AUTO_REGISTER_IP_COOLDOWN_MINUTES = cooldown;
    }

    static byte[] body(Object o) throws Exception {
        return JSON.writeValueAsBytes(o);
    }

    @Test
    void everyRequestNeedsTheSecret() throws Exception {
        byte[] b = body(Map.of("user", "x", "pass", "y"));
        assertEquals(401, api.handle("POST", "/verify", null, b).status());
        assertEquals(401, api.handle("POST", "/verify", "wrong", b).status());
        assertEquals(401, api.handle("POST", "/verify", SECRET + "x", b).status());
        assertEquals(401, api.handle("POST", "/nothing", null, b).status(), "the secret comes before the route");
        assertEquals("UNAUTHORIZED", api.handle("POST", "/verify", "", b).body().get("error"));
        assertEquals(200, api.handle("POST", "/verify", SECRET, b).status());
    }

    @Test
    void routesMethodsAndBadBodies() throws Exception {
        assertEquals(404, api.handle("POST", "/admin", SECRET, body(Map.of())).status());
        assertEquals(405, api.handle("GET", "/verify", SECRET, new byte[0]).status());
        assertEquals(400, api.handle("POST", "/verify", SECRET, "{not json".getBytes()).status());
        assertEquals(413, api.handle("POST", "/verify", SECRET, new byte[SiteAuthApi.MAX_BODY + 1]).status());
    }

    @Test
    void signUpThenSignInAnswersTheContract() throws Exception {
        store.insertInvite(InviteCodes.Invite.fresh("ABCDEFGHJK", clock.get(), 1, "Gm", "", 0));
        var up = api.handle("POST", "/signup", SECRET,
                body(Map.of("code", "ABCDE-FGHJK", "user", "Newfriend", "pass", PASSWORD, "ipHash", "h1")));
        assertEquals(200, up.status());
        assertEquals(true, up.body().get("ok"));
        assertEquals("Newfriend", up.body().get("account"));
        assertTrue(up.body().containsKey("account_id") && up.body().containsKey("gm") && up.body().containsKey("characters"));

        var in = api.handle("POST", "/verify", SECRET, body(Map.of("user", "newfriend", "pass", PASSWORD, "ipHash", "h1")));
        assertEquals(true, in.body().get("ok"));
        assertEquals(false, in.body().get("gm"));
        assertEquals(List.of(), in.body().get("characters"));

        var again = api.handle("POST", "/signup", SECRET,
                body(Map.of("code", "ABCDEFGHJK", "user", "Another", "pass", PASSWORD, "ipHash", "h2")));
        assertEquals(Map.of("ok", false, "error", "BAD_CODE"), again.body(), "generic, nothing else");
        var wrong = api.handle("POST", "/verify", SECRET, body(Map.of("user", "Newfriend", "pass", "nope", "ipHash", "h1")));
        assertEquals(Map.of("ok", false, "error", "WRONG_LOGIN"), wrong.body());
        var rule = api.handle("POST", "/signup", SECRET, body(Map.of("code", "x", "user", "a", "pass", PASSWORD)));
        assertEquals(Map.of("ok", false, "error", "BAD_INPUT", "rule", "user_length"), rule.body());
    }

    @Test
    void rateLimitedAnswers429() throws Exception {
        byte[] b = body(Map.of("user", "x", "pass", "y", "ipHash", "flood"));
        for (int i = 0; i < SiteAccounts.PER_IP_PER_MINUTE; i++) {
            assertEquals(200, api.handle("POST", "/verify", SECRET, b).status());
        }
        var r = api.handle("POST", "/verify", SECRET, b);
        assertEquals(429, r.status());
        assertEquals("RATE_LIMITED", r.body().get("error"));
    }

    @Test
    void nothingSecretIsLogged() throws Exception {
        store.insertInvite(InviteCodes.Invite.fresh("ABCDEFGHJK", clock.get(), 1, "Gm", "", 0));
        api.handle("POST", "/signup", SECRET, body(Map.of("code", "ABCDEFGHJK", "user", "Newfriend", "pass", PASSWORD, "ipHash", "h")));
        for (int i = 0; i < 6; i++) {
            api.handle("POST", "/verify", SECRET, body(Map.of("user", "Newfriend", "pass", PASSWORD + i, "ipHash", "h" + i)));
        }
        api.handle("POST", "/verify", "wrong-" + SECRET, body(Map.of("user", "Newfriend", "pass", PASSWORD)));
        assertFalse(capture.lines.isEmpty(), "the sign-up and the lock are logged");
        assertTrue(capture.lines.stream().anyMatch(l -> l.contains("Site sign-up") && l.contains("ABCDE-FGHJK")));
        for (String line : capture.lines) {
            assertFalse(line.contains(PASSWORD), line);
            assertFalse(line.contains(SECRET), line);
        }
    }

    // ── ticket 107: /reset/* ────────────────────────────────────────────────────────────────────

    final MemoryResetStore resetStore = new MemoryResetStore();

    SiteAuthApi withResets() {
        SiteAccounts site = new SiteAccounts(store, clock::get, n -> false, id -> false);
        return new SiteAuthApi(SECRET.getBytes(StandardCharsets.UTF_8), site,
                new PasswordResets(resetStore, store, site, clock::get, Runnable::run, m -> { }));
    }

    @Test
    void resetRoutesNeedTheSecretAndExist() throws Exception {
        SiteAuthApi r = withResets();
        byte[] b = body(Map.of("user", "x", "contact", "bob", "ipHash", "h"));
        assertEquals(401, r.handle("POST", "/reset/request", null, b).status());
        assertEquals(404, r.handle("POST", "/reset/other", SECRET, b).status());
        assertEquals(405, r.handle("GET", "/reset/status", SECRET, b).status());
        assertEquals(Map.of("ok", false, "error", "UNAVAILABLE"), api.handle("POST", "/reset/request", SECRET, b).body(),
                "an API built without resets says so");
    }

    @Test
    void resetRequestAnswersTheSameShapeForEveryName() throws Exception {
        store.put(new AuthStore.Account(7, "Forgetful", "x", false, 0));
        store.put(new AuthStore.Account(8, "Banned", "x", true, 0));
        SiteAuthApi r = withResets();
        for (String name : List.of("Forgetful", "Nobodyhere", "Banned")) {
            var res = r.handle("POST", "/reset/request", SECRET, body(Map.of("user", name, "contact", "bob#1", "ipHash", name)));
            assertEquals(200, res.status());
            assertEquals(List.of("ok", "request_id", "secret"), new ArrayList<>(res.body().keySet()), name);
            assertEquals(true, res.body().get("ok"));
        }
        assertEquals(3, resetStore.rows.size(), "a decoy for each other name");
        assertEquals(1, resetStore.rows.values().stream().filter(x -> x.accountId() > 0).count());
        var bad = r.handle("POST", "/reset/request", SECRET, body(Map.of("user", "Forgetful", "contact", "", "ipHash", "z")));
        assertEquals(Map.of("ok", false, "error", "BAD_INPUT", "rule", "contact_length"), bad.body());
    }

    @Test
    void resetFlowOverTheApi() throws Exception {
        store.put(new AuthStore.Account(7, "Forgetful", "x", false, 0));
        SiteAuthApi r = withResets();
        var req = r.handle("POST", "/reset/request", SECRET, body(Map.of("user", "Forgetful", "contact", "bob#1", "ipHash", "h")));
        String id = (String) req.body().get("request_id"), secret = (String) req.body().get("secret");
        byte[] st = body(Map.of("request_id", id, "secret", secret));
        assertEquals(Map.of("ok", true, "status", "pending"), r.handle("POST", "/reset/status", SECRET, st).body());
        assertEquals(Map.of("ok", true, "status", "expired"), r.handle("POST", "/reset/status", SECRET,
                body(Map.of("request_id", id, "secret", secret + "x"))).body(), "a wrong secret looks expired");
        var early = r.handle("POST", "/reset/complete", SECRET, body(Map.of("request_id", id, "secret", secret, "pass", PASSWORD, "ipHash", "h")));
        assertEquals(Map.of("ok", false, "error", "NOT_APPROVED"), early.body());

        assertTrue(id.matches("[\\w-]+") && secret.matches("[\\w-]{8,128}"), "the site's format check");
        PasswordResets.approve(resetStore, "Forgetful", "Gm", clock.get());
        assertEquals(Map.of("ok", true, "status", "approved"), r.handle("POST", "/reset/status", SECRET, st).body());
        var rule = r.handle("POST", "/reset/complete", SECRET, body(Map.of("request_id", id, "secret", secret, "pass", "x", "ipHash", "h")));
        assertEquals(Map.of("ok", false, "error", "BAD_INPUT", "rule", "pass_length"), rule.body());
        var done = r.handle("POST", "/reset/complete", SECRET, body(Map.of("request_id", id, "secret", secret, "pass", PASSWORD, "ipHash", "h")));
        assertEquals(200, done.status());
        assertEquals(List.of("ok", "account_id", "account", "gm", "gm_level", "characters", "revoke_sessions"),
                new ArrayList<>(done.body().keySet()), "the /verify shape plus revoke_sessions");
        assertEquals(7, done.body().get("account_id"));
        assertEquals(true, done.body().get("revoke_sessions"));
        var twice = r.handle("POST", "/reset/complete", SECRET, body(Map.of("request_id", id, "secret", secret, "pass", PASSWORD, "ipHash", "h")));
        assertEquals(Map.of("ok", false, "error", "USED"), twice.body());
        assertEquals(Map.of("ok", true, "status", "expired"), r.handle("POST", "/reset/status", SECRET, st).body());

        for (String line : capture.lines) {
            assertFalse(line.contains(PASSWORD), line);
            assertFalse(line.contains(secret), line);
            assertFalse(line.contains(PasswordResets.hash(secret)), line);
            assertFalse(line.contains("bob#1"), "the contact stays out of the log: " + line);
        }
        assertTrue(capture.lines.stream().anyMatch(l -> l.contains("Password reset done for account 'Forgetful'")));
    }

    @Test
    void unknownNamesAreNotLogged() throws Exception {
        withResets().handle("POST", "/reset/request", SECRET, body(Map.of("user", "MyPassword99", "contact", "bob", "ipHash", "h")));
        for (String line : capture.lines) {
            assertFalse(line.contains("MyPassword99"), line);
        }
    }

    @Test
    void resetRateLimitsAnswer429() throws Exception {
        SiteAuthApi r = withResets();
        byte[] b = body(Map.of("user", "x", "contact", "bob", "ipHash", "flood"));
        for (int i = 0; i < PasswordResets.REQUESTS_PER_IP_PER_HOUR; i++) {
            assertEquals(200, r.handle("POST", "/reset/request", SECRET, b).status());
        }
        assertEquals(429, r.handle("POST", "/reset/request", SECRET, b).status());
        byte[] st = body(Map.of("request_id", "x", "secret", "y", "ipHash", "poll"));
        for (int i = 0; i < PasswordResets.STATUS_PER_IP_PER_MINUTE; i++) {
            assertEquals(200, r.handle("POST", "/reset/status", SECRET, st).status());
        }
        assertEquals(Map.of("ok", false, "error", "RATE_LIMITED"), r.handle("POST", "/reset/status", SECRET, st).body());
    }

    @Test
    void servesOnLoopbackOnlyOverHttp() throws Exception {
        HttpServer server = SiteAuthApi.start(0, api);
        try {
            assertEquals(InetAddress.getLoopbackAddress(), server.getAddress().getAddress());
            int port = server.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + port + "/verify");
            String json = "{\"user\":\"x\",\"pass\":\"y\",\"ipHash\":\"h\"}";
            HttpResponse<String> no = http.send(HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, no.statusCode());
            HttpResponse<String> yes = http.send(HttpRequest.newBuilder(uri).header(SiteAuthApi.HEADER, SECRET)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, yes.statusCode());
            assertEquals("{\"ok\":false,\"error\":\"WRONG_LOGIN\"}", yes.body());
            assertEquals("application/json", yes.headers().firstValue("Content-Type").orElse(""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theSecretFileMustExistAndBeLongEnough(@TempDir Path dir) throws Exception {
        assertNull(SiteAuthApi.readSecret(dir.resolve("missing")));
        Path shortOne = Files.writeString(dir.resolve("short"), "tooshort\n");
        assertNull(SiteAuthApi.readSecret(shortOne));
        Path good = Files.writeString(dir.resolve("good"), SECRET + "\n");
        assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8), SiteAuthApi.readSecret(good));
        assertNull(SiteAuthApi.readSecret(Path.of("")));
        Path padded = Files.writeString(dir.resolve("padded"), "  " + SECRET + " \r\n\n");
        Files.setPosixFilePermissions(padded, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8), SiteAuthApi.readSecret(padded), "0640, whitespace stripped");
    }

    @Test
    void offWithoutThePortProperty() {
        System.clearProperty("slums.auth.port");
        SiteAuthApi.startIfEnabled();   // returns at once, binds nothing
        SiteAuthApi.stop();
    }
}
