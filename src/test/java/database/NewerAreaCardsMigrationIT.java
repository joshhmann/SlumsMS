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
 * 1.6.1: migration 043 puts 254 Monster Book cards (2%) on the Arcane River / Grandis mobs and adds their
 * monstercarddata rows; the rollback removes only what 043 inserted. Skipped unless
 * {@code -Dslums.family.it.url=jdbc:mysql://...} points at an empty scratch schema (same switch as the 041 test).
 */
class NewerAreaCardsMigrationIT {

    static final String URL = System.getProperty("slums.family.it.url");

    @Test
    void applyTwiceThenRollbackTwiceKeepsOtherRows() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            exec(db, "DROP TABLE IF EXISTS drop_data, monstercarddata, cards_043_rows, mapleroot_integration_migrations");
            exec(db, "CREATE TABLE drop_data (id bigint AUTO_INCREMENT PRIMARY KEY, dropperid int NOT NULL, itemid int NOT NULL"
                    + " DEFAULT 0, minimum_quantity int NOT NULL DEFAULT 1, maximum_quantity int NOT NULL DEFAULT 1,"
                    + " questid int NOT NULL DEFAULT 0, chance int NOT NULL DEFAULT 0, UNIQUE KEY (dropperid, itemid))");
            exec(db, "CREATE TABLE monstercarddata (id int AUTO_INCREMENT PRIMARY KEY, cardid int NOT NULL DEFAULT 0,"
                    + " mobid int NOT NULL DEFAULT 0)");
            // an older row 043 must not claim: the first pair of the list already dropped at 8%, with its card row
            exec(db, "INSERT INTO drop_data (dropperid, itemid, chance) VALUES (2400000, 2386030, 8000), (100100, 4000000, 600000)");
            exec(db, "INSERT INTO monstercarddata (cardid, mobid) VALUES (2386030, 2400000), (2380000, 100100)");

            for (int i = 0; i < 2; i++) {
                script(db, "043-newer-area-cards.sql");
            }
            // 254 pairs, one of them already there: 253 new drops, and the old row keeps its 8%
            assertEquals(255, count(db, "SELECT COUNT(*) FROM drop_data"));
            assertEquals(253, count(db, "SELECT COUNT(*) FROM drop_data WHERE chance = 20000 AND minimum_quantity = 1"
                    + " AND maximum_quantity = 1 AND questid = 0"));
            assertEquals(8000, count(db, "SELECT chance FROM drop_data WHERE dropperid = 2400000 AND itemid = 2386030"));
            assertEquals(255, count(db, "SELECT COUNT(*) FROM monstercarddata"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM monstercarddata WHERE cardid = 2386030"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id ="
                    + " '043-newer-area-cards'"));

            for (int i = 0; i < 2; i++) {
                script(db, "043-newer-area-cards.rollback.sql");
            }
            assertEquals(2, count(db, "SELECT COUNT(*) FROM drop_data"), "the older rows stay");
            assertEquals(8000, count(db, "SELECT chance FROM drop_data WHERE dropperid = 2400000 AND itemid = 2386030"));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM monstercarddata"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id ="
                    + " '043-newer-area-cards'"));
        }
    }
}
