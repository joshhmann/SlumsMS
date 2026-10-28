package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What answered each player line a bot heard (tickets 36 and 37): the model, a scripted line, an emote, nothing, or
 * a brush-off for a flagged con, and how long it took from the player's line to the bot's. One INFO line per reply
 * so the live log can tell a Qwen reply from a scripted one, one summary line per hour, and a snapshot for the
 * dashboard exporter (ticket 35). Also counts the fast decision model's calls.
 *
 * <p>Tunable with system properties only: {@code -Dsolomapling.chat.logReplies=false} drops the per-reply line
 * (the hourly summary stays).
 */
public final class ChatStats {

    private static final Logger log = LoggerFactory.getLogger(ChatStats.class);

    /** How a line was answered. */
    public enum Tier { LLM, SCRIPTED, EMOTE, IGNORED, FLAGGED }

    static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);

    private static final class Counter {
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalMs = new AtomicLong();
        final AtomicLong maxMs = new AtomicLong();

        void add(long ms) {
            count.incrementAndGet();
            totalMs.addAndGet(Math.max(0, ms));
            maxMs.accumulateAndGet(ms, Math::max);
        }

        Map<String, Object> json() {
            long n = count.get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", n);
            m.put("avgMs", n == 0 ? 0 : totalMs.get() / n);
            m.put("maxMs", maxMs.get());
            return m;
        }

        void reset() {
            count.set(0);
            totalMs.set(0);
            maxMs.set(0);
        }
    }

    private static final Map<Tier, Counter> total = new EnumMap<>(Tier.class);
    private static final Map<Tier, Counter> hour = new EnumMap<>(Tier.class);
    private static final Map<String, AtomicLong> flags = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> actions = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong decideCalls = new AtomicLong();
    private static final AtomicLong decideCacheHits = new AtomicLong();
    private static final AtomicLong decideFailures = new AtomicLong();
    private static final AtomicLong decideSkipped = new AtomicLong();
    private static final AtomicLong decideTotalMs = new AtomicLong();
    private static volatile long hourStartedAt = System.currentTimeMillis();
    private static volatile Map<String, Object> lastHour = Map.of();

    static {
        for (Tier tier : Tier.values()) {
            total.put(tier, new Counter());
            hour.put(tier, new Counter());
        }
    }

    private ChatStats() {
    }

    /**
     * One player line answered.
     *
     * @param detail a few words for the log line (the intent, the flag, why it fell back); may be null
     */
    public static void reply(Tier tier, long latencyMs, String botName, String detail) {
        total.get(tier).add(latencyMs);
        hour.get(tier).add(latencyMs);
        if (!"false".equalsIgnoreCase(System.getProperty("solomapling.chat.logReplies", "true"))) {
            log.info("Bot chat reply tier={} ms={} bot={}{}", tier, latencyMs, botName,
                    detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
        }
        maybeSummarize(System.currentTimeMillis());
    }

    /** A line flagged as a con, by kind (scam, manipulation, harassment, rwt). */
    public static void flagged(String kind) {
        flags.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
    }

    /** A chat action and how it went ("party:accepted", "trade:declined"). */
    public static void action(String what) {
        actions.computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
    }

    /** How one call to a Decide endpoint ended (1.6.1 chat-split provenance). */
    public enum Outcome { OK, TIMEOUT, ERROR, EMPTY }

    /** Per-endpoint counters, rolled into one INFO line a minute while there were calls. */
    public static final class Endpoint {
        final String name;
        final AtomicLong calls = new AtomicLong(), ok = new AtomicLong(), timeout = new AtomicLong(),
                error = new AtomicLong(), empty = new AtomicLong(), late = new AtomicLong(), totalMs = new AtomicLong();
        long mCalls, mOk, mTimeout, mError, mEmpty, mLate, mMs;

        Endpoint(String name) {
            this.name = name;
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            long n = calls.get();
            m.put("calls", n);
            m.put("ok", ok.get());
            m.put("timeout", timeout.get());
            m.put("error", error.get());
            m.put("empty", empty.get());
            m.put("late", late.get());
            m.put("avgMs", n == 0 ? 0 : totalMs.get() / n);
            return m;
        }
    }

    private static final Map<String, Endpoint> endpoints = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> reads = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long minuteStartedAt = System.currentTimeMillis();
    static final long MINUTE_MS = TimeUnit.MINUTES.toMillis(1);

    /** One finished call to the named endpoint ("intent+con@host:port"). {@code late}: the caller had given up. */
    static void endpointCall(String name, long ms, Outcome outcome, boolean late) {
        Endpoint e = endpoints.computeIfAbsent(name, Endpoint::new);
        e.calls.incrementAndGet();
        e.totalMs.addAndGet(Math.max(0, ms));
        switch (outcome) {
            case OK -> e.ok.incrementAndGet();
            case TIMEOUT -> e.timeout.incrementAndGet();
            case ERROR -> e.error.incrementAndGet();
            case EMPTY -> e.empty.incrementAndGet();
        }
        if (late) {
            e.late.incrementAndGet();
        }
        maybeLogEndpoints(System.currentTimeMillis());
    }

    /** Who decided one convo read: {@code field} intent/tone/con, {@code source} decide/rule/default. */
    public static void read(String field, String source) {
        reads.computeIfAbsent(field + ":" + source.toLowerCase(java.util.Locale.ROOT), k -> new AtomicLong()).incrementAndGet();
    }

    /** One compact line a minute (only when an endpoint was called): calls, ok, timeout, error, empty, late, avg ms. */
    static void maybeLogEndpoints(long now) {
        if (now - minuteStartedAt < MINUTE_MS) {
            return;
        }
        synchronized (endpoints) {
            if (now - minuteStartedAt < MINUTE_MS) {
                return;
            }
            minuteStartedAt = now;
            StringBuilder line = new StringBuilder();
            for (Endpoint e : new java.util.TreeMap<>(endpoints).values()) {
                long calls = e.calls.get(), ok = e.ok.get(), to = e.timeout.get(), er = e.error.get(),
                        em = e.empty.get(), la = e.late.get(), ms = e.totalMs.get();
                long dc = calls - e.mCalls;
                if (dc > 0) {
                    line.append(" [").append(e.name).append(" calls=").append(dc).append(" ok=").append(ok - e.mOk)
                            .append(" timeout=").append(to - e.mTimeout).append(" error=").append(er - e.mError)
                            .append(" empty=").append(em - e.mEmpty).append(" late=").append(la - e.mLate)
                            .append(" avg=").append((ms - e.mMs) / dc).append("ms]");
                }
                e.mCalls = calls; e.mOk = ok; e.mTimeout = to; e.mError = er; e.mEmpty = em; e.mLate = la; e.mMs = ms;
            }
            if (line.length() > 0) {
                log.info("Decide endpoints last minute:{} reads(total)={}", line, counts(reads));
            }
        }
    }

    /** Per-endpoint totals since start (also in {@link #snapshot()} as {@code decide.endpoints}). */
    public static Map<String, Map<String, Object>> endpointTotals() {
        Map<String, Map<String, Object>> m = new java.util.TreeMap<>();
        endpoints.forEach((k, v) -> m.put(k, v.json()));
        return m;
    }

    /** Convo reads by field and source since start, e.g. {@code tone:decide=12}. */
    public static Map<String, Long> readTotals() {
        return counts(reads);
    }

    static void decideCall(long ms, boolean ok) {
        decideCalls.incrementAndGet();
        decideTotalMs.addAndGet(ms);
        if (!ok) {
            decideFailures.incrementAndGet();
        }
    }

    private static final AtomicLong cloudCalls = new AtomicLong();
    private static final AtomicLong cloudFailures = new AtomicLong();
    private static final AtomicLong cloudTotalMs = new AtomicLong();

    /** Ticket 59: a cloud overflow call (router). */
    static void cloudCall(long ms, boolean ok) {
        cloudCalls.incrementAndGet();
        cloudTotalMs.addAndGet(ms);
        if (!ok) {
            cloudFailures.incrementAndGet();
        }
    }

    static void decideCacheHit() {
        decideCacheHits.incrementAndGet();
    }

    static void decideSkipped() {
        decideSkipped.incrementAndGet();
    }

    /** Logs and rolls the hourly counts once an hour has passed (checked on every reply and by the sweeper). */
    public static void maybeSummarize(long now) {
        if (now - hourStartedAt < HOUR_MS) {
            return;
        }
        synchronized (ChatStats.class) {
            if (now - hourStartedAt < HOUR_MS) {
                return;
            }
            Map<String, Object> summary = tiers(hour);
            StringBuilder line = new StringBuilder("Bot chat last hour:");
            for (Tier tier : Tier.values()) {
                Counter c = hour.get(tier);
                long n = c.count.get();
                line.append(' ').append(tier.name().toLowerCase()).append('=').append(n);
                if (n > 0) {
                    line.append(" (avg ").append(c.totalMs.get() / n).append("ms)");
                }
            }
            line.append(" flags=").append(counts(flags)).append(" actions=").append(counts(actions))
                    .append(" decide calls=").append(decideCalls.get()).append(" failed=").append(decideFailures.get())
                    .append(" cached=").append(decideCacheHits.get())
                    .append(" cloud calls=").append(cloudCalls.get()).append(" failed=").append(cloudFailures.get());
            log.info(line.toString());
            lastHour = summary;
            hour.values().forEach(Counter::reset);
            hourStartedAt = now;
        }
    }

    /** For the dashboard's {@code live.json}: totals since start, the running hour and the last full hour. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tiers", tiers(total));
        m.put("thisHour", tiers(hour));
        m.put("lastHour", lastHour);
        m.put("flags", counts(flags));
        m.put("actions", counts(actions));
        Map<String, Object> decide = new LinkedHashMap<>();
        long calls = decideCalls.get();
        decide.put("calls", calls);
        decide.put("avgMs", calls == 0 ? 0 : decideTotalMs.get() / calls);
        decide.put("failed", decideFailures.get());
        decide.put("cacheHits", decideCacheHits.get());
        decide.put("rateLimited", decideSkipped.get());
        decide.put("endpoints", endpointTotals());
        decide.put("reads", counts(reads));
        m.put("decide", decide);
        Map<String, Object> cloud = new LinkedHashMap<>();
        long cc = cloudCalls.get();
        cloud.put("calls", cc);
        cloud.put("avgMs", cc == 0 ? 0 : cloudTotalMs.get() / cc);
        cloud.put("failed", cloudFailures.get());
        m.put("cloud", cloud);
        BotBrain brain = BotBrains.peek();
        if (brain != null) {
            m.put("llm", brain.describe());
        }
        return m;
    }

    /** Lines answered since start, one count per {@link Tier} in declaration order (the dashboard series). */
    public static java.util.List<Long> tierCounts() {
        java.util.List<Long> out = new java.util.ArrayList<>(Tier.values().length);
        for (Tier tier : Tier.values()) {
            out.add(total.get(tier).count.get());
        }
        return out;
    }

    /** How many lines each tier answered since start. */
    public static long count(Tier tier) {
        return total.get(tier).count.get();
    }

    private static Map<String, Object> tiers(Map<Tier, Counter> counters) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Tier tier : Tier.values()) {
            m.put(tier.name().toLowerCase(), counters.get(tier).json());
        }
        return m;
    }

    private static Map<String, Long> counts(Map<String, AtomicLong> source) {
        Map<String, Long> m = new java.util.TreeMap<>();
        source.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    /** Test seam: zero everything. */
    public static void resetForTest() {
        for (Tier tier : Tier.values()) {
            total.get(tier).reset();
            hour.get(tier).reset();
        }
        flags.clear();
        actions.clear();
        decideCalls.set(0);
        cloudCalls.set(0);
        cloudFailures.set(0);
        cloudTotalMs.set(0);
        decideCacheHits.set(0);
        decideFailures.set(0);
        decideSkipped.set(0);
        decideTotalMs.set(0);
        endpoints.clear();
        reads.clear();
        minuteStartedAt = System.currentTimeMillis();
        hourStartedAt = System.currentTimeMillis();
        lastHour = Map.of();
    }

    static void startHourAtForTest(long at) {
        hourStartedAt = at;
    }

    static Map<String, Object> lastHourForTest() {
        return lastHour;
    }
}
