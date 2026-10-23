package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Migration 022 (rc6 bug hunt): the botmemory index the @botdrama off purge needs; guarded, with a rollback. */
class BotMemoryIndexMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "022-botmemory-about-index";

    @Test
    void theMigrationIsGuardedLedgeredAndListedForTheRelease() throws Exception {
        String sql = Files.readString(DIR.resolve(ID + ".sql"));
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.contains("information_schema.STATISTICS") && sql.contains("ADD INDEX about (AboutCharacterId"),
                "added only when missing");
        assertTrue(rollback.contains("information_schema.STATISTICS") && rollback.contains("DROP INDEX about"),
                "dropped only when present");
        assertTrue(rollback.contains("migration_id='" + ID + "'"), "the rollback clears its ledger row");
        assertTrue(Files.readString(Path.of("docs/release/1.5.0.conf")).contains(" " + ID + ")"),
                "listed last in the 1.5.0 MIGRATIONS");
    }
}
