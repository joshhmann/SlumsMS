package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static soloMapling.autoqa.Check.Status;

/**
 * Runs checks with the synthetic players, as a state machine:
 * <pre>
 *   IDLE -> PREPARING -> (per lane: STEPPING -> ASSERTING -> CLEANING) -> FINISHED | ABORTED -> IDLE
 * </pre>
 * One check at a time by default ({@code -Dslums.autoqa.parallel=1}), or several at once on separate lanes of
 * synthetic players when the operator asks for it ({@code -Dslums.autoqa.parallel=2..4}); {@link AutoQaScheduler}
 * decides which check may start, so two checks that need the same map, instance or the whole world never overlap.
 * A check is SKIP when it is MANUAL, when it needs something the server lacks (a precondition refusal, a missing
 * LLM judge on a judge-only check), or when it is not nightly and the run is the nightly one. A step that fails ends
 * the check as FAIL with the step's reason; otherwise every expectation is evaluated and any FAIL fails the check.
 * Cleanup always runs: {@code !qa stop} for each player that started a scenario. One runner per server; it runs on
 * the caller's thread ({@link AutoQaService} gives it its own).
 */
public final class AutoQaRunner {

    /**
     * 1.6.0 triage: how many of the suite's answers to the GM (the YELLOW replies to !qa run / !qa do, e.g. "no free
     * grinder of X fits map ...") a FAIL keeps as evidence. The fails of 2026-10-27 could not be told apart without them.
     */
    static final int GM_ANSWERS_ON_FAIL = 15;

    /**
     * How long after a server start the first check waits (-Dslums.autoqa.settleMs, default 3 min). TEST 2026-10-30
     * 18:02 UTC: {@code tools/ops/autoqa-run all} was picked up 47 s after a restart, while the population director's
     * boot fill logged in 1,153 bots and the item pools loaded; the first check's {@code !qa run af-1} took 38 s to
     * answer and t-af-1 failed on the 20 s answer timeout. The load was over about 95 s after the boot.
     */
    static long settleMs() {
        return Math.max(0, Long.getLong("slums.autoqa.settleMs", 180_000L));
    }


    public enum State { IDLE, PREPARING, STEPPING, ASSERTING, CLEANING, FINISHED, ABORTED }

    /** One check's outcome, keyed by its page id in the report. */
    public record CheckResult(String id, String title, Check.Kind kind, Status status, String reason, List<String> evidence,
                              long startedMs, long finishedMs, List<String> logs) {

        public CheckResult {
            logs = logs == null ? List.of() : List.copyOf(logs);
        }

        /** A result with no QA log (a probe-only check, a SKIP before any step). */
        public CheckResult(String id, String title, Check.Kind kind, Status status, String reason, List<String> evidence,
                           long startedMs, long finishedMs) {
            this(id, title, kind, status, reason, evidence, startedMs, finishedMs, List.of());
        }
    }

    private final AutoQaWorld world;
    private final List<String> transitions = new CopyOnWriteArrayList<>();
    private final Map<Integer, String> laneCheck = new ConcurrentHashMap<>();
    private final Map<Integer, String> laneWhere = new ConcurrentHashMap<>();
    private volatile State state = State.IDLE;
    private volatile boolean abort;

    public AutoQaRunner(AutoQaWorld world) {
        this.world = world;
    }

    public State state() {
        return state;
    }

    /** The check running now on the first busy lane, or null. */
    public String current() {
        Integer lane = firstBusyLane();
        return lane == null ? null : laneCheck.get(lane);
    }

    public String stepNow() {
        Integer lane = firstBusyLane();
        return lane == null ? null : laneWhere.get(lane);
    }

    /** Every check running now, {@code t-af-1 (lane 2): step}, in lane order. */
    public List<String> running() {
        List<String> out = new ArrayList<>();
        for (int lane = 1; lane <= SyntheticPlayers.MAX_LANES; lane++) {
            String id = laneCheck.get(lane);
            if (id != null) {
                out.add(id + " (lane " + lane + "): " + laneWhere.get(lane));
            }
        }
        return out;
    }

    private Integer firstBusyLane() {
        for (int lane = 1; lane <= SyntheticPlayers.MAX_LANES; lane++) {
            if (laneCheck.containsKey(lane)) {
                return lane;
            }
        }
        return null;
    }

    private void markStep(int lane, String checkId, String what) {
        laneCheck.put(lane, checkId);
        laneWhere.put(lane, what);
    }

    /** Asks the run to stop after the step it is on (cleanup still runs). */
    public void abort() {
        abort = true;
    }

    /** Every state change of the last run, "STATE" or "STATE check-id" (tests). */
    public List<String> transitions() {
        return Collections.unmodifiableList(transitions);
    }

    private void to(State s, String what) {
        state = s;
        transitions.add(what == null ? s.name() : s.name() + " " + what);
    }

