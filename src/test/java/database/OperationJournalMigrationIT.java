package database;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import server.transactions.OperationJournal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static database.FamilyBotJuniorsMigrationIT.count;
import static database.FamilyBotJuniorsMigrationIT.exec;
import static database.FamilyBotJuniorsMigrationIT.script;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.7.0 P0-1: migration 066 creates operation_journal; its rollback drops it; both are safe to repeat. Also drives
 * OperationJournal's SQL on the new table. Skipped unless {@code -Dslums.family.it.url=jdbc:mysql://...} points at an
 * empty scratch schema (never a game database), the same switch as the other *MigrationIT classes.
 */
class OperationJournalMigrationIT {

    static final String URL = System.getProperty("slums.family.it.url");
    static final String ID = "066-operation-journal";

    @Test
    void appliesTwiceJournalWorksThenRollbackTwice() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            exec(db, "DROP TABLE IF EXISTS operation_journal, mapleroot_integration_migrations");
            for (int i = 0; i < 2; i++) {
                script(db, ID + ".sql");
            }
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
                    + " AND table_name = 'operation_journal' AND index_name = 'idx_operation_journal_state'"));

            long id = OperationJournal.start(db, OperationJournal.BUYBACK, 1, 9, "{}");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM operation_journal WHERE state = 'STARTED' AND ref_id = 9"));
            OperationJournal.markApplied(db, id);
            assertEquals(1, count(db, "SELECT COUNT(*) FROM operation_journal WHERE state = 'APPLIED'"));
            assertThrows(SQLException.class, () -> OperationJournal.markRolledBack(db, id, "late"),
                    "an APPLIED row can no longer be rolled back");
            assertThrows(SQLException.class, () -> exec(db,
                    "INSERT INTO operation_journal (kind, actor_id, ref_id, deltas, state) VALUES ('X', 1, 1, '{}', 'BOGUS')"));

            for (int i = 0; i < 2; i++) {
                script(db, ID + ".rollback.sql");
            }
            assertEquals(0, count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                    + " AND table_name = 'operation_journal'"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
            script(db, ID + ".sql");
            assertEquals(0, count(db, "SELECT COUNT(*) FROM operation_journal"), "applies again after a rollback");
        }
    }
}
