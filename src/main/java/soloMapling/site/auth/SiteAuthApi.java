package soloMapling.site.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ticket 105: the game's auth API for the site's login service, on 127.0.0.1 only (docs/tickets/105-invite-signup.md,
 * "Server API"). Off unless {@code -Dslums.auth.port} is set; it then needs {@code -Dslums.auth.secretFile}, a file
 * holding the shared secret every request must send in {@code X-Slums-Auth}. Two small daemon threads serve it, so
 * nothing runs on a game thread; database work goes through the server's connection pool. Request bodies, passwords
 * and the secret are never logged. Ticket 107 adds the GM-approved password reset routes {@code /reset/request},
 * {@code /reset/status} and {@code /reset/complete} ({@link PasswordResets}); the site's invite page adds
 * {@code /invite/create}, {@code /invite/list} and {@code /invite/cancel} ({@link SiteInvites}).
 */
public final class SiteAuthApi {

    private static final Logger log = LoggerFactory.getLogger(SiteAuthApi.class);
    static final String HEADER = "X-Slums-Auth";
    static final int MAX_BODY = 4096;
    static final int MIN_SECRET = 16;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile HttpServer running;
    /** The API behind {@link #running} (auto-QA t-au-1 calls its routes in-process, {@link SiteAuthQa}). */
    private static volatile SiteAuthApi runningApi;

    /** One answer: an HTTP status and a JSON body. */
    record Response(int status, Map<String, Object> body) {
    }

    private final byte[] secret;
    private final SiteAccounts accounts;
    private final PasswordResets resets;
    private final SiteInvites invites;

    SiteAuthApi(byte[] secret, SiteAccounts accounts) {
        this(secret, accounts, null);
    }

    /** {@code resets} null: the {@code /reset/*} routes answer UNAVAILABLE. */
    SiteAuthApi(byte[] secret, SiteAccounts accounts, PasswordResets resets) {
        this(secret, accounts, resets, null);
    }

    /** {@code invites} null: the {@code /invite/*} routes answer UNAVAILABLE. */
    SiteAuthApi(byte[] secret, SiteAccounts accounts, PasswordResets resets, SiteInvites invites) {
        this.secret = secret.clone();
        this.accounts = accounts;
        this.resets = resets;
        this.invites = invites;
    }

    /** Called once at server start. */
    public static void startIfEnabled() {
        String port = System.getProperty("slums.auth.port");
        if (port == null || port.isBlank()) {
            return;
        }
        try {
            byte[] secret = readSecret(Path.of(System.getProperty("slums.auth.secretFile", "")));
            if (secret == null) {
                log.warn("Site auth API not started: -Dslums.auth.secretFile is missing, unreadable or shorter than {} characters", MIN_SECRET);
                return;
            }
            SiteAccounts accounts = SiteAccounts.forGame();
            SiteAuthApi api = new SiteAuthApi(secret, accounts, PasswordResets.forGame(accounts, secret),
                    new SiteInvites(new AuthStore.Jdbc(), System::currentTimeMillis, accounts));
            running = start(Integer.parseInt(port.trim()), api);
            runningApi = api;
            log.info("Site auth API listening on 127.0.0.1:{}", running.getAddress().getPort());
        } catch (Exception e) {
            log.warn("Site auth API not started", e);
        }
    }

    public static void stop() {
        HttpServer s = running;
        if (s != null) {
            s.stop(0);
            running = null;
        }
        runningApi = null;
    }

    /** Test seam: listen on a free loopback port with {@code api}, as {@link #startIfEnabled} does. */
    static void startForTest(SiteAuthApi api) throws IOException {
        running = start(0, api);
        runningApi = api;
    }

    /** The running API and its port, or null when it is not listening (auto-QA t-au-1). */
    static SiteAuthApi runningApi() {
        return running == null ? null : runningApi;
    }

    /** The loopback port the API listens on, or -1. */
    static int runningPort() {
        HttpServer s = running;
        return s == null ? -1 : s.getAddress().getPort();
    }

