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
    private final double priceOutPerMTok;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Path file;
    private LocalDate day;
    private double spent;
    private long calls;
    private long skipped;

    public CloudBudget(double dailyUsd, double priceInPerMTok, double priceOutPerMTok, LongSupplier clock, ZoneId zone,
                       Path file) {
        this.dailyUsd = Math.max(0, dailyUsd);
        this.priceInPerMTok = Math.max(0, priceInPerMTok);
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
            calls = 0;
            skipped = 0;
        }
    }

    /** Whether a call may go out now (spend so far below the cap). Counts a skip when not. */
    public synchronized boolean canSpend() {
        roll();
        if (dailyUsd <= 0 || spent >= dailyUsd) {
            skipped++;
            return false;
        }
        return true;
    }

    /** The cost of a call in USD. */
    public double cost(long inTokens, long outTokens) {
        return inTokens * priceInPerMTok / 1e6 + outTokens * priceOutPerMTok / 1e6;
    }

    /** Records a call's tokens. */
    public synchronized void charge(long inTokens, long outTokens) {
        roll();
        spent += cost(Math.max(0, inTokens), Math.max(0, outTokens));
        calls++;
        save();
    }

    public synchronized double spentToday() {
        roll();
        return spent;
    }

    public synchronized String describe() {
        roll();
        return String.format(java.util.Locale.ROOT, "cloud $%.4f of $%.2f today (%d calls, %d skipped at the cap)", spent,
                dailyUsd, calls, skipped);
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
            m.put("capUsd", dailyUsd);
            Files.writeString(file, Json.write(m));
        } catch (IOException | RuntimeException e) {
            log.debug("Cloud budget file not written", e);
        }
    }
}
