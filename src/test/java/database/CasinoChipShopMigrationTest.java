package database;

import org.junit.jupiter.api.Test;
import server.ShopFactory;
import soloMapling.Casino.CasinoChipConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket 164: the Henesys Game Park NPC 9000055 was mute - spawned by EnvironmentManager.spawnCasinoNpcs, no
 * scripts/npc/armi.js, no shops row - so NPC talk logged "NPC 9000055 (9000055) is not coded". Migration 058 inserts
 * the shop CasinoChipConfig describes.
 *
 * <p>The load-bearing invariant is that this file and {@link CasinoChipConfig} agree: {@code Shop.buy} charges the
 * {@code shopitems.price} row and {@code Shop.sell} pays {@code CasinoChipConfig.sellValue}, so a price that drifts
 * here turns the chip exchange lossy (buy 1m, sell back the WZ 350). Editing one without the other fails this test.
 */
class CasinoChipShopMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "058-casino-chip-shop";

    /** One {@code SELECT shopid, itemid, price, pitch, position} row of the UNION ALL table. */
    static final Pattern ITEM_ROW = Pattern.compile(
            "SELECT (\\d+)(?: AS shopid)?, (\\d+)(?: AS itemid)?, (\\d+)(?: AS price)?, (\\d+)(?: AS pitch)?,"
                    + " (\\d+)(?: AS position)?");
    static final Pattern SHOP_ROW = Pattern.compile("INSERT INTO shops \\(shopid, npcid\\)\\s*SELECT (\\d+), (\\d+)");

    record ItemRow(int shop, int item, int price, int pitch, int position) {
    }

    static String sql() throws Exception {
        return Files.readString(DIR.resolve(ID + ".sql"));
    }

    static String rollback() throws Exception {
        return Files.readString(DIR.resolve(ID + ".rollback.sql"));
    }

    static List<ItemRow> itemRows(String sql) {
        List<ItemRow> out = new ArrayList<>();
        Matcher m = ITEM_ROW.matcher(sql);
        while (m.find()) {
            out.add(new ItemRow(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))));
        }
        return out;
    }

    @Test
    void insertsTheShopCasinoChipConfigNames() throws Exception {
        Matcher m = SHOP_ROW.matcher(sql());
        assertTrue(m.find(), "058 inserts the shops row");
        assertEquals(CasinoChipConfig.CASINO_SHOP_ID, Integer.parseInt(m.group(1)), "shop id");
        assertEquals(CasinoChipConfig.CASINO_NPC_ID, Integer.parseInt(m.group(2)), "NPC 9000055 owns the shop");
    }

    @Test
    void insertsExactlyTheChipTableAtItsPrices() throws Exception {
        List<ItemRow> rows = itemRows(sql());
        Map<Integer, Integer> prices = new LinkedHashMap<>();
        for (ItemRow r : rows) {
            assertEquals(CasinoChipConfig.CASINO_SHOP_ID, r.shop(), "every row belongs to the chip shop");
            assertNull(prices.put(r.item(), r.price()), "item " + r.item() + " is inserted once");
        }
        assertEquals(CasinoChipConfig.getChips(), prices, "same items at the same prices, in the same order");
        assertEquals(4, rows.size(), "four chips, no more");

        // Shop.createFromDB reads shopitems ORDER BY position DESC: that order is the shop list players see.
        List<Integer> displayed = new ArrayList<>(rows.stream()
                .sorted((a, b) -> Integer.compare(b.position(), a.position()))
                .map(ItemRow::item).toList());
        assertEquals(List.copyOf(CasinoChipConfig.getChips().keySet()), displayed, "shop list order");
        assertEquals(rows.size(), rows.stream().map(ItemRow::position).distinct().count(), "distinct slots");
    }

    @Test
    void appliesOnceAndOnlyInsertsMissingRows() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE"
                + " migration_id = '" + ID + "')"), "ledger gate");
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM shops s WHERE s.shopid = "
                + CasinoChipConfig.CASINO_SHOP_ID + ")"), "never steals an existing shop id");
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM shops s WHERE s.npcid = "
                + CasinoChipConfig.CASINO_NPC_ID + ")"), "never gives the NPC a second shop");
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM shopitems i WHERE i.shopid = r.shopid AND i.itemid ="
                + " r.itemid)"), "never duplicates an item the shop already sells");
        assertTrue(sql.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('"
                + ID + "')"), "ledger row");
        assertTrue(sql.contains("START TRANSACTION;") && sql.contains("COMMIT;"), "one transaction");
        assertTrue(sql.contains("CREATE TEMPORARY TABLE migration_shops_before")
                && sql.contains("CREATE TEMPORARY TABLE migration_shopitems_before"), "wholesale snapshots for ownership");
    }

    @Test
    void theShopIsNotAGmShop() {
        assertFalse(ShopFactory.isGmShop(CasinoChipConfig.CASINO_SHOP_ID), "not 1337/9999992-9999999");
        assertNotEquals(1337, CasinoChipConfig.CASINO_SHOP_ID);
        assertTrue(CasinoChipConfig.CASINO_SHOP_ID < 9999992 || CasinoChipConfig.CASINO_SHOP_ID > 9999999);
    }

    @Test
    void rollbackDeletesOnlyTheRowsThisMigrationOwns() throws Exception {
        String rb = rollback();
        assertTrue(rb.contains("DELETE FROM shopitems WHERE shopitemid IN ("));
        assertTrue(rb.contains("DELETE FROM shops WHERE shopid IN ("));
        assertTrue(rb.contains("table_name = 'shopitems'") && rb.contains("table_name = 'shops'"), "by ownership rows");
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '" + ID + "'"));
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
        assertFalse(rb.contains("shopid = " + CasinoChipConfig.CASINO_SHOP_ID), "never guesses by shop id");
        assertFalse(rb.contains("npcid = " + CasinoChipConfig.CASINO_NPC_ID), "never guesses by NPC id");
    }

    @Test
    void theOrphanSetupScriptIsGone() {
        assertFalse(Files.exists(Path.of("src/main/java/soloMapling/Casino/casino_chip_shop_setup.sql")),
                "this migration is the single source of truth for the chip shop rows");
        assertFalse(Files.exists(Path.of("src/main/java/soloMapling/Casino/WzXmlPatcher.java")),
                "nothing ran it; Sell prices come from CasinoChipConfig.sellValue, not WZ edits");
    }
}
