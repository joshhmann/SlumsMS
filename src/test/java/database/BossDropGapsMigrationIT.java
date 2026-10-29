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
 * Owner 2026-10-29: migration 055 (boss drop gaps) applies twice without change, skips the Lantern Erda's existing card
 * row and leaves 050's rows and ledger alone; its rollback (twice) removes exactly what it inserted. Skipped unless
 * {@code -Dslums.family.it.url=jdbc:mysql://...} points at an empty scratch schema.
 */
class BossDropGapsMigrationIT {

    static final String URL = System.getProperty("slums.family.it.url");

    @Test
    void applyTwiceThenRollbackTwiceKeepsOtherRows() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        int generated = BossDropGapsDataTest.rows().size();
        int with050 = BbDropTablesDataTest.rows().size();
        try (Connection db = DriverManager.getConnection(URL)) {
            exec(db, "DROP TABLE IF EXISTS drop_data, bb_drops_050_rows, boss_drops_055_rows, mapleroot_integration_migrations");
            exec(db, "CREATE TABLE drop_data (id bigint AUTO_INCREMENT PRIMARY KEY, dropperid int NOT NULL, itemid int NOT NULL"
                    + " DEFAULT 0, minimum_quantity int NOT NULL DEFAULT 1, maximum_quantity int NOT NULL DEFAULT 1,"
                    + " questid int NOT NULL DEFAULT 0, chance int NOT NULL DEFAULT 0, UNIQUE KEY (dropperid, itemid))"
                    + " ENGINE=MyISAM");
            // the Lantern Erda's card (045), the Castellan Toad's meso row, a row 055 would also add (kept at its own
            // chance) and an unrelated mob
            exec(db, "INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, chance) VALUES"
                    + " (8641008, 2387158, 1, 1, 20000), (9400409, 0, 10000, 20000, 400000),"
                    + " (8850006, 2000004, 1, 1, 50000), (100100, 4000000, 1, 1, 600000)");
            String snapshot = "SELECT COUNT(*) FROM drop_data WHERE (dropperid, itemid, chance) IN ((8641008, 2387158, 20000),"
                    + " (9400409, 0, 400000), (8850006, 2000004, 50000), (100100, 4000000, 600000))";
            script(db, "050-bb-drop-tables.sql");
            int after050 = count(db, "SELECT COUNT(*) FROM drop_data");
            assertEquals(4 + with050, after050);

            for (int i = 0; i < 2; i++) {
                script(db, "055-boss-drop-gaps.sql");
            }
            assertEquals(after050 + generated - 1, count(db, "SELECT COUNT(*) FROM drop_data"), "the 8850006 Elixir pair is skipped");
            assertEquals(generated - 1, count(db, "SELECT COUNT(*) FROM boss_drops_055_rows"));
            assertEquals(4, count(db, snapshot), "card, older and unrelated rows unchanged");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM drop_data WHERE dropperid = 8641008 AND itemid BETWEEN 2380000 AND 2389999"));
            for (int stub : BossDropGapsDataTest.STUBS) {
                assertEquals(0, count(db, "SELECT COUNT(*) FROM drop_data WHERE dropperid = " + stub), "stub " + stub);
            }
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '055-boss-drop-gaps'"));

            for (int i = 0; i < 2; i++) {
                script(db, "055-boss-drop-gaps.rollback.sql");
            }
            assertEquals(after050, count(db, "SELECT COUNT(*) FROM drop_data"), "050's rows and the older rows stay");
            assertEquals(4, count(db, snapshot));
            assertEquals(with050, count(db, "SELECT COUNT(*) FROM bb_drops_050_rows"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM boss_drops_055_rows"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '055-boss-drop-gaps'"));

            script(db, "055-boss-drop-gaps.sql");
            assertEquals(after050 + generated - 1, count(db, "SELECT COUNT(*) FROM drop_data"), "applies again after a rollback");
        }
    }
}
