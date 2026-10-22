package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One release-notes check the runner can do by itself (or knows it cannot): its page id ({@code t-af-1}), the steps
 * a tester would take and what must be true afterwards. Built with {@link #auto}, {@link #half} or {@link #manual}
 * in {@link AutoQaCatalog}; the steps and expectations come from {@link Steps} and {@link Expects}.
 *
 * @param id         the notes page id, e.g. {@code t-af-1}
 * @param title      the page's title for it
 * @param kind       AUTO (the outcome is server-side), HALF (logic here, the look needs eyes), MANUAL (never run)
 * @param why        for HALF what still needs eyes; for MANUAL why it cannot run headless
 * @param players    synthetic players it needs (1 or 2)
 * @param nightly    false when a run leaves a lasting change (it then runs only when asked by id)
 * @param minutes    the check's time budget; the runner stops waiting past it
 */
public record Check(String id, String title, Kind kind, String why, int players, boolean nightly, int minutes,
                    List<Step> steps, List<Expect> expects) {

    public enum Kind { AUTO, HALF, MANUAL }

    public enum Status { PASS, FAIL, SKIP }

    /** What a step did: carry on, or end the check now as FAIL or SKIP. */
    public record StepResult(Status status, String reason) {
        public static final StepResult OK = new StepResult(Status.PASS, null);

        public static StepResult fail(String reason) {
            return new StepResult(Status.FAIL, reason);
        }

        public static StepResult skip(String reason) {
            return new StepResult(Status.SKIP, reason);
        }

        public boolean ok() {
            return status == Status.PASS;
        }
    }

    /** One thing the tester does. */
    public interface Step {
        StepResult run(CheckRun run) throws InterruptedException;

        String describe();
    }

    /** One thing that must hold at the end. SKIP = could not be judged (e.g. no LLM judge); it never fails a check. */
    public record Verdict(Status status, List<String> evidence) {
        public static Verdict pass(String... evidence) {
            return new Verdict(Status.PASS, List.of(evidence));
        }

        public static Verdict fail(String... evidence) {
            return new Verdict(Status.FAIL, List.of(evidence));
        }

        public static Verdict skip(String... evidence) {
            return new Verdict(Status.SKIP, List.of(evidence));
        }
    }

    public interface Expect {
        Verdict eval(CheckRun run) throws InterruptedException;

        String describe();
    }

    public Check {
        Objects.requireNonNull(id);
        if (!id.matches("t-[a-z]{2}-\\d{1,2}")) {
            throw new IllegalArgumentException("check id '" + id + "' is not a notes page id like t-af-1");
        }
        steps = List.copyOf(steps);
        expects = List.copyOf(expects);
    }

    /** The {@code !qa} scenario id this check runs first, or null ({@code t-af-1} usually runs {@code af-1}). */
    public String shortId() {
        return id.substring(2);
    }

    public static Builder auto(String id, String title) {
        return new Builder(id, title, Kind.AUTO, null);
    }

    /** Logic automated; {@code eyes} says what a person still has to look at. */
    public static Builder half(String id, String title, String eyes) {
        return new Builder(id, title, Kind.HALF, eyes);
    }

    /** Never run headless: {@code why} goes into the report as the SKIP reason. */
    public static Check manual(String id, String title, String why) {
        return new Check(id, title, Kind.MANUAL, why, 1, false, 0, List.of(), List.of());
    }

    public static final class Builder {
        private final String id, title, why;
        private final Kind kind;
        private int players = 1;
        private boolean nightly = true;
        private int minutes = 10;
        private final List<Step> steps = new ArrayList<>();
        private final List<Expect> expects = new ArrayList<>();

        private Builder(String id, String title, Kind kind, String why) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.why = why;
        }

        public Builder players(int n) {
            this.players = n;
            return this;
        }

        /** Only when asked by id: a run leaves something behind (a poached bot, a rating). */
        public Builder notNightly() {
            this.nightly = false;
            return this;
        }

        public Builder minutes(int m) {
            this.minutes = m;
            return this;
        }

        public Builder step(Step... s) {
            steps.addAll(List.of(s));
            return this;
        }

        public Builder expect(Expect... e) {
            expects.addAll(List.of(e));
            return this;
        }

        public Check build() {
            return new Check(id, title, kind, why, players, nightly, minutes, steps, expects);
        }
    }
}
