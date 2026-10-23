package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/* 1.5.0 rc6 (QA t-rm-2): staging asked for a lv 60 partner and got lv 27, 48, 78 - a level range with no class fell
 * into the random 10-80 path. */
class BotGenerationLevelRangeTest {

    @Test
    void aLevelRangeWithoutAClassKeepsItsRange() {
        assertEquals(BotGeneration.Decoration.LEVEL_RANGE, BotGeneration.decorationFor(0, 60));
        assertEquals(BotGeneration.Decoration.LEVEL_RANGE, BotGeneration.decorationFor(0, 4));
    }

    @Test
    void noRangeIsTheRandomMixAndAClassIsTheClassPath() {
        assertEquals(BotGeneration.Decoration.RANDOM, BotGeneration.decorationFor(0, 0));
        assertEquals(BotGeneration.Decoration.CLASS, BotGeneration.decorationFor(3, 30));
    }
}
