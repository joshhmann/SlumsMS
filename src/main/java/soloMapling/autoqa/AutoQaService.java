package soloMapling.autoqa;

import config.YamlConfig;
import constants.net.ServerConstants;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The check runner as a server service: one run at a time on its own daemon thread (never a game thread), the
 * report written as each check finishes, and the nightly {@code all} run at AUTOQA_NIGHTLY_UTC_HOUR.
 */
public final class AutoQaService {

    private static final AutoQaService INSTANCE = new AutoQaService();

    private final GameAutoQaWorld world = new GameAutoQaWorld();
    private final AutoQaRunner runner = new AutoQaRunner(world);
    private volatile Thread thread;
    private volatile String trigger;
    private boolean nightlyScheduled;

    private AutoQaService() {
    }

    public static AutoQaService get() {
        return INSTANCE;
    }

    public AutoQaRunner runner() {
        return runner;
    }

    /** The checks a selector names: "all", a page id ("t-af-1"), a scenario-style id ("af-1") or a group ("af"). */
    static List<Check> select(List<Check> catalogue, String selector) {
        String s = selector.toLowerCase(Locale.ROOT).trim();
        if (s.equals("all")) {
            return catalogue;
        }
        if (s.equals("smoke")) {
            s = String.join(",", SMOKE);
        }
        List<Check> out = new ArrayList<>();
        // 1.5.0: a comma list ("bg-1,vg-1,ch-1") runs each, in catalogue order
        List<String> wanted = new ArrayList<>();
        for (String part : s.split(",")) {
            if (!part.isBlank()) {
                wanted.add(part.trim());
            }
        }
        for (Check c : catalogue) {
            for (String w : wanted) {
                if (c.id().equals(w) || c.shortId().equals(w) || c.shortId().startsWith(w + "-")) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** {@code !autoqa run smoke}: the quick smoke set (a minute each). */
    public static final List<String> SMOKE = List.of("bg-1", "vg-1", "ch-1");

    /** The latest report for the {@code !qa} menu; null when there is none yet. */
    public AutoQaReport.Snapshot latest() {
        return AutoQaReport.snapshot(AutoQaReport.read(AutoQa.dataDir().resolve("latest.json")));
    }

    /**
     * Starts a run in the background. Returns the lines to show (what started, or why not). {@code progress} gets a
     * line per finished check (the GM who asked, if still online).
     */
    public synchronized List<String> start(String selector, String by, boolean nightly, Consumer<String> progress) {
        if (thread != null && thread.isAlive()) {
            return List.of("Auto-QA is already running (" + trigger + "): " + status().get(0));
        }
        List<Check> checks = select(AutoQaCatalog.all(), selector);
        if (checks.isEmpty()) {
            return List.of("No check '" + selector + "'. !autoqa list shows them.");
        }
        String why = world.unavailableReason();
        if (why != null) {
            return List.of("Auto-QA cannot run here: " + why);
        }
        trigger = (nightly ? "nightly" : "!autoqa run " + selector) + " by " + by;
        String runTrigger = trigger;
        long budget = 0;
        for (Check c : checks) {
            budget += c.kind() == Check.Kind.MANUAL ? 0 : c.minutes();
        }
        Thread t = new Thread(() -> runNow(checks, nightly, runTrigger, progress), "autoqa-runner");
        t.setDaemon(true);
        thread = t;
        t.start();
        return List.of("Auto-QA started: " + checks.size() + " check(s), up to ~" + budget + " min. Synthetic players "
                + String.join(" and ", SyntheticPlayers.NAMES) + " log in on channel 1. !autoqa status / stop / report.");
    }

    private void runNow(List<Check> checks, boolean nightly, String runTrigger, Consumer<String> progress) {
        world.open();
        long t0 = System.currentTimeMillis();
        List<AutoQaRunner.CheckResult> done = new ArrayList<>();
        try {
            runner.run(checks, nightly, r -> {
                done.add(r);
                write(runTrigger, t0, List.of(r), false);
                String line = r.status() + " " + r.id() + " " + r.title() + (r.reason() == null ? "" : ": " + r.reason());
                AutoQa.log.info("Auto-QA {}", line);
                if (progress != null) {
                    try {
                        progress.accept(line);
                    } catch (RuntimeException ignored) {
                        // the GM logged off
                    }
                }
            });
        } catch (RuntimeException e) {
            AutoQa.log.warn("Auto-QA run failed", e);
        } finally {
            write(runTrigger, t0, done, true);
            world.close();
            if (progress != null) {
                try {
                    progress.accept("Auto-QA done: " + String.join(" | ", summary()));
                } catch (RuntimeException ignored) {
                    // the GM logged off
                }
            }
        }
    }

    /** Merges results into today's report; {@code finalLine} adds the run's own line to "runs". */
    private synchronized void write(String runTrigger, long t0, List<AutoQaRunner.CheckResult> results, boolean finalLine) {
        Path dir = AutoQa.dataDir();
        String day = HealthSweeper.DAY.format(Instant.now());
        Map<String, Object> report = AutoQaReport.read(dir.resolve("report-" + day + ".json"));
        if (report == null) {
            report = AutoQaReport.empty(ServerConstants.SLUMSMS_VERSION, day, AutoQaCatalog.all());
        }
        if (finalLine) {
            AutoQaReport.mergeChecks(report, ServerConstants.SLUMSMS_VERSION, List.of(), HealthSweeper.get().last());
            AutoQaReport.addRun(report, runTrigger, t0, System.currentTimeMillis(), results);
        } else {
            AutoQaReport.mergeChecks(report, ServerConstants.SLUMSMS_VERSION, results, HealthSweeper.get().last());
        }
        try {
            AutoQaReport.write(dir, day, report);
        } catch (IOException e) {
            AutoQa.log.warn("Auto-QA could not write the report: {}", e.toString());
        }
    }

    public List<String> status() {
        Thread t = thread;
        if (t == null || !t.isAlive()) {
            return List.of("Auto-QA idle. Last report: !autoqa report");
        }
        return List.of("Auto-QA " + runner.state() + " (" + trigger + "): check " + runner.current() + ", step "
                + runner.stepNow());
    }

    public List<String> stop() {
        Thread t = thread;
        if (t == null || !t.isAlive()) {
            return List.of("Auto-QA is not running.");
        }
        runner.abort();
        return List.of("Auto-QA stops after the current step (cleanup still runs).");
    }

    public List<String> summary() {
        Path dir = AutoQa.dataDir();
        return AutoQaReport.summaryLines(AutoQaReport.read(dir.resolve("latest.json")), 25);
    }

    /** Schedules the nightly run (AUTOQA_NIGHTLY_UTC_HOUR, -1 = none). */
    synchronized void scheduleNightly() {
        int hour = YamlConfig.config.server.AUTOQA_NIGHTLY_UTC_HOUR;
        if (nightlyScheduled || hour < 0 || hour > 23) {
            return;
        }
        nightlyScheduled = true;
        long first = msUntilNext(hour, System.currentTimeMillis());
        soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                if (AutoQa.isEnabled()) {
                    AutoQa.log.info("Auto-QA nightly: {}", start("all", "the schedule", true, null));
                }
            } catch (RuntimeException e) {
                AutoQa.log.warn("Auto-QA nightly run failed to start", e);
            }
        }, first, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
        AutoQa.log.info("Auto-QA nightly run at {}:00 UTC (in {} min)", hour, first / 60_000);
    }

    /** The file an operator writes to start a run without logging in (tools/ops/autoqa-run). */
    static final String REQUEST_FILE = "run-request.txt";
    private boolean requestWatch;

    /**
     * Polls {@code <dataDir>/run-request.txt} every 30 s: its first line is a selector ({@code all}, {@code smoke},
     * a check id or family, or a comma list), the file is deleted and the run starts as if a GM had typed
     * {@code !autoqa run <selector>}. A run already in progress keeps going and the request is reported and dropped.
     */
    synchronized void watchRunRequests() {
        if (requestWatch) {
            return;
        }
        requestWatch = true;
        soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            try {
                String selector = takeRequest(AutoQa.dataDir().resolve(REQUEST_FILE));
                if (selector != null && AutoQa.isEnabled()) {
                    AutoQa.log.info("Auto-QA run request '{}': {}", selector, start(selector, "a run request", false, null));
                }
            } catch (RuntimeException e) {
                AutoQa.log.warn("Auto-QA run request failed", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /** The selector in a request file (first non-blank line, trimmed), deleting the file; null when there is none. */
    static String takeRequest(java.nio.file.Path file) {
        try {
            if (!java.nio.file.Files.isRegularFile(file)) {
                return null;
            }
            String selector = java.nio.file.Files.readAllLines(file).stream().map(String::trim)
                    .filter(l -> !l.isEmpty()).findFirst().orElse("");
            java.nio.file.Files.deleteIfExists(file);
            return selector.isEmpty() ? null : selector;
        } catch (java.io.IOException e) {
            AutoQa.log.warn("Auto-QA could not read {}", file, e);
            return null;
        }
    }

    /** Milliseconds from now until the next HH:00 UTC. */
    static long msUntilNext(int hourUtc, long nowMs) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC);
        ZonedDateTime next = now.withHour(hourUtc).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next.toInstant().toEpochMilli() - nowMs;
    }
}
