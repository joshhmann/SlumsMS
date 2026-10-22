package soloMapling.autoqa;

import config.YamlConfig;
import constants.net.ServerConstants;
import soloMapling.qa.QaLog;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the auto-QA health sweep every few minutes on the shared scheduled executor (never a game or tick thread)
 * and writes {@code data/autoqa/health-latest.json} plus one line per sweep in
 * {@code data/autoqa/health/<yyyy-MM-dd>.jsonl}. Each sweep is also an {@code AUTOQA HEALTH} line in any running
 * {@code !qa} log that listens to the AUTOQA topic, and a line in the server log.
 */
public final class HealthSweeper {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static final HealthSweeper INSTANCE = new HealthSweeper();

    private final GameHealthSource source = new GameHealthSource();
    private HealthSweep sweep;
    private ScheduledFuture<?> future;
    private volatile Map<String, Object> last;

    private HealthSweeper() {
    }

    public static HealthSweeper get() {
        return INSTANCE;
    }

    int periodMinutes() {
        return Math.max(1, YamlConfig.config.server.AUTOQA_SWEEP_MINUTES);
    }

    synchronized void start() {
        if (future != null) {
            return;
        }
        long period = TimeUnit.MINUTES.toMillis(periodMinutes());
        future = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                sweepNow();
            } catch (RuntimeException e) {
                AutoQa.log.warn("Auto-QA health sweep failed", e);
            }
        }, Math.min(period, TimeUnit.MINUTES.toMillis(2)), period, TimeUnit.MILLISECONDS);
        soloMapling.server.BotShutdown.onQuiesce(this::stop);
    }

    synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    /** One sweep now (also {@code !autoqa health}); returns the result written. */
    public synchronized Map<String, Object> sweepNow() {
        var cfg = YamlConfig.config.server;
        if (sweep == null) {
            HealthSweep.Limits d = HealthSweep.Limits.defaults();
            sweep = new HealthSweep(new HealthSweep.Limits(d.footholdTolerancePx(),
                    TimeUnit.MINUTES.toMillis(Math.max(1, cfg.AUTOQA_STUCK_MINUTES)), cfg.AUTOQA_CLUMP_RADIUS,
                    Math.max(2, cfg.AUTOQA_CLUMP_MIN_BOTS), d.channelTolerance(), d.channelMinTotal(),
                    d.errorMinPerWindow(), d.errorSpikeFactor(), d.maxListed()));
        }
        long t0 = System.currentTimeMillis();
        HealthSweep.Snapshot snap = source.take(Math.max(100, cfg.AUTOQA_SWEEP_MAX_BOTS));
        Map<String, Object> result = sweep.run(snap, source::footholds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", Instant.ofEpochMilli(t0).toString());
        out.put("build", ServerConstants.SLUMSMS_VERSION);
        out.put("durationMs", System.currentTimeMillis() - t0);
        out.putAll(result);
        last = out;
        write(out, AutoQa.dataDir(), t0);
        String line = "HEALTH " + out.get("status") + ": " + out.get("summary") + " (" + out.get("botsSampled") + " of "
                + out.get("botsSeen") + " bots, " + out.get("durationMs") + " ms)";
        if (QaLog.on(QaLog.Topic.AUTOQA)) {
            QaLog.any(QaLog.Topic.AUTOQA, line);
        }
        if ("OK".equals(out.get("status"))) {
            AutoQa.log.info(line);
        } else {
            AutoQa.log.warn(line);
        }
        return out;
    }

    /** The last sweep's result, or null before the first. */
    public Map<String, Object> last() {
        return last;
    }

    /** health-latest.json and the day's history line. */
    static void write(Map<String, Object> result, Path dir, long timeMs) {
        String json = Json.write(result);
        try {
            AutoQa.writeAtomic(dir.resolve("health-latest.json"), json);
            AutoQa.appendLine(dir.resolve("health").resolve(DAY.format(Instant.ofEpochMilli(timeMs)) + ".jsonl"), json);
        } catch (IOException e) {
            AutoQa.log.warn("Auto-QA could not write the health sweep to {}: {}", dir, e.toString());
        }
    }
}
