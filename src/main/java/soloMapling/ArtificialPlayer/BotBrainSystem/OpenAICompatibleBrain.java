package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A brain backed by any server that speaks OpenAI's {@code POST /chat/completions}: OpenRouter, Ollama,
 * llama.cpp server, LM Studio, vLLM. The server is chosen entirely by config (base URL, key, model, extra body).
 *
 * <p>Every call runs on its own virtual thread and is bounded three ways, so a bot never waits on the model:
 * a global cap on calls in flight (match the server's parallel slots), a minimum interval per player, and one
 * deadline that covers both the wait for a free slot and the request itself. Whatever goes wrong, the
 * future completes with empty and the caller uses its scripted line.
 */
public final class OpenAICompatibleBrain implements ChatRouter.Slotted {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleBrain.class);

    /** Everything the brain is configured with; built from {@code config.yaml} by {@link BotBrains}. */
    public record Settings(String baseUrl, String apiKey, String model, int maxTokens, double temperature,
                           int timeoutMs, int maxConcurrent, int playerMinIntervalMs,
                           Map<String, String> extraBody, Map<String, String> extraHeaders, String notesModel) {

        public Settings(String baseUrl, String apiKey, String model, int maxTokens, double temperature, int timeoutMs,
                        int maxConcurrent, int playerMinIntervalMs, Map<String, String> extraBody,
                        Map<String, String> extraHeaders) {
            this(baseUrl, apiKey, model, maxTokens, temperature, timeoutMs, maxConcurrent, playerMinIntervalMs,
                    extraBody, extraHeaders, "");
        }
    }

    /** How long a note-writing call may take: not chat, so nobody is waiting on it. */
    private static final long NOTES_TIMEOUT_MS = 30_000;
    private static final int NOTES_MAX_TOKENS = 120;
    /** Ticket 59: a bot thread (2-4 lines in one call). */
    private static final int THREAD_MAX_TOKENS = 140;

    private final Settings settings;
    private final URI endpoint;
    private final HttpClient http;
    private final Semaphore slots;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Integer, Long> lastCallByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Json.Raw> extraBody = new LinkedHashMap<>();

    private final AtomicLong answered = new AtomicLong();
    private final AtomicLong fellBack = new AtomicLong();
    private final AtomicLong voiceRejected = new AtomicLong();
    private final AtomicLong totalAnswerMs = new AtomicLong();
    private volatile String lastFailure = "none";

    public OpenAICompatibleBrain(Settings settings) {
        this.settings = settings;
        String base = settings.baseUrl().endsWith("/") ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1)
                : settings.baseUrl();
        this.endpoint = URI.create(base + "/chat/completions");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMs())).build();
        this.slots = new Semaphore(Math.max(1, settings.maxConcurrent()), true);
        for (Map.Entry<String, String> entry : settings.extraBody().entrySet()) {
            if (Json.isValid(entry.getValue())) {
                extraBody.put(entry.getKey(), new Json.Raw(entry.getValue()));
            } else {
                log.warn("BOT_LLM_EXTRA_BODY.{} is not valid JSON and is ignored: {}", entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
        long now = System.currentTimeMillis();
        Long last = lastCallByPlayer.get(playerId);
        if (last != null && now - last < settings.playerMinIntervalMs()) {
            return fallback("rate limited");
        }
        lastCallByPlayer.put(playerId, now);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.timeoutMs());
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        calls.execute(() -> result.complete(call(conversation, deadline, settings.model(), settings.maxTokens(),
                ReplyCleaner::chat, true)));
        return result;
    }

    /**
     * Ticket 59: the contract reply. The action tag is read and stripped first, then the line goes through the same
     * voice check and cleaning as {@link #complete}. Same rate limit, slots and deadline.
     */
    @Override
    public CompletableFuture<Optional<soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply>> reply(
            Priority priority, int playerId, List<Turn> conversation) {
        long now = System.currentTimeMillis();
        Long last = lastCallByPlayer.get(playerId);
        if (playerId > 0 && last != null && now - last < settings.playerMinIntervalMs()) {
            fail("rate limited");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (playerId > 0) {
            lastCallByPlayer.put(playerId, now);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.timeoutMs());
        CompletableFuture<Optional<soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply>> result =
                new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply> parsed =
                new java.util.concurrent.atomic.AtomicReference<>();
        if (priority == Priority.THREAD) {
            // a whole bot-to-bot exchange: several lines, each checked by BotThreads
            calls.execute(() -> result.complete(call(conversation, deadline, settings.model(),
                    Math.max(settings.maxTokens(), THREAD_MAX_TOKENS), ReplyCleaner::lines, false)
                    .map(soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply::plain)));
            return result;
        }
        calls.execute(() -> {
            Optional<String> line = call(conversation, deadline, settings.model(), settings.maxTokens(), replyTemperature(),
                    raw -> {
                soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply r =
                        soloMapling.ArtificialPlayer.BotConvo.ConvoContract.parse(raw);
                parsed.set(r);
                // ticket 59's quality loop: a phrase rated bad ("dontSay") sends the reply to the bank
                return r == null ? Optional.empty() : ReplyCleaner.chat(r.text())
                        .filter(t -> soloMapling.ArtificialPlayer.BotConvo.ConvoTune.violation(t) == null);
            }, true);
            result.complete(line.map(text -> parsed.get() == null
                    ? soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply.plain(text) : parsed.get().withText(text)));
        });
        return result;
    }

    /** How many slots are free right now (ticket 59's router reads it). */
    @Override
    public int freeSlots() {
        return slots.availablePermits();
    }

    @Override
    public int maxSlots() {
        return Math.max(1, settings.maxConcurrent());
    }

    /** The last call's token use from the response's {@code usage}, or -1 (ticket 59's budget reads it). */
    public long[] lastUsage() {
        return lastUsage.get();
    }

    @Override
    public CompletableFuture<Optional<String>> notes(List<Turn> conversation) {
        // No per-player limit: one call per finished conversation. The notes model, when set, can be a stronger one.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NOTES_TIMEOUT_MS);
        String model = settings.notesModel() == null || settings.notesModel().isBlank() ? settings.model()
                : settings.notesModel();
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        calls.execute(() -> result.complete(call(conversation, deadline, model, NOTES_MAX_TOKENS, ReplyCleaner::notes, false)));
        return result;
    }

    private Optional<String> call(List<Turn> conversation, long deadline, String model, int maxTokens,
                                  java.util.function.Function<String, Optional<String>> cleaner, boolean chat) {
        return call(conversation, deadline, model, maxTokens, settings.temperature(), cleaner, chat);
    }

    private Optional<String> call(List<Turn> conversation, long deadline, String model, int maxTokens, double temperature,
                                  java.util.function.Function<String, Optional<String>> cleaner, boolean chat) {
        boolean acquired = false;
        long started = System.nanoTime();
        try {
            acquired = slots.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!acquired) {
                return fail("no free slot before the deadline");
            }
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMs <= 0) {
                return fail("deadline passed while queued");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(leftMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(conversation, model, maxTokens, temperature)));
            String key = apiKey();
            if (!key.isEmpty()) {
                request.header("Authorization", "Bearer " + key);
            }
            settings.extraHeaders().forEach(request::header);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return fail("HTTP " + response.statusCode());
            }
            lastUsage.set(usage(response.body()));
            String raw = replyText(response.body());
            Optional<String> line = cleaner.apply(raw);
            if (line.isEmpty()) {
                String voice = chat ? ReplyCleaner.voiceProblem(raw) : null;
                if (voice != null) {
                    voiceRejected.incrementAndGet();
                    return fail("voice check: " + voice);
                }
                return fail("empty after cleaning");
            }
            answered.incrementAndGet();
            totalAnswerMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted");
        } catch (Exception e) {
            return fail(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }

    String requestBody(List<Turn> conversation) {
        return requestBody(conversation, settings.model(), settings.maxTokens());
    }

    /**
     * Owner report 2026-10-22: a reply to a line is one short on-topic answer, so it runs cooler than a thread (which
     * wants variety): the configured temperature, at most {@code -Dsolomapling.chat.replyTemperatureMax} (0.7).
     */
    double replyTemperature() {
        double max = 0.7;
        try {
            max = Double.parseDouble(System.getProperty("solomapling.chat.replyTemperatureMax", "0.7"));
        } catch (NumberFormatException ignored) {
            // keep 0.7
        }
        return Math.min(settings.temperature(), max);
    }

    String requestBody(List<Turn> conversation, String model, int maxTokens) {
        return requestBody(conversation, model, maxTokens, settings.temperature());
    }

    String requestBody(List<Turn> conversation, String model, int maxTokens, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!model.isEmpty()) {
            body.put("model", model);
        }
        List<Object> messages = new ArrayList<>();
        for (Turn turn : conversation) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", turn.role().wire);
            message.put("content", turn.content());
            messages.add(message);
        }
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);
        body.putAll(extraBody);
        return Json.write(body);
    }

    private final java.util.concurrent.atomic.AtomicReference<long[]> lastUsage =
            new java.util.concurrent.atomic.AtomicReference<>(new long[]{-1, -1});

    /** {prompt_tokens, completion_tokens} from {@code usage}, -1 each when absent. */
    static long[] usage(String responseBody) {
        try {
            if (Json.parse(responseBody) instanceof Map<?, ?> root && root.get("usage") instanceof Map<?, ?> u) {
                long in = u.get("prompt_tokens") instanceof Number n ? n.longValue() : -1;
                long out = u.get("completion_tokens") instanceof Number n ? n.longValue() : -1;
                return new long[]{in, out};
            }
        } catch (RuntimeException ignored) {
            // no usage block
        }
        return new long[]{-1, -1};
    }

    /** Ticket 59's bank generator: the reply text of a chat completion response body. */
    public static String replyTextOf(String responseBody) {
        return replyText(responseBody);
    }

    /** Ticket 59's bank generator and budget: {prompt_tokens, completion_tokens}, -1 when absent. */
    public static long[] usageOf(String responseBody) {
        return usage(responseBody);
    }

    /** {@code choices[0].message.content}, or null when the response has no such text. */
    static String replyText(String responseBody) {
        if (!(Json.parse(responseBody) instanceof Map<?, ?> root)
                || !(root.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)) {
            return null;
        }
        return content;
    }

    private String apiKey() {
        if (!settings.apiKey().isEmpty()) {
            return settings.apiKey();
        }
        String env = System.getenv("SOLOMAPLING_LLM_API_KEY");
        return env == null ? "" : env.trim();
    }

    private CompletableFuture<Optional<String>> fallback(String why) {
        return CompletableFuture.completedFuture(fail(why));
    }

    private Optional<String> fail(String why) {
        fellBack.incrementAndGet();
        lastFailure = why;
        log.debug("Bot LLM fell back to scripted dialogue: {}", why);
        return Optional.empty();
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public String describe() {
        long ok = answered.get();
        return String.format("%s model=%s slots=%d/%d answered=%d (avg %dms) fell back=%d voice rejects=%d (last: %s)",
                endpoint, settings.model().isEmpty() ? "(server default)" : settings.model(),
                slots.availablePermits(), Math.max(1, settings.maxConcurrent()), ok,
                ok == 0 ? 0 : totalAnswerMs.get() / ok, fellBack.get(), voiceRejected.get(), lastFailure);
    }
}
