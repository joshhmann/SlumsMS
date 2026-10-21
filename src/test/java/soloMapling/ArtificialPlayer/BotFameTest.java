package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotFameTest {

    @Test
    void sameBotSameLevelSameFame() {
        assertEquals(BotFame.forBot(1_000_000_123, 70), BotFame.forBot(1_000_000_123, 70));
    }

    @Test
    void neverAnIdLikeNumberAndAlwaysInRange() {
        for (int id = 1_000_000_000; id < 1_000_020_000; id++) {
            for (int level : new int[]{1, 10, 30, 70, 120, 200, 250}) {
                int fame = BotFame.forBot(id, level);
                assertTrue(fame >= -20 && fame <= Math.round(level * 1.2) + 1, "id " + id + " lv " + level + ": " + fame);
            }
        }
    }

    @Test
    void lowLevelBotsHaveLittleFameAndTheSpreadLooksLikePlayers() {
        int n = 20_000, zeroish = 0, high = 0, negative = 0;
        long sum70 = 0;
        for (int i = 0; i < n; i++) {
            int id = 1_000_000_000 + i;
            if (BotFame.forBot(id, 5) <= 1) zeroish++;
            int f = BotFame.forBot(id, 70);
            sum70 += f;
            if (f >= 35) high++;
            if (f < 0) negative++;
        }
        assertTrue(zeroish > n * 0.6, "most level-5 bots have 0-1 fame: " + zeroish);
        double avg = (double) sum70 / n;
        assertTrue(avg > 5 && avg < 25, "average fame at 70: " + avg);
        assertTrue(high > n * 0.03 && high < n * 0.2, "a few well-known bots at 70: " + high);
        assertTrue(negative > n * 0.03 && negative < n * 0.1, "occasional negative fame: " + negative);
    }

    @Test
    void fameGrowsWithLevelForTheSameBot() {
        // A bot with positive fame keeps it and gains as it levels; the few defamed bots stay a small negative.
        for (int i = 0; i < 5000; i++) {
            int id = 1_000_000_000 + i;
            int at30 = BotFame.forBot(id, 30);
            int at120 = BotFame.forBot(id, 120);
            if (at30 >= 0) {
                assertTrue(at120 >= at30, "id " + id + ": " + at30 + " -> " + at120);
            } else {
                assertTrue(at120 < 0 && at120 >= -20, "id " + id + ": " + at30 + " -> " + at120);
            }
        }
    }
}
