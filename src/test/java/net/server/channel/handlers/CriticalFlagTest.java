package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.2 Cosmic parity: critical hits are flagged in the attack broadcast again, so other players' clients draw
 * them as crits. The flag is the sign bit; the low 31 bits still carry the number the client shows.
 */
class CriticalFlagTest {

    @Test
    void aCritCarriesTheSignBitAndTheSameNumber() {
        int marked = AbstractDealDamageHandler.markCritical(123_456);

        assertTrue(marked < 0, "sign bit set");
        assertEquals(123_456, marked & 0x7FFFFFFF, "the client shows the real number");
    }

    @Test
    void theServerStillAppliesTheHitAfterDecodingIt() {
        // applyAttack decodes with eachd += Integer.MAX_VALUE, as in Cosmic (one point lost to Cosmic's encoding).
        int marked = AbstractDealDamageHandler.markCritical(50_000);
        assertEquals(49_999, AbstractDealDamageHandler.lineDamage(marked));
        assertEquals(49_999, marked + Integer.MAX_VALUE);
    }

    @Test
    void theLargestHitStillDecodesPositive() {
        int marked = AbstractDealDamageHandler.markCritical(Integer.MAX_VALUE);

        assertTrue(marked < 0);
        assertEquals(Integer.MAX_VALUE - 1, AbstractDealDamageHandler.lineDamage(marked));
    }

    @Test
    void aMissIsNeverFlagged() {
        assertEquals(0, AbstractDealDamageHandler.markCritical(0));
    }

    @Test
    void anUnflaggedLineIsUnchanged() {
        assertEquals(777, AbstractDealDamageHandler.lineDamage(777));
    }
}
