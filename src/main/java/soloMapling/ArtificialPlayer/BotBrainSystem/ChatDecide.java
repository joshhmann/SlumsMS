package soloMapling.ArtificialPlayer.BotBrainSystem;

import config.ServerConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The fast "system one" for chat (ticket 36): small few-label questions to GLiNER2.5-Decide
 * ({@code BOT_DECIDE_URL} + {@code /v1/systemone}) about one player line. Several questions travel in one call.
 *
 * <p>Bounded like the LLM brain, so a bot never waits on it: each call runs on a virtual thread with a tight
 * deadline, answers are cached per normalised line, calls are rate limited, and on a timeout, an error, a full
 * budget or no endpoint the answer is empty - the caller then uses its rules. Never "pick one of N players": that
 * stays a rule ({@code BotAddressee}).
 *
 * <p>System properties (no config.yaml keys): {@code solomapling.chat.decide} (default true when
 * {@code BOT_DECIDE_URL} is set), {@code solomapling.chat.decideTimeoutMs} (400),
 * {@code solomapling.chat.decidePerSecond} (8).
 */
public final class ChatDecide {

    private static final Logger log = LoggerFactory.getLogger(ChatDecide.class);

    /** One question: a name, the instruction, and its labels with their descriptions (2-6 labels). */
    public record Question(String name, String instructions, Map<String, String> labels) {
    }

    /** The model's answer to one question. */
    public record Answer(String choice, double confidence) {
    }

    /** Sends a request body, returns the response body; throws on HTTP or network trouble. */
    public interface Transport {
        String post(String body, long timeoutMs) throws Exception;
    }

    static final int CACHE_SIZE = 2048;
    static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(30);

    private record Cached(Map<String, Answer> answers, long at) {
    }

    private final Transport transport;
    private final long timeoutMs;
    private final int perSecond;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Cached> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long windowStart;
    private int windowCalls;

    private final String model;
    private volatile String endpoint = "decide";

    /** Names this client in the per-minute endpoint line ("intent+con@host:port"). */
    public ChatDecide named(String name) {
        this.endpoint = name;
        return this;
    }

    public String endpointName() {
        return endpoint;
    }

    public ChatDecide(Transport transport, long timeoutMs, int perSecond) {
        this(transport, timeoutMs, perSecond, "decide");
    }

    public ChatDecide(Transport transport, long timeoutMs, int perSecond, String model) {
        this.model = model == null || model.isBlank() ? "decide" : model.trim();
        this.transport = transport;
        this.timeoutMs = timeoutMs;
        this.perSecond = perSecond;
    }

    private static volatile ChatDecide shared;
    private static volatile boolean sharedBuilt;

    /** The server's client, or null when no endpoint is configured or it is switched off. */
    public static ChatDecide get() {
        if (!sharedBuilt) {
            synchronized (ChatDecide.class) {
                if (!sharedBuilt) {
                    shared = fromConfig(YamlConfig.config == null ? null : YamlConfig.config.server);
                    sharedBuilt = true;
                }
            }
        }
        return shared;
    }

    private static volatile ChatDecide sharedTone;
    private static volatile boolean sharedToneBuilt;

    /**
     * The tone-only client ({@code BOT_DECIDE_TONE_URL}, 1.6.1 split) with the same timeout and per-second budget as
     * {@link #get()} and its own cache, or null when unset (then one call carries every question).
     */
    public static ChatDecide getTone() {
        if (!sharedToneBuilt) {
            synchronized (ChatDecide.class) {
                if (!sharedToneBuilt) {
                    sharedTone = toneFromConfig(YamlConfig.config == null ? null : YamlConfig.config.server);
                    sharedToneBuilt = true;
                }
            }
        }
        return sharedTone;
    }

    /** Test seam: a stand-in tone client (null = none). */
    public static void useToneForTest(ChatDecide client) {
        sharedTone = client;
        sharedToneBuilt = true;
    }

    static ChatDecide toneFromConfig(ServerConfig server) {
        if (server == null || server.BOT_DECIDE_TONE_URL == null || server.BOT_DECIDE_TONE_URL.isBlank()
                || "false".equalsIgnoreCase(System.getProperty("solomapling.chat.decide", "true"))) {
            return null;
        }
        return forUrl(server.BOT_DECIDE_TONE_URL.trim(), Long.getLong("solomapling.chat.decideTimeoutMs", 400),
                Integer.getInteger("solomapling.chat.decidePerSecond", 8), server.BOT_DECIDE_TONE_MODEL)
                .named("tone@" + hostPort(server.BOT_DECIDE_TONE_URL));
    }

    /** Test seam: a stand-in client (null = none). */
    public static void useForTest(ChatDecide client) {
        shared = client;
        sharedBuilt = true;
    }

    static ChatDecide fromConfig(ServerConfig server) {
        if (server == null || server.BOT_DECIDE_URL == null || server.BOT_DECIDE_URL.isBlank()
                || "false".equalsIgnoreCase(System.getProperty("solomapling.chat.decide", "true"))) {
            return null;
        }
        return forUrl(server.BOT_DECIDE_URL.trim(), Long.getLong("solomapling.chat.decideTimeoutMs", 400),
                Integer.getInteger("solomapling.chat.decidePerSecond", 8), server.BOT_DECIDE_MODEL)
                .named((toneSplit(server) ? "intent+con@" : "all@") + hostPort(server.BOT_DECIDE_URL));
    }

