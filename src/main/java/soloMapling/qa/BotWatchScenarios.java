package soloMapling.qa;

import soloMapling.autoqa.BotWatch;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 1.6.0 (owner 2026-10-26) bot watch: bw-1 follows {@value Bw1Watch#BOTS} bots for {@value Bw1Watch#MINUTES} minutes
 * ({@link BotWatch}) and logs each bot's verdict, the summary and PASS or FAIL against the bar (auto-QA t-bw-1, "Bots
 * do what they decided"). It stages nothing and changes nothing; the watch writes data/autoqa/botwatch-latest.json.
 */
final class BotWatchScenarios {
    private BotWatchScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Bw1Watch());
    }

    static final class Bw1Watch implements QaScenario {
        static final int BOTS = 20, MINUTES = 10;

        interface Starter {
            List<String> start(int n, int minutes, String by, Consumer<BotWatch.Result> done);
        }

        /** The watch to start and stop; tests substitute them. */
        static volatile Starter starter = (n, m, by, done) -> BotWatch.get().start(n, m, by, done);
        static volatile Runnable stopper = () -> BotWatch.get().stop();

        public String id() { return "bw-1"; }
        public String title() { return "Bot watch: follow " + BOTS + " bots for " + MINUTES + " min, they do what they decided"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return MINUTES + 3; }

        public List<String> stage(QaContext ctx) {
            QaRun run = ctx.run();
            AtomicBoolean finished = new AtomicBoolean();
            List<String> lines = starter.start(BOTS, MINUTES, "!qa bw-1", r -> {
                finished.set(true);
                report(run, r);
            });
            String first = lines.isEmpty() ? "no answer" : lines.get(0);
            if (!first.startsWith("Watching ")) {
                throw new QaRefusal(first); // "already running" / "no bot could" read as a SKIP precondition
            }
            run.note("BW1", "START " + first);
            ctx.onStop("bot watch", () -> {
                if (!finished.get()) {
                    stopper.run();
                }
            });
            // what the GM reads: it must never contain the log's own verdict words (a waitFor would match the hint)
            return List.of("A " + MINUTES + "-minute bot watch of " + BOTS + " bots starts now; nothing is staged.",
                    "Each bot's verdict and the run's result are logged at the end; the file is "
                            + "data/autoqa/botwatch-latest.json.");
        }

        static void report(QaRun run, BotWatch.Result r) {
            for (String l : r.botLines()) {
                run.note("BW1", "BOT " + l);
            }
            run.note("BW1", "SUMMARY " + r.line());
            if (r.problem() == null) {
                run.note("BW1", "PASS " + r.line());
            } else {
                run.note("BW1", "FAIL " + r.problem());
            }
        }
    }
}
