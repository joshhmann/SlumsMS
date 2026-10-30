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

    private static final int GELT_CHOCOLATE = 2022121, ONYX_APPLE = 2022179, GUARDIAN_30 = 2290015;

    /** Every generated non-equip line (potions, stars, ETC, mastery books, chairs). */
    private static java.util.List<FMItem> everyUseLine(String tier) {
        java.util.List<FMItem> out = new java.util.ArrayList<>();
        out.addAll(ArtificialShopGenerator.generatePotionsList(tier));
        out.addAll(ArtificialShopGenerator.generateThiefStarsList(tier));
        out.addAll(ArtificialShopGenerator.generateETCList(tier));
        out.addAll(ArtificialShopGenerator.generateMasteryBookList(tier));
        out.addAll(ArtificialShopGenerator.generateChairList(tier));
        return out;
    }

    /**
     * TEST 2026-10-29, t-bs-2: 141 bot-shop listings refused by SaleRules, all from the generated potion, mastery
     * book and chair lines (Gelt Chocolate is tradeBlock in Item.wz; Guardian 30 and Onyx Apple count as boss-only).
     * Only scrolls and gear followed SaleRules; now every generated line does.
     */
    @Test
    void generatedUseEtcAndSetupStockFollowsSaleRules() {
        SaleRules.useForTest(Set.of(ONYX_APPLE, GUARDIAN_30), id -> false, id -> id == GELT_CHOCOLATE);
        for (int id : new int[]{GELT_CHOCOLATE, ONYX_APPLE, GUARDIAN_30}) {
            assertFalse(ArtificialShopGenerator.idStockable(id), "" + id);
        }
        int made = 0;
        for (int round = 0; round < 60; round++) {
            for (String tier : new String[]{"S", "A", "B", "C"}) {
                for (FMItem fi : everyUseLine(tier)) {
                    made++;
                    assertEquals(null, SaleRules.whyNotId(fi.getItemId()), "generated " + fi.getItemId() + " tier " + tier);
                }
            }
        }
        assertTrue(made > 0, "the generators made something");
    }

    @Test
    void whenSaleRulesRefuseEverythingNoUseEtcOrSetupStockIsMinted() {
        SaleRules.useForTest(Set.of(), id -> false, id -> true);   // everything untradeable
        for (int round = 0; round < 20; round++) {
            for (String tier : new String[]{"S", "A", "B", "C"}) {
                assertTrue(everyUseLine(tier).isEmpty(), "tier " + tier);
            }
        }
    }

    /** Owner 2026-10-30: a player saw no spear or polearm scrolls on the Slums Market; generated stock only had claw and dagger. */
    @Test
    void generatedScrollStockIncludesEveryWeaponsAttackScrolls() {
        SaleRules.useForTest(Set.of(), id -> false, id -> false);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 3000; i++) {
            for (FMItem item : ArtificialShopGenerator.generateScrollsList("A")) {
                seen.add(item.getItemId());
            }
        }
        for (int id : new int[]{2043002, 2043001, 2043102, 2043101, 2043202, 2043201, 2043702, 2043701, 2043802,
                2043801, 2044002, 2044001, 2044102, 2044101, 2044202, 2044201, 2044302, 2044301, 2044402, 2044401,
                2044502, 2044501, 2044602, 2044601, 2044802, 2044801, 2044902, 2044901}) {
            assertTrue(seen.contains(id), "weapon scroll " + id + " never stocked");
        }
        assertTrue(seen.stream().anyMatch(id -> id / 100 == 20408),
                "gloves scrolls still stocked");
    }
}
