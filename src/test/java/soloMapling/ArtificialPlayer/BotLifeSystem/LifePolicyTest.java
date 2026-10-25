package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifePolicyTest {
    @Test
    void actualPolicyGoldenCasesChooseTownRestockAndHelp() {
        var social = context(Map.of(Need.SOCIAL, .8, Need.PROGRESS, .4), 2, 1, 0, false, false, false);
        assertEquals(Activity.HANG_OUT, LifePolicy.preview(social, profile(LifeProfile.Archetype.SOCIALITE), 100)
                .selected().orElseThrow());
        var grinder = context(Map.of(Need.SUPPLIES, 1.0, Need.PROGRESS, 1.0), 0, 0, 0, false, false, false);
        assertEquals(Activity.RESTOCK, LifePolicy.preview(grinder, profile(LifeProfile.Archetype.GRINDER), 100)
                .selected().orElseThrow());
        var helper = context(Map.of(Need.SOCIAL, .8, Need.PROGRESS, .3), 1, 0, 0, true, false, false);
        assertEquals(Activity.HELP_FRIEND, LifePolicy.preview(helper, profile(LifeProfile.Archetype.HELPER), 100)
                .selected().orElseThrow());
    }

    @Test
    void strangersHaveLessPullThanFriendsAndDramaOffRemovesRivalPenalty() {
        var profile = profile(LifeProfile.Archetype.SOCIALITE);
        var friend = context(Map.of(), 1, 0, 0, false, false, false);
        var stranger = context(Map.of(), 0, 1, 0, false, false, false);
        assertTrue(hangout(friend, profile) > hangout(stranger, profile));
        var rival = context(Map.of(), 0, 0, 1, false, false, false);
        var optedOut = context(Map.of(), 0, 0, 1, false, true, false);
        assertEquals(-.4, hangout(rival, profile));
        assertEquals(0, hangout(optedOut, profile));
    }

    @Test
    void aHelpRequestNeedsAnEligibleDestinationAndCommitmentsStillHold() {
        var noDestination = new LifePolicy.Context(new Needs(Map.of()), Map.of(Activity.GRIND, "here"),
                1, 0, 0, true, false, false, null, List.of(), List.of());
        assertEquals(Activity.GRIND, LifePolicy.preview(noDestination, profile(LifeProfile.Archetype.HELPER), 0)
                .selected().orElseThrow());
        var held = context(Map.of(Need.SUPPLIES, 1.0), 0, 0, 0, false, false, true);
        assertEquals(Outcome.HOLD_COMMITMENT, LifePolicy.preview(held, profile(LifeProfile.Archetype.GRINDER), 0)
                .outcome());
    }

    private static double hangout(LifePolicy.Context context, LifeProfile profile) {
        return LifePolicy.options(context, profile).stream().filter(o -> o.activity() == Activity.HANG_OUT)
                .findFirst().orElseThrow().contextBonus();
    }

    static LifeProfile profile(LifeProfile.Archetype primary) {
        var weights = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
        for (var type : LifeProfile.Archetype.values()) weights.put(type, type == primary ? .7 : .05);
        return new LifeProfile(weights, 40 * 60_000L, 10 * 60_000L);
    }

    static LifePolicy.Context context(Map<Need, Double> needs, int friends, int strangers, int rivals,
                                      boolean asked, boolean dramaOff, boolean held) {
        return new LifePolicy.Context(new Needs(needs), Map.of(Activity.GRIND, "here", Activity.HANG_OUT, "town",
                Activity.RESTOCK, "shop trip", Activity.HELP_FRIEND, "friend destination"), friends, strangers,
                rivals, asked, dramaOff, held, null, List.of(), List.of());
    }
}
