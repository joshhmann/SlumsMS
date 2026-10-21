package soloMapling.qa;

import client.Character;
import server.TimerManager;
import soloMapling.server.AttackCapture;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The {@code !qa} test-scenario suite (test server only). A GM runs a scenario by its release-notes test id; the
 * suite stages it for that GM, turns on the scenario's {@link QaLog} topics, and writes everything to
 * {@code logs/qa/<id>-<UTC time>.log} until {@code !qa stop} or the timeout, which undoes what the scenario handed
 * out and turns the logging off. One scenario per GM; two GMs' scenarios may not hold the same claim (a map).
 * <p>
 * Gating: the command exists only when the JVM runs with {@code -Dslums.qa=true} or with attack capture
 * ({@code -Dslums.attackcapture=true}, which the test server's start-test.sh passes). Live's unit has neither.
 */
public final class QaSuite {

    public static final String PROPERTY = "slums.qa";
    static final Pattern ID = Pattern.compile("[a-z]{2}-\\d{1,2}");

    /** Runs a task later / repeatedly; returns how to cancel it. */
    public interface Scheduler {
        Runnable schedule(Runnable task, long delayMs);

        Runnable repeat(Runnable task, long periodMs);
    }

    private static volatile QaSuite instance;

    private final QaWorld world;
    private final Path logDir;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Map<String, QaScenario> byId = new LinkedHashMap<>();
    private final List<QaScenario> scenarios;
    private final Map<Integer, QaRun> active = new LinkedHashMap<>();

    QaSuite(List<QaScenario> scenarios, QaWorld world, Path logDir, Scheduler scheduler, Clock clock) {
        this.scenarios = List.copyOf(scenarios);
        this.world = world;
        this.logDir = logDir;
        this.scheduler = scheduler;
        this.clock = clock;
        for (QaScenario s : scenarios) {
            register(s.id(), s);
            for (String alias : s.aliases()) {
                register(alias, s);
            }
        }
    }

    private void register(String id, QaScenario s) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("QA scenario id '" + id + "' is not a test id like gd-1");
        }
        if (byId.putIfAbsent(id, s) != null) {
            throw new IllegalArgumentException("QA scenario id '" + id + "' is registered twice");
        }
    }

    /** True when the suite (and the {@code !qa} command) is on: {@code -Dslums.qa=true} or attack capture. */
    public static boolean isEnabled() {
        return Boolean.getBoolean(PROPERTY) || AttackCapture.isEnabled();
    }

    /** The same rule for a set of -D properties (the unit test reads live's and the test server's command lines). */
    static boolean enabledFor(Function<String, String> property) {
        return "true".equalsIgnoreCase(property.apply(PROPERTY))
                || "true".equalsIgnoreCase(property.apply(AttackCapture.PROPERTY));
    }

    /** The suite of this server, built on first use with the game's world, logs/qa and the TimerManager. */
    public static QaSuite get() {
        QaSuite s = instance;
        if (s == null) {
            synchronized (QaSuite.class) {
                s = instance;
                if (s == null) {
                    s = new QaSuite(QaScenarios.all(), new GameQaWorld(), Path.of("logs", "qa"), timerScheduler(),
                            Clock.systemUTC());
                    instance = s;
                }
            }
        }
        return s;
    }

    private static Scheduler timerScheduler() {
        return new Scheduler() {
            @Override
            public Runnable schedule(Runnable task, long delayMs) {
                ScheduledFuture<?> f = TimerManager.getInstance().schedule(task, delayMs);
                return () -> f.cancel(false);
            }

            @Override
            public Runnable repeat(Runnable task, long periodMs) {
                ScheduledFuture<?> f = TimerManager.getInstance().register(task, periodMs, periodMs);
                return () -> f.cancel(false);
            }
        };
    }

    public List<QaScenario> scenarios() {
        return scenarios;
    }

    public QaScenario find(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** {@code !qa list}. */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        for (QaScenario s : scenarios) {
            String ids = s.id() + (s.aliases().isEmpty() ? "" : " (also " + String.join(", ", s.aliases()) + ")");
            out.add(ids + " - " + s.title() + (s.usage().isEmpty() ? "" : "  [" + s.usage() + "]"));
        }
        out.add("Planned for 1.4.2 (not in this build): " + String.join("; ", QaScenarios.PLANNED));
        return out;
    }

    /** {@code !qa run <id> [args]}: stages the scenario for the GM. Throws {@link QaRefusal} with the reason. */
    public synchronized List<String> start(Character gm, String id, String[] args) {
        QaScenario scenario = find(id);
        if (scenario == null) {
            throw new QaRefusal("No scenario '" + id + "'. !qa list shows them.");
        }
        QaRun mine = active.get(gm.getId());
        if (mine != null) {
            throw new QaRefusal("You are already running " + mine.scenarioId() + ". !qa stop first.");
        }
        String[] a = args == null ? new String[0] : args;
        Set<String> claims = scenario.claims(gm, a);
        for (QaRun other : active.values()) {
            Set<String> both = new HashSet<>(claims);
            both.retainAll(other.claims());
            if (!both.isEmpty()) {
                throw new QaRefusal(other.gmName() + " is running " + other.scenarioId() + " on " + both
                        + "; wait for it (or ask them to !qa stop).");
            }
        }
        Instant now = clock.instant();
        int minutes = Math.max(1, scenario.minutes(a));
        QaRun run = new QaRun(scenario.id(), gm.getId(), gm.getName(), now, logDir, scenario.topics(),
                scenario.watchedMaps(gm, a), claims);
        try {
            run.open(List.of(
                    "scenario " + scenario.id() + ": " + scenario.title(),
                    "gm " + gm.getName() + " id " + gm.getId() + " gm level " + gm.gmLevel(),
                    "where map " + gm.getMapId() + " at " + pointText(gm) + " channel " + channelOf(gm),
                    "args " + String.join(" ", a),
                    "topics " + scenario.topics() + ", maps " + (run.maps().isEmpty() ? "all" : run.maps())
                            + ", stops after " + minutes + " min"));
        } catch (IOException e) {
            throw new QaRefusal("Cannot write " + run.logPath() + ": " + e.getMessage());
        }
        QaContext ctx = new QaContext(run, world, gm, a);
        QaLog.attach(run);
        List<String> lines;
        try {
            lines = scenario.stage(ctx);
        } catch (QaRefusal r) {
            run.stop("refused: " + r.getMessage(), clock.instant());
            QaLog.detach(run);
            throw r;
        } catch (RuntimeException e) {
            run.stop("staging failed: " + e, clock.instant());
            QaLog.detach(run);
            throw new QaRefusal("Staging " + scenario.id() + " failed (" + e + "); what it had set up is undone. Log: "
                    + run.logPath());
        }
        for (String l : lines) {
            run.note("STAGE", "told GM: " + l);
        }
        int gmId = gm.getId();
        run.addCancel(scheduler.schedule(() -> stop(gmId, "timeout"), minutes * 60_000L));
        if (scenario.tickSeconds() > 0) {
            run.addCancel(scheduler.repeat(() -> {
                if (!run.isStopped()) {
                    try {
                        scenario.tick(ctx);
                    } catch (RuntimeException e) {
                        run.note("TICK", "failed: " + e);
                    }
                }
            }, scenario.tickSeconds() * 1000L));
        }
        active.put(gmId, run);
        List<String> out = new ArrayList<>();
        out.add("QA " + scenario.id() + " running: " + scenario.title());
        out.addAll(lines);
        out.add("Logging to " + run.logPath() + ". Stops in " + minutes + " min, or !qa stop.");
        return out;
    }

    /** {@code !qa stop}, or the timeout: undoes the staging and closes the log. */
    public List<String> stop(int gmId, String reason) {
        QaRun run;
        synchronized (this) {
            run = active.remove(gmId);
        }
        if (run == null) {
            return List.of("No QA scenario running.");
        }
        List<String> results = run.stop(reason, clock.instant());
        QaLog.detach(run);
        List<String> out = new ArrayList<>();
        out.add("QA " + run.scenarioId() + " stopped (" + reason + ") after " + QaRun.human(run.elapsed(clock.instant())) + ".");
        out.addAll(results);
        out.add("Log: " + run.logPath());
        if ("timeout".equals(reason)) {
            Character gm = world.online(gmId);
            if (gm != null) {
                for (String l : out) {
                    world.message(gm, l);
                }
            }
        }
        return out;
    }

    /** {@code !qa where}. */
    public synchronized List<String> where(int gmId) {
        List<String> out = new ArrayList<>();
        QaRun run = active.get(gmId);
        if (run == null) {
            out.add("No QA scenario running.");
        } else {
            out.add("Running " + run.scenarioId() + " for " + QaRun.human(run.elapsed(clock.instant())) + ", log "
                    + run.logPath() + ", " + run.pendingCleanups() + " thing(s) to undo at stop.");
        }
        for (QaRun other : active.values()) {
            if (other.gmId() != gmId) {
                out.add("Also running: " + other.scenarioId() + " by " + other.gmName() + " ("
                        + QaRun.human(other.elapsed(clock.instant())) + ")");
            }
        }
        return out;
    }

    /** {@code !qa do <step> [args]}: a step of the GM's running scenario. */
    public List<String> action(Character gm, String name, String[] args) {
        QaRun run;
        synchronized (this) {
            run = active.get(gm.getId());
        }
        if (run == null) {
            throw new QaRefusal("No QA scenario running.");
        }
        QaScenario scenario = find(run.scenarioId());
        run.note("STEP", "!qa do " + name + " " + String.join(" ", args == null ? new String[0] : args));
        return scenario.action(new QaContext(run, world, gm, args), name, args);
    }

    synchronized QaRun runOf(int gmId) {
        return active.get(gmId);
    }

    private static String pointText(Character gm) {
        java.awt.Point p = gm.getPosition();
        return p == null ? "?" : "(" + p.x + ", " + p.y + ")";
    }

    private static String channelOf(Character gm) {
        try {
            return String.valueOf(gm.getClient().getChannel());
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
