package database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0nk/Cosmic bfcadc069: Cat's Eye (4031568) drops from Meerkat 2100108 for quest 3911 "Making the Fancy Sword".
 * Ours only had the Scorpion 2110301 row, so the quest's item text pointed at a mob that never dropped it.
 */
class CatEyeMeerkatDropMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "068-cat-eye-meerkat-drop";
    static final int MEERKAT = 2100108;
    static final int SCORPION = 2110301;
    static final int CATS_EYE = 4031568;
    static final int QUEST = 3911;
    static final int CHANCE = 80000;

    private static String migration() throws Exception {
        return Files.readString(DIR.resolve(ID + ".sql"));
    }

    private static String rollback() throws Exception {
        return Files.readString(DIR.resolve(ID + ".rollback.sql"));
    }

    /** SQL without its -- comment lines, so a mention in prose is not read as a statement. */
    private static String statements(String sql) {
        return sql.lines().filter(l -> !l.stripLeading().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void theMigrationInsertsExactlyTheUpstreamRow() throws Exception {
        String sql = migration();
        Pattern insert = Pattern.compile(
                "INSERT INTO drop_data \\(dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance\\)\\s*"
                        + "SELECT (\\d+), (\\d+), (\\d+), (\\d+), (\\d+), (\\d+) FROM DUAL");
        Matcher m = insert.matcher(sql);
        assertTrue(m.find(), "the migration must insert the Cat's Eye drop");
        assertEquals(MEERKAT, Integer.parseInt(m.group(1)));
        assertEquals(CATS_EYE, Integer.parseInt(m.group(2)));
        assertEquals(1, Integer.parseInt(m.group(3)), "minimum_quantity");
        assertEquals(1, Integer.parseInt(m.group(4)), "maximum_quantity");
        assertEquals(QUEST, Integer.parseInt(m.group(5)), "quest-only drop");
        assertEquals(CHANCE, Integer.parseInt(m.group(6)));

        assertFalse(m.find(), "the migration must not insert a second row");
        assertTrue(sql.contains("WHERE NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = " + MEERKAT
                        + " AND d.itemid = " + CATS_EYE + ")"),
                "a repeated apply must skip the row (drop_data has a unique key on dropperid+itemid)");
        assertFalse(statements(sql).contains(String.valueOf(SCORPION)), "the Scorpion row must be left alone");
        assertFalse(sql.contains("DELETE"), "the migration only adds the Meerkat row");
        assertFalse(sql.contains("UPDATE"), "the migration only adds the Meerkat row");
    }

    @Test
    void theMigrationIsLedgeredAndApplyingTwiceChangesNothing() throws Exception {
        String sql = migration();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations"));
        assertTrue(sql.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.contains("START TRANSACTION") && sql.contains("COMMIT"));
    }

    @Test
    void theRollbackRemovesOnlyTheMeerkatRow() throws Exception {
        String rb = rollback();
        assertTrue(rb.contains("DELETE FROM drop_data"));
        for (String condition : new String[]{
                "dropperid = " + MEERKAT, "itemid = " + CATS_EYE, "questid = " + QUEST, "chance = " + CHANCE}) {
            assertTrue(rb.contains(condition), "the rollback must match the row it removes: " + condition);
        }
        assertFalse(statements(rb).contains(String.valueOf(SCORPION)), "the rollback must not touch the Scorpion row");
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
    }
}
