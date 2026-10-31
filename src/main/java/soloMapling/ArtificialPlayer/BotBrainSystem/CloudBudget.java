package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Ticket 59, owner decision 7: the cloud overflow's daily spend cap. Cost is estimated from tokens (the response's
 * {@code usage} when it has one, else characters / 4) times the configured price per million tokens. The day is the
 * server's local day; at the cap the cloud is skipped until midnight. The day's spend is kept in a small file so a
 * restart does not reset it.
 */
public final class CloudBudget {

    private static final Logger log = LoggerFactory.getLogger(CloudBudget.class);

    private final double dailyUsd;
    private final double priceInPerMTok;
    /** What the provider charges for an input token it had already cached (DeepSeek's cache-hit price). */
    private final double priceInCachedPerMTok;
    private final double priceOutPerMTok;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Path file;
    private LocalDate day;
    private double spent;
    /** Worst-case cost of calls in flight (reserved before they go out, settled when they come back). */
    private double reserved;
    private long calls;
    private long skipped;
    /** Today's input tokens the provider had already cached (billed at the cache-hit price). */
    private long cacheHitTokens;
    /** Today's input tokens it had not (billed at the input price). */
    private long cacheMissTokens;

    /** Hits are billed at the input price (no cached price configured, or the provider reports no cache). */
    public CloudBudget(double dailyUsd, double priceInPerMTok, double priceOutPerMTok, LongSupplier clock, ZoneId zone,
                       Path file) {
        this(dailyUsd, priceInPerMTok, -1, priceOutPerMTok, clock, zone, file);
    }

    /** {@code priceInCachedPerMTok} negative = a cached input token costs the same as any other. */
    public CloudBudget(double dailyUsd, double priceInPerMTok, double priceInCachedPerMTok, double priceOutPerMTok,
                       LongSupplier clock, ZoneId zone, Path file) {
        this.dailyUsd = Math.max(0, dailyUsd);
        this.priceInPerMTok = Math.max(0, priceInPerMTok);
        this.priceInCachedPerMTok = priceInCachedPerMTok < 0 ? this.priceInPerMTok : priceInCachedPerMTok;
        this.priceOutPerMTok = Math.max(0, priceOutPerMTok);
        this.clock = clock;
        this.zone = zone;
        this.file = file;
        load();
    }

    private LocalDate today() {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate();
    }

    private void roll() {
        LocalDate t = today();
        if (!t.equals(day)) {
            day = t;
            spent = 0;
            reserved = 0;
            calls = 0;
            skipped = 0;
            cacheHitTokens = 0;
            cacheMissTokens = 0;
        }
    }

    /** Whether anything is left today (spent plus calls in flight below the cap). Does not reserve. */
    public synchronized boolean canSpend() {
        roll();
        return dailyUsd > 0 && spent + reserved < dailyUsd;
    }

    /**
     * Reserves a call's worst-case cost before it goes out. False (and nothing reserved) when it would not fit under
     * the cap with everything already spent and in flight: the hard cap. Settle with {@link #settle}.
     */
    public synchronized boolean reserve(double worstUsd) {
        roll();
        if (dailyUsd <= 0 || spent + reserved + worstUsd > dailyUsd) {
            skipped++;
            return false;
        }
        reserved += worstUsd;
        return true;
    }

    /**
     * A reserved call came back: frees its reservation and charges what it really used. 1.5.0 rc6 bug hunt: the
     * charge used to be capped at the reservation, so a call that ran over its estimate (a longer prompt in real
     * tokens than characters / 2, reasoning tokens) was under-counted and the day could go past the cap unseen. The
     * whole real cost is charged now; an overrun then stops further calls until midnight.
     */
    public synchronized void settle(double reservedUsd, long inTokens, long outTokens) {
        settle(reservedUsd, inTokens, 0, outTokens);
    }

