package soloMapling.FreeMarket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 1.4.2: a normal value for equips the YAML item pool does not price, so FM bots can judge them.
class EquipValuationTest {

    private static EquipValuation.Facts clean(int reqLevel, int slots) {
        return new EquipValuation.Facts(reqLevel, 0, slots, slots, 0, 0, 0, 0, 0, false, false);
    }

    private static EquipValuation.Facts scrolled(int reqLevel, int slots, int passed, int failed, int stat, int att) {
        return new EquipValuation.Facts(reqLevel, 0, slots, slots - passed - failed, passed, 0, stat, att, 0,
                false, false);
    }

    @Test
    void aCleanEquipIsWorthItsLevelBasePlusItsSlots() {
        assertEquals(2_500, EquipValuation.estimate(clean(0, 0)));
        // 25 * 80^2 = 160,000, plus 7 slots at 5% each
        assertEquals(216_000, EquipValuation.estimate(clean(70, 7)));
    }

    @Test
    void higherLevelIsWorthMore() {
        assertTrue(EquipValuation.estimate(clean(100, 7)) > EquipValuation.estimate(clean(50, 7)));
    }

    @Test
    void neverBelowTwiceTheNpcPrice() {
        EquipValuation.Facts f = new EquipValuation.Facts(10, 50_000, 0, 0, 0, 0, 0, 0, 0, false, false);
        assertEquals(100_000, EquipValuation.estimate(f));
    }

    @Test
    void attackFromScrollsAddsALotStatsSomeAndBurnedSlotsTakeOff() {
        int clean = EquipValuation.estimate(clean(30, 5));
        int attack = EquipValuation.estimate(scrolled(30, 5, 3, 0, 0, 6));
        int stats = EquipValuation.estimate(scrolled(30, 5, 3, 0, 6, 0));
        int burned = EquipValuation.estimate(scrolled(30, 5, 0, 5, 0, 0));
        assertTrue(attack > stats && stats > clean, attack + " > " + stats + " > " + clean);
        assertTrue(attack >= 600_000, "6 attack is worth at least 600k: " + attack);
        assertTrue(burned < clean, "all slots burned is worth less than clean: " + burned);
    }

    @Test
    void belowBaseStatsLowerTheValueButNotBelowAQuarter() {
        int clean = EquipValuation.estimate(clean(50, 7));
        int poor = EquipValuation.estimate(new EquipValuation.Facts(50, 0, 7, 7, 0, 0, -4, -2, 0, false, false));
        assertTrue(poor < clean && poor >= clean / 4);
    }

    @Test
    void capped() {
        EquipValuation.Facts godly = new EquipValuation.Facts(120, 0, 7, 0, 7, 0, 60, 40, 500, false, false);
        assertEquals(EquipValuation.MAX_VALUE, EquipValuation.estimate(godly));
    }

    @Test
    void cashAndUntradeableEquipsHaveNoValue() {
        assertNull(EquipValuation.estimate(new EquipValuation.Facts(0, 0, 0, 0, 0, 0, 0, 0, 0, true, false)));
        assertNull(EquipValuation.estimate(new EquipValuation.Facts(30, 0, 5, 5, 0, 0, 0, 0, 0, false, true)));
        assertNull(EquipValuation.estimate(null));
    }

    @Test
    void hammeredSlotsAreNotCountedAsBurned() {
        EquipValuation.Facts hammered = new EquipValuation.Facts(70, 0, 7, 8, 0, 1, 0, 0, 0, false, false);
        assertTrue(EquipValuation.estimate(hammered) > EquipValuation.estimate(clean(70, 7)));
    }
}
