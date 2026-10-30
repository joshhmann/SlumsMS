package soloMapling.itemPool;

import client.inventory.Equip;
import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Ticket 162 part 5: old (naive chain) vs new (reproduction cost, -Dslums.scroll.ev) prices of scrolled equips, so the
 * owner can see the price impact before any switch-on. The model checks run everywhere; the report over real items
 * needs WZ data (-Dwz-path=<dir with Item.wz and Character.wz>) and writes target/scroll-price-impact.tsv:
 *   mvn -q -o test -Dtest=ScrollPriceImpactReportTest -Dwz-path=/path/to/wz
 */
class ScrollPriceImpactReportTest {

    // A v83-like weapon attack set: 10% (+5, 1.2m) and 60% (+2, 300k), on a 7-slot 2m base.
    private static List<UpgradeSimulator.Scroll> weaponSet() {
        return List.of(new UpgradeSimulator.Scroll(1_200_000, 10, 5), new UpgradeSimulator.Scroll(300_000, 60, 2));
    }

    @Test
    void cleanIsTheBasePriceInBothModels() {
        assertEquals(2_000_000, UpgradeSimulator.naivePrice(weaponSet(), 2_000_000, 7, 0));
        assertEquals(2_000_000, UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, 0));
    }

    @Test
    void theNewModelRisesWithTheBonusAndNeverPricesAScrolledItemAtOneMeso() {
        long previous = 0;
        for (int bonus = 0; bonus <= 35; bonus++) {
            long ev = UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, bonus);
            assertTrue(ev >= previous, "bonus " + bonus + ": " + ev + " < " + previous);
            previous = ev;
        }
        // +1 cannot be made exactly from +2/+5 passes: the old chain prices it at 1 meso, the new one at least as a +2.
        assertEquals(1, UpgradeSimulator.naivePrice(weaponSet(), 2_000_000, 7, 1));
        assertTrue(UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, 1) > 1_000_000);
    }

    @Test
    void sampleTableWithoutItemData() throws Exception {
        // The same comparison on the synthetic set, written for the report whether or not WZ data is here.
        StringBuilder out = new StringBuilder("set\tslots\tbonus\tnaive\tev\tev/naive\n");
        for (int bonus : new int[]{2, 4, 6, 10, 14, 15, 20, 25, 35}) {
            long naive = UpgradeSimulator.naivePrice(weaponSet(), 2_000_000, 7, bonus);
            long ev = UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, bonus);
            out.append(String.format(Locale.ROOT, "weapon 10%%+5/60%%+2, base 2m\t7\t%d\t%d\t%d\t%.2f%n", bonus,
                    naive, ev, naive > 1 ? ev / (double) naive : Double.NaN));
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "scroll-price-impact-synthetic.tsv"), out);
        System.out.print("[scroll-price-impact synthetic]\n" + out);
    }

    // A spread of v83 gear: weapons of every class, armour, accessories.
    private static final int[] SAMPLE = {1302020, 1312032, 1322054, 1332025, 1372034, 1382039, 1402039, 1412027,
            1422029, 1432040, 1442051, 1452045, 1462040, 1472055, 1482022, 1492022, 1002357, 1082149, 1072238,
            1102041, 1092030, 1040000, 1060000, 1050000, 1032003, 1012070};

    @Test
    void reportOverRealItems() throws Exception {
        WzTestData.assumePresent("Item.wz");
        WzTestData.assumePresent("Character.wz");
        // The item data with no database behind it (its card table is read from the DB at start).
        try (var db = org.mockito.Mockito.mockStatic(tools.DatabaseConnection.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(org.mockito.Mockito.mock(
                    java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            server.ItemInformationProvider.getInstance();
        }
        StringBuilder out = new StringBuilder("item\tname\tslots\tbase\tscrolls\tbonus\tnaive\tev\tev/naive\n");
        int rows = 0;
        for (int id : SAMPLE) {
            Equip clean;
            try {
                clean = (Equip) soloMapling.FreeMarket.EquipListGenerator.generateCleanItemEquip(id);
            } catch (RuntimeException e) {
                continue;
            }
            if (clean == null) {
                continue;
            }
            ScrollInfoManager.ScrollInfo info = ScrollInfoManager.getScrollInfo(clean);
            Integer price = ItemDatabase.getInstance().getItemPrice(id);
            if (info == null) {
                continue;
            }
            long base = price == null ? 100 : price;
            List<UpgradeSimulator.Scroll> scrolls = UpgradeSimulator.scrollsFor(info);
            int slots = clean.getUpgradeSlots();
            if (scrolls.isEmpty() || slots <= 0) {
                continue;
            }
            String name = server.ItemInformationProvider.getInstance().getName(id);
            int maxInc = UpgradeSimulator.getHighestStatBonus(new ArrayList<>(scrolls));
            int minInc = Integer.MAX_VALUE;
            for (UpgradeSimulator.Scroll s : scrolls) {
                minInc = Math.min(minInc, s.statBonus);
            }
            // one cheap pass, half the slots, every slot with the cheap scroll, a perfect long-shot item
            int[] bonuses = {minInc, minInc * Math.max(1, slots / 2), minInc * slots, maxInc * slots};
            for (int bonus : bonuses) {
                long naive = UpgradeSimulator.naivePrice(scrolls, base, slots, bonus);
                long ev = UpgradeSimulator.evPrice(scrolls, base, slots, bonus);
                out.append(String.format(Locale.ROOT, "%d\t%s\t%d\t%d\t%s\t%d\t%d\t%d\t%.2f%n", id, name, slots, base,
                        describe(scrolls), bonus, naive, ev, naive > 1 ? ev / (double) naive : Double.NaN));
                rows++;
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "scroll-price-impact.tsv"), out);
        System.out.print("[scroll-price-impact]\n" + out);
        assertTrue(rows > 0, "no sample item priced");
    }

    private static String describe(List<UpgradeSimulator.Scroll> scrolls) {
        List<String> parts = new ArrayList<>();
        for (UpgradeSimulator.Scroll s : scrolls) {
            parts.add(s.successRate + "%+" + s.statBonus + "@" + s.price);
        }
        return String.join(" ", parts);
    }
}
