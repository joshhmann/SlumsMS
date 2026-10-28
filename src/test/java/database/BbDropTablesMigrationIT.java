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
 * Ticket 81 (draft): migration 050 adds drop tables for the post-Big-Bang mobs; applying it twice changes nothing,
 * the Monster Book card rows and any row already there are left alone, and the rollback (twice) removes exactly what
 * 050 inserted. Skipped unless {@code -Dslums.family.it.url=jdbc:mysql://...} points at an empty scratch schema
 * (same switch as the 041 and 045 tests).
 */
class BbDropTablesMigrationIT {

    static final String URL = System.getProperty("slums.family.it.url");

    @Test
    void applyTwiceThenRollbackTwiceKeepsOtherRows() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        int generated = BbDropTablesDataTest.rows().size();
        try (Connection db = DriverManager.getConnection(URL)) {
            exec(db, "DROP TABLE IF EXISTS drop_data, bb_drops_050_rows, mapleroot_integration_migrations");
            exec(db, "CREATE TABLE drop_data (id bigint AUTO_INCREMENT PRIMARY KEY, dropperid int NOT NULL, itemid int NOT NULL"
                    + " DEFAULT 0, minimum_quantity int NOT NULL DEFAULT 1, maximum_quantity int NOT NULL DEFAULT 1,"
                    + " questid int NOT NULL DEFAULT 0, chance int NOT NULL DEFAULT 0, UNIQUE KEY (dropperid, itemid))"
                    + " ENGINE=MyISAM");
            // 045's card row on a target mob, a row 050 would also add (kept at its own 5%), and an unrelated mob
            exec(db, "INSERT INTO drop_data (dropperid, itemid, chance) VALUES (2400000, 2386030, 20000),"
                    + " (2400000, 2000005, 50000), (100100, 4000000, 600000)");
            String snapshot = "SELECT COUNT(*) FROM drop_data WHERE (dropperid, itemid, chance) IN"
                    + " ((2400000, 2386030, 20000), (2400000, 2000005, 50000), (100100, 4000000, 600000))";

            for (int i = 0; i < 2; i++) {
                script(db, "050-bb-drop-tables.sql");
            }
            assertEquals(3 + generated - 1, count(db, "SELECT COUNT(*) FROM drop_data"));
            assertEquals(generated - 1, count(db, "SELECT COUNT(*) FROM bb_drops_050_rows"));
            assertEquals(3, count(db, snapshot), "card, older and unrelated rows unchanged");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM drop_data WHERE itemid BETWEEN 2380000 AND 2389999"),
                    "050 adds no card rows");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM drop_data WHERE dropperid = 2400000 AND itemid = 0"
                    + " AND chance = 400000 AND maximum_quantity > minimum_quantity"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id ="
                    + " '050-bb-drop-tables'"));

            for (int i = 0; i < 2; i++) {
                script(db, "050-bb-drop-tables.rollback.sql");
            }
            assertEquals(3, count(db, "SELECT COUNT(*) FROM drop_data"), "only the older rows stay");
            assertEquals(3, count(db, snapshot));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM bb_drops_050_rows"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id ="
                    + " '050-bb-drop-tables'"));

            // and it applies cleanly again after a rollback
            script(db, "050-bb-drop-tables.sql");
            assertEquals(3 + generated - 1, count(db, "SELECT COUNT(*) FROM drop_data"));
        }
    }
}
