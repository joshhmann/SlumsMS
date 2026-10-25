package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifeDecisionTest {
    private static final Needs EMPTY = new Needs(Map.of());

    // Golden personalities are explicit fixtures, not deployed blend ranges or assigned bot profiles.
    @Test
    void socialiteWithFriendsInTownChoosesHangout() {
        Result result = pick(new Needs(Map.of(Need.SOCIAL, .8, Need.PROGRESS, .3)), List.of(
                option(Activity.GRIND, Map.of(Need.PROGRESS, 1.0), 0, .1),
                option(Activity.HANG_OUT, Map.of(Need.SOCIAL, 1.0), .4, .5),
                option(Activity.BREAK, Map.of(Need.FATIGUE, 1.0), 0, 0)));
        assertEquals(Activity.HANG_OUT, result.selected().orElseThrow());
        assertEquals(1.7, result.ranking().getFirst().total(), 1e-9);
        assertEquals(.8, result.ranking().getFirst().contributions().get(Need.SOCIAL));
    }

    @Test
    void grinderOutOfPotionsRestocksDespitePersonality() {
        Result result = pick(new Needs(Map.of(Need.SUPPLIES, 1.0, Need.PROGRESS, .6)), List.of(
                option(Activity.GRIND, Map.of(Need.PROGRESS, 1.0, Need.SUPPLIES, -1.0), 0, .5),
                option(Activity.RESTOCK, Map.of(Need.SUPPLIES, 2.0), 0, 0)));
        assertEquals(Activity.RESTOCK, result.selected().orElseThrow());
    }

    @Test
    void helperAnswersFriendsHelpRequest() {
        Result result = pick(new Needs(Map.of(Need.PROGRESS, .7)), List.of(
                option(Activity.HELP_FRIEND, Map.of(), .8, .5),
                option(Activity.GRIND, Map.of(Need.PROGRESS, 1.0), 0, .1)));
        assertEquals(Activity.HELP_FRIEND, result.selected().orElseThrow());
    }

    @Test
    void tiesHaveStableEnumOrderRegardlessOfInputOrder() {
        List<Option> options = List.of(option(Activity.SELL, Map.of(), 1, 0),
                option(Activity.GRIND, Map.of(), 1, 0), option(Activity.RESTOCK, Map.of(), 1, 0));
        assertEquals(List.of(Activity.GRIND, Activity.RESTOCK, Activity.SELL),
                pick(EMPTY, options).topThree().stream().map(Score::activity).toList());
        assertEquals(pick(EMPTY, options), pick(EMPTY, options.reversed()));
    }

    @Test
    void stickyGrindWinsEarlyAndDecaysAtItsExpectedEnd() {
        Current current = new Current(Activity.GRIND, 1_000, 40 * 60_000, 1);
        List<Option> options = List.of(option(Activity.GRIND, Map.of(), 0, 0),
                option(Activity.HANG_OUT, Map.of(), .6, 0));
        assertEquals(Activity.GRIND, evaluate(EMPTY, options, current, 1_000, .1, 0, a -> 0, false)
                .selected().orElseThrow());
        Result expired = evaluate(EMPTY, options, current, 1_000 + 40 * 60_000, .1, 0, a -> 0, false);
        assertEquals(Activity.HANG_OUT, expired.selected().orElseThrow());
        assertEquals(0, expired.ranking().get(1).stickiness());
    }

    @Test
    void interruptMustStrictlyBeatTheMarginAndRankingIsNotSelection() {
        Current current = new Current(Activity.GRIND, 0, 1, 0);
        List<Option> options = List.of(option(Activity.GRIND, Map.of(), 0, 0),
                option(Activity.HELP_FRIEND, Map.of(), .5, 0));
        Result equal = evaluate(EMPTY, options, current, 10, .5, 0, a -> 0, false);
        assertEquals(Activity.HELP_FRIEND, equal.ranking().getFirst().activity());
        assertEquals(Activity.GRIND, equal.selected().orElseThrow());
        assertEquals(Outcome.KEEP_CURRENT, equal.outcome());
        assertEquals(Activity.HELP_FRIEND, evaluate(EMPTY, options, current, 10, .49, 0, a -> 0, false)
                .selected().orElseThrow());
    }

    @Test
    void commitmentsHoldEvenWhenCurrentIsUnavailableOrUnknown() {
        List<Option> options = List.of(option(Activity.RESTOCK, Map.of(), 100, 0));
        Result held = evaluate(EMPTY, options, new Current(Activity.GRIND, 0, 1, 0), 10,
                0, 0, a -> 0, true);
        assertEquals(Outcome.HOLD_COMMITMENT, held.outcome());
        assertEquals(Activity.GRIND, held.selected().orElseThrow());
        assertTrue(evaluate(EMPTY, options, null, 10, 0, 0, a -> 0, true).selected().isEmpty());
    }

    @Test
    void unavailableOptionsAndUnimplementedFeaturesCannotWin() {
        Option unavailable = new Option(Activity.PARTY_QUEST, Map.of(), 100, 100, false, "no opening");
        assertEquals(Activity.GRIND, pick(EMPTY, List.of(unavailable,
                option(Activity.GRIND, Map.of(), 0, 0))).selected().orElseThrow());
        Result none = pick(EMPTY, List.of(unavailable));
        assertTrue(none.ranking().isEmpty());
        assertTrue(none.selected().isEmpty());
        assertEquals(Outcome.NO_AVAILABLE_OPTION, none.outcome());
    }

    @Test
    void unknownNeedsAreNotReportedAsObservedZeroAndInputsAreCopied() {
        Map<Need, Double> values = new EnumMap<>(Need.class);
        values.put(Need.SUPPLIES, 0.0);
        Needs needs = new Needs(values);
        values.put(Need.SOCIAL, 1.0);
        Score score = pick(needs, List.of(option(Activity.HANG_OUT,
                Map.of(Need.SUPPLIES, 1.0, Need.SOCIAL, 1.0), 0, 0))).ranking().getFirst();
        assertEquals(Map.of(Need.SUPPLIES, 0.0), score.contributions());
        assertFalse(needs.values().containsKey(Need.SOCIAL));
        assertThrows(UnsupportedOperationException.class, () -> needs.values().clear());
    }

    @Test
    void injectedNoiseIsBoundedAndZeroAmplitudeDoesNotReadRandomness() {
        List<Option> options = List.of(option(Activity.GRIND, Map.of(), .5, 0),
                option(Activity.HANG_OUT, Map.of(), .5, 0));
        assertEquals(Activity.HANG_OUT, evaluate(EMPTY, options, null, 0, 0, .01,
                a -> a == Activity.HANG_OUT ? 1 : -1, false).selected().orElseThrow());
        assertDoesNotThrow(() -> evaluate(EMPTY, options, null, 0, 0, 0,
                a -> { throw new AssertionError("noise disabled"); }, false));
        assertThrows(IllegalArgumentException.class, () -> evaluate(EMPTY, options, null, 0, 0, .01,
                a -> 1.1, false));
    }

    @Test
    void backwardsClockCannotIncreaseStickinessBeyondItsInitialValue() {
        Result result = evaluate(EMPTY, List.of(option(Activity.GRIND, Map.of(), 0, 0)),
                new Current(Activity.GRIND, 100, 1000, .8), -100, 0, 0, a -> 0, false);
        assertEquals(.8, result.ranking().getFirst().stickiness());
    }

    @Test
    void invalidNumbersDuplicateOptionsAndOverflowAreRejected() {
        for (double value : new double[]{-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Needs(Map.of(Need.SOCIAL, value)));
        }
        assertThrows(IllegalArgumentException.class, () -> option(Activity.GRIND, Map.of(), Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Current(Activity.GRIND, 0, 0, 0));
        Option grind = option(Activity.GRIND, Map.of(), 0, 0);
        assertThrows(IllegalArgumentException.class, () -> pick(EMPTY, List.of(grind, grind)));
        assertThrows(IllegalArgumentException.class, () -> evaluate(EMPTY, List.of(), null, 0, -1, 0, a -> 0, false));
        assertThrows(IllegalArgumentException.class, () -> pick(EMPTY,
                List.of(option(Activity.GRIND, Map.of(), Double.MAX_VALUE, Double.MAX_VALUE))));
    }

    @Test
    void threeThousandInjectedSnapshotsProduceRepeatableBoundedRankings() {
        // Work-count check, not a CPU/soak claim or a timing assertion on CI.
        List<Option> options = new ArrayList<>();
        for (Activity activity : Activity.values()) {
            options.add(option(activity, Map.of(Need.SOCIAL, .5), 0, 0));
        }
        for (int bot = 0; bot < 3000; bot++) {
            Needs needs = new Needs(Map.of(Need.SOCIAL, (bot % 101) / 100.0));
            Result result = pick(needs, options);
            assertEquals(3, result.topThree().size());
            assertEquals(Activity.GRIND, result.selected().orElseThrow());
        }
    }

    private static Option option(Activity activity, Map<Need, Double> weights, double context, double personality) {
        return new Option(activity, weights, context, personality, true, "fixture");
    }

    private static Result pick(Needs needs, List<Option> options) {
        return evaluate(needs, options, null, 0, 0, 0, a -> 0, false);
    }
}
