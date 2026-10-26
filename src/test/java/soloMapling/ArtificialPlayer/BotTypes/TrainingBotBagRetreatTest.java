package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 1.6.0 owner report 2026-10-26: a bot whose bag is nearly full leaves the grind for town to sell, like a player,
// instead of grinding on over drops it cannot take. Once per cooldown, so a bag of keepers cannot loop it.
class TrainingBotBagRetreatTest {

    private static final long NOW = 10_000_000L;

    @Test
    void aNearlyFullBagSendsTheBotToTown() {
        assertTrue(TrainingBot.bagRetreatDue(true, false, NOW, Long.MIN_VALUE));
    }

    @Test
    void roomInTheBagOrAPlayerTalkingKeepsItGrinding() {
        assertFalse(TrainingBot.bagRetreatDue(false, false, NOW, Long.MIN_VALUE));
        assertFalse(TrainingBot.bagRetreatDue(true, true, NOW, Long.MIN_VALUE));
    }

    @Test
    void itGoesAtMostOncePerCooldown() {
        long last = NOW - TrainingBot.BAG_RETREAT_COOLDOWN_MS + 1;
        assertFalse(TrainingBot.bagRetreatDue(true, false, NOW, last));
        assertTrue(TrainingBot.bagRetreatDue(true, false, NOW + 1, last));
    }
}
