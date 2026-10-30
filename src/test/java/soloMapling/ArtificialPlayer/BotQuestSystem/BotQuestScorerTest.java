package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 158 item 3: a quest is worth its detour in the bot's own EXP per minute. */
class BotQuestScorerTest {
    static final BotQuestScorer.MobExp TEN = mob -> 10;

    @Test
    void rewardAgainstTheTripAtTheGrindRate() {
        // 600 EXP reward, a 60 s round trip, grinding at 300 EXP/min: worth 2x the grind
        double s = BotQuestScorer.score(Map.of(1, 10), 600, 0, Set.of(1), TEN, 2, 60, 300);
        assertEquals(2.0, s, 1e-9);
        assertTrue(s >= BotQuestScorer.TAKE_MIN_SCORE);
    }

    @Test
    void killsOnTheGrindMapAreNeitherValueNorCost() {
        double few = BotQuestScorer.score(Map.of(1, 10), 600, 0, Set.of(1), TEN, 2, 60, 300);
        double many = BotQuestScorer.score(Map.of(1, 500), 600, 0, Set.of(1), TEN, 2, 60, 300);
        assertEquals(few, many, 1e-9, "the bot kills them anyway");
    }

    @Test
    void killsElsewhereCostTheirTimeAndEarnTheirExp() {
        // 10 kills elsewhere at 6 s each = 60 s more and 100 EXP more: (600 + 100) / ((60 + 60) / 60 * 300)
        double s = BotQuestScorer.score(Map.of(1, 10), 600, 0, Set.of(), TEN, 6, 60, 300);
        assertEquals(700.0 / 600.0, s, 1e-9);
    }

    @Test
    void aSmallRewardFarAwayIsNotWorthIt() {
        double s = BotQuestScorer.score(Map.of(1, 10), 50, 0, Set.of(1), TEN, 2, 240, 300);
        assertTrue(s < BotQuestScorer.RECOMMEND_MIN_SCORE, "score " + s);
    }

    @Test
    void noRewardNoValueAndNoTripIsPureWin() {
        assertEquals(0.0, BotQuestScorer.score(Map.of(1, 10), 0, 0, Set.of(1), TEN, 2, 60, 300));
        assertEquals(Double.MAX_VALUE, BotQuestScorer.score(Map.of(1, 10), 100, 0, Set.of(1), TEN, 2, 0, 300));
        // a bot that barely grinds is floored, never divided by zero
        assertTrue(Double.isFinite(BotQuestScorer.score(Map.of(1, 10), 100, 0, Set.of(1), TEN, 2, 60, 0)));
    }
}