    /**
     * Says on the result itself which QA clock it ran on: a 0.4x pass must never be mistaken for a normal one when
     * someone reads the evidence weeks later.
     */
    private static CheckResult label(CheckResult r) {
        if (!soloMapling.qa.QaTiming.fast()) {
            return r;
        }
        List<String> ev = new ArrayList<>();
        ev.add("fast lane: QA staging ran at " + soloMapling.qa.QaTiming.label()
                + " - staged windows are shorter than a normal run's");
        ev.addAll(r.evidence());
        return new CheckResult(r.id(), r.title(), r.kind(), r.status(), r.reason(), ev, r.startedMs(), r.finishedMs(),
                r.logs());
    }

    /**
     * Runs the checks; {@code nightly} skips the ones that leave a lasting change. Each result also goes to
     * {@code onResult} as soon as it is known (the report is written as the run goes).
     */
    public synchronized List<CheckResult> run(List<Check> checks, boolean nightly, Consumer<CheckResult> onResult) {
        transitions.clear();
        abort = false;
        List<CheckResult> out = new ArrayList<>();
        to(State.PREPARING, null);
        String unavailable = world.unavailableReason();
        int parallel = AutoQaScheduler.parallel();
        try {
            if (unavailable == null && checks.stream().anyMatch(c -> c.kind() != Check.Kind.MANUAL)) {
                settle();
            }
            if (unavailable != null) {
                for (Check c : checks) {
                    CheckResult r = c.kind() == Check.Kind.MANUAL ? runOne(c, nightly, 1) : skipped(c, unavailable);
                    out.add(deliver(r, onResult));
                }
            } else if (parallel <= 1) {
                for (Check c : checks) {
                    CheckResult r = abort ? skipped(c, "run aborted") : runOne(c, nightly, 1);
                    out.add(deliver(r, onResult));
                }
            } else {
                out.addAll(runLanes(checks, nightly, onResult, parallel));
            }
        } finally {
            laneCheck.clear();
            laneWhere.clear();
            try {
                world.logoutAll();
            } catch (RuntimeException e) {
                AutoQa.log.warn("Auto-QA logout failed", e);
            }
            to(abort ? State.ABORTED : State.FINISHED, null);
            to(State.IDLE, null);
        }
        return out;
    }

    private CheckResult skipped(Check c, String why) {
        long t = world.now();
        return new CheckResult(c.id(), c.title(), c.kind(), Status.SKIP, why, List.of(), t, t);
    }

    /** Labels a result with the QA clock it ran on and hands it to the report as it is known. */
    private CheckResult deliver(CheckResult r, Consumer<CheckResult> onResult) {
        CheckResult labelled = label(r);
        if (onResult != null) {
            onResult.accept(labelled);
        }
        return labelled;
    }

