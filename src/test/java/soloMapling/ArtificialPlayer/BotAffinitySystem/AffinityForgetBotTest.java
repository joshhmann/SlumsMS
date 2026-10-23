package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 auto-QA triage (t-af-1): a re-staged bot id kept the last run's greet/invite/buddy cooldowns. */
class AffinityForgetBotTest {

    static final int BOT = BotStore.BOT_ID_BASE + 100;
    static final int OTHER = BotStore.BOT_ID_BASE + 101;
    static final int PLAYER = 44;

    @BeforeEach
    @AfterEach
    void reset() {
        AffinityReactions.resetForTest();
    }

    @Test
    void unstagingForgetsTheBotsCooldownsAndOnlyItsOwn() {
        long now = 1_000_000L;
        assertTrue(AffinityReactions.take(AffinityReactions.Action.GREET, BOT, PLAYER, now));
        assertTrue(AffinityReactions.take(AffinityReactions.Action.BUDDY, BOT, PLAYER, now));
        assertTrue(AffinityReactions.take(AffinityReactions.Action.GREET, OTHER, PLAYER, now));
        assertFalse(AffinityReactions.take(AffinityReactions.Action.GREET, BOT, PLAYER, now + 1000), "cooling down");
        AffinityReactions.forgetBot(BOT);
        assertTrue(AffinityReactions.take(AffinityReactions.Action.GREET, BOT, PLAYER, now + 2000), "a fresh bot");
        assertTrue(AffinityReactions.take(AffinityReactions.Action.BUDDY, BOT, PLAYER, now + 2000));
        assertFalse(AffinityReactions.take(AffinityReactions.Action.GREET, OTHER, PLAYER, now + 2000), "others keep theirs");
    }
}
