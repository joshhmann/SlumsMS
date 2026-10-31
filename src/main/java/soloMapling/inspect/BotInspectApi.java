package soloMapling.inspect;

import client.Character;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.server.DebugTools;
import soloMapling.server.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ticket 157, live bot inspect (the idea of nutnnut/Cosmic's {@code /api/botdebug}, {@code /api/mapinfo} and the world
 * map's bot dots, dev 77e0c5bc9; their server is not ported - it listened on every interface with no login). A
 * read-only JSON endpoint for the website's staff admin (GM 3+), which proxies it server-side:
 * <ul>
 *   <li>bound to the loopback address only; any other bind address is refused and the endpoint stays off;</li>
 *   <li>off unless {@code -Dslums.inspect.port} is set, the switch {@code slums.inspect} is on ({@link DebugTools}
 *       rule: on where QA runs, off by default, {@code -Dslums.inspect=true|false} forces it) and
 *       {@code -Dslums.inspect.secretFile} names a readable file of at least 16 characters. 1.6.2 ships it on for
 *       live with its own port and secret file (owner 2026-10-30, docs/release/1.6.2.conf);</li>
 *   <li>every request must carry that secret in {@link #HEADER} (403 otherwise) and come from a loopback address;
 *       GET only (405); answers cached for {@link #CACHE_MS} per path, so a busy admin page costs one build per path
 *       every two seconds;</li>
 *   <li>the secret is read once and never logged, printed or echoed.</li>
 * </ul>
 * Routes: {@code /bots/{cid}}, {@code /maps/{id}}, {@code /positions}. Contract: docs/design/inspect-api.md.
 */
public final class BotInspectApi {

    private static final Logger log = LoggerFactory.getLogger(BotInspectApi.class);
    public static final String SWITCH = "slums.inspect";
    public static final String PORT = "slums.inspect.port";
    public static final String SECRET_FILE = "slums.inspect.secretFile";
    public static final String BIND = "slums.inspect.bind";
    public static final String HEADER = "X-Slums-Inspect";
    static final int MIN_SECRET = 16;
    static final long CACHE_MS = 2_000;
    static final int MAX_CACHED = 512;
    private static final Pattern BOT = Pattern.compile("^/bots/(\\d{1,10})$");
    private static final Pattern MAP = Pattern.compile("^/maps/(\\d{1,10})$");

    private static volatile HttpServer running;
    private static volatile QaEventFeed feed;

    /** One answer: a status and its JSON text. */
    record Response(int status, String json) {
    }

    /** What the endpoint reads from the game; tests hand in their own. A null document means "not found". */
    interface Sources {
        Map<String, Object> bot(int cid, long now);

        Map<String, Object> map(int mapId, long now);

        Map<String, Object> positions(long now);
    }

    private record Cached(long at, Response response) {
    }

    private final byte[] secret;
    private final Sources sources;
    private final LongSupplier clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    BotInspectApi(byte[] secret, Sources sources, LongSupplier clock) {
        this.secret = secret.clone();
        this.sources = sources;
        this.clock = clock;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────

    /** Called once at server start; does nothing unless every condition in the class comment holds. */
    public static void startIfEnabled() {
        String port = System.getProperty(PORT);
        if (port == null || port.isBlank() || !DebugTools.featureOn(SWITCH)) {
            return;
        }
        try {
            InetAddress bind = bindAddress(System.getProperty(BIND));
            if (bind == null) {
                log.warn("Bot inspect API not started: -D{} must be a loopback address", BIND);
                return;
            }
            byte[] secret = readSecret(Path.of(System.getProperty(SECRET_FILE, "")));
            if (secret == null) {
                log.warn("Bot inspect API not started: -D{} is missing, unreadable or shorter than {} characters",
                        SECRET_FILE, MIN_SECRET);
                return;
            }
            running = start(bind, Integer.parseInt(port.trim()),
                    new BotInspectApi(secret, new GameSources(), System::currentTimeMillis));
            log.info("Bot inspect API listening on {}:{} (read-only)", bind.getHostAddress(),
                    running.getAddress().getPort());
        } catch (Exception e) {
            log.warn("Bot inspect API not started", e);
        }
    }

    /** The listening server, or null (tests). */
    static HttpServer running() {
        return running;
    }

    /** Auto-QA t-nv-3 (ticket 157, b224): the port the endpoint listens on, or -1 when it is not running. */
    public static int qaPort() {
        HttpServer s = running;
        return s == null || s.getAddress() == null ? -1 : s.getAddress().getPort();
    }

    /** Auto-QA t-nv-3: the {@code /bots/{cid}} document the endpoint would serve (in process, no secret involved). */
    public static Map<String, Object> qaBot(int cid) {
        return new GameSources().bot(cid, System.currentTimeMillis());
    }

    /** Auto-QA t-nv-3: the {@code /positions} document the endpoint would serve. */
    public static Map<String, Object> qaPositions() {
        return new GameSources().positions(System.currentTimeMillis());
    }

    public static void stop() {
        HttpServer s = running;
        if (s != null) {
            s.stop(0);
            running = null;
        }
        QaEventFeed f = feed;
        if (f != null) {
            f.close();
            feed = null;
        }
    }

    /**
     * The address to bind: loopback when nothing is configured; the configured address only when it is a loopback
     * one (127.0.0.0/8, ::1, "localhost"); null for anything else (0.0.0.0, a LAN address, a bad name), which keeps
     * the endpoint off. Literal addresses only besides "localhost", so no lookup is made.
     */
    static InetAddress bindAddress(String configured) {
        if (configured == null || configured.isBlank()) {
            return InetAddress.getLoopbackAddress();
        }
        String a = configured.trim();
        if ("localhost".equalsIgnoreCase(a)) {
            return InetAddress.getLoopbackAddress();
        }
        if (!a.matches("[0-9.]+|[0-9a-fA-F:]+")) {
            return null;
        }
        try {
            InetAddress addr = InetAddress.getByName(a);
            return addr.isLoopbackAddress() ? addr : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Binds {@code bind} (which must be loopback: anything else is refused) on {@code port}; 0 picks a free one. */
    static HttpServer start(InetAddress bind, int port, BotInspectApi api) throws IOException {
        if (bind == null || !bind.isLoopbackAddress()) {
            throw new IllegalArgumentException("the bot inspect API binds a loopback address only");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        // ticket 170: the TEST-only QA event feed rides this listener (off unless -Dslums.qa.feed=true, never on live);
        // its long-polls get their own threads so the inspect routes never wait behind them
        feed = QaEventFeed.attachIfEnabled(server, api.secret);
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(feed == null ? 2 : 2 + QaEventFeed.MAX_POLLERS, r -> {
            Thread t = new Thread(r, "bot-inspect-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
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

    // ── requests ────────────────────────────────────────────────────────────────────────────────

    private void serve(HttpExchange ex) throws IOException {
        Response r;
        try {
            ex.getRequestBody().close();   // GET only: a body is never read
            boolean local = ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null
                    && ex.getRemoteAddress().getAddress().isLoopbackAddress();
            r = local ? handle(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst(HEADER))
                    : error(403, "FORBIDDEN");
        } catch (RuntimeException e) {
            log.warn("Bot inspect request failed", e);
            r = error(500, "UNAVAILABLE");
        }
        byte[] out = r.json().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(r.status(), out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** One request: the secret, then the method, then the route (cached). */
    Response handle(String method, String path, String auth) {
        if (!authorized(auth)) {
            return error(403, "FORBIDDEN");
        }
        if (!"GET".equals(method)) {
            return error(405, "METHOD_NOT_ALLOWED");
        }
        String p = path == null ? "" : path;
        if (!"/positions".equals(p) && !BOT.matcher(p).matches() && !MAP.matcher(p).matches()) {
            return error(404, "NOT_FOUND");
        }
        long now = clock.getAsLong();
        Cached c = cache.get(p);
        if (c != null && now >= c.at() && now - c.at() < CACHE_MS) {
            return c.response();
        }
        Response r = route(p, now);
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(p, new Cached(now, r));
        return r;
    }

    private Response route(String path, long now) {
        Map<String, Object> doc;
        Matcher m;
        if ("/positions".equals(path)) {
            doc = sources.positions(now);
        } else if ((m = BOT.matcher(path)).matches()) {
            long cid = Long.parseLong(m.group(1));
            doc = cid > Integer.MAX_VALUE ? null : sources.bot((int) cid, now);
        } else {
            m = MAP.matcher(path);
            m.matches();
            long id = Long.parseLong(m.group(1));
            doc = id > Integer.MAX_VALUE ? null : sources.map((int) id, now);
        }
        return doc == null ? error(404, "NOT_FOUND") : new Response(200, Json.write(doc));
    }

    boolean authorized(String header) {
        if (header == null) {
            return false;
        }
        return MessageDigest.isEqual(secret, header.trim().getBytes(StandardCharsets.UTF_8));   // constant time
    }

    private static Response error(int status, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return new Response(status, Json.write(m));
    }

    // ── the game ────────────────────────────────────────────────────────────────────────────────

    /** The live game: every world's online characters; a map's loaded instances on every channel (never loads one). */
    static final class GameSources implements Sources {

        @Override
        public Map<String, Object> bot(int cid, long now) {
            BotSM sm = CharacterStorage.getBotById(cid);
            Character chr = sm != null && sm.getChr() != null && sm.getChr().getId() == cid ? sm.getChr() : online(cid);
            if (chr == null || !BotHelpers.isBot(chr)) {
                return null;   // real players are never inspected here
            }
            return InspectViews.bot(chr, sm, now);
        }

        @Override
        public Map<String, Object> map(int mapId, long now) {
            List<MapleMap> instances = new ArrayList<>();
            for (World w : Server.getInstance().getWorlds()) {
                for (Channel ch : w.getChannels()) {
                    MapleMap loaded = ch.getMapFactory().getMaps().get(mapId);   // never loads a map
                    if (loaded != null) {
                        instances.add(loaded);
                    }
                }
            }
            Collection<Character> everyone = everyone();
            if (instances.isEmpty() && everyone.stream().noneMatch(c -> c != null && c.getMapId() == mapId)) {
                return null;
            }
            return InspectViews.map(mapId, everyone, instances, now);
        }

        @Override
        public Map<String, Object> positions(long now) {
            return InspectViews.positions(everyone(), now);
        }

        private static Character online(int cid) {
            for (World w : Server.getInstance().getWorlds()) {
                Character chr = w.getPlayerStorage().getCharacterById(cid);
                if (chr != null) {
                    return chr;
                }
            }
            return null;
        }

        private static Collection<Character> everyone() {
            Map<Integer, Character> out = new LinkedHashMap<>();
            for (World w : Server.getInstance().getWorlds()) {
                for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                    out.put(chr.getId(), chr);
                }
            }
            for (BotSM sm : CharacterStorage.getAllBots().values()) {   // a bot brain whose character is not stored
                Character chr = sm == null ? null : sm.getChr();
                if (chr != null) {
                    out.putIfAbsent(chr.getId(), chr);
                }
            }
            return out.values();
        }
    }
}
