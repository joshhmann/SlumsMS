package server.life;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc3b: SaleRules asks whether a drop_data dropper exists before loading it, so missing mobs log nothing. */
class LifeFactoryMonsterExistsTest {

    @Test
    void knownMobExistsAndMissingOneDoesNot() {
        assertTrue(LifeFactory.monsterExists(100100));     // Snail
        assertFalse(LifeFactory.monsterExists(9999998));   // no such Mob.wz entry
    }
}
