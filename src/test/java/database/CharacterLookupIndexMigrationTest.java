package database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration 023 (1.5.2, ticket 80, after nutnnut/Cosmic 028/029/032): the per-character, per-account and drop_data
 * lookup indexes. Every index is added only when missing and dropped only when present, and both files keep the ledger.
 */
class CharacterLookupIndexMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "023-character-lookup-indexes";

    private static final String[][] INDEXES = {
            {"queststatus", "idx_characterid"}, {"questprogress", "idx_characterid"}, {"keymap", "idx_characterid"},
            {"savedlocations", "idx_characterid"}, {"trocklocations", "idx_characterid"}, {"buddies", "idx_characterid"},
            {"skillmacros", "idx_characterid"}, {"area_info", "idx_charid"}, {"inventoryitems", "idx_accountid"},
            {"drop_data", "idx_itemid_chance_dropperid"},
    };

    @Test
    void everyIndexIsGuardedAndRolledBackAndTheLedgerIsKept() throws Exception {
        String sql = Files.readString(DIR.resolve(ID + ".sql"));
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(rollback.contains("migration_id='" + ID + "'"), "the rollback clears its ledger row");
        for (String[] ix : INDEXES) {
            String table = ix[0], name = ix[1];
            assertTrue(sql.contains("TABLE_NAME = '" + table + "' AND SEQ_IN_INDEX = 1"), table + ": added only when missing");
            assertTrue(sql.contains("'ALTER TABLE " + table + " ADD INDEX " + name + " "), table + ": adds " + name);
            assertTrue(rollback.contains("TABLE_NAME = '" + table + "' AND INDEX_NAME = '" + name + "'"),
                    table + ": dropped only when present");
            assertTrue(rollback.contains("'ALTER TABLE " + table + " DROP INDEX " + name + "'"), table + ": drops " + name);
        }
        assertTrue(sql.contains("ADD INDEX idx_itemid_chance_dropperid (itemid, chance, dropperid)"), "covering drop index");
    }
}
