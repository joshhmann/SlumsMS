package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.4.2 Cosmic parity: Mana Reflection bounces x% of the hit back (the repack had made it a flat 20), with
 * Cosmic's cap of a fifth of the mob's max HP worked out in longs for MapleRoot's huge mob HP.
 */
class ManaReflectionTest {

    @Test
    void reflectsThePercentageOfTheHit() {
        assertEquals(400, TakeDamageHandler.manaReflectDamage(1000, 40, 1_000_000L));
    }

    @Test
    void isCappedAtAFifthOfTheMobsMaxHp() {
        assertEquals(200, TakeDamageHandler.manaReflectDamage(5000, 50, 1000L));
    }

    @Test
    void hugeMapleRootMobHpNeitherOverflowsNorCapsTheBounce() {
        // Cosmic's int (getMaxHp() / 5) would not even compile against a long HP; past 2^31 it would wrap.
        assertEquals(30_000, TakeDamageHandler.manaReflectDamage(100_000, 30, 50_000_000_000L));
    }

    @Test
    void aHugeHitDoesNotOverflowTheProduct() {
        // damage * x in int wraps negative for a 2^31-ish hit; the long product does not.
        assertEquals(1_932_735_282, TakeDamageHandler.manaReflectDamage(Integer.MAX_VALUE, 90, Long.MAX_VALUE));
    }

    @Test
    void neverNegative() {
        assertEquals(0, TakeDamageHandler.manaReflectDamage(1000, 40, 0L));
    }
}
