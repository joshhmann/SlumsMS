package server;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Migration 024: the stock items of Puno, Puri Puri and Thorr (empty shops) and Singapore's missing pirate gear. */
class ShopStockRestoreMigrationTest {
    static final Path SQL = Path.of("database/migrations/024-restore-stock-shop-items.sql");
    static final Path ROLLBACK = Path.of("database/migrations/024-restore-stock-shop-items.rollback.sql");
    static final Pattern ROW = Pattern.compile("SELECT (\\d+)(?: AS shopid)?, (\\d+)(?: AS itemid)?, (\\d+)(?: AS price)?, "
            + "(\\d+)(?: AS pitch)?, (\\d+)");

    record Row(int shop, int item, int price, int pitch, int position) {
    }

    static List<Row> rows() throws Exception {
        List<Row> out = new ArrayList<>();
        Matcher m = ROW.matcher(Files.readString(SQL));
        while (m.find()) {
            out.add(new Row(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))));
        }
        return out;
    }

    @Test
    void restoresTheStockCountsAndNothingElse() throws Exception {
        Map<Integer, Integer> perShop = new TreeMap<>();
        for (Row r : rows()) {
            perShop.merge(r.shop(), 1, Integer::sum);
            assertTrue(r.price() > 1 || r.item() / 1000 == 2060 || r.item() / 1000 == 2061,
                    r + ": only arrows are 1 meso (per arrow, as stock)");
            assertEquals(0, r.pitch());
            assertFalse(ShopFactory.isGmShop(r.shop()));
        }
        assertEquals(Map.of(1200001, 37, 1200002, 21, 1301000, 17, 9270019, 6, 9270020, 3), perShop);
        String sql = Files.readString(SQL);
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM shopitems i WHERE i.shopid = r.shopid AND i.itemid = r.itemid)"));
        assertTrue(sql.contains("INSERT INTO mapleroot_integration_migration_rows"));
        assertTrue(sql.contains("migration_id = '024-restore-stock-shop-items'"));
        String rollback = Files.readString(ROLLBACK);
        assertTrue(rollback.contains("DELETE FROM shopitems WHERE shopitemid IN ("));
        assertTrue(rollback.contains("migration_id = '024-restore-stock-shop-items' AND table_name = 'shopitems'"));
    }

    @Test
    void everyItemExistsInTheServersWz() throws Exception {
        WzTestData.assumePresent("String.wz");
        Path str = WzTestData.dir().resolve("String.wz");
        String all = Files.readString(str.resolve("Eqp.img.xml")) + Files.readString(str.resolve("Consume.img.xml"))
                + Files.readString(str.resolve("Ins.img.xml"));
        for (Row r : rows()) {
            assertTrue(all.contains("name=\"" + r.item() + "\""), r + ": item has a name in String.wz");
        }
    }
}
