package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.1 promise audit: t-bt-1 left Bob's answer to chance, and on TEST he said no, so no party was ever proven. While
 * TalkQa forces a yes, a listener says yes to any ask the game allows; the game's rule still wins, and outside the
 * forced talk the answer is the bot's own again.
 */
class TalkQaForcedYesTest {

    private static TalkBrain.Mind unwilling() {
        // a busy killer in a bad mood: willingness 0 for a party
        return new TalkBrain.Mind(2, "Bob", 50, 0, 1, 0, TalkBrain.Goal.values()[0], 0, false, false, false, -1, true);
    }

    private static TalkBrain.Mind asker() {
        return new TalkBrain.Mind(1, "Ann", 50, 0.5, 0.5, 0.5, TalkBrain.Goal.values()[0], 0, false, false, false, -1, false);
    }

    /** A Random that would always say no. */
    private static Random never() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.999;
            }
        };
    }

    @Test
    void aForcedTalkSaysYesWhenTheGameAllowsIt() {
        assertFalse(TalkBrain.answer(unwilling(), asker(), TalkBrain.Relation.STRANGER, Ask.PARTY,
                TalkBrain.Feasible.YES, -1, never()).yes(), "left to the bot, it says no");
        TalkBrain.QA_YES.set(true);
        try {
            assertTrue(TalkBrain.answer(unwilling(), asker(), TalkBrain.Relation.STRANGER, Ask.PARTY,
                    TalkBrain.Feasible.YES, -1, never()).yes());
            assertFalse(TalkBrain.answer(unwilling(), asker(), TalkBrain.Relation.STRANGER, Ask.PARTY,
                    TalkBrain.Feasible.no("already in a party"), -1, never()).yes(), "the game's rule still wins");
        } finally {
            TalkBrain.QA_YES.remove();
        }
        assertFalse(TalkBrain.answer(unwilling(), asker(), TalkBrain.Relation.STRANGER, Ask.PARTY,
                TalkBrain.Feasible.YES, -1, never()).yes(), "back to the bot's own answer");
    }
}
