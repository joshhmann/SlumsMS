package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* 1.5.0 rc6 (QA t-rm-2): an any-class bot asked for a level range gets a level in it (one level: exactly that). */
class BotDecorateLevelRangeTest {

    @Test
    void oneLevelIsExactlyThatLevel() {
        for (soloMapling.ArtificialPlayer.BotTier tier : soloMapling.ArtificialPlayer.BotTier.values()) {
            assertEquals(60, BotDecorate.levelInRange(tier, 60, 60));
        }
    }

    @RepeatedTest(20)
    void aRangeStaysInsideIt() {
        for (soloMapling.ArtificialPlayer.BotTier tier : soloMapling.ArtificialPlayer.BotTier.values()) {
            int level = BotDecorate.levelInRange(tier, 4, 16);
            assertTrue(level >= 4 && level <= 16, tier + " gave " + level);
        }
    }
}
