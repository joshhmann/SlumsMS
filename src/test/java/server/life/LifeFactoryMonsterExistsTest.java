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

    // 1.5.0 rc4: on the rc4 test deploy these droppers exist in Mob.wz but lack level/maxHP; getMonster logged
    // "MOB n failed to load" for each when the first bot shop opened.
    @Test
    void theBossFlagIsReadQuietlyForIncompleteAndMissingMobs() {
        assertFalse(LifeFactory.monsterIsBoss(100100));     // Snail
        assertTrue(LifeFactory.monsterIsBoss(6130101));     // Mushmom
        assertFalse(LifeFactory.monsterIsBoss(9999998));    // no such Mob.wz entry
        for (int mob : new int[] {4300017, 9302010, 9500320, 9400523, 9400534}) {
            LifeFactory.monsterIsBoss(mob);                 // never throws
        }
    }
}
