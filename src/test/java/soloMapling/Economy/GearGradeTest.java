package soloMapling.Economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.EquipValuation.Facts;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GearGradeTest {

    static final int SWORD = 1302000, GLOVES = 1082002;

    @BeforeEach
    void setUp() {
        EconomyConfig.useForTest(Map.of());
    }

    @AfterEach
    void tearDown() {
        EconomyConfig.useForTest(null);
    }

    /** reqLevel 50, npc 10k, `slots` WZ slots, `passed` passes, the gains given. */
    static Facts f(int slots, int left, int passed, int stat, int att) {
        return new Facts(50, 10_000, slots, left, passed, 0, stat, att, 0, false, false);
    }

    @Test
    void sixtyPercentScrollsOnEverySlotAreNeverGodly() {
        // gear audit: a 5-slot glove passing every 60% glove ATT (+2) is +10: the most 60%s can give
        assertEquals(GearGrade.Grade.ABOVE, GearGrade.grade(GLOVES, f(5, 0, 5, 0, 10)));
        assertEquals(GearGrade.Grade.GODLY, GearGrade.grade(GLOVES, f(5, 0, 5, 0, 11)), "past it: a 10% pass");
        // an 8-slot weapon: 2 x 8 + 5 for the drop roll = 21
        assertEquals(GearGrade.Grade.ABOVE, GearGrade.grade(SWORD, f(8, 0, 8, 0, 21)));
        assertEquals(GearGrade.Grade.GODLY, GearGrade.grade(SWORD, f(8, 0, 8, 0, 22)));
        // main stats: 2 x slots + 2
        assertEquals(GearGrade.Grade.GODLY, GearGrade.grade(GLOVES, f(5, 0, 5, 13, 0)));
        assertEquals(GearGrade.Grade.ABOVE, GearGrade.grade(GLOVES, f(5, 0, 5, 12, 0)));
    }

    @Test
    void cleanAndBurnedGear() {
        assertEquals(GearGrade.Grade.AVERAGE, GearGrade.grade(GLOVES, f(5, 5, 0, 0, 0)));
        assertEquals(GearGrade.Grade.ABOVE, GearGrade.grade(GLOVES, f(5, 5, 0, 3, 0)), "a +3 stat clean roll");
        assertEquals(GearGrade.Grade.BELOW, GearGrade.grade(GLOVES, f(5, 2, 1, 0, 2)), "two slots burned");
        assertEquals(GearGrade.Grade.BELOW, GearGrade.grade(GLOVES, f(5, 5, 0, -2, 0)));
    }

    @Test
    void ownerTunablesMoveTheLine() {
        EconomyConfig.useForTest(Map.of("gear.godlyAttackPerSlot", "1"));
        assertEquals(GearGrade.Grade.GODLY, GearGrade.grade(GLOVES, f(5, 0, 5, 0, 6)));
    }

    @Test
    void qualityIsValueNextToACleanOne() {
        assertEquals(1.0, GearGrade.quality(f(5, 5, 0, 0, 0)), 1e-9);
        assertTrue(GearGrade.quality(f(5, 0, 5, 0, 10)) > 1.5);
        assertTrue(GearGrade.quality(f(5, 0, 0, 0, 0)) < 1.0, "burned slots are worth less");
    }
}
