package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MegaphoneWeightsTest {

    @AfterEach
    void clear() {
        for (String c : SocialHotPotatoManager.MEGA_CATEGORIES) {
            System.clearProperty("solomapling.megaWeight." + c);
        }
    }

    private static Map<String, Integer> distribution() {
        Map<String, Integer> counts = new HashMap<>();
        int steps = 10_000;
        for (int i = 0; i < steps; i++) {
            counts.merge(SocialHotPotatoManager.pickWeightedMegaCategory((i + 0.5) / steps), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void rwtSpamIsFlavourByDefault() {
        assertEquals(300, distribution().getOrDefault("RWTSpam", 0), "3% of megaphones");
    }

    @Test
    void theOwnerCanChangeAWeightWithASystemProperty() {
        System.setProperty("solomapling.megaWeight.RWTSpam", "0");
        assertEquals(0, distribution().getOrDefault("RWTSpam", 0));
        System.setProperty("solomapling.megaWeight.RWTSpam", "19");
        int total = 0;
        for (int w : SocialHotPotatoManager.MEGA_DEFAULT_WEIGHTS) {
            total += w;
        }
        int expected = (int) Math.round(10_000.0 * 19 / (total - 3 + 19));
        assertEquals(expected, distribution().get("RWTSpam"), 2.0);
    }

    @Test
    void allZeroMeansNoAmbientMegaphones() {
        for (String c : SocialHotPotatoManager.MEGA_CATEGORIES) {
            System.setProperty("solomapling.megaWeight." + c, "0");
        }
        assertNull(SocialHotPotatoManager.pickWeightedMegaCategory(0.5));
    }
}
