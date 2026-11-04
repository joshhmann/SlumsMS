package database;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static database.FamilyBotJuniorsMigrationIT.count;
import static database.FamilyBotJuniorsMigrationIT.exec;
import static database.FamilyBotJuniorsMigrationIT.script;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ticket 164: migration 059 gives NPC 9000055 ("Aramia", spawned in Henesys Game Park 100000203) the chip shop
 * CasinoChipConfig describes, and its rollback takes back exactly what it inserted. Skipped unless
 * {@code -Dslums.family.it.url=jdbc:mysql://...} points at an empty scratch schema (never a game database: it makes its
 * own minimal shops/shopitems tables). Applies twice, rolls back twice, re-applies.
 */
class CasinoChipShopMigrationIT {

    static final String URL = System.getProperty("slums.family.it.url");
    static final String ID = "059-casino-chip-shop";

    /** The predicate of Shop.NPC_SHOP_QUERY (package-private there): its own shop, never a GM shop. */
    static final String NPC_SHOP = "SELECT shopid FROM shops WHERE npcid = 9000055 AND shopid <> 1337"
            + " AND shopid NOT BETWEEN 9999992 AND 9999999 ORDER BY shopid = npcid DESC, shopid";

    static void fixtures(Connection db) throws Exception {
        exec(db, "DROP TABLE IF EXISTS shops, shopitems, mapleroot_integration_migrations,"
                + " mapleroot_integration_migration_rows");
        exec(db, "CREATE TABLE shops (shopid int unsigned NOT NULL AUTO_INCREMENT, npcid int NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (shopid)) ENGINE=InnoDB");
        exec(db, "CREATE TABLE shopitems (shopitemid int unsigned NOT NULL AUTO_INCREMENT, shopid int unsigned NOT NULL,"
                + " itemid int NOT NULL, price int NOT NULL, pitch int NOT NULL DEFAULT 0, position int NOT NULL,"
                + " PRIMARY KEY (shopitemid)) ENGINE=InnoDB");
        // Another NPC's shop and an item of it: nothing here may change.
        exec(db, "INSERT INTO shops (shopid, npcid) VALUES (11000, 11000)");
        exec(db, "INSERT INTO shopitems (shopid, itemid, price, pitch, position) VALUES (11000, 1302000, 50, 0, 104)");
    }

    @Test
    void appliesTwiceThenRollbackTwice() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            fixtures(db);
            assertEquals(0, count(db, "SELECT COUNT(*) FROM shops WHERE npcid = 9000055"), "the NPC is mute to begin with");

            for (int i = 0; i < 2; i++) {
                script(db, ID + ".sql");
            }
            assertEquals(1, count(db, "SELECT COUNT(*) FROM shops WHERE npcid = 9000055"), "one shop for the NPC");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM shops WHERE shopid = 9999001"));
            assertEquals(4, count(db, "SELECT COUNT(*) FROM shopitems WHERE shopid = 9999001"), "four chips, not eight");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM (" + NPC_SHOP + ") s"), "Shop's NPC query finds it");
            // The prices Shop.buy charges; CasinoChipShopMigrationTest keeps them equal to CasinoChipConfig.CHIPS.
            assertEquals("10000,50000,250000,1000000", prices(db));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '"
                    + ID + "'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migration_rows WHERE migration_id = '"
                    + ID + "' AND table_name = 'shops'"), "the shop row is owned");
            assertEquals(4, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migration_rows WHERE migration_id = '"
                    + ID + "' AND table_name = 'shopitems'"), "the item rows are owned");

            for (int i = 0; i < 2; i++) {
                script(db, ID + ".rollback.sql");
            }
            assertEquals(0, count(db, "SELECT COUNT(*) FROM shops WHERE npcid = 9000055"), "the shop goes with its items");
            assertEquals(0, count(db, "SELECT COUNT(*) FROM shopitems WHERE shopid = 9999001"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM shops WHERE shopid = 11000"), "the other NPC's shop stays");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM shopitems WHERE shopid = 11000"), "its items stay");
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '"
                    + ID + "'"));

            script(db, ID + ".sql");
            assertEquals(4, count(db, "SELECT COUNT(*) FROM shopitems WHERE shopid = 9999001"), "applies again after a rollback");
        }
    }

    @Test
    void aShopAnOperatorAlreadyMadeStaysTheirS() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            fixtures(db);
            // The live-shaped case: whoever ran the old casino_chip_shop_setup.sql by hand already has the shop.
            exec(db, "INSERT INTO shops (shopid, npcid) VALUES (9999001, 9000055)");
            exec(db, "INSERT INTO shopitems (shopid, itemid, price, pitch, position) VALUES (9999001, 4002000, 10, 0, 4)");

            script(db, ID + ".sql");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM shops WHERE shopid = 9999001"), "no second row, no duplicate key");
            assertEquals(10, count(db, "SELECT price FROM shopitems WHERE shopid = 9999001 AND itemid = 4002000"),
                    "the operator's own price is not overwritten");
            assertEquals(4, count(db, "SELECT COUNT(*) FROM shopitems WHERE shopid = 9999001"), "the three missing chips come");
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migration_rows WHERE migration_id = '"
                    + ID + "' AND table_name = 'shops'"), "a shop it did not insert is not claimed");
            assertEquals(3, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migration_rows WHERE migration_id = '"
                    + ID + "' AND table_name = 'shopitems'"), "only the three it inserted");

            script(db, ID + ".rollback.sql");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM shops WHERE shopid = 9999001"), "the operator's shop stays");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM shopitems WHERE shopid = 9999001"), "their item row stays");
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations"
                    + " WHERE migration_id = '" + ID + "'"), "the ledger row goes");
        }
    }

    /** comma-separated shopitems prices in slot (position DESC) order */
    static String prices(Connection db) throws Exception {
        try (var st = db.createStatement();
             var rs = st.executeQuery("SELECT price FROM shopitems WHERE shopid = 9999001 ORDER BY position DESC")) {
            StringBuilder out = new StringBuilder();
            while (rs.next()) {
                out.append(out.isEmpty() ? "" : ",").append(rs.getInt(1));
            }
            return out.toString();
        }
    }
}
