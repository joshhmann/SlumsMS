package soloMapling.ArtificialPlayer.BotConvo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.server.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 1.6.0 chat-model shadow run (ticket 60 follow-up, owner 2026-10-27; TEST only, logged only). Every player line the
 * current Decide model judges in {@link ConvoSense#read} is also sent to each shadow model (gliclass, OneJev, Jev...)
 * with the same three questions, and one JSONL row per line goes to
 * {@code data/autoqa/decide-shadow/<UTC day>.jsonl}: the line, its speaker as a hash, the current model's read and
 * latency, each shadow model's read and latency, and whether they agree. Nothing a shadow model says reaches a bot.
 *
 * <p>Off by default. On with {@code -Dslums.decide.shadow=gliclass,onejev} (names below, or any name with
 * {@code -Dslums.decide.shadow.<name>.url=...}). Never blocks the caller: {@link #offer} only puts the line on a
 * bounded queue ({@code slums.decide.shadow.queue}, 256) and drops it when the queue is full; one worker thread asks
 * the shadow models (in parallel per line, {@code slums.decide.shadow.timeoutMs} 2000 each) and writes the row.
 * A shadow answer later than the live deadline ({@code solomapling.chat.decideTimeoutMs}, 400) is logged, but scored
 * as production would use it: rules only.
 *
 * <p>Per model: {@code .url} (base URL, {@code /v1/systemone} is added), {@code .model} (wire model id),
 * {@code .keyFile} (a private file holding a bearer key, read at start, never logged). Built in:
 * <ul>
 *   <li>{@code gliclass}: http://192.168.0.17:8911, model decide</li>
 *   <li>{@code onejev}: http://192.168.0.17:8910, model OneJev1.6-0.8B</li>
 *   <li>{@code jev}: https://api.typesafe.ai, model jev-latest, key ~/.config/slumsms/jev.key (hosted TypeSafe)</li>
 *   <li>{@code gliner}: http://192.168.0.17:8900, model decide (the current model, as a sanity check)</li>
 * </ul>
 * Summary: {@code !decideshadow status} or {@link DecideShadowReport}. Replay of logged chat: {@link DecideShadowReplay}.
 *
 * <p>Live (owner 2026-10-27, ticket 108: chat statistics only): {@code -Dslums.decide.shadow.text=false} keeps the
 * line's length instead of its text, so the rows carry only the scores; such rows cannot be replayed.
 */
public final class DecideShadow {

    private static final Logger log = LoggerFactory.getLogger(DecideShadow.class);

    public static final String PROPERTY = "slums.decide.shadow";
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /** One shadow model: its log name, wire model id and how to reach it. */
    public record Model(String name, String modelId, ChatDecide.Transport transport) {
    }

    /** One line to judge, captured on the caller's thread (cheap: no I/O). */
    record Job(long at, String line, String speaker, ChatTriage.Result byRule, Map<String, ChatDecide.Answer> current,
               long currentMs, ConvoSense.Read currentRead, double intentMin, double toneMin, double conMin) {
    }

    private final List<Model> models;
    private final BlockingQueue<Job> queue;
    private final long timeoutMs;
    private final long deadlineMs;
    private final String source;
    private final String currentName;
    private final Consumer<Map<String, Object>> sink;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicInteger busy = new AtomicInteger();
    private volatile Thread worker;
    private volatile boolean keepText = true;

    DecideShadow(List<Model> models, int capacity, long timeoutMs, long deadlineMs, String source, String currentName,
                 Consumer<Map<String, Object>> sink) {
        this.models = List.copyOf(models);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.timeoutMs = timeoutMs;
        this.deadlineMs = deadlineMs;
        this.source = source;
        this.currentName = currentName;
        this.sink = sink;
    }

    private static volatile DecideShadow active;
    private static volatile boolean built;

    /** False: rows keep the line's length, never its text (live). */
    DecideShadow keepText(boolean keep) {
        keepText = keep;
        return this;
    }

    /** The running shadow, or null when off (the default). */
    public static DecideShadow get() {
        if (!built) {
            synchronized (DecideShadow.class) {
                if (!built) {
                    try {
                        active = fromProperties("live");
                        if (active != null) {
                            active.start();
                            log.info("Decide shadow on: {} -> {}", active.names(), dir(soloMapling.autoqa.AutoQa.dataDir()));
                        }
                    } catch (RuntimeException e) {
                        log.warn("Decide shadow off: {}", e.getMessage());
                        active = null;
                    }
                    built = true;
                }
            }
        }
        return active;
    }

    /** Auto-QA t-ds-1: the server's running shadow, or null; never builds one. */
    public static DecideShadow running() {
        return built ? active : null;
    }

    /** Whether rows keep the line's text (false on live: only its length). */
    public boolean keepsText() {
        return keepText;
    }

    /**
     * Auto-QA t-ds-1: runs the shadow in-process with a fake model (no network, the server's own shadow untouched),
     * once keeping the text and once with {@code text=false}, writes each row under {@code dir} the way the live sink
     * does, reads the file back and checks it: a row is written, with text it carries {@code text} and no {@code len},
     * without text it carries {@code len} and neither the {@code text} key nor the words. The scratch files are
     * removed. First line PASS or FAIL, then what was seen.
     */
    public static List<String> selfCheck(Path dir) {
        String line = "anyone know when the event starts";
        String answer = "{\"answers\":{\"convo_intent\":{\"choice\":\"talk\",\"confidence\":0.9},"
                + "\"convo_tone\":{\"choice\":\"neutral\",\"confidence\":0.9},"
                + "\"con\":{\"choice\":\"normal\",\"confidence\":0.9}}}";
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        try {
            Map<String, ChatDecide.Answer> current = ChatDecide.parse(answer, ConvoSense.ASKED).orElseThrow();
            ChatTriage.Result byRule = ChatTriage.rules(line);
            ConvoSense.Read read = ConvoSense.combine(line, byRule, current, 0.4, 0.8, 0.4);
            for (boolean keep : new boolean[]{true, false}) {
                Path sub = dir.resolve(keep ? "text" : "notext");
                DecideShadow shadow = new DecideShadow(List.of(new Model("fake", "decide", (body, ms) -> answer)), 4,
                        2000, 400, "autoqa", "gliner", row -> append(sub, row)).keepText(keep);
                long at = System.currentTimeMillis();
                Path file = sub.resolve(DAY.format(Instant.ofEpochMilli(at)) + ".jsonl");
                Files.deleteIfExists(file);
                append(sub, shadow.judge(new Job(at, line, "AutoQaOne", byRule, current, 1, read, 0.4, 0.8, 0.4)));
                List<String> rows = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
                Files.deleteIfExists(file);
                String tag = "text=" + keep;
                if (rows.size() != 1) {
                    bad.add(tag + ": " + rows.size() + " row(s) written, want 1");
                    continue;
                }
                Object parsed = Json.parse(rows.get(0));
                if (!(parsed instanceof Map<?, ?> row)) {
                    bad.add(tag + ": the row is not a JSON object");
                    continue;
                }
                Object shadows = row.get("shadows");
                boolean modelOk = shadows instanceof Map<?, ?> m && m.get("fake") instanceof Map<?, ?> f
                        && Boolean.TRUE.equals(f.get("ok"));
                String fields = "keys " + row.keySet();
                if (keep) {
                    if (!line.equals(row.get("text")) || row.containsKey("len")) {
                        bad.add(tag + ": want text and no len, got " + fields);
                    }
                } else {
                    Object len = row.get("len");
                    if (row.containsKey("text") || !(len instanceof Number n) || n.intValue() != line.length()
                            || rows.get(0).contains("event starts")) {
                        bad.add(tag + ": want len=" + line.length() + " and no text, got " + fields + " len=" + len);
                    }
                }
                if (!modelOk) {
                    bad.add(tag + ": the fake model's read is missing or not ok");
                }
                out.add(tag + ": 1 row, " + fields + (keep ? "" : ", len=" + row.get("len")));
            }
        } catch (IOException | RuntimeException e) {
            bad.add("self-check threw " + e);
        }
        DecideShadow live = running();
        out.add(live == null ? "the server's shadow is off (-D" + PROPERTY + " unset)"
                : "the server's shadow: " + live.counters() + ", text=" + live.keepsText());
        out.add(0, bad.isEmpty() ? "PASS decide shadow rows are written; text=false keeps len and no text"
                : "FAIL " + String.join("; ", bad));
        return out;
    }

    /** Installs this shadow as the server's (the replay tool, tests); null switches it off. */
    public static void install(DecideShadow shadow) {
        active = shadow;
        built = true;
    }

    /** The shadow named by {@code -Dslums.decide.shadow}, not started; null when the property is unset or blank. */
    static DecideShadow fromProperties(String source) {
        return fromProperties(source, null);
    }

    /** As {@link #fromProperties(String)}; {@code also} sees every row too (the replay tool's summary). */
    static DecideShadow fromProperties(String source, Consumer<Map<String, Object>> also) {
        String names = System.getProperty(PROPERTY, "").trim();
        if (names.isEmpty() || "false".equalsIgnoreCase(names)) {
            return null;
        }
        long timeoutMs = Long.getLong(PROPERTY + ".timeoutMs", 2000);
        List<Model> models = new ArrayList<>();
        for (String raw : names.split(",")) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                models.add(model(name, timeoutMs));
            }
        }
        if (models.isEmpty()) {
            return null;
        }
        Path dir = dir(soloMapling.autoqa.AutoQa.dataDir());
        return new DecideShadow(models, Integer.getInteger(PROPERTY + ".queue", 256), timeoutMs,
                Long.getLong("solomapling.chat.decideTimeoutMs", 400), source,
                System.getProperty(PROPERTY + ".current", "gliner"), row -> {
                    append(dir, row);
                    if (also != null) {
                        also.accept(row);
                    }
                }).keepText(!"false".equalsIgnoreCase(System.getProperty(PROPERTY + ".text", "true").trim()));
    }

    /** A model by name: the built-in defaults, each overridable by {@code slums.decide.shadow.<name>.url/model/keyFile}. */
    static Model model(String name, long timeoutMs) {
        String url, id, key = null;
        switch (name) {
            case "gliclass" -> { url = "http://192.168.0.17:8911"; id = "decide"; }
            case "onejev" -> { url = "http://192.168.0.17:8910"; id = "OneJev1.6-0.8B"; }
            case "jev" -> {
                url = "https://api.typesafe.ai";
                id = "jev-latest";
                key = System.getProperty("user.home") + "/.config/slumsms/jev.key";
            }
            case "gliner" -> { url = "http://192.168.0.17:8900"; id = "decide"; }
            default -> { url = null; id = "decide"; }
        }
        url = System.getProperty(PROPERTY + "." + name + ".url", url);
        id = System.getProperty(PROPERTY + "." + name + ".model", id);
        key = System.getProperty(PROPERTY + "." + name + ".keyFile", key);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("no url for shadow model " + name + " (-D" + PROPERTY + "." + name + ".url)");
        }
        return new Model(name, id, http(url.trim(), key == null || key.isBlank() ? null : readKey(Path.of(key.trim())),
                timeoutMs));
    }

    /** A bearer key from a private file: one line, not readable by group or others. Never logged. */
    static String readKey(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                throw new IllegalArgumentException("key file " + file + " must be chmod 600");
            }
            String key = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (key.isEmpty() || key.contains("\n")) {
                throw new IllegalArgumentException("key file " + file + " must hold one non-empty line");
            }
            return key;
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("key file " + file + " unreadable: " + e.getClass().getSimpleName());
        }
    }

    static ChatDecide.Transport http(String baseUrl, String bearer, long timeoutMs) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        URI endpoint = URI.create(base.endsWith("/v1/systemone") ? base : base + "/v1/systemone");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(200, timeoutMs))).build();
        return (body, ms) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(ms))
                    .header("Content-Type", "application/json");
            if (bearer != null) {
                b.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<String> response = client.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return response.body();
        };
    }

    // ── the caller's side: never blocks, never throws ──

    /**
     * Called by {@link ConvoSense#read} after the live decision is made. A no-op when the shadow is off; otherwise
     * queues the line, or drops it when the queue is full.
     */
    static void offer(String line, String speaker, ChatTriage.Result byRule, Map<String, ChatDecide.Answer> current,
                      long currentMs, ConvoSense.Read read, double intentMin, double toneMin, double conMin) {
        DecideShadow s = active;
        if (s == null && (built || System.getProperty(PROPERTY) == null)) {
            return;
        }
        try {
            s = s != null ? s : get();
            if (s != null) {
                s.submit(new Job(System.currentTimeMillis(), line, speaker, byRule, current, currentMs, read,
                        intentMin, toneMin, conMin));
            }
        } catch (RuntimeException e) {
            log.debug("decide shadow offer failed", e);
        }
    }

    /** Queues one job; false (and counted) when the queue is full. */
    boolean submit(Job job) {
        offered.incrementAndGet();
        if (!queue.offer(job)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /** Starts the worker (a daemon thread). */
    synchronized DecideShadow start() {
        if (worker == null) {
            worker = Thread.ofPlatform().daemon().name("decide-shadow").start(this::loop);
        }
        return this;
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            busy.incrementAndGet();
            try {
                sink.accept(judge(job));
                written.incrementAndGet();
            } catch (RuntimeException e) {
                log.debug("decide shadow row failed", e);
            } finally {
                busy.decrementAndGet();
            }
        }
    }

    /** Waits until the queue is empty and the worker idle (the replay tool), at most {@code maxMs}. */
    public boolean drain(long maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            if (queue.isEmpty() && busy.get() == 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    public List<String> names() {
        return models.stream().map(Model::name).toList();
    }

    /** Since start: lines offered, dropped (queue full), rows written, queued now. */
    public Map<String, Object> counters() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("models", names());
        m.put("offered", offered.get());
        m.put("dropped", dropped.get());
        m.put("written", written.get());
        m.put("queued", queue.size());
        return m;
    }

    // ── the worker's side ──

    private record Outcome(Optional<Map<String, ChatDecide.Answer>> answers, long ms, String error, boolean timeout) {
    }

    /** Asks every shadow model about one line (in parallel) and builds its row. */
    Map<String, Object> judge(Job job) {
        Map<String, CompletableFuture<Outcome>> pending = new LinkedHashMap<>();
        for (Model m : models) {
            pending.put(m.name(), CompletableFuture.supplyAsync(() -> ask(m, job.line()), calls)
                    .completeOnTimeout(new Outcome(Optional.empty(), timeoutMs, "timeout", true), timeoutMs + 250,
                            TimeUnit.MILLISECONDS));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", job.at());
        row.put("time", Instant.ofEpochMilli(job.at()).toString());
        row.put("src", source);
        row.put("speaker", speakerHash(job.speaker()));
        if (keepText) {
            row.put("text", job.line());
        } else {
            row.put("len", job.line() == null ? 0 : job.line().length());
        }
        Map<String, Object> current = read(job.currentRead(), job.current());
        current.put("model", currentName);
        current.put("ok", job.current() != null && !job.current().isEmpty());
        current.put("ms", job.currentMs());
        row.put("current", current);
        Map<String, Object> shadows = new LinkedHashMap<>();
        Map<String, Object> agree = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<Outcome>> e : pending.entrySet()) {
            Outcome o = e.getValue().join();
            Map<String, ChatDecide.Answer> answers = o.answers().orElse(null);
            boolean late = o.ms() > deadlineMs;
            // scored as production would use it: a late or failed answer means rules only
            ConvoSense.Read r = ConvoSense.combine(job.line(), job.byRule(),
                    answers == null || late ? Map.of() : answers, job.intentMin(), job.toneMin(), job.conMin());
            Map<String, Object> s = read(r, answers);
            s.put("ok", answers != null);
            s.put("ms", o.ms());
            s.put("late", late);
            s.put("timeout", o.timeout());
            if (o.error() != null) {
                s.put("error", o.error());
            }
            boolean intentSame = same(current.get("intent"), s.get("intent"));
            boolean all = intentSame && same(current.get("tone"), s.get("tone")) && same(current.get("con"), s.get("con"));
            s.put("agreeIntent", intentSame);
            s.put("agree", all);
            shadows.put(e.getKey(), s);
            agree.put(e.getKey(), all);
        }
        row.put("shadows", shadows);
        row.put("agree", agree);
        return row;
    }

    private Outcome ask(Model m, String line) {
        long started = System.nanoTime();
        try {
            String body = m.transport().post(Json.write(ChatDecide.request(line, ConvoSense.ASKED, m.modelId())), timeoutMs);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Optional<Map<String, ChatDecide.Answer>> answers = ChatDecide.parse(body, ConvoSense.ASKED);
            return new Outcome(answers, ms, answers.isEmpty() ? "schema" : null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Optional.empty(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "interrupted", false);
        } catch (Exception e) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            boolean timeout = e instanceof HttpTimeoutException || ms >= timeoutMs;
            return new Outcome(Optional.empty(), ms, timeout ? "timeout" : e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), timeout);
        }
    }

    /** The combined read (what the bot would do) plus the model's own raw answers. */
    static Map<String, Object> read(ConvoSense.Read r, Map<String, ChatDecide.Answer> answers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", r.intent().label());
        m.put("intentSource", r.intentSource().name().toLowerCase(Locale.ROOT));
        m.put("tone", r.tone().name().toLowerCase(Locale.ROOT));
        m.put("con", r.triage().con().name().toLowerCase(Locale.ROOT));
        Map<String, Object> raw = new LinkedHashMap<>();
        if (answers != null) {
            for (ChatDecide.Question q : ConvoSense.ASKED) {
                ChatDecide.Answer a = answers.get(q.name());
                if (a != null) {
                    raw.put(q.name(), a.choice() + "@" + String.format(Locale.ROOT, "%.2f", a.confidence()));
                }
            }
        }
        m.put("raw", raw);
        return m;
    }

    private static boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** The speaker as a short stable hash (no names in the log). */
    static String speakerHash(String speaker) {
        if (speaker == null || speaker.isBlank()) {
            return "-";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(("decide-shadow|" + speaker.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 5);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "-";
        }
    }

    // ── the file ──

    /** {@code <autoqa>/decide-shadow}. */
    public static Path dir(Path autoqaDir) {
        return autoqaDir.resolve("decide-shadow");
    }

    /** {@code <autoqa>/decide-shadow/<UTC day>.jsonl}. */
    public static Path file(Path autoqaDir, long atMs) {
        return dir(autoqaDir).resolve(DAY.format(Instant.ofEpochMilli(atMs)) + ".jsonl");
    }

    static void append(Path dir, Map<String, Object> row) {
        long at = row.get("at") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        Path f = dir.resolve(DAY.format(Instant.ofEpochMilli(at)) + ".jsonl");
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Files.writeString(f, Json.write(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("decide shadow row not written", e);
        }
    }
}
