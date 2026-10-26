package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** 1.6.0 S8: CHANGE_CHANNEL preconditions, the per-map cap on the target, and how the brain weighs it. */
class LifeChannelMoveTest {

    @Test
    void neverMidTradePartyPqCommitmentDeadOrTwiceInHalfAnHour() {
        assertNull(LifeChannelMove.blocked(false, false, false, false, false, false));
        assertEquals("mid-trade", LifeChannelMove.blocked(true, false, false, false, false, false));
        assertEquals("in a party", LifeChannelMove.blocked(false, true, false, false, false, false));
        assertEquals("in a PQ or boss instance", LifeChannelMove.blocked(false, false, true, false, false, false));
        assertEquals("holding a commitment", LifeChannelMove.blocked(false, false, false, true, false, false));
        assertEquals("dead", LifeChannelMove.blocked(false, false, false, false, true, false));
        assertEquals("changed channel recently", LifeChannelMove.blocked(false, false, false, false, false, true));
    }

    @Test
    void crowdIsTheLoadAgainstTheCap() {
        assertEquals(1.0, LifeChannelMove.crowd(50, 50));
        assertEquals(.5, LifeChannelMove.crowd(25, 50));
        assertEquals(1.0, LifeChannelMove.crowd(40, 0), "no cap: judged against 40 bots");
    }

    @Test
    void theTargetIsClearlyQuieterAndUnderItsCap() {
        // Channel 1 holds 50 on this map; channel 3 the fewest.
        assertEquals(3, LifeChannelMove.quieter(1, new int[]{50, 20, 4, 30, 12}, 50));
        // Nothing clearly quieter (at most 60% of here): stay.
        assertEquals(-1, LifeChannelMove.quieter(1, new int[]{20, 15, 18}, 50));
        // A full channel is never a target, however the cap is set.
        assertEquals(-1, LifeChannelMove.quieter(2, new int[]{10, 30, 10}, 10));
        assertEquals(1, LifeChannelMove.quieter(2, new int[]{9, 30, 10}, 10));
        // Single channel: nowhere to go.
        assertEquals(-1, LifeChannelMove.quieter(1, new int[]{50}, 50));
    }

    @Test
    void theBrainOffersItOnlyOnACrowdedMapOrAKsWarAndCommitmentsHold() {
        var profile = LifeProfile.forBot(1000000123);
        var eligible = Map.of(Activity.GRIND, "here", Activity.CHANGE_CHANNEL, "quieter channel");
        var context = new LifePolicy.Context(new Needs(Map.of(Need.PROGRESS, .3, Need.BOREDOM, .3)), eligible,
                0, 3, 0, false, false, false, null, List.of(), List.of());
        var calm = new LifePolicy.Situation(new SocialProfile(.5, -.5), false, false, .3, false, null, 0, 1);
        assertEquals(Activity.GRIND, pick(context, profile, calm));
        assertFalse(option(context, profile, calm, Activity.CHANGE_CHANNEL).available(), "a quiet map: no cc");
        var packed = new LifePolicy.Situation(new SocialProfile(.5, -.5), false, false, 1.1, false, null, 0, 1);
        assertEquals(Activity.CHANGE_CHANNEL, pick(context, profile, packed));
        var ksWar = new LifePolicy.Situation(new SocialProfile(.5, -.5), false, false, .3, true, null, 0, 1);
        assertTrue(option(context, profile, ksWar, Activity.CHANGE_CHANNEL).available());
        // A Killer enjoys the KS war: much less pull to leave it than a calm explorer.
        var killer = new LifePolicy.Situation(new SocialProfile(-.9, .9), false, false, .3, true, null, 0, 1);
        assertTrue(option(context, profile, killer, Activity.CHANGE_CHANNEL).contextBonus()
                < option(context, profile, ksWar, Activity.CHANGE_CHANNEL).contextBonus());
        assertTrue(option(context, profile, killer, Activity.GRIND).contextBonus()
                > option(context, profile, ksWar, Activity.GRIND).contextBonus(), "a killer stays to fight");
        var held = new LifePolicy.Context(context.needs(), eligible, 0, 3, 0, false, false, true, null, List.of(),
                List.of());
        var result = LifeDecision.evaluate(held.needs(), LifePolicy.options(held, profile, packed), null, 0,
                LifePolicy.INTERRUPT_MARGIN, 0, a -> 0, held.commitment());
        assertEquals(Outcome.HOLD_COMMITMENT, result.outcome());
    }

    static Activity pick(LifePolicy.Context context, LifeProfile profile, LifePolicy.Situation situation) {
        return LifeDecision.evaluate(context.needs(), LifePolicy.options(context, profile, situation), null, 0,
                LifePolicy.INTERRUPT_MARGIN, 0, a -> 0, context.commitment()).selected().orElseThrow();
    }

    static Option option(LifePolicy.Context context, LifeProfile profile, LifePolicy.Situation situation, Activity a) {
        return LifePolicy.options(context, profile, situation).stream().filter(o -> o.activity() == a).findFirst()
                .orElseThrow();
    }
}
