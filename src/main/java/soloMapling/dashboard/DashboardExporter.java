package soloMapling.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * The admin dashboard's live snapshots (ticket 35). Once a minute, on its own low-priority daemon thread, it reads
 * the bot population, the economy feed, the DB pool, bot tick times and memory, and writes small JSON files under
 * {@code reports/dashboard/} with an atomic rename: {@code live.json} every run, the day's series every few runs,
 * a rollup when the day turns. The dashboard web app only reads those files; nothing reads back from them.
 *
 * <p>Off by default. Turned on with JVM system properties only (no config.yaml keys):
 * <pre>
 *   -Dsolomapling.dashboard=true                  write snapshots
 *   -Dsolomapling.dashboard.dir=reports/dashboard where (the web server's snapshot directory)
 *   -Dsolomapling.dashboard.periodSeconds=60      how often (at least 15)
 * </pre>
 * It cannot slow or break the game: the reads are a pass over the online bots and a few counters, nothing waits on
 * a game lock, the only database reads are the Accounts panel's few small queries every {@link #ACCOUNTS_EVERY_MS}
 * (on this thread, never the game's), a run never overlaps the next, and every failure is logged and skipped.
 * Chat appears only as counts per tier, flag kind and action ({@code ChatStats}), never a line anyone said.
 */
public final class DashboardExporter {

    private static final Logger log = LoggerFactory.getLogger(DashboardExporter.class);

    static final String ENABLED = "solomapling.dashboard";
    static final String DIR = "solomapling.dashboard.dir";
    static final String PERIOD = "solomapling.dashboard.periodSeconds";
    static final int SAVE_SERIES_EVERY = 5;
    /**
     * The Accounts panel is the only part that reads the database (a few small queries through the game's pool), so it
     * is read at most this often and the last reading rides along in between (ticket 108: live at 3,000 bots).
     */
    static final long ACCOUNTS_EVERY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long WARN_EVERY_MS = TimeUnit.MINUTES.toMillis(15);

    private static volatile ScheduledExecutorService executor;
    private static volatile EconomyFeed economy;
    private static volatile Runner runner;

    private DashboardExporter() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED);
    }

    /** Starts the exporter when {@code -Dsolomapling.dashboard=true}; otherwise does nothing. Never throws. */
    public static synchronized void startIfEnabled() {
        if (!enabled() || executor != null) {
            return;
        }
        try {
            Path dir = Path.of(System.getProperty(DIR, "reports/dashboard"));
            long period = Math.max(15, Long.getLong(PERIOD, 60));
            ZoneId zone = ZoneId.systemDefault();
            if (economy == null) {
                economy = new DerivedEconomy(zone);
            }
            runner = new Runner(dir, zone, new ServerSources(), DashboardExporter::economy);
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dashboard-export");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            ex.scheduleWithFixedDelay(runner::runSafely, 10, period, TimeUnit.SECONDS);
            executor = ex;
            log.info("Dashboard snapshots every {}s to {}", period, dir.toAbsolutePath());
        } catch (RuntimeException e) {
            log.warn("Dashboard exporter did not start; the game is unaffected", e);
        }
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Ticket 15's adapter point: replaces the derived economy estimate with a real ledger (see {@link EconomyFeed}).
     * Safe to call before or after the exporter starts, or when it is off.
     */
    public static void useEconomyFeed(EconomyFeed feed) {
        if (feed != null) {
            economy = feed;
        }
    }

    static EconomyFeed economy() {
        EconomyFeed feed = economy;
        return feed != null ? feed : new DerivedEconomy(ZoneId.systemDefault());
    }

    // ── one run ─────────────────────────────────────────────────────────────────────────────────

    /** What a run reads from the server; tests substitute one. */
    interface Sources {
        List<LiveSnapshot.BotSample> bots();

        LiveSnapshot.Population population();

        LiveSnapshot.Health health();

        IntFunction<String> mapNames();

        /** The Accounts panel (see {@link AccountsExport}); null when not available. */
        default java.util.Map<String, Object> accounts(long now) {
            return null;
        }
    }

    /** One exporter's state: the rate baselines, the day's series, the run count. */
    static final class Runner {
        private final Path dir;
        private final Sources sources;
        private final java.util.function.Supplier<EconomyFeed> economy;
        private final LiveSnapshot snapshot = new LiveSnapshot();
        private final SeriesStore series;
        private final AtomicLong lastWarnAt = new AtomicLong();
        private int runs;
        private long failures;
        private java.util.Map<String, Object> accounts;
        private long accountsAt;

        Runner(Path dir, ZoneId zone, Sources sources, java.util.function.Supplier<EconomyFeed> economy) {
            this.dir = dir;
            this.sources = sources;
            this.economy = economy;
            this.series = new SeriesStore(dir, zone);
        }

        /** A run that can only log: the scheduler must never see an exception, or it would stop the timer. */
        void runSafely() {
            try {
                runOnce(System.currentTimeMillis());
            } catch (Throwable t) {
                failures++;
                long now = System.currentTimeMillis();
                long last = lastWarnAt.get();
                if (now - last >= WARN_EVERY_MS && lastWarnAt.compareAndSet(last, now)) {
                    log.warn("Dashboard snapshot failed ({} so far); the game is unaffected", failures, t);
                } else {
                    log.debug("Dashboard snapshot failed", t);
                }
                if (t instanceof VirtualMachineError vme && !(t instanceof StackOverflowError)) {
                    throw vme;
                }
            }
        }

        void runOnce(long now) throws java.io.IOException {
            long t0 = System.nanoTime();
            List<LiveSnapshot.BotSample> bots = sources.bots();
            EconomyFeed feed = economy.get();
            feed.observe(bots, now);
            LiveSnapshot.Result result = snapshot.build(now, bots, sources.population(), sources.health(), feed,
                    sources.mapNames());
            // Tickets 36/37: which tier answered players' lines and how fast, flags, actions, Decide calls.
            result.live().put("chat", soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats.snapshot());
            // Cumulative replies per tier (llm, scripted, emote, ignored, flagged) so the day's series can chart them.
            result.point().put("ch", soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats.tierCounts());
            if (accounts == null || now - accountsAt >= ACCOUNTS_EVERY_MS || now < accountsAt) {
                accounts = sources.accounts(now);
                accountsAt = now;
            }
            if (accounts != null) {
                result.live().put("accounts", accounts);
            }
            result.live().put("wealthIndex", soloMapling.ArtificialPlayer.PlayerWealthIndex.snapshot());
            // Ticket 157: the effective on/off settings (system properties and config toggles), once per export
            result.live().put("switches", EffectiveSwitches.snapshot());
            result.live().put("exportMs", (System.nanoTime() - t0) / 1_000_000);
            AtomicFiles.writeJson(dir.resolve("live.json"), result.live());
            if (soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration.enabled()) {
                // Ticket 157: real vs modelled kill rates by job and level band (report-only, off on live)
                AtomicFiles.writeJson(dir.resolve("calibration.json"),
                        soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration.snapshotJson(now));
            }
            boolean newDay = series.add(result.point(), now);
            if (++runs % SAVE_SERIES_EVERY == 1 || newDay) {
                series.save();
                series.writeIndex();
            }
        }

        int seriesSize() {
            return series.size();
        }
    }
}