    /**
     * As above, with the input tokens the provider reported as cached ({@code prompt_cache_hit_tokens}); those are
     * charged at the cache-hit price, the rest at the input price.
     */
    public synchronized void settle(double reservedUsd, long inTokens, long cachedInTokens, long outTokens) {
        roll();
        reserved = Math.max(0, reserved - reservedUsd);
        long in = Math.max(0, inTokens);
        long cached = Math.min(Math.max(0, cachedInTokens), in);
        spent += cost(in, cached, outTokens);
        cacheHitTokens += cached;
        cacheMissTokens += in - cached;
        calls++;
        save();
    }

    public synchronized double reservedNow() {
        return reserved;
    }

    /** The cost of a call in USD. */
    public double cost(long inTokens, long outTokens) {
        return cost(inTokens, 0, outTokens);
    }

    /** The cost of a call in USD, {@code cachedInTokens} of the input at the cache-hit price. */
    public double cost(long inTokens, long cachedInTokens, long outTokens) {
        long in = Math.max(0, inTokens);
        long cached = Math.min(Math.max(0, cachedInTokens), in);
        return (in - cached) * priceInPerMTok / 1e6 + cached * priceInCachedPerMTok / 1e6
                + outTokens * priceOutPerMTok / 1e6;
    }

    /** Records a call's tokens. */
    public synchronized void charge(long inTokens, long outTokens) {
        charge(inTokens, 0, outTokens);
    }

    /** Records a call's tokens, {@code cachedInTokens} of the input at the cache-hit price. */
    public synchronized void charge(long inTokens, long cachedInTokens, long outTokens) {
        roll();
        long in = Math.max(0, inTokens);
        long cached = Math.min(Math.max(0, cachedInTokens), in);
        spent += cost(in, cached, outTokens);
        cacheHitTokens += cached;
        cacheMissTokens += in - cached;
        calls++;
        save();
    }

    /** Today's input tokens the provider had cached. */
    public synchronized long cacheHitTokens() {
        roll();
        return cacheHitTokens;
    }

    /** Today's input tokens it had not cached. */
    public synchronized long cacheMissTokens() {
        roll();
        return cacheMissTokens;
    }

    /** The share of today's input tokens that came back cached (0 when there were none yet). */
    public synchronized double cacheHitRatio() {
        roll();
        long all = cacheHitTokens + cacheMissTokens;
        return all == 0 ? 0 : (double) cacheHitTokens / all;
    }

    /** Spent plus in flight, as a share of the daily cap (1 when there is no cap). */
    public synchronized double usedShare() {
        roll();
        return dailyUsd <= 0 ? 1 : (spent + reserved) / dailyUsd;
    }

    public synchronized double spentToday() {
        roll();
        return spent;
    }

    public synchronized String describe() {
        roll();
        long all = cacheHitTokens + cacheMissTokens;
        String cache = all == 0 ? "no cache data yet"
                : String.format(java.util.Locale.ROOT, "cache hit %d%% of %d input tokens",
                        Math.round(100.0 * cacheHitTokens / all), all);
        return String.format(java.util.Locale.ROOT, "cloud $%.4f of $%.2f today (%d calls, %d skipped at the cap; %s)",
                spent, dailyUsd, calls, skipped, cache);
    }

    /** Tokens of a text, estimated (about four characters a token). */
    public static long estimateTokens(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }

    private void load() {
        day = today();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            if (Json.parse(Files.readString(file)) instanceof Map<?, ?> m && day.toString().equals(m.get("day"))
                    && m.get("spent") instanceof Number n) {
                spent = n.doubleValue();
                calls = m.get("calls") instanceof Number c ? c.longValue() : 0;
                cacheHitTokens = m.get("cacheHitTokens") instanceof Number h ? h.longValue() : 0;
                cacheMissTokens = m.get("cacheMissTokens") instanceof Number mi ? mi.longValue() : 0;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Cloud budget file unreadable: starting the day at zero", e);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", day.toString());
            m.put("spent", spent);
            m.put("calls", calls);
            m.put("cacheHitTokens", cacheHitTokens);
            m.put("cacheMissTokens", cacheMissTokens);
            m.put("capUsd", dailyUsd);
            Files.writeString(file, Json.write(m));
        } catch (IOException | RuntimeException e) {
            log.debug("Cloud budget file not written", e);
        }
    }
}
