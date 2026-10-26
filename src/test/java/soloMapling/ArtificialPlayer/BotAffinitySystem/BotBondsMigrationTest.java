package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Migration 029 (1.6.0 S2): the bot bonds table, idempotent, ledgered, with a rollback the store's SQL matches. */
class BotBondsMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "029-bot-bonds";

    @Test
    void theMigrationIsIdempotentLedgeredAndRollsBack() throws Exception {
        String sql = Files.readString(DIR.resolve(ID + ".sql"));
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS botbond"));
        assertTrue(sql.contains("PRIMARY KEY (BotId, OtherId)"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(rollback.contains("DROP TABLE IF EXISTS botbond"));
        assertTrue(rollback.contains("migration_id='" + ID + "'"), "the rollback clears its ledger row");
        for (String col : new String[]{"BotId", "OtherId", "Score", "Kind", "Hostile", "UpdatedAt"}) {
            assertTrue(sql.contains(col) && BondStore.Jdbc.UPSERT.contains(col) && BondStore.Jdbc.SELECT.contains(col),
                    col);
        }
    }
}
