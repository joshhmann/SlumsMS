package soloMapling.FreeMarket;

import client.inventory.Equip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.SaleRules;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final int ZAKUM_HELMET = 1002357, ZAKUM_2 = 1002390, ZAKUM_3 = 1002430, HORNTAIL_NECKLACE = 1122000, WORK_GLOVES = 1082002;

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
        SaleRules.useForTest(Set.of(ZAKUM_HELMET, ZAKUM_2, ZAKUM_3, HORNTAIL_NECKLACE), id -> false, id -> false);
        assertFalse(EquipListGenerator.stockable(equip(ZAKUM_HELMET)), "Zakum Helmet");
        assertFalse(EquipListGenerator.stockable(equip(ZAKUM_2)), "Zakum Helmet 2");
        assertFalse(EquipListGenerator.stockable(equip(ZAKUM_3)), "Zakum Helmet 3");
        assertFalse(EquipListGenerator.stockable(equip(HORNTAIL_NECKLACE)), "Horntail Necklace");
        assertTrue(EquipListGenerator.stockable(equip(WORK_GLOVES)), "ordinary gear stays");
        assertFalse(EquipListGenerator.stockable(null));
    }

    @Test
    void theYamlListsAllThreeZakumHelmets() {
        SaleRules.useForTest(null, id -> false, id -> false);
        for (int id : new int[]{ZAKUM_HELMET, ZAKUM_2, ZAKUM_3}) {
            assertTrue(soloMapling.Economy.EconomyConfig.map("saleRules.bossOnly").containsKey(id), "yaml " + id);
        }
    }

    @Test
    void godlyExclusionIsItsOwnSwitchAndAnOwnGodlyPieceMayBeListedOrConsigned() {
        SaleRules.useForTest(Set.of(ZAKUM_HELMET), id -> false, id -> false);
        Equip godly = equip(WORK_GLOVES);
        try (var gg = org.mockito.Mockito.mockStatic(soloMapling.Economy.GearGrade.class)) {
            gg.when(() -> soloMapling.Economy.GearGrade.godly(godly)).thenReturn(true);
            assertEquals("godly gear", SaleRules.whyNot(godly), "gifts, Mega Buy, bot-to-bot keep refusing");
            assertEquals(null, SaleRules.whyNotListOwn(godly), "a bot lists its own godly gear");
            assertEquals("boss-only drop", SaleRules.whyNotListOwn(equip(ZAKUM_HELMET)), "boss-only still refused");
            soloMapling.Economy.EconomyConfig.useForTest(java.util.Map.of("botShops.generatedStockExcludesGodly", "true"));
            assertFalse(EquipListGenerator.stockable(godly), "generated stock leaves godly out by default");
            soloMapling.Economy.EconomyConfig.useForTest(java.util.Map.of("botShops.generatedStockExcludesGodly", "false"));
            assertTrue(EquipListGenerator.stockable(godly), "switch off: godly may be generated");
            assertFalse(EquipListGenerator.stockable(equip(ZAKUM_HELMET)), "boss-only is out whatever the switch says");
        } finally {
            soloMapling.Economy.EconomyConfig.useForTest(null);
        }
    }

    @Test
    void generatedScrollStockNeverMintsChaosWhiteCashOrBossOnly() {
        SaleRules.useForTest(Set.of(2040000), id -> id == 2022000, id -> false);
        for (int id : new int[]{2049100, 2049101, 2049102, 2049103, 2340000, 2022000, 2040000}) {
            assertFalse(ArtificialShopGenerator.scrollStockable(id), "" + id);
        }
        assertTrue(ArtificialShopGenerator.scrollStockable(2040001));
    }
}
