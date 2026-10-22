package server.cashshop;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped catalogue's partner rings against the server WZ (-Dwz-path): every ring the window's partner flow
 * sells is a real cash ring with an Effect.wz/ItemEff look and a String.wz name, sold as one unisex permanent
 * ring; and no OTHER ItemEff ring is in the catalogue (it would arrive without its ring records and show nothing).
 */
class CashShopPartnerRingCatalogWzTest {

    static Map<Integer, String[]> shippedRows() throws Exception {
        final Map<Integer, String[]> rows = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("cashshop", "catalog.tsv"))) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            final String[] f = line.split("\t");
            rows.put(Integer.parseInt(f[0]), f);
        }
        return rows;
    }

    /** Top-level ids of Effect.wz/ItemEff.img. */
    static Set<Integer> itemEffIds() throws Exception {
        final String xml = Files.readString(WzTestData.dir().resolve("Effect.wz").resolve("ItemEff.img.xml"));
        final Set<Integer> ids = new HashSet<>();
        final Matcher m = Pattern.compile("<imgdir name=\"(111\\d{4})\"").matcher(xml);
        while (m.find()) ids.add(Integer.parseInt(m.group(1)));
        return ids;
    }

    @Test void everySoldPartnerRingIsAValidCashRingWithAnEffect() throws Exception {
        WzTestData.assumePresent("Character.wz");
        WzTestData.assumePresent("Effect.wz");
        WzTestData.assumePresent("String.wz");
        final Map<Integer, String[]> rows = shippedRows();
        final Set<Integer> eff = itemEffIds();
        final String names = Files.readString(WzTestData.dir().resolve("String.wz").resolve("Eqp.img.xml"));

        final Set<Integer> sold = new TreeSet<>(CashShopPartnerRings.COUPLE_RINGS);
        sold.addAll(CashShopPartnerRings.FRIENDSHIP_RINGS);
        assertEquals(13, sold.size());
        for (int id : sold) {
            final String[] f = rows.get(id);
            assertNotNull(f, "partner ring not in the catalogue: " + id);
            assertTrue(Integer.parseInt(f[1]) >= 3000 && Integer.parseInt(f[1]) <= 6000, "price " + id);
            assertEquals("1", f[2], "count " + id);
            assertEquals("2", f[3], "tab " + id);
            assertEquals("9", f[4], "category (Ring) " + id);
            assertEquals("0", f[5], "period " + id);
            assertEquals("2", f[6], "unisex " + id);

            final Path img = WzTestData.dir().resolve("Character.wz").resolve("Ring").resolve("0" + id + ".img.xml");
            assertTrue(Files.exists(img), "no Character.wz data for " + id);
            final String data = Files.readString(img);
            assertTrue(data.contains("<int name=\"cash\" value=\"1\"/>"), "not cash-flagged: " + id);
            assertTrue(eff.contains(id), "no Effect.wz/ItemEff look for " + id);
            assertTrue(names.contains("<imgdir name=\"" + id + "\">"), "no String.wz name for " + id);
        }
    }

    @Test void noOtherEffectRingIsSoldAsAPlainItem() throws Exception {
        WzTestData.assumePresent("Effect.wz");
        final Set<Integer> eff = itemEffIds();
        final Set<Integer> plain = new TreeSet<>();
        for (int id : shippedRows().keySet()) {
            if (id / 10000 == 111 && eff.contains(id) && !CashShopPartnerRings.isPartnerRing(id)) {
                plain.add(id);
            }
        }
        assertTrue(plain.isEmpty(), "ItemEff rings sold without the partner flow: " + plain);
    }
}
