package soloMapling.FreeMarket;

import client.inventory.Equip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.SaleRules;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner 2026-10-29: the live site showed bot FM shops selling Zakum Helmets for ~600k. Generated bot-shop stock never
 * went through {@link SaleRules}, and the desirable-equip list skipped every other check, so bot shops minted
 * boss-only drops. Generated stock now follows the same rules as what a bot may sell.
 */
class BotShopStockRulesTest {

    private static final int ZAKUM_HELMET = 1002357, HORNTAIL_NECKLACE = 1122000, WORK_GLOVES = 1082002;

    @AfterEach
    void reset() {
        SaleRules.useForTest(null, null, null);
    }

    private static Equip equip(int id) {
        Equip e = mock(Equip.class); // a real Equip loads item data from the DB
        when(e.getItemId()).thenReturn(id);
        return e;
    }

    @Test
    void bossOnlyDropsAreNeverStockedButOrdinaryGearIs() {
        SaleRules.useForTest(Set.of(ZAKUM_HELMET, HORNTAIL_NECKLACE), id -> false, id -> false);
        assertFalse(EquipListGenerator.stockable(equip(ZAKUM_HELMET)), "Zakum Helmet");
        assertFalse(EquipListGenerator.stockable(equip(HORNTAIL_NECKLACE)), "Horntail Necklace");
        assertTrue(EquipListGenerator.stockable(equip(WORK_GLOVES)), "ordinary gear stays");
        assertFalse(EquipListGenerator.stockable(null));
    }
}
