package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Need;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Needs;
import soloMapling.fishing.BotFishingChairs;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.0 t-fh-2 (TEST 2026-10-27): bots with a Fishing Chair never picked FISH (0 of thousands of decisions): it had
 * no pull of its own and lost to HANG_OUT, MOVE_MAP or BREAK whatever the needs. A bored chair owner in town now
 * goes fishing some of the time, and FISH is still never chosen where it is not eligible.
 */
class LifePolicyFishTest {
    @Test
    void aBoredChairOwnerSometimesGoesFishing() {
        Map<Need, Double> values = new EnumMap<>(Need.class);
        values.put(Need.BOREDOM, .8); values.put(Need.SOCIAL, .4); values.put(Need.FATIGUE, .3);
        values.put(Need.PROGRESS, .4); values.put(Need.SUPPLIES, .1); values.put(Need.BAG_SPACE, .1);
        Needs needs = new Needs(values);
        int fish = 0, bots = 0, notEligible = 0;
        for (int id = 1_000_000_000; bots < 60; id++) {
            if (!BotFishingChairs.eligible(id)) continue;
            bots++;
            for (boolean eligible : new boolean[]{true, false}) {
                Map<Activity, String> e = new EnumMap<>(Activity.class);
                e.put(Activity.GRIND, "x"); e.put(Activity.HANG_OUT, "x");
                if (eligible) e.put(Activity.FISH, "x");
                var ctx = new LifePolicy.Context(needs, e, 0, 2, 0, false, false, false, null, List.of(), List.of());
                var sit = new LifePolicy.Situation(SocialProfile.forCharacter(id), false, false, .2, false, null, 0, id);
                var options = LifePolicy.options(ctx, LifeProfile.forBot(id), sit);
                var picked = LifeDecision.evaluate(needs, options, null, 0, LifePolicy.INTERRUPT_MARGIN, 0, a -> 0, false)
                        .selected().orElseThrow();
                if (eligible && picked == Activity.FISH) fish++;
                if (!eligible && picked == Activity.FISH) notEligible++;
                if (eligible) assertEquals(LifePolicy.FISH_PULL, options.stream()
                        .filter(o -> o.activity() == Activity.FISH).findFirst().orElseThrow().contextBonus(), 1e-9);
            }
        }
        assertEquals(0, notEligible, "never without a chair and a reachable fishing map");
        assertTrue(fish > 0, "some bored chair owners go fishing (" + fish + " of " + bots + ")");
        assertTrue(fish < bots, "not every one (" + fish + " of " + bots + ")");
    }
}
