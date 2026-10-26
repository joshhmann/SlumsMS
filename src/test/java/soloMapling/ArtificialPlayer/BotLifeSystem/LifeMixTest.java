package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/**
 * 1.6.0 S8: the decision mix of a simulated population (3,000 roster slots, their real Bartle seeds, ten decisions
 * each over randomised needs and places). Audit finding 4 saw 45k GRIND / 53k HANG_OUT with HELP_FRIEND, PARTY_QUEST
 * and FISH at 0 on TEST. This pins the expectations: every option a bot can take is chosen sometimes, neither GRIND
 * nor HANG_OUT takes the whole day, and each Bartle type leans its own way.
 */
class LifeMixTest {
    static final int BOTS = 3000, DECISIONS = 10;

    record Mix(Map<Activity, Integer> all, Map<SocialProfile.Type, Map<Activity, Integer>> byType, int total) {
        double share(Activity a) { return all.getOrDefault(a, 0) / (double) total; }
        double share(SocialProfile.Type t, Activity a) {
            Map<Activity, Integer> m = byType.get(t);
            int n = m.values().stream().mapToInt(Integer::intValue).sum();
            return m.getOrDefault(a, 0) / (double) Math.max(1, n);
        }
    }

    static Mix simulate(boolean s8) {
        Map<Activity, Integer> all = new EnumMap<>(Activity.class);
        Map<SocialProfile.Type, Map<Activity, Integer>> byType = new EnumMap<>(SocialProfile.Type.class);
        for (SocialProfile.Type t : SocialProfile.Type.values()) byType.put(t, new EnumMap<>(Activity.class));
        int total = 0;
        for (int slot = 0; slot < BOTS; slot++) {
            int id = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + slot;
            SocialProfile social = SocialProfile.forSlot(slot);
            LifeProfile profile = LifeProfile.fromSocial(social, id);
            SplittableRandom r = new SplittableRandom(slot * 7919L + 17);
            int level = 10 + r.nextInt(130);
            for (int d = 0; d < DECISIONS; d++) {
                boolean grinding = r.nextDouble() < .6;
                long townMs = profile.townDurationMs();
                double sinceSocialMs = r.nextDouble() * 4 * townMs;
                Map<Need, Double> needs = new EnumMap<>(Need.class);
                needs.put(Need.SUPPLIES, r.nextDouble() < .08 ? .6 + .4 * r.nextDouble() : .25 * r.nextDouble());
                needs.put(Need.BAG_SPACE, .2 + .5 * r.nextDouble());
                needs.put(Need.BOREDOM, r.nextDouble());
                // Pre-S8 the SOCIAL need filled over one town stay and only a friend's ask emptied it.
                needs.put(Need.SOCIAL, Math.min(1, s8 ? sinceSocialMs / (LifeRuntime.SOCIAL_PERIOD_FACTOR * townMs)
                        : 1));
                needs.put(Need.FATIGUE, Math.min(1, .9 * r.nextDouble()));
                needs.put(Need.PROGRESS, .3 + .7 * r.nextDouble());
                Map<Activity, String> eligible = new EnumMap<>(Activity.class);
                eligible.put(Activity.GRIND, "x");
                eligible.put(Activity.HANG_OUT, "x");
                eligible.put(Activity.FREE_MARKET, "x");
                if (grinding) {
                    eligible.put(Activity.RESTOCK, "x");
                    eligible.put(Activity.MOVE_MAP, "x");
                    eligible.put(Activity.BREAK, "x");
                }
                if (level >= 70) eligible.put(Activity.SHOW_OFF, "x");
                if (needs.get(Need.FATIGUE) > .75) eligible.put(Activity.LOG_OUT, "x");
                boolean fishing = r.nextDouble() < .5;   // a chair and a pond in reach
                if (fishing) eligible.put(Activity.FISH, "x");
                boolean pq = s8 && LifePq.lobbyFor(level) != null && r.nextDouble() < .5; // PQ hours
                if (pq) eligible.put(Activity.PARTY_QUEST, "x");
                boolean help = s8 && r.nextDouble() < .2; // a friend or guild mate grinding a map that fits
                if (help) eligible.put(Activity.HELP_FRIEND, "x");
                double crowd = r.nextDouble() < .1 ? .8 + .3 * r.nextDouble() : .4 * r.nextDouble();
                boolean ks = r.nextDouble() < .02;
                if (s8 && (crowd >= LifePolicy.CROWDED || ks)) eligible.put(Activity.CHANGE_CHANNEL, "x");
                int strangers = r.nextInt(8), friends = r.nextDouble() < .1 ? 1 : 0, rivals = r.nextDouble() < .05 ? 1 : 0;
                Activity now = grinding ? Activity.GRIND : Activity.HANG_OUT;
                long dur = grinding ? profile.grindDurationMs() : profile.townDurationMs();
                long elapsed = (long) (needs.get(Need.BOREDOM) * dur);
                // Half the decisions come when the activity is due (no stickiness: the runtime passes no current then),
                // half mid-activity when a need changed.
                boolean due = r.nextBoolean();
                if (due) needs.put(Need.BOREDOM, Math.max(needs.get(Need.BOREDOM), .8));
                var current = due ? null : new Current(now, 1_000_000_000L - elapsed, dur, LifePolicy.STICKINESS);
                var context = new LifePolicy.Context(new Needs(needs), eligible, friends, strangers, rivals, false,
                        false, false, current, List.of(), List.of());
                var situation = s8 ? new LifePolicy.Situation(social, help, pq, crowd, ks, null, 0, id)
                        : LifePolicy.Situation.NONE;
                double townBias = s8 ? LifeRuntime.townBias(level) : level <= 30 ? .4 : level <= 70 ? .25 : .05;
                var options = LifePolicy.options(context, profile, situation).stream().map(o ->
                        o.activity() == Activity.HANG_OUT || o.activity() == Activity.SHOW_OFF
                                ? new Option(o.activity(), o.weights(), o.contextBonus() + townBias, o.personalityBonus(),
                                o.available(), o.reason()) : o).toList();
                long t = 1_000_000_000L;
                int seed = slot * 31 + d;
                var result = LifeDecision.evaluate(context.needs(), options, current, t, LifePolicy.INTERRUPT_MARGIN,
                        .02, a -> new SplittableRandom(seed * 101L + a.ordinal()).nextDouble(-1, 1), false);
                Activity chosen = result.selected().orElseThrow();
                all.merge(chosen, 1, Integer::sum);
                byType.get(social.leading()).merge(chosen, 1, Integer::sum);
                total++;
            }
        }
        return new Mix(all, byType, total);
    }

