package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.5.0 rc3 (spread): grind spot claims are kept per map instance, so channels no longer fill each other's spots. */
class BotSpotClaimsInstanceTest {

    static final int MAP = 104040000;

    @AfterEach
    void reset() {
        for (int bot = 1; bot <= 6; bot++) {
            BotSpotClaims.releaseAllFor(bot);
        }
    }

    @Test
    void channelsHaveTheirOwnSlots() {
        long ch1 = BotSpotClaims.instanceKey(1, MAP);
        long ch2 = BotSpotClaims.instanceKey(2, MAP);
        assertEquals(0, BotSpotClaims.claim(ch1, 3, 1, 1));
        assertEquals(-1, BotSpotClaims.claim(ch1, 3, 1, 2)); // full on channel 1
        assertEquals(0, BotSpotClaims.claim(ch2, 3, 1, 2));  // free on channel 2
        assertEquals(1, BotSpotClaims.holders(ch1, 3));
        assertEquals(2, BotSpotClaims.holdersAllChannels(MAP, 3));
        assertEquals(0, BotSpotClaims.holders(MAP, 3));      // the old channel-blind key is separate
    }

    @Test
    void theOldIntKeyIsChannelZero() {
        assertEquals(MAP, BotSpotClaims.instanceKey(0, MAP));
        assertEquals(0, BotSpotClaims.claim(MAP, 1, 2, 5));
        assertEquals(1, BotSpotClaims.holders(BotSpotClaims.instanceKey(0, MAP), 1));
        BotSpotClaims.release(MAP, 1, 5);
        assertEquals(0, BotSpotClaims.holders(MAP, 1));
        assertEquals(MAP, BotSpotClaims.mapIdOf(BotSpotClaims.instanceKey(3, MAP)));
    }
}
