package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The seam the tier rewards travel through: the number {@code KpqStaging} hands to {@code EventManager.startInstance},
 * which the event scripts receive as {@code setup(level, lobbyid)} and store as the instance's "level" property.
 *
 * <p>NORMAL must be the literal 1 those scripts hardcoded before tiers existed, because a level-1 run has to stay
 * byte-identical to the stock party quest; everything above NORMAL is its ordinal plus one, which is the number the
 * scripts switch on.
 */
class TierPaySeamTest {

    @Test
    void normalIsOneAndEachTierIsItsOrdinalPlusOne() {
        assertEquals(1, KpqStaging.difficulty(MechanicsModel.Tier.NORMAL), "NORMAL is the stock literal");
        assertEquals(2, KpqStaging.difficulty(MechanicsModel.Tier.HARD));
        assertEquals(3, KpqStaging.difficulty(MechanicsModel.Tier.EXPERT));
        assertEquals(4, KpqStaging.difficulty(MechanicsModel.Tier.MASTER));
        assertEquals(1, KpqStaging.difficulty(null), "an unknown tier is NORMAL, not a crash");
    }
}
