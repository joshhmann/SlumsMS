package soloMapling.Economy;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner 2026-10-30: anything a regular NPC shop sells is exempt from the boss-only rule (Red Bean Sundae is a
 * 4,000-meso shop potion that only the Balrog drops). 1-meso rows and the GM/test shops (NPCs 9001002, 11000) are not
 * regular. economy.yaml's explicit bossOnly list wins over the exemption. Rows are TEST's shopitems for these items.
 */
class SaleRulesNpcSoldTest {

    private static final int RED_BEAN_SUNDAE = 2001002, ONYX_APPLE = 2022179, GUARDIAN_30 = 2290015,
            ZAKUM_HELMET = 1002357;

    /** item -> {npc, price} rows, as shopitems JOIN shops gives them. */
    private static Set<Integer> npcSold(Map<Integer, long[][]> rows) {
        Set<Integer> out = new HashSet<>();
        rows.forEach((item, rs) -> {
            for (long[] r : rs) {
                if (SaleRules.regularSale((int) r[0], r[1])) {
                    out.add(item);
                }
            }
        });
        return out;
    }

    @Test
    void npcSoldItemsLeaveTheBossOnlySetButGmShopsAndTheYamlListDoNot() {
        Set<Integer> onlyBossesDrop = Set.of(RED_BEAN_SUNDAE, ONYX_APPLE, GUARDIAN_30);
        Set<Integer> sold = npcSold(Map.of(
                RED_BEAN_SUNDAE, new long[][]{{2012005, 4000}, {1081000, 4000}},
                ONYX_APPLE, new long[][]{{9001002, 1}, {11000, 1}},
                ZAKUM_HELMET, new long[][]{{1012000, 50000}}));   // pretend: the yaml list must still win
        Set<Integer> boss = SaleRules.combineBossOnly(Set.of(ZAKUM_HELMET, 1002390, 1002430, 1122000), onlyBossesDrop, sold);
        assertFalse(boss.contains(RED_BEAN_SUNDAE), "a regular NPC shop sells Red Bean Sundae");
        assertTrue(boss.contains(ONYX_APPLE), "only the GM/test shops sell Onyx Apple, at 1 meso");
        assertTrue(boss.contains(GUARDIAN_30), "no NPC sells the Guardian 30 book");
        assertTrue(boss.contains(ZAKUM_HELMET), "economy.yaml's list wins over the exemption");
    }

    @Test
    void regularSaleMeansMoreThanOneMesoAtANonGmShop() {
        assertTrue(SaleRules.regularSale(2012005, 4000));
        assertFalse(SaleRules.regularSale(2012005, 1), "a 1-meso row in a regular shop");
        assertFalse(SaleRules.regularSale(2012005, 0));
        assertFalse(SaleRules.regularSale(9001002, 50000), "GM shop NPC");
        assertFalse(SaleRules.regularSale(11000, 50000), "test shop NPC");
    }

    @Test
    void npcSoldRedBeanSundaeMayBeSoldAgainButYamlBossItemsMayNot() {
        try {
            Set<Integer> boss = SaleRules.combineBossOnly(Set.of(ZAKUM_HELMET), Set.of(RED_BEAN_SUNDAE, ONYX_APPLE),
                    Set.of(RED_BEAN_SUNDAE, ZAKUM_HELMET));
            SaleRules.useForTest(boss, id -> false, id -> false);
            assertTrue(SaleRules.whyNotId(RED_BEAN_SUNDAE) == null, "Red Bean Sundae");
            assertTrue("boss-only drop".equals(SaleRules.whyNotId(ONYX_APPLE)), "Onyx Apple");
            assertTrue("boss-only drop".equals(SaleRules.whyNotId(ZAKUM_HELMET)), "Zakum Helmet");
        } finally {
            SaleRules.useForTest(null, null, null);
        }
    }
}
