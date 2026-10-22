package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static soloMapling.autoqa.Check.Status;

/**
 * Runs checks one after another with the synthetic players, as a state machine:
 * <pre>
 *   IDLE -> PREPARING -> (per check: STEPPING -> ASSERTING -> CLEANING) -> FINISHED | ABORTED -> IDLE
 * </pre>
 * A check is SKIP when it is MANUAL, when it needs something the server lacks (a precondition refusal, a missing
 * LLM judge on a judge-only check), or when it is not nightly and the run is the nightly one. A step that fails ends
 * the check as FAIL with the step's reason; otherwise every expectation is evaluated and any FAIL fails the check.
 * Cleanup always runs: {@code !qa stop} for each player that started a scenario. One runner per server; it runs on
 * the caller's thread ({@link AutoQaService} gives it its own).
 */
public final class AutoQaRunner {

    public enum State { IDLE, PREPARING, STEPPING, ASSERTING, CLEANING, FINISHED, ABORTED }

    /** One check's outcome, keyed by its page id in the report. */
    public record CheckResult(String id, String title, Check.Kind kind, Status status, String reason, List<String> evidence,
                              long startedMs, long finishedMs) {
    }

    private final AutoQaWorld world;
    private final List<String> transitions = new CopyOnWriteArrayList<>();
    private volatile State state = State.IDLE;
    private volatile String current;
    private volatile boolean abort;
    private volatile String stepNow;

    public AutoQaRunner(AutoQaWorld world) {
        this.world = world;
    }

    public State state() {
        return state;
    }

    /** The check running now, or null. */
    public String current() {
        return current;
    }

    public String stepNow() {
        return stepNow;
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
     * Runs the checks; {@code nightly} skips the ones that leave a lasting change. Each result also goes to
     * {@code onResult} as soon as it is known (the report is written as the run goes).
     */
    public synchronized List<CheckResult> run(List<Check> checks, boolean nightly, Consumer<CheckResult> onResult) {
        transitions.clear();
        abort = false;
        List<CheckResult> out = new ArrayList<>();
        to(State.PREPARING, null);
        String unavailable = world.unavailableReason();
        try {
            for (Check c : checks) {
                CheckResult r;
                if (unavailable != null && c.kind() != Check.Kind.MANUAL) {
                    long t = world.now();
                    r = new CheckResult(c.id(), c.title(), c.kind(), Status.SKIP, unavailable, List.of(), t, t);
                } else if (abort) {
                    long t = world.now();
                    r = new CheckResult(c.id(), c.title(), c.kind(), Status.SKIP, "run aborted", List.of(), t, t);
                } else {
                    r = runOne(c, nightly);
                }
                out.add(r);
                if (onResult != null) {
                    onResult.accept(r);
                }
            }
        } finally {
            current = null;
            stepNow = null;
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

    CheckResult runOne(Check c, boolean nightly) {
        long t0 = world.now();
        if (c.kind() == Check.Kind.MANUAL) {
            return new CheckResult(c.id(), c.title(), c.kind(), Status.SKIP, c.why(), List.of(), t0, t0);
        }
        if (nightly && !c.nightly()) {
            return new CheckResult(c.id(), c.title(), c.kind(), Status.SKIP,
                    "not in the nightly run (leaves a lasting change); run it with !autoqa run " + c.id(), List.of(), t0, t0);
        }
        current = c.id();
        CheckRun run = new CheckRun(c, world);
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
                stepNow = step.describe();
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
                stepNow = "expectations";
                int pass = 0, skip = 0;
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
                        case SKIP -> skip++;
                        case FAIL -> failed.add(e.describe());
                    }
                }
                if (!failed.isEmpty()) {
                    status = Status.FAIL;
                    reason = failed.size() + " expectation(s) failed: " + String.join("; ", failed);
                } else if (pass == 0 && skip > 0) {
                    status = Status.SKIP;
                    reason = "nothing could be judged here";
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
            stepNow = "cleanup";
            cleanup(run);
        }
        List<String> all = new ArrayList<>(run.notes());
        all.addAll(evidence);
        return new CheckResult(c.id(), c.title(), c.kind(), status, reason, all, t0, world.now());
    }

    private void cleanup(CheckRun run) {
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
