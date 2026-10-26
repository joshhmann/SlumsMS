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

    /**
     * One decision per bot per round at a real choice point: when the activity is due (no stickiness, boredom full),
     * the way the runtime decides on TEST (a mid-activity re-check almost always keeps the activity, so it measures
     * time, not choices). Needs follow TEST (2026-10-26, 3,057 bots): progress is high (goal pressure is level over the
     * next advance), bags and supplies are mostly light, fields have no real players, SOCIAL grows from the last town
     * visit over three town stays. {@code grinding} picks the choice point: leaving a grind, or leaving a town stay.
     */
    static Mix simulate(boolean s8, boolean grinding) {
        Map<Activity, Integer> all = new EnumMap<>(Activity.class);
        Map<SocialProfile.Type, Map<Activity, Integer>> byType = new EnumMap<>(SocialProfile.Type.class);
        for (SocialProfile.Type t : SocialProfile.Type.values()) byType.put(t, new EnumMap<>(Activity.class));
        int total = 0;
        for (int slot = 0; slot < BOTS; slot++) {
            int id = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + slot;
            SocialProfile social = SocialProfile.forSlot(slot);
            LifeProfile profile = LifeProfile.fromSocial(social, id);
            SplittableRandom r = new SplittableRandom(slot * 7919L + (grinding ? 17 : 29));
            int level = 10 + r.nextInt(130);
            for (int d = 0; d < DECISIONS; d++) {
                long townMs = profile.townDurationMs();
                // Leaving a grind: the last town visit was a grind ago; leaving town: it was just now.
                double sinceSocialMs = grinding ? profile.grindDurationMs() * (.5 + r.nextDouble()) : 0;
                Map<Need, Double> needs = new EnumMap<>(Need.class);
                needs.put(Need.SUPPLIES, grinding && r.nextDouble() < .08 ? .6 + .4 * r.nextDouble() : .15 * r.nextDouble());
                needs.put(Need.BAG_SPACE, .1 + .35 * r.nextDouble());
                needs.put(Need.BOREDOM, 1.0);
                // Pre-S8 the SOCIAL need filled over one town stay and only a friend's ask emptied it.
                needs.put(Need.SOCIAL, Math.min(1, s8 ? sinceSocialMs / (LifeRuntime.SOCIAL_PERIOD_FACTOR * townMs)
                        : 1));
                // Resumed sessions have no valid session timing on TEST: FATIGUE is unknown for about half.
                if (r.nextBoolean()) needs.put(Need.FATIGUE, Math.min(1, .8 * r.nextDouble()));
                needs.put(Need.PROGRESS, .75 + .25 * r.nextDouble());
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
                if (needs.getOrDefault(Need.FATIGUE, 0.0) > .75) eligible.put(Activity.LOG_OUT, "x");
                boolean fishing = r.nextDouble() < .5;   // a chair and a pond in reach
                if (fishing) eligible.put(Activity.FISH, "x");
                boolean pq = s8 && LifePq.lobbyFor(level) != null; // PQ hours
                if (pq) eligible.put(Activity.PARTY_QUEST, "x");
                boolean help = s8 && r.nextDouble() < .2; // a friend or guild mate grinding a map that fits
                if (help) eligible.put(Activity.HELP_FRIEND, "x");
                double crowd = r.nextDouble() < .1 ? .8 + .3 * r.nextDouble() : .4 * r.nextDouble();
                boolean ks = r.nextDouble() < .02;
                if (s8 && (crowd >= LifePolicy.CROWDED || ks)) eligible.put(Activity.CHANGE_CHANNEL, "x");
                int strangers = grinding ? 0 : r.nextInt(3);
                var context = new LifePolicy.Context(new Needs(needs), eligible, 0, strangers, 0, false,
                        false, false, null, List.of(), List.of());
                var situation = s8 ? new LifePolicy.Situation(social, help, pq, crowd, ks, null, 0, id)
                        : LifePolicy.Situation.NONE;
                double townBias = s8 ? LifeRuntime.townBias(level) : level <= 30 ? .4 : level <= 70 ? .25 : .05;
                var options = LifePolicy.options(context, profile, situation).stream().map(o ->
                        o.activity() == Activity.HANG_OUT || o.activity() == Activity.SHOW_OFF
                                ? new Option(o.activity(), o.weights(), o.contextBonus() + townBias, o.personalityBonus(),
                                o.available(), o.reason()) : o).toList();
                int seed = slot * 31 + d;
                var result = LifeDecision.evaluate(context.needs(), options, null, 0, LifePolicy.INTERRUPT_MARGIN,
                        .02, a -> new SplittableRandom(seed * 101L + a.ordinal()).nextDouble(-1, 1), false);
                Activity chosen = result.selected().orElseThrow();
                all.merge(chosen, 1, Integer::sum);
                byType.get(social.leading()).merge(chosen, 1, Integer::sum);
                total++;
            }
        }
        return new Mix(all, byType, total);
    }

    static Mix simulate(boolean s8) {
        Mix a = simulate(s8, true), b = simulate(s8, false);
        Map<Activity, Integer> all = new EnumMap<>(a.all());
        b.all().forEach((k, v) -> all.merge(k, v, Integer::sum));
        Map<SocialProfile.Type, Map<Activity, Integer>> byType = new EnumMap<>(SocialProfile.Type.class);
        for (SocialProfile.Type t : SocialProfile.Type.values()) {
            Map<Activity, Integer> m = new EnumMap<>(a.byType().get(t));
            b.byType().get(t).forEach((k, v) -> m.merge(k, v, Integer::sum));
            byType.put(t, m);
        }
        return new Mix(all, byType, a.total() + b.total());
    }

    @Test
    void theS8MixUsesEveryOptionAndNoActivityTakesTheWholeDay() {
        Mix grind = simulate(true, true), town = simulate(true, false), before = simulate(false, true);
        System.out.println("LifeMixTest leaving a grind, before " + before.all());
        System.out.println("LifeMixTest leaving a grind " + grind.all());
        System.out.println("LifeMixTest leaving town    " + town.all());
        for (SocialProfile.Type t : SocialProfile.Type.values()) {
            System.out.println("LifeMixTest " + t + " grind " + grind.byType().get(t) + " town " + town.byType().get(t));
        }
        // A due grind is a real choice: about half the time the bot keeps at it, otherwise town, PQ, a new map, help.
        assertTrue(grind.share(Activity.GRIND) > .35 && grind.share(Activity.GRIND) < .65, grind.all().toString());
        assertTrue(grind.share(Activity.HANG_OUT) > .10, grind.all().toString());
        assertTrue(grind.share(Activity.PARTY_QUEST) > .03, grind.all().toString());
        assertTrue(grind.share(Activity.HELP_FRIEND) > .01, grind.all().toString());
        assertTrue(grind.share(Activity.MOVE_MAP) > .03, grind.all().toString());
        assertTrue(town.share(Activity.FISH) > .005, town.all().toString());
        assertTrue(grind.share(Activity.CHANGE_CHANNEL) > .002, grind.all().toString());
        // Pre-S8 town won every due grind (audit finding 4); S8 must not swing to the other extreme.
        assertTrue(before.share(Activity.HANG_OUT) > .6, before.all().toString());
        // After a town stay (SOCIAL just filled) most bots go back to grinding; some go to a PQ, fish or log off.
        assertTrue(town.share(Activity.GRIND) > .45 && town.share(Activity.GRIND) < .9, town.all().toString());
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
