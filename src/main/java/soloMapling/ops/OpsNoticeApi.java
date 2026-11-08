package soloMapling.ops;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * b377 (owner, 8 Nov 2026: "an announcement in game that server will go down in a min"): the one way for a shell on the
 * host, in practice deploy.sh, to tell every player in game that the server is about to restart.
 * <ul>
 *   <li>bound to the loopback address only, and a request from any other address is refused (403);</li>
 *   <li>off unless {@code -Dslums.ops.port} is set and {@code -Dslums.ops.secretFile} names a readable file of at least
 *       {@link #MIN_SECRET} characters; that secret is read once and never logged, printed or echoed;</li>
 *   <li>kept apart from the bot inspect API on purpose: that one is read-only and the website proxies it with its own
 *       secret, so the website must never gain a broadcast;</li>
 *   <li>one route, {@code POST /notice}, with the notice text as the UTF-8 body (at most {@link #MAX_TEXT} characters,
 *       one line, no product or company names, see {@link #refusal}); at most one notice per {@link #MIN_GAP_MS}.</li>
 * </ul>
 * A notice goes to every channel of every world as the blue "[Notice]" chat line ({@code !notice} sends the same)
 * and as the scrolling top line, so a player in a dialog or a menu still sees it.
 */
public final class OpsNoticeApi {
    private static final Logger log = LoggerFactory.getLogger(OpsNoticeApi.class);

    public static final String PORT = "slums.ops.port";
    public static final String SECRET_FILE = "slums.ops.secretFile";
    public static final String HEADER = "X-Slums-Ops";
    static final int MIN_SECRET = 16;
    static final int MAX_TEXT = 120;
    static final int MAX_BODY = 1024;
    static final long MIN_GAP_MS = 5_000;
    /** Names player text never carries (the copy rule); matched case-insensitively anywhere in the text. */
    static final List<String> FORBIDDEN = List.of("maplestory", "nexon", "wizet", "v83", "gms", "mapleroot", "cosmic",
            "heavenms", "big bang");

    private static volatile HttpServer running;

    /** One answer: a status and its JSON text. */
    record Response(int status, String json) {
    }

    /** Sends one notice to every channel; returns how many channels it reached. Tests hand in their own. */
    interface Broadcaster {
        int send(String text);
    }

    private final byte[] secret;
    private final Broadcaster broadcaster;
    private final LongSupplier clock;
    private long lastNoticeAt = Long.MIN_VALUE;

    OpsNoticeApi(byte[] secret, Broadcaster broadcaster, LongSupplier clock) {
        this.secret = secret.clone();
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────

    /** Called once at server start, after the worlds are up; does nothing unless both flags are set. */
    public static void startIfEnabled() {
        String port = System.getProperty(PORT);
        if (port == null || port.isBlank()) {
            return;
        }
        try {
            byte[] secret = readSecret(Path.of(System.getProperty(SECRET_FILE, "")));
            if (secret == null) {
                log.warn("Ops notice endpoint not started: -D{} is missing, unreadable or shorter than {} characters",
                        SECRET_FILE, MIN_SECRET);
                return;
            }
            running = start(Integer.parseInt(port.trim()),
                    new OpsNoticeApi(secret, OpsNoticeApi::sendToEveryChannel, System::currentTimeMillis));
            log.info("Ops notice endpoint listening on 127.0.0.1:{}", running.getAddress().getPort());
        } catch (Exception e) {
            log.warn("Ops notice endpoint not started", e);
        }
    }

    public static void stop() {
        HttpServer s = running;
        if (s != null) {
            s.stop(0);
            running = null;
        }
    }

    static HttpServer start(int port, OpsNoticeApi api) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 4);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ops-notice");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", api::serve);
        server.start();
        return server;
    }

    /** The secret, trimmed, or null when the file is missing, unreadable or too short. Never logged. */
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

    /** The game side: the notice on every channel of every world, as chat and as the top line. */
    static int sendToEveryChannel(String text) {
        int reached = 0;
        for (World world : Server.getInstance().getWorlds()) {
            for (Channel ch : world.getChannels()) {
                ch.broadcastPacket(PacketCreator.serverNotice(6, "[Notice] " + text));
                ch.broadcastPacket(PacketCreator.serverMessage(text));
                reached++;
            }
        }
        return reached;
    }

    // ── requests ────────────────────────────────────────────────────────────────────────────────

    private void serve(HttpExchange ex) throws IOException {
        Response r;
        try {
            boolean local = ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null
                    && ex.getRemoteAddress().getAddress().isLoopbackAddress();
            String body = readBody(ex.getRequestBody());
            r = handle(local, ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst(HEADER), body);
        } catch (RuntimeException e) {
            log.warn("Ops notice request failed", e);
            r = new Response(500, "{\"error\":\"UNAVAILABLE\"}");
        }
        byte[] out = r.json().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(r.status(), out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** At most {@link #MAX_BODY} bytes of the body; null when it is longer (refused as too long). */
    private static String readBody(InputStream in) throws IOException {
        try (in) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            return b.length > MAX_BODY ? null : new String(b, StandardCharsets.UTF_8);
        }
    }

    /** One request: where from, the secret, the method, the route, the text, the pace; then the notice. */
    synchronized Response handle(boolean fromLoopback, String method, String path, String auth, String body) {
        if (!fromLoopback || !authorized(auth)) {
            return new Response(403, "{\"error\":\"FORBIDDEN\"}");
        }
        if (!"/notice".equals(path)) {
            return new Response(404, "{\"error\":\"NOT_FOUND\"}");
        }
        if (!"POST".equals(method)) {
            return new Response(405, "{\"error\":\"METHOD_NOT_ALLOWED\"}");
        }
        String text = body == null ? null : body.strip();
        String why = refusal(text);
        if (why != null) {
            return new Response(400, "{\"error\":\"" + why + "\"}");
        }
        long now = clock.getAsLong();
        if (lastNoticeAt != Long.MIN_VALUE && now - lastNoticeAt < MIN_GAP_MS) {
            return new Response(429, "{\"error\":\"TOO_SOON\"}");
        }
        lastNoticeAt = now;
        int channels = broadcaster.send(text);
        log.info("Ops notice sent to {} channel(s): {}", channels, text);
        return new Response(200, "{\"ok\":true,\"channels\":" + channels + "}");
    }

    /** Why this text is refused, or null when it may go out. */
    static String refusal(String text) {
        if (text == null || text.isEmpty()) {
            return "EMPTY";
        }
        if (text.length() > MAX_TEXT) {
            return "TOO_LONG";
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return "NOT_ONE_LINE";
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : FORBIDDEN) {
            if (lower.contains(name)) {
                return "NAME_RULE";
            }
        }
        return null;
    }

    boolean authorized(String header) {
        if (header == null) {
            return false;
        }
        return MessageDigest.isEqual(secret, header.trim().getBytes(StandardCharsets.UTF_8));   // constant time
    }
}
