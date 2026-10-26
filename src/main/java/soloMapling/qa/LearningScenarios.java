package soloMapling.qa;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.6.0 S9, learning: lr-1. A staged high-plasticity Socializer takes 30 KS events, spread over the last five days
 * through the real learning path (the daily budget, the daily pull-back toward its seed and the crossing check, no
 * shortcuts): its point moves toward Killer, stays inside its plasticity cap, crosses into the Killer quadrant once,
 * and {@code !botwhy} shows the drift and the crossing. The bot is a run-only staged bot (never a roster member), so
 * nothing it learns is saved.
 */
final class LearningScenarios {
    private LearningScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new KsTurnsKiller());
    }

    static final class KsTurnsKiller implements QaScenario {
        private final Map<Integer, List<Integer>> staged = new java.util.concurrent.ConcurrentHashMap<>();

        public String id() { return "lr-1"; }
        public String title() { return "Bot learning: 30 KS turn a plastic Socializer toward Killer, one crossing"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.HABITS); }
        public int minutes(String[] args) { return 2; }

        public List<String> stage(QaContext ctx) {
            if (ctx.world().mobLevel(ctx.gm()) < 0) throw new QaRefusal("Needs a map with live mobs, such as HHG1.");
            List<Integer> ids = ctx.world().stageGrinders(ctx.gm(), List.of("WARRIOR:30"), 1, 0, true, true);
            if (ids.isEmpty()) throw new QaRefusal("No bot could be staged here.");
            int gm = ctx.run().gmId();
            staged.put(gm, ids);
            ctx.onStop("remove the learning fixture", () -> { ctx.world().unstageGrinders(ids); staged.remove(gm); });
            List<String> lines = ctx.world().learningStage(ids.getFirst());
            if (lines.isEmpty()) throw new QaRefusal("This world can't stage learning.");
            lines.forEach(line -> ctx.run().note("LR1", line));
            return List.of("One staged bot (run-only, nothing saved) is made a plastic Socializer and takes 30 KS events"
                            + " spread over five days, through the normal daily caps and pull-back.",
                    "The log shows each push (or CAPPED), LEARN CROSSED SOCIALIZER->KILLER once, the !botwhy lines,"
                            + " then LR1 PASS.",
                    "!botwhy <name> in game shows the same drift and the crossing while the bot is here.");
        }
    }
}
