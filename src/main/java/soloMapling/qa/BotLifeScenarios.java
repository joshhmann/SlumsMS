package soloMapling.qa;

import soloMapling.ArtificialPlayer.BotLifeSystem.LifeAcceptance;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePolicy;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** Deterministic policy/acceptance smoke inside a running feature jar. Does not stage players or alter holdings. */
final class BotLifeScenarios {
    private BotLifeScenarios() {}
    static List<QaScenario> all() { return List.of(new GoldenPolicy()); }

    static final class GoldenPolicy implements QaScenario {
        public String id() { return "bl-1"; }
        public String title() { return "Bot life: provisional policy and rejected-action safeguards"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 1; }

        public List<String> stage(QaContext ctx) {
            check(ctx, "socialite", Map.of(Need.SOCIAL, .8, Need.PROGRESS, .4), 2, false,
                    LifeProfile.Archetype.SOCIALITE, Activity.HANG_OUT);
            Result restock = check(ctx, "empty-potions", Map.of(Need.SUPPLIES, 1.0, Need.PROGRESS, 1.0),
                    0, false, LifeProfile.Archetype.GRINDER, Activity.RESTOCK);
            check(ctx, "helper", Map.of(Need.SOCIAL, .8, Need.PROGRESS, .3), 1, true,
                    LifeProfile.Archetype.HELPER, Activity.HELP_FRIEND);
            var refusal = LifeAcceptance.tryStart(restock, true, () -> true, new LifeAcceptance.Executor() {
                public boolean supports(Activity activity) { return true; }
                public Optional<LifeAcceptance.Accepted> start(Activity activity) { return Optional.empty(); }
            });
            if (refusal.status() != LifeAcceptance.Status.REJECTED || refusal.accepted().isPresent()) {
                throw new QaRefusal("Rejected executor produced an action fact");
            }
            ctx.run().note("LIFE", "REFUSAL accepted=false actionFact=false");
            return List.of("Golden shadow policy cases and executor refusal were checked in this jar.",
                    "No actual bot, inventory or mesos were changed. Use !botwhy <name> for real context inspection.",
                    "This scenario does not prove travel, purchases, population mix or a 3,000-bot soak.");
        }

        private Result check(QaContext ctx, String name, Map<Need, Double> needs, int friends, boolean asked,
                             LifeProfile.Archetype primary, Activity expected) {
            var blend = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
            for (var type : LifeProfile.Archetype.values()) blend.put(type, type == primary ? .7 : .05);
            var context = new LifePolicy.Context(new Needs(needs), Map.of(Activity.GRIND, "fixture field",
                    Activity.HANG_OUT, "fixture town", Activity.RESTOCK, "fixture trip",
                    Activity.HELP_FRIEND, "fixture friend destination"), friends, 0, 0, asked,
                    false, false, null, List.of(), List.of());
            Result result = LifePolicy.preview(context, new LifeProfile(blend, 40 * 60_000L, 10 * 60_000L), 0);
            if (result.selected().orElse(null) != expected) {
                throw new QaRefusal("Life policy fixture " + name + " chose " + result.selected());
            }
            ctx.run().note("LIFE", "SHADOW case=" + name + " selected=" + expected);
            return result;
        }
    }
}
