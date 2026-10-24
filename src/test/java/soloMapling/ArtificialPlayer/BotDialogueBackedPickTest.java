package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * rc6 say-do: a merchant's flavor lines (scam, begging, RWT, funny) are picked so that a claim line ("S> my soul",
 * "follow me to the top of the map for a secret drop party", "trade me") is said only when the bot would back it.
 * The rc6 auto-QA run of 21095df97 had these lines UNBACKED in every cv-* check.
 */
class BotDialogueBackedPickTest {

    static final List<String> SCAM = List.of(
            "Follow me to the top of the map for a secret drop party!",
            "S> My soul for 1m mesos... anyone?",
            "trade me any equip and ill upgrade it for free no scam i swear",
            "why is everyone so broke in this channel");

    @Test
    void aClaimTheBotWouldNotBackIsNeverPicked() {
        Random rng = new Random(7);
        for (int i = 0; i < 200; i++) {
            int idx = BotDialogueHandler.pickBacked(SCAM, line -> false, rng);
            assertEquals(3, idx, "only the claim-free line");
        }
    }

    @Test
    void aClaimItBacksMayBePicked() {
        Random rng = new Random(11);
        boolean sawTrade = false;
        for (int i = 0; i < 200; i++) {
            int idx = BotDialogueHandler.pickBacked(SCAM, line -> line.startsWith("trade me"), rng);
            assertNotEquals(0, idx);
            assertNotEquals(1, idx);
            sawTrade |= idx == 2;
        }
        assertEquals(true, sawTrade, "a merchant that takes trades may say 'trade me'");
    }

    @Test
    void nothingWhenEveryLineClaimsWhatItWouldNotDo() {
        assertEquals(-1, BotDialogueHandler.pickBacked(SCAM.subList(0, 3), line -> false, new Random(3)));
    }
}
