package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Migration 031 (1.6.0 S5): the social gate's tables, idempotent, ledgered, with a rollback. */
class SocialGateMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "031-social-gate";

    @Test
    void theMigrationIsIdempotentLedgeredAndRollsBack() throws Exception {
        String sql = Files.readString(DIR.resolve(ID + ".sql"));
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS botsocialopt"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS botstartedit"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.contains("information_schema.TABLES") && sql.contains("migration_id = '" + ID + "'"),
                "the grudge backfill runs once, and only when botaffinity exists");
        assertTrue(sql.contains("BotId < 4000000000"), "the defame-claim row is not a tie");
        assertTrue(rollback.contains("DROP TABLE IF EXISTS botstartedit")
                && rollback.contains("DROP TABLE IF EXISTS botsocialopt"));
        assertTrue(rollback.contains("migration_id='" + ID + "'"), "the rollback clears its ledger row");
        // The JDBC store's statements name the migration's columns.
        for (String col : new String[]{"QuietMegaphones", "QuietBots", "RomanceOff", "Spice", "SpiceAt", "Flirt",
                "FlirtAt", "LastKind"}) {
            assertTrue(sql.contains(col) && (SocialGateStore.Jdbc.OPT_UPSERT.contains(col)
                    || SocialGateStore.Jdbc.LEDGER_UPSERT.contains(col)), col);
        }
    }
}