    @Test
    void theS8MixUsesEveryOptionAndNoActivityTakesTheWholeDay() {
        Mix before = simulate(false), after = simulate(true);
        System.out.println("LifeMixTest before " + before.all());
        System.out.println("LifeMixTest after  " + after.all());
        for (SocialProfile.Type t : SocialProfile.Type.values()) {
            System.out.println("LifeMixTest " + t + " " + after.byType().get(t));
        }
        // The friend and party options fire without a player's ask; fishing fires where a bot can fish.
        assertTrue(after.share(Activity.HELP_FRIEND) > .01, after.all().toString());
        assertTrue(after.share(Activity.PARTY_QUEST) > .01, after.all().toString());
        assertTrue(after.share(Activity.FISH) > .01, after.all().toString());
        assertTrue(after.share(Activity.CHANGE_CHANNEL) > .002, after.all().toString());
        assertTrue(after.share(Activity.MOVE_MAP) > .02, after.all().toString());
        // Town no longer wins every idle moment, and grinding isn't everything either.
        assertTrue(after.share(Activity.HANG_OUT) < before.share(Activity.HANG_OUT), before.all() + " -> " + after.all());
        assertTrue(after.share(Activity.HANG_OUT) < .40, after.all().toString());
        assertTrue(after.share(Activity.GRIND) < .55, after.all().toString());
        assertTrue(after.share(Activity.GRIND) > .25, after.all().toString());
    }

    @Test
    void eachBartleTypeLeansItsOwnWay() {
        Mix mix = simulate(true);
        var A = SocialProfile.Type.ACHIEVER; var E = SocialProfile.Type.EXPLORER;
        var S = SocialProfile.Type.SOCIALIZER; var K = SocialProfile.Type.KILLER;
        // Achievers grind and party (bossing) more than socializers grind.
        assertTrue(mix.share(A, Activity.GRIND) > mix.share(S, Activity.GRIND));
        // Explorers move maps and fish most.
        for (var other : List.of(A, S, K)) {
            assertTrue(mix.share(E, Activity.MOVE_MAP) > mix.share(other, Activity.MOVE_MAP), "move " + other);
            assertTrue(mix.share(E, Activity.FISH) > mix.share(other, Activity.FISH), "fish " + other);
        }
        // Socializers help friends and hang out most.
        for (var other : List.of(A, E, K)) {
            assertTrue(mix.share(S, Activity.HELP_FRIEND) > mix.share(other, Activity.HELP_FRIEND), "help " + other);
            assertTrue(mix.share(S, Activity.HANG_OUT) > mix.share(other, Activity.HANG_OUT), "hang " + other);
        }
        // Killers show off most.
        for (var other : List.of(A, E, S)) {
            assertTrue(mix.share(K, Activity.SHOW_OFF) > mix.share(other, Activity.SHOW_OFF), "show " + other);
        }
    }
}