    /**
     * Runs the checks on {@code parallel} lanes at once, starting the first check (in catalogue order) whose resources
     * are free whenever a lane frees up; the results come back in catalogue order whichever lane finished first.
     */
    private List<CheckResult> runLanes(List<Check> checks, boolean nightly, Consumer<CheckResult> onResult, int parallel) {
        int n = checks.size();
        CheckResult[] results = new CheckResult[n];
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pending.add(i);
        }
        boolean[] laneFree = new boolean[parallel + 1];
        Arrays.fill(laneFree, true);
        Set<String> busy = new HashSet<>();
        int[] running = {0};
        Object lock = new Object();
        ExecutorService pool = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "autoqa-lane");
            t.setDaemon(true);
            return t;
        });
        try {
            synchronized (lock) {
                while (true) {
                    AutoQaScheduler.Start start;
                    while (!abort && (start = AutoQaScheduler.pick(checks, pending, busy, laneFree)) != null) {
                        int pos = start.position();
                        int idx = pending.get(pos);
                        int lane = start.lane();
                        Check c = checks.get(idx);
                        pending.remove(pos);
                        laneFree[lane] = false;
                        busy.addAll(AutoQaScheduler.keys(c));
                        running[0]++;
                        pool.execute(() -> {
                            CheckResult labelled = null;
                            try {
                                CheckResult r;
                                try {
                                    r = runOne(c, nightly, lane);
                                } catch (RuntimeException e) {
                                    long t = world.now();
                                    r = new CheckResult(c.id(), c.title(), c.kind(), Status.FAIL, "error: " + e,
                                            List.of(), t, t);
                                }
                                labelled = deliver(r, onResult);
                            } finally { // a lane is freed even if handing the result over throws
                                synchronized (lock) {
                                    results[idx] = labelled;
                                    laneFree[lane] = true;
                                    busy.removeAll(AutoQaScheduler.keys(c));
                                    running[0]--;
                                    lock.notifyAll();
                                }
                            }
                        });
                    }
                    if (abort) {
                        for (int idx : pending) {
                            CheckResult r = deliver(skipped(checks.get(idx), "run aborted"), onResult);
                            synchronized (lock) {
                                results[idx] = r;
                            }
                        }
                        pending.clear();
                    }
                    if (pending.isEmpty() && running[0] == 0) {
                        break;
                    }
                    try {
                        lock.wait(1000); // a lane finishing (or an abort) wakes this up
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        abort = true;
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<CheckResult> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(results[i]);
        }
        return out;
    }

    /** Waits until the server has been up {@link #settleMs()} (an abort ends the wait; the checks then SKIP). */
    private void settle() {
        long up = world.uptimeMs();
        long left = settleMs() - up;
        if (left <= 0) {
            return;
        }
        to(State.PREPARING, "settling " + (left / 1000) + " s");
        AutoQa.log.info("Auto-QA: the server has been up {} s; the first check waits {} s for the boot load to settle",
                up / 1000, left / 1000);
        try {
            long until = world.now() + left;
            while (!abort && world.now() < until) {
                world.sleep(Math.min(1000, until - world.now()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort = true;
        }
    }

    CheckResult runOne(Check c, boolean nightly, int lane) {
        long t0 = world.now();
        if (c.kind() == Check.Kind.MANUAL) {
            return new CheckResult(c.id(), c.title(), c.kind(), Status.SKIP, c.why(), List.of(), t0, t0);
        }
        if (nightly && !c.nightly()) {
            return new CheckResult(c.id(), c.title(), c.kind(), Status.SKIP,
                    "not in the nightly run (leaves a lasting change); run it with !autoqa run " + c.id(), List.of(), t0, t0);
        }
        markStep(lane, c.id(), "logging in");
        CheckRun run = new CheckRun(c, world, lane);
        List<String> evidence = new ArrayList<>();
        Status status = null;
        String reason = null;
        long budgetEnd = t0 + Math.max(1, c.minutes()) * 60_000L;
        to(State.STEPPING, c.id());
        try {
            for (int i = 0; i < c.players(); i++) {
                run.actor(i + 1); // log the players in first: a login failure is the check's reason
            }
            for (Check.Step step : c.steps()) {
                if (abort) {
                    status = Status.SKIP;
                    reason = "run aborted at '" + step.describe() + "'";
                    break;
                }
                if (world.now() > budgetEnd) {
                    status = Status.FAIL;
                    reason = "over its " + c.minutes() + " min budget at '" + step.describe() + "'";
                    break;
                }
                markStep(lane, c.id(), step.describe());
                run.stepStarting();
                Check.StepResult sr = step.run(run);
                if (!sr.ok()) {
                    status = sr.status();
                    reason = sr.reason();
                    break;
                }
            }
            if (status == null) {
                to(State.ASSERTING, c.id());
                markStep(lane, c.id(), "expectations");
                int pass = 0, skip = 0;
                String firstSkip = null;
                List<String> failed = new ArrayList<>();
                for (Check.Expect e : c.expects()) {
                    Check.Verdict v;
                    try {
                        v = e.eval(run);
                    } catch (RuntimeException ex) {
                        v = Check.Verdict.fail("expectation threw " + ex);
                    }
                    String tag = v.status() == Status.PASS ? "ok" : v.status().name();
                    evidence.add(tag + ": " + e.describe());
                    for (String ev : v.evidence()) {
                        evidence.add("  " + ev);
                    }
                    switch (v.status()) {
                        case PASS -> pass++;
                        case SKIP -> {
                            skip++;
                            if (firstSkip == null && !v.evidence().isEmpty()) {
                                firstSkip = v.evidence().get(0);
                            }
                        }
                        case FAIL -> failed.add(e.describe());
                    }
                }
                if (!failed.isEmpty()) {
                    status = Status.FAIL;
                    reason = failed.size() + " expectation(s) failed: " + String.join("; ", failed);
                } else if (pass == 0 && skip > 0) {
                    status = Status.SKIP;
                    // 1.6.0: a probe's own SKIP line ("SKIP OWNED_ALTS_ENABLED is off ...") is the reason
                    reason = "nothing could be judged here" + (firstSkip == null ? "" : ": " + firstSkip);
                } else {
                    status = Status.PASS;
                    reason = c.kind() == Check.Kind.HALF ? "logic passes; still look: " + c.why()
                            : skip > 0 ? skip + " expectation(s) not judged" : null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = Status.SKIP;
            reason = "interrupted";
            abort = true;
        } catch (RuntimeException e) {
            status = Status.FAIL;
            reason = "error: " + e;
        } finally {
            to(State.CLEANING, c.id());
            markStep(lane, c.id(), "cleanup");
            cleanup(run);
        }
        laneCheck.remove(lane);
        laneWhere.remove(lane);
        List<String> all = new ArrayList<>(run.notes());
        all.addAll(evidence);
        if (status == Status.FAIL) {
            all.addAll(run.gmAnswers(GM_ANSWERS_ON_FAIL));
        }
        return new CheckResult(c.id(), c.title(), c.kind(), status, reason, all, t0, world.now(), run.logs());
    }

    private void cleanup(CheckRun run) {
        for (int slot : run.hiddenSlots()) {
            try {
                run.actor(slot).say("!unhide");
                world.sleep(Steps.SPAM_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                run.note("cleanup: !unhide failed: " + e);
            }
        }
        for (int slot : run.slotsWithScenario()) {
            try {
                run.actor(slot).say("!qa stop");
                world.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                run.note("cleanup: !qa stop failed: " + e);
            }
        }
    }
}
