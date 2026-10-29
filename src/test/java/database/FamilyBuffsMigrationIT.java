package database;

import client.FamilyBuffs;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.6.1 promise audit (ticket 53): migration 052 keeps the running Family buffs for a restart. Skipped unless
 * {@code -Dslums.family.it.url=jdbc:mysql://...} points at an empty scratch schema (never a game database). Applies
 * twice, writes and overwrites a row with the game's own upsert, rolls back twice.
 */
class FamilyBuffsMigrationIT {

    static final String URL = System.getProperty("slums.family.it.url");

    @Test
    void applyTwiceUpsertThenRollbackTwice() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            FamilyBotJuniorsMigrationIT.exec(db, "DROP TABLE IF EXISTS family_buffs, mapleroot_integration_migrations");
            for (int i = 0; i < 2; i++) {
                FamilyBotJuniorsMigrationIT.script(db, "052-family-buffs.sql");
            }
            assertEquals(1, FamilyBotJuniorsMigrationIT.count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations"
                    + " WHERE migration_id = '052-family-buffs'"));
            for (double rate : new double[]{1.5, 2.0}) {
                try (PreparedStatement ps = db.prepareStatement(FamilyBuffs.DbStore.SAVE_SQL)) {
                    ps.setInt(1, 1_000_000_007); // any id: no foreign key
                    ps.setDouble(2, rate);
                    ps.setLong(3, 123L);
                    ps.setDouble(4, 0);
                    ps.setLong(5, 0);
                    ps.executeUpdate();
                }
            }
            assertEquals(1, FamilyBotJuniorsMigrationIT.count(db, "SELECT COUNT(*) FROM family_buffs"));
            assertEquals(2, FamilyBotJuniorsMigrationIT.count(db, "SELECT exp_rate FROM family_buffs"), "overwritten");
            for (int i = 0; i < 2; i++) {
                FamilyBotJuniorsMigrationIT.script(db, "052-family-buffs.rollback.sql");
            }
            assertEquals(0, FamilyBotJuniorsMigrationIT.count(db, "SELECT COUNT(*) FROM information_schema.TABLES WHERE"
                    + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'family_buffs'"));
            assertEquals(0, FamilyBotJuniorsMigrationIT.count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations"
                    + " WHERE migration_id = '052-family-buffs'"));
        }
    }
}
