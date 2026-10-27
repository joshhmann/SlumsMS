package soloMapling.ArtificialPlayer.BotCoupleSystem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** TEST t-we-1 2026-10-26 22:28: "No couple could be staged here." with ten bots made for a same-gender pair. */
class CoupleStagingPairTest {

    static final int M = 0, F = 1;

    @Test
    void anyTwoOfTheBotsMadePairUpNotOnlyTheFirst() {
        // the first is a woman, the rest men: the old rule only compared against the first and never found a pair
        assertArrayEquals(new int[]{1, 2}, CoupleStaging.pickPair(List.of(F, M, M), true));
        assertNull(CoupleStaging.pickPair(List.of(F, M), true));
    }

    @Test
    void mixedNeedsOneOfEach() {
        assertNull(CoupleStaging.pickPair(List.of(M, M, M), false));
        assertArrayEquals(new int[]{0, 3}, CoupleStaging.pickPair(List.of(M, M, M, F), false));
    }

    @Test
    void anyGenderTakesTheFirstTwo() {
        assertArrayEquals(new int[]{0, 1}, CoupleStaging.pickPair(List.of(F, M), null));
        assertNull(CoupleStaging.pickPair(List.of(F), null));
    }
}
