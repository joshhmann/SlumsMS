package soloMapling.autoqa;

import config.YamlConfig;
import soloMapling.qa.QaTiming;
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
     * Starts a run in the background. {@code selector} is a plain selector ({@code all}, {@code smoke}, an id or a
     * family) and may also carry the fast-lane flags ({@code "all --fast"}). Returns the lines to show (what started,
     * or why not). {@code progress} gets a line per finished check (the GM who asked, if still online).
     */
    public synchronized List<String> start(String selector, String by, boolean nightly, Consumer<String> progress) {
        return start(parseRequest(selector), by, nightly, progress);
    }

    /**
     * A run request: the selector, plus {@code --fast} (the QA clock at {@link QaTiming#FAST_SCALE}) and
     * {@code --failed} (rerun what did not pass, carrying the rest forward).
     */
    record Request(String selector, boolean fast, boolean failed, boolean release) {

        Request(String selector, boolean fast, boolean failed) {
            this(selector, fast, failed, false);
        }
    }

    /**
     * Reads a request line: {@code <selector|all> [--fast] [--failed] [--release|release]}, any order. b222: a release
     * run ({@link AutoQaRelease}) is asked with {@code --release} ({@code tools/ops/autoqa-run --release all}) or the
     * word {@code release} ({@code !autoqa run all release}).
     */
    static Request parseRequest(String raw) {
        String selector = null;
        boolean fast = false;
        boolean failed = false;
        boolean release = false;
        for (String token : raw.trim().split("\\s+")) {
            switch (token) {
                case "--fast" -> fast = true;
                case "--failed" -> failed = true;
                case "--release", "release" -> release = true;
                case "" -> {
                    // extra spaces
                }
                default -> {
                    if (selector == null) {
                        selector = token;
                    }
                }
            }
        }
        return new Request(selector == null ? "all" : selector, fast, failed, release);
    }

    /** How a run runs: its QA clock, the deployed commit, and the earlier passes it carries instead of rerunning. */
    private record Mode(double timeScale, String commit, Map<String, Map<String, Object>> carried, String carriedRun,
                        boolean release) {
    }

    /** {@link #start(String, String, boolean, Consumer)} with the flags parsed. */
    public synchronized List<String> start(Request req, String by, boolean nightly, Consumer<String> progress) {
        if (thread != null && thread.isAlive()) {
            return List.of("Auto-QA is already running (" + trigger + "): " + status().get(0));
        }
        if (req.release()) {
            String no = AutoQaRelease.refusal(req.selector(), req.fast(), req.failed());
            if (no != null) {
                return List.of("Auto-QA release run refused: " + no + ". Use: !autoqa run all release");
            }
        }
        double timeScale = req.fast() ? QaTiming.FAST_SCALE : 1.0;
        String commit = AutoQa.deployCommit();
        List<Check> catalogue = AutoQaCatalog.all();
        List<Check> checks = select(catalogue, req.selector());
        if (checks.isEmpty() && !req.failed()) {
            return List.of("No check '" + req.selector() + "'. !autoqa list shows them.");
        }
        Map<String, Map<String, Object>> carried = Map.of();
        String carriedRun = null;
        String note = "";
        if (req.failed()) {
            Map<String, Object> report = AutoQaReport.read(AutoQa.dataDir().resolve("latest.json"));
            AutoQaRerun.Plan plan = AutoQaRerun.plan(catalogue, report, timeScale, commit);
            checks = select(plan.checks(), req.selector());
            if (checks.isEmpty()) {
                return List.of("Auto-QA rerun skipped: " + plan.refused());
            }
            carried = carriedFor(catalogue, req.selector(), plan.carried());
            carriedRun = plan.sourceRun();
            if (plan.refused() != null) {
                note = " --failed: " + plan.refused();
            }
        }
        Mode mode = new Mode(timeScale, commit, carried, carriedRun, req.release());
        String why = world.unavailableReason();
        if (why != null) {
            return List.of("Auto-QA cannot run here: " + why);
        }
        trigger = (nightly ? "nightly" : "!autoqa run " + req.selector() + (req.fast() ? " --fast" : "")
                + (req.failed() ? " --failed" : "") + (req.release() ? " release" : "")) + " by " + by;
        String runTrigger = trigger;
        long budget = 0;
        for (Check c : checks) {
            budget += c.kind() == Check.Kind.MANUAL ? 0 : c.minutes();
        }
        List<Check> toRun = List.copyOf(checks);
        Thread t = new Thread(() -> runNow(toRun, nightly, runTrigger, progress, mode), "autoqa-runner");
        t.setDaemon(true);
        thread = t;
        t.start();
        return List.of("Auto-QA started: " + checks.size() + " check(s), up to ~" + budget + " min"
                + (req.fast() ? ", fast lane " + AutoQaReport.scaleLabel(timeScale) + " (QA staging only)" : "")
                + (carried.isEmpty() ? "" : ", " + carried.size() + " passed last time carried forward, not rerun")
                + note + (req.release() ? ". RELEASE run: any FAIL, or a SKIP off the allow-list ("
                + AutoQaRelease.allowList().size() + " client-only ids), fails it" : "") + ". Synthetic players " + String.join(" and ", SyntheticPlayers.NAMES)
                + " log in on channel 1. !autoqa status / stop / report.");
    }

    /**
     * The carried entries for the checks a {@code --failed} request covers: those the selector names, which is a
     * superset of the ones it reruns - a carried pass is by definition not in the rerun list, so narrowing by the
     * rerun list would drop every one of them and report a partial run as if nothing had been carried.
     */
    static Map<String, Map<String, Object>> carriedFor(List<Check> catalogue, String selector,
                                                       Map<String, Map<String, Object>> carried) {
        return narrow(carried, select(catalogue, selector));
    }

    /** The carried entries for the checks this run actually covers (a selector narrows a rerun too). */
    private static Map<String, Map<String, Object>> narrow(Map<String, Map<String, Object>> carried, List<Check> checks) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Check c : checks) {
            if (carried.containsKey(c.id())) {
                out.put(c.id(), carried.get(c.id()));
            }
        }
        return out;
    }

    private void runNow(List<Check> checks, boolean nightly, String runTrigger, Consumer<String> progress, Mode mode) {
        QaTiming.beginRun(mode.timeScale());
        try {
            AutoQa.compressHistory(AutoQa.dataDir());
        } catch (RuntimeException e) {
            AutoQa.log.debug("Auto-QA history sweep failed", e);
        }
        world.open();
        long t0 = System.currentTimeMillis();
        List<AutoQaRunner.CheckResult> done = new ArrayList<>();
        try {
            runner.run(checks, nightly, r -> {
                done.add(r);
                write(runTrigger, t0, List.of(r), false, mode, null);
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
            try {
                ChatQuality.runNow(); // rc6: the chat-quality summary after every run
            } catch (RuntimeException e) {
                AutoQa.log.warn("Auto-QA chat quality failed", e);
            }
            AutoQaRelease.Verdict release = mode.release()
                    ? AutoQaRelease.judge(AutoQaCatalog.all(), done, AutoQaRelease.allowList()) : null;
            String runLine = write(runTrigger, t0, done, true, mode, release);
            // b222: every run's summary line names what it skipped and why, and a release run's verdict
            AutoQa.log.info("Auto-QA run done: {}", runLine);
            world.close();
            QaTiming.endRun();
            if (progress != null) {
                try {
                    progress.accept("Auto-QA done: " + runLine);
                    progress.accept("Report: " + String.join(" | ", summary()));
                } catch (RuntimeException ignored) {
                    // the GM logged off
                }
            }
        }
    }

    /**
     * Merges results into today's report; {@code finalLine} adds the run's own line (and its carried passes) and
     * returns its one-line summary (else null).
     */
    private synchronized String write(String runTrigger, long t0, List<AutoQaRunner.CheckResult> results, boolean finalLine,
                                      Mode mode, AutoQaRelease.Verdict release) {
        Path dir = AutoQa.dataDir();
        String day = HealthSweeper.DAY.format(Instant.now());
        Map<String, Object> report = AutoQaReport.read(dir.resolve("report-" + day + ".json"));
        if (report == null) {
            report = AutoQaReport.empty(ServerConstants.SLUMSMS_VERSION, day, AutoQaCatalog.all());
        }
        AutoQaReport.putInference(report, ChatQuality.brief(ChatQuality.last()),
                soloMapling.ArtificialPlayer.BotConvo.SayDo.stats());
        Map<String, Object> health = HealthSweeper.get().last();
        if (finalLine) {
            AutoQaReport.merge(report, ServerConstants.SLUMSMS_VERSION, new AutoQaReport.Run(runTrigger, t0,
                    System.currentTimeMillis(), mode.timeScale(), mode.commit(), results, mode.carried(),
                    mode.carriedRun(), release), health);
        } else {
            AutoQaReport.mergeChecks(report, ServerConstants.SLUMSMS_VERSION, mode.timeScale(), mode.commit(), results,
                    health);
        }
        try {
            AutoQaReport.write(dir, day, report);
        } catch (IOException e) {
            AutoQa.log.warn("Auto-QA could not write the report: {}", e.toString());
        }
        return finalLine && AutoQaReport.lastRun(report) instanceof Map<?, ?> last ? String.valueOf(last.get("summary"))
                : null;
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
     * Polls {@code <dataDir>/run-request.txt} every 30 s: its first line is a request ({@code <selector> [--fast]
     * [--failed]}), the file is deleted and the run starts as if a GM had typed it. A run already in progress keeps
     * going and the request is reported and dropped.
     */
    synchronized void watchRunRequests() {
        if (requestWatch) {
            return;
        }
        requestWatch = true;
        soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            try {
                String request = takeRequest(AutoQa.dataDir().resolve(REQUEST_FILE));
                if (request != null && AutoQa.isEnabled()) {
                    AutoQa.log.info("Auto-QA run request '{}': {}", request,
                            start(parseRequest(request), "a run request", false, null));
                }
            } catch (RuntimeException e) {
                AutoQa.log.warn("Auto-QA run request failed", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /** The request in a request file (first non-blank line, trimmed), deleting the file; null when there is none. */
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
