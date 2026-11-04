package soloMapling.ArtificialPlayer.BotCoupleSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ticket 188 (MA-03): a refused wedding start must not use up the day's one wedding window. */
class WeddingWindowTest {
    private static final BotCouples.Couple C = new BotCouples.Couple(1, 1, 2, BotCouples.Stage.DATING, 0, 0, 0);

    @Test
    void refusedStartKeepsTheWindowOpen() {
        BotCouples.useForTest(null, null);
        BotCouples.tryStartWedding(C, 5_000L, c -> "someone is busy");
        assertEquals(0L, BotCouples.lastWeddingAtForTest());
    }

    @Test
    void successfulStartUsesTheWindow() {
        BotCouples.useForTest(null, null);
        BotCouples.tryStartWedding(C, 5_000L, c -> null);
        assertEquals(5_000L, BotCouples.lastWeddingAtForTest());
    }
}
