package soloMapling.inspect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.qa.QaLog;
import soloMapling.server.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Ticket 170 (166's server side): the TEST-only QA event feed the auto-QA client's runner waits on. Script waits such as
 * {@code server_line {topic: COMBAT, regex: "PARTY HEAL .* -> {me.name}"}} long-poll it instead of sleeping.
 * <ul>
 *   <li>Rides the bot inspect listener ({@link BotInspectApi}: 127.0.0.1 only, the same secret in
 *       {@link BotInspectApi#HEADER}, GET only) under {@code /qa/events}.</li>
 *   <li>Off unless {@code -Dslums.qa.feed=true} is given explicitly (no "on where QA runs" default), and it refuses to
 *       start on anything that looks like live: the live folder, login port 8485 (also the default when none is set)
 *       or database port 3309 ({@link #liveReason}).</li>
 *   <li>Feeds only QA log lines: a {@link QaLog.Sink} for the topics a poll asks for (those topics are on while a
 *       runner polls, {@link #ARM_MS}), and the lines of running {@code !qa} scenarios (STAGE, STEP, verdicts:
 *       topic {@code QA_RUN}). No journal scraping. Markers: {@code MAP_CHANGE} (a real player entered a map),
 *       {@code CHAT} (map chat and whispers), {@code MECHANICS} (KPQ "RUN ..." lines), {@code COMBAT}
 *       ({@code PARTY HEAL}, {@code FOLLOWER FIGHT}).</li>
 * </ul>
 * {@code GET /qa/events?since=<seq>&chars=<id,id>&topics=<T,T>&match=<regex>&wait=<s>&limit=<n>} answers the lines
 * after {@code since} that pass every filter, oldest first, each with its sequence number and UTC time; with none yet
 * it waits up to {@code wait} seconds (at most 20) for one. Contract: docs/qa/client-scripts/README.md, "QA event feed".
 */
public final class QaEventFeed {

    private static final Logger log = LoggerFactory.getLogger(QaEventFeed.class);
    public static final String SWITCH = "slums.qa.feed";
    public static final String PATH = "/qa/events";
    /** The pseudo topic of a running scenario's own lines (STAGE, STEP, its notes and verdicts). */
    public static final String QA_RUN = "QA_RUN";
    static final int RING = 20_000;
    static final int MAX_WAIT_S = 20;
    static final int MAX_LIMIT = 1_000;
    static final int MAX_MATCH = 300;
    static final int MAX_CHARS = 16;
    /** A topic or a character a poll named stays armed/watched this long after that poll. */
    static final long ARM_MS = 120_000;
    /** Long-polls at once; the listener gets this many more threads when the feed rides it. */
    static final int MAX_POLLERS = 4;
    /** What a poll without {@code topics} gets. */
    static final List<String> DEFAULT_TOPICS = List.of("MAP_CHANGE", "CHAT", "COMBAT", "MECHANICS", QA_RUN);
    private static final String LIVE_FOLDER = "/opt/mapleroot/MapleRoot Full Repack";
    private static final int LIVE_LOGIN_PORT = 8485;
    private static final String LIVE_DB_PORT = ":3309";

    /** One fed line. {@code chars} are the characters it is about (named by the hook, or watched ones on its map). */
    record Event(long seq, long at, String topic, String kind, String scenario, int map, int[] chars, String line) {
    }

    /** An answer: a status and its JSON text. */
    record Response(int status, String json) {
    }

    private final byte[] secret;
    private final LongSupplier clock;
    /** A character's map right now, -1 when not online (tests hand in their own). */
    private final IntUnaryOperator mapOf;
    private final ArrayDeque<Event> ring = new ArrayDeque<>();
    private long nextSeq = 1;
    private final Map<QaLog.Topic, Long> armed = new EnumMap<>(QaLog.Topic.class);
    private boolean runLinesArmed;
    private long runLinesUntil;
    private final Map<Integer, Long> watched = new HashMap<>();
    private final AtomicInteger polling = new AtomicInteger();
    private QaLog.SinkHandle sink;
    private Runnable unlisten = () -> { };

    QaEventFeed(byte[] secret, LongSupplier clock, IntUnaryOperator mapOf) {
        this.secret = secret.clone();
        this.clock = clock;
        this.mapOf = mapOf;
    }

    // ── switch and live guard ───────────────────────────────────────────────────────────────────

    /** On only when {@code -Dslums.qa.feed=true} is given (never by default). */
    public static boolean enabled() {
        return enabledFor(System::getProperty);
    }

    static boolean enabledFor(Function<String, String> props) {
        return "true".equalsIgnoreCase(props.apply(SWITCH));
    }

    /**
     * Why this server looks like live (and the feed must stay off), or null. Checks the working folder, the login
     * port ({@code -Dmapleroot.login-port}, 8485 when unset, as {@link Server} reads it) and the database URL.
     */
    static String liveReason(String userDir, String loginPortProperty, String dbUrl) {
        if (userDir != null && userDir.replace('\\', '/').startsWith(LIVE_FOLDER)) {
            return "the server runs from the live folder";
        }
        int port;
        try {
            port = loginPortProperty == null || loginPortProperty.isBlank() ? LIVE_LOGIN_PORT
                    : Integer.parseInt(loginPortProperty.trim());
        } catch (NumberFormatException e) {
            return "the login port is not a number";
        }
        if (port == LIVE_LOGIN_PORT) {
            return "the login port is live's 8485";
        }
        if (dbUrl != null && dbUrl.contains(LIVE_DB_PORT)) {
            return "the database is live's (port 3309)";
        }
        return null;
    }

    private static String serverDbUrl() {
        try {
            return config.YamlConfig.config.server.DB_URL_FORMAT;
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    /**
     * Called by {@link BotInspectApi} while it builds its listener: adds {@link #PATH} when the switch is on and the
     * server is not live; null (and a log line) otherwise.
     */
    static QaEventFeed attachIfEnabled(HttpServer server, byte[] secret) {
        if (!enabled()) {
            return null;
        }
        String live = liveReason(System.getProperty("user.dir"), System.getProperty("mapleroot.login-port"),
                serverDbUrl());
        if (live != null) {
            log.warn("QA event feed refused: {} (TEST only)", live);
            return null;
        }
        QaEventFeed feed = new QaEventFeed(secret, System::currentTimeMillis, QaEventFeed::gameMapOf);
        feed.open();
        server.createContext(PATH, feed::serve);
        log.info("QA event feed on {} (TEST only, QA log lines)", PATH);
        return feed;
    }

    private static int gameMapOf(int cid) {
        try {
            for (World w : Server.getInstance().getWorlds()) {
                client.Character chr = w.getPlayerStorage().getCharacterById(cid);
                if (chr != null) {
                    return chr.getMapId();
                }
            }
        } catch (RuntimeException e) {
            // the world is not up
        }
        return -1;
    }

    /** Registers the sink and the run listener (no topic armed yet). */
    void open() {
        sink = QaLog.sink(this::fromSink);
        unlisten = QaLog.listen(this::fromRun);
    }

    void close() {
        if (sink != null) {
            sink.close();
            sink = null;
        }
        unlisten.run();
        unlisten = () -> { };
    }

    // ── recording ───────────────────────────────────────────────────────────────────────────────

    private void fromSink(QaLog.Topic topic, int mapId, int[] charIds, String message) {
        record(topic.name(), topic.name(), null, mapId, charIds, message);
    }

    void fromRun(int gmId, String scenarioId, String kind, String message) {
        if (isTopic(kind)) {
            return;   // a topic line: the sink feeds it when a poll armed that topic
        }
        synchronized (this) {
            if (!runLinesArmed || clock.getAsLong() > runLinesUntil) {
                return;
            }
        }
        record(QA_RUN, kind, scenarioId, 0, gmId == 0 ? new int[0] : new int[]{gmId}, message);
    }

    private static boolean isTopic(String kind) {
        try {
            QaLog.Topic.valueOf(kind);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /**
     * Keeps a line when it names a character, is about a map a watched character stands on (that character is added),
     * or names neither (a server-wide line). A map line nobody watched is dropped, so the ring holds what runners need.
     */
    void record(String topic, String kind, String scenario, int mapId, int[] charIds, String message) {
        int[] chars = charIds == null ? new int[0] : charIds;
        synchronized (this) {
            long now = clock.getAsLong();
            if (chars.length == 0 && mapId != 0) {
                List<Integer> on = new ArrayList<>();
                for (Map.Entry<Integer, Long> w : watched.entrySet()) {
                    if (w.getValue() >= now && mapOf.applyAsInt(w.getKey()) == mapId) {
                        on.add(w.getKey());
                    }
                }
                if (on.isEmpty()) {
                    return;
                }
                chars = on.stream().mapToInt(Integer::intValue).toArray();
            }
            ring.addLast(new Event(nextSeq++, now, topic, kind, scenario, mapId, chars,
                    message == null ? "" : message.replace('\n', ' ')));
            while (ring.size() > RING) {
                ring.removeFirst();
            }
            notifyAll();
        }
    }

    /** Arms the topics and watches the characters a poll names, for {@link #ARM_MS}; updates the QA log mask. */
    private void arm(Set<String> topics, Set<Integer> chars) {
        Set<QaLog.Topic> on = EnumSet.noneOf(QaLog.Topic.class);
        synchronized (this) {
            long now = clock.getAsLong();
            long until = now + ARM_MS;
            for (String t : topics) {
                if (QA_RUN.equals(t)) {
                    runLinesArmed = true;
                    runLinesUntil = until;
                } else {
                    armed.put(QaLog.Topic.valueOf(t), until);
                }
            }
            armed.entrySet().removeIf(e -> e.getValue() < now);
            on.addAll(armed.keySet());
            for (int c : chars) {
                watched.put(c, until);
            }
            watched.entrySet().removeIf(e -> e.getValue() < now);
        }
        if (sink != null) {
            sink.topics(on);
        }
    }

    // ── requests ────────────────────────────────────────────────────────────────────────────────

    private void serve(HttpExchange ex) throws IOException {
        Response r;
        try {
            ex.getRequestBody().close();
            boolean local = ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null
                    && ex.getRemoteAddress().getAddress().isLoopbackAddress();
            r = local ? handle(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(),
                    ex.getRequestHeaders().getFirst(BotInspectApi.HEADER)) : error(403, "FORBIDDEN");
        } catch (RuntimeException e) {
            log.warn("QA event feed request failed", e);
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

    /** A parsed query. */
    record Query(long since, Set<Integer> chars, Set<String> topics, Pattern match, int waitS, int limit) {
    }

    /** One request: the secret first, then the method, the path and the query; long-polls when nothing matches yet. */
    Response handle(String method, String path, String rawQuery, String auth) {
        if (!authorized(auth)) {
            return error(403, "FORBIDDEN");
        }
        if (!"GET".equals(method)) {
            return error(405, "METHOD_NOT_ALLOWED");
        }
        if (!PATH.equals(path)) {
            return error(404, "NOT_FOUND");
        }
        Query q;
        try {
            q = parse(rawQuery);
        } catch (IllegalArgumentException e) {
            return error(400, "BAD_REQUEST: " + e.getMessage());
        }
        if (polling.incrementAndGet() > MAX_POLLERS) {
            polling.decrementAndGet();
            return error(429, "TOO_MANY_POLLS");
        }
        try {
            arm(q.topics(), q.chars());
            return answer(q);
        } finally {
            polling.decrementAndGet();
        }
    }

    private Response answer(Query q) {
        long deadline = clock.getAsLong() + q.waitS() * 1_000L;
        synchronized (this) {
            while (true) {
                List<Event> hits = new ArrayList<>();
                long oldest = ring.isEmpty() ? nextSeq : ring.peekFirst().seq();
                for (Event e : ring) {
                    if (e.seq() > q.since() && matches(e, q)) {
                        hits.add(e);
                        if (hits.size() >= q.limit()) {
                            break;
                        }
                    }
                }
                long left = deadline - clock.getAsLong();
                if (!hits.isEmpty() || left <= 0) {
                    long last = nextSeq - 1;
                    // next: where the following poll starts (past the scanned lines, or the last hit when cut short)
                    long next = hits.size() >= q.limit() ? hits.get(hits.size() - 1).seq() : Math.max(q.since(), last);
                    return ok(hits, last, next, q.since() > 0 && q.since() < oldest - 1);
                }
                try {
                    wait(Math.min(left, 1_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return error(503, "INTERRUPTED");
                }
            }
        }
    }

    static boolean matches(Event e, Query q) {
        if (!q.topics().contains(e.topic())) {
            return false;
        }
        if (!q.chars().isEmpty()) {
            boolean any = e.chars().length == 0 && e.map() == 0;   // a server-wide line reaches every char filter
            for (int c : e.chars()) {
                any |= q.chars().contains(c);
            }
            if (!any) {
                return false;
            }
        }
        return q.match() == null || q.match().matcher(e.line()).find();
    }

    static Query parse(String rawQuery) {
        Map<String, String> p = new HashMap<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (!Set.of("since", "chars", "topics", "match", "wait", "limit").contains(k)) {
                    throw new IllegalArgumentException("unknown parameter " + k);
                }
                p.put(k, v);
            }
        }
        long since = number(p.get("since"), 0, Long.MAX_VALUE, 0, "since");
        Set<Integer> chars = new LinkedHashSet<>();
        if (p.get("chars") != null && !p.get("chars").isBlank()) {
            for (String c : p.get("chars").split(",")) {
                chars.add((int) number(c, 1, Integer.MAX_VALUE, 0, "chars"));
            }
            if (chars.size() > MAX_CHARS) {
                throw new IllegalArgumentException("at most " + MAX_CHARS + " chars");
            }
        }
        Set<String> topics = new LinkedHashSet<>();
        if (p.get("topics") == null || p.get("topics").isBlank()) {
            topics.addAll(DEFAULT_TOPICS);
        } else {
            for (String t : p.get("topics").split(",")) {
                String name = t.trim().toUpperCase(Locale.ROOT);
                if (!QA_RUN.equals(name) && !isTopic(name)) {
                    throw new IllegalArgumentException("unknown topic " + t.trim());
                }
                topics.add(name);
            }
        }
        Pattern match = null;
        String m = p.get("match");
        if (m != null && !m.isEmpty()) {
            if (m.length() > MAX_MATCH) {
                throw new IllegalArgumentException("match longer than " + MAX_MATCH);
            }
            try {
                match = Pattern.compile(m);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("match is not a regex");
            }
        }
        int waitS = (int) number(p.get("wait"), 0, MAX_WAIT_S, MAX_WAIT_S, "wait");
        int limit = (int) number(p.get("limit"), 1, MAX_LIMIT, 500, "limit");
        return new Query(since, chars, topics, match, waitS, limit);
    }

    private static long number(String v, long min, long max, long dflt, String name) {
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            long n = Long.parseLong(v.trim());
            if (n < min || n > max) {
                throw new IllegalArgumentException(name + " out of range");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number");
        }
    }

    boolean authorized(String header) {
        return header != null && MessageDigest.isEqual(secret, header.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static Response ok(List<Event> hits, long last, long next, boolean gap) {
        List<Object> lines = new ArrayList<>();
        for (Event e : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq());
            m.put("utc", Instant.ofEpochMilli(e.at()).toString());
            m.put("topic", e.topic());
            m.put("kind", e.kind());
            if (e.scenario() != null) {
                m.put("scenario", e.scenario());
            }
            m.put("map", e.map());
            m.put("chars", Arrays.stream(e.chars()).boxed().toList());
            m.put("line", e.line());
            lines.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("last", last);
        out.put("next", next);
        out.put("gap", gap);   // true: lines after since fell off the ring before this poll
        out.put("lines", lines);
        return new Response(200, Json.write(out));
    }

    private static Response error(int status, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return new Response(status, Json.write(m));
    }
}