    /** Binds the loopback address only; port 0 picks a free port (tests). */
    static HttpServer start(int port, SiteAuthApi api) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "site-auth-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", api::serve);
        server.start();
        return server;
    }

    /**
     * The secret with surrounding whitespace and newlines stripped (the login service strips it the same way), or
     * null when the file is missing, unreadable or too short. Only read: its owner and mode are the host's business
     * (live: group slums-login, 0640).
     */
    static byte[] readSecret(Path file) {
        try {
            if (file.toString().isEmpty() || !Files.isRegularFile(file)) {
                return null;
            }
            String s = Files.readString(file, StandardCharsets.UTF_8).trim();
            return s.length() < MIN_SECRET ? null : s.getBytes(StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void serve(HttpExchange ex) throws IOException {
        Response r;
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            r = handle(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst(HEADER), body);
        } catch (RuntimeException e) {
            log.warn("Site auth API request failed", e);
            r = error(500, SiteAccounts.UNAVAILABLE);
        }
        byte[] out = MAPPER.writeValueAsBytes(r.body());
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(r.status(), out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** One request: the secret first, then the route, then the JSON body. */
    Response handle(String method, String path, String auth, byte[] body) {
        if (!authorized(auth)) {
            return error(401, "UNAUTHORIZED");
        }
        return route(method, path, body);
    }

    /**
     * One request after the secret check: the route, then the JSON body. Only {@link #handle} (the HTTP side, after
     * the secret) and auto-QA's in-process check ({@link SiteAuthQa}, which never sees the secret) call it.
     */
    Response route(String method, String path, byte[] body) {
        if (!"/signup".equals(path) && !"/verify".equals(path) && !RESET_PATHS.contains(path)
                && !INVITE_PATHS.contains(path)) {
            return error(404, "NOT_FOUND");
        }
        if (!"POST".equals(method)) {
            return error(405, "METHOD_NOT_ALLOWED");
        }
        if (body == null || body.length > MAX_BODY) {
            return error(413, "BAD_REQUEST");
        }
        Map<?, ?> json;
        try {
            json = MAPPER.readValue(body, Map.class);
        } catch (IOException | RuntimeException e) {
            return error(400, "BAD_REQUEST");
        }
        if (RESET_PATHS.contains(path)) {
            return reset(path, json);
        }
        if (INVITE_PATHS.contains(path)) {
            return invite(path, json);
        }
        SiteAccounts.Result result = "/signup".equals(path)
                ? accounts.signup(text(json, "code"), text(json, "user"), text(json, "pass"), text(json, "ipHash"))
                : accounts.verify(text(json, "user"), text(json, "pass"), text(json, "ipHash"));
        return new Response(result.ok() || !SiteAccounts.RATE_LIMITED.equals(result.error()) ? 200 : 429, body(result));
    }

    static final java.util.Set<String> RESET_PATHS = java.util.Set.of("/reset/request", "/reset/status", "/reset/complete");

    static final java.util.Set<String> INVITE_PATHS = java.util.Set.of("/invite/create", "/invite/list", "/invite/cancel");

    /** Ticket 105, "Site invite page": the {@code /invite/*} routes ({@link SiteInvites}); errors answer 200. */
    private Response invite(String path, Map<?, ?> json) {
        if (invites == null) {
            return error(200, SiteAccounts.UNAVAILABLE);
        }
        Integer account = number(json, "account_id");
        Map<String, Object> m = switch (path) {
            case "/invite/create" -> invites.create(account, json.get("life"), text(json, "note"));
            case "/invite/list" -> invites.list(account, number(json, "limit"), number(json, "page"),
                    number(json, "offset"));
            default -> invites.cancel(account, text(json, "code"));
        };
        return new Response(200, m);
    }

    /** Ticket 107: the {@code /reset/*} routes (docs/tickets/107-password-reset-requests.md). */
    private Response reset(String path, Map<?, ?> json) {
        if (resets == null) {
            return error(200, SiteAccounts.UNAVAILABLE);
        }
        switch (path) {
            case "/reset/request" -> {
                PasswordResets.Ticket t = resets.request(text(json, "user"), text(json, "contact"), text(json, "ipHash"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", t.ok());
                if (t.ok()) {
                    m.put("request_id", t.requestId());
                    m.put("secret", t.secret());
                } else {
                    m.put("error", t.error());
                    if (t.rule() != null) {
                        m.put("rule", t.rule());
                    }
                }
                return new Response(SiteAccounts.RATE_LIMITED.equals(t.error()) ? 429 : 200, m);
            }
            case "/reset/status" -> {
                PasswordResets.State s = resets.status(text(json, "request_id"), text(json, "secret"), text(json, "ipHash"));
                if (s == null) {
                    return error(429, SiteAccounts.RATE_LIMITED);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("status", s.wire());
                return new Response(200, m);
            }
            default -> {
                SiteAccounts.Result d = resets.complete(text(json, "request_id"), text(json, "secret"),
                        text(json, "pass"), text(json, "ipHash"));
                Map<String, Object> m = body(d);
                if (d.ok()) {
                    m.put("revoke_sessions", true);   // informational: the site revokes the sessions itself
                }
                return new Response(SiteAccounts.RATE_LIMITED.equals(d.error()) ? 429 : 200, m);
            }
        }
    }

    boolean authorized(String header) {
        if (header == null) {
            return false;
        }
        return MessageDigest.isEqual(secret, header.trim().getBytes(StandardCharsets.UTF_8));   // constant time
    }

    static Map<String, Object> body(SiteAccounts.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        if (r.ok()) {
            m.put("account_id", r.accountId());
            m.put("account", r.account());
            m.put("gm", r.gm());
            m.put("gm_level", r.gmLevel());
            m.put("characters", r.characters());
        } else {
            m.put("error", r.error());
            if (r.rule() != null) {
                m.put("rule", r.rule());
            }
        }
        return m;
    }

    private static Response error(int status, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return new Response(status, m);
    }

    /** A whole JSON number that fits an int, else null. */
    private static Integer number(Map<?, ?> json, String key) {
        Object v = json.get(key);
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        return null;
    }

    private static String text(Map<?, ?> json, String key) {
        Object v = json.get(key);
        return v instanceof String s ? s : null;
    }
}
