package soloMapling.qa;

import soloMapling.autoqa.SocialTrace;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 1.6.0 (owner 2026-10-26), the social trace: st-1 runs a short trace (10 minutes, one observer standing in town) and
 * notes each bot thread whose lines are all out, with its first three stages judged. A trace already running (the
 * owner's 9-hour one) is reused and left running.
 */
final class SocialTraceScenarios {
    private SocialTraceScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new ShortTrace());
    }

    static final class ShortTrace implements QaScenario {
        public String id() { return "st-1"; }
        public String title() { return "Social trace: a short trace with one observer records a bot thread"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 12; }
        public int tickSeconds() { return 15; }

        private volatile long since;
        private final Set<String> noted = new HashSet<>();

        public List<String> stage(QaContext ctx) {
            since = System.currentTimeMillis();
            synchronized (noted) {
                noted.clear();
            }
            boolean mine = !SocialTrace.running();
            for (String l : SocialTrace.start(10 / 60.0, "!qa st-1", 1)) {
                ctx.run().note("ST1", "START " + l);
            }
            if (mine) {
                ctx.onStop("stop the st-1 trace and log its observer out", () -> SocialTrace.stop("st-1 done"));
            }
            return List.of("A ten-minute trace runs with one pretend observer standing in town.",
                    "Each bot thread that finishes talking is written to this log with how its first stages went.");
        }

        public void tick(QaContext ctx) {
            for (String s : SocialTrace.deliveredThreads(since)) {
                String id = s.substring(0, s.indexOf(' '));
                synchronized (noted) {
                    if (!noted.add(id)) {
                        continue;
                    }
                }
                ctx.run().note("ST1", "THREAD " + s);
            }
        }
    }
}