    private static boolean toneSplit(ServerConfig server) {
        return server.BOT_DECIDE_TONE_URL != null && !server.BOT_DECIDE_TONE_URL.isBlank();
    }

    private static String hostPort(String url) {
        try {
            URI u = URI.create(url.trim());
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** A client for a Decide base URL (the evaluation tool uses this directly). */
    public static ChatDecide forUrl(String baseUrl, long timeoutMs, int perSecond) {
        return forUrl(baseUrl, timeoutMs, perSecond, "decide");
    }

    /** As above, sending this wire model id with every request. */
    public static ChatDecide forUrl(String baseUrl, long timeoutMs, int perSecond, String model) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        URI endpoint = URI.create(base + "/v1/systemone");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(200, timeoutMs))).build();
        return new ChatDecide((body, ms) -> {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(ms))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return response.body();
        }, timeoutMs, perSecond, model);
    }

    /**
     * Asks these questions about one line. Completes within the deadline with the answers, or with empty on any
     * failure; never exceptionally.
     */
    public CompletableFuture<Optional<Map<String, Answer>>> ask(String line, List<Question> questions) {
        String key = cacheKey(line, questions);
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && now - hit.at() <= CACHE_TTL_MS) {
                ChatStats.decideCacheHit();
                return CompletableFuture.completedFuture(Optional.of(hit.answers()));
            }
            if (!takeBudget(now)) {
                ChatStats.decideSkipped();
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }
        CompletableFuture<Optional<Map<String, Answer>>> result = new CompletableFuture<>();
        calls.execute(() -> {
            long started = System.nanoTime();
            Optional<Map<String, Answer>> answers;
            ChatStats.Outcome outcome;
            try {
                answers = parse(transport.post(Json.write(request(line, questions, model)), timeoutMs), questions);
                outcome = answers.isPresent() ? ChatStats.Outcome.OK : ChatStats.Outcome.EMPTY;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                answers = Optional.empty();
                outcome = ChatStats.Outcome.ERROR;
            } catch (Exception e) {
                log.debug("Decide unavailable: {}", e.toString());
                answers = Optional.empty();
                outcome = e instanceof java.net.http.HttpTimeoutException ? ChatStats.Outcome.TIMEOUT : ChatStats.Outcome.ERROR;
            }
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            ChatStats.decideCall(took, answers.isPresent());
            ChatStats.endpointCall(endpoint, took, outcome, result.isDone());
            answers.ifPresent(a -> {
                synchronized (cache) {
                    cache.put(key, new Cached(a, System.currentTimeMillis()));
                }
            });
            result.complete(answers);
        });
        return result.completeOnTimeout(Optional.empty(), timeoutMs + 50, TimeUnit.MILLISECONDS);
    }

    /** The wire model id this client sends. */
    public String model() {
        return model;
    }

    /** As {@link #ask}, waiting for the answer on the caller's thread (never longer than the deadline). */
    public Optional<Map<String, Answer>> askNow(String line, List<Question> questions) {
        try {
            return ask(line, questions).get(timeoutMs + 100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean takeBudget(long now) {
        if (now - windowStart >= 1000) {
            windowStart = now;
            windowCalls = 0;
        }
        if (windowCalls >= perSecond) {
            return false;
        }
        windowCalls++;
        return true;
    }

    static Map<String, Object> request(String line, List<Question> questions) {
        return request(line, questions, "decide");
    }

    /** The request body with another wire model id (OneJev and Jev need their own; the shadow run uses this). */
    public static Map<String, Object> request(String line, List<Question> questions, String model) {
        Map<String, Object> qs = new LinkedHashMap<>();
        for (Question q : questions) {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("type", "choice");
            question.put("instructions", q.instructions());
            question.put("criteria", new LinkedHashMap<>(q.labels()));
            qs.put(q.name(), question);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("state", "A MapleStory player typed this to you in chat: " + clean(line));
        body.put("questions", qs);
        return body;
    }

    public static Optional<Map<String, Answer>> parse(String responseBody, List<Question> questions) {
        Object parsed;
        try {
            parsed = responseBody == null ? null : Json.parse(responseBody);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("answers") instanceof Map<?, ?> answers)) {
            return Optional.empty();
        }
        Map<String, Answer> out = new LinkedHashMap<>();
        for (Question q : questions) {
            if (answers.get(q.name()) instanceof Map<?, ?> a && a.get("choice") instanceof String choice
                    && a.get("confidence") instanceof Number confidence && q.labels().containsKey(choice)) {
                out.put(q.name(), new Answer(choice, confidence.doubleValue()));
            }
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(Map.copyOf(out));
    }

    /** The line as the cache sees it: lower case, letters, digits and question marks, single spaces. */
    static String normalise(String line) {
        return line == null ? "" : line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9? ]", " ").replaceAll("\\s+", " ")
                .trim();
    }

    private static String cacheKey(String line, List<Question> questions) {
        StringBuilder key = new StringBuilder(normalise(line));
        for (Question q : questions) {
            key.append('|').append(q.name());
        }
        return key.toString();
    }

    /** The decision server rejects some characters (a ")" is a 400); chat is user text. */
    static String clean(String text) {
        return text == null ? "" : text.replaceAll("[^A-Za-z0-9 ,.'!?%-]", " ").replaceAll("\\s+", " ").trim();
    }
}
