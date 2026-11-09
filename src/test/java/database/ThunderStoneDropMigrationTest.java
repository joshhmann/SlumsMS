package database;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.8 card cygnus-fight-unreachable: the Dream Key 4032923 is made from the four stones plus the Thunder Stone
 * 2430200 (scripts/item/dreamkey.js), and nothing dropped that stone, so no player could make a key and enter the
 * Cygnus Garden. 072 adds the one drop row the reference server has - Thunder 8610004 -> 2430200 - at the default
 * 1.5% (chance is per 1,000,000), and records the row id for an exact rollback.
 */
class ThunderStoneDropMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "072-thunder-stone-drop";
    static final int THUNDER = 8610004;
    static final int THUNDER_STONE = 2430200;
    static final int CHANCE = 15000;

    private static String migration() throws Exception {
        return Files.readString(DIR.resolve(ID + ".sql"));
    }

    private static String rollback() throws Exception {
        return Files.readString(DIR.resolve(ID + ".rollback.sql"));
    }

    /** SQL without its -- comment lines, so a mention in prose is not read as a statement. */
    private static String statements(String sql) {
        return sql.lines().filter(l -> !l.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
    }

    /** The first statement of the file: this is what the owner edits to change the rate. */
    private static String firstStatement(String sql) {
        return sql.lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("--"))
                .findFirst().map(l -> l.split("--")[0].strip()).orElseThrow();
    }

    @Test
    void theChanceIsOneVariableAtTheTopOfTheFile() throws Exception {
        String sql = migration();
        assertEquals("SET @thunder_stone_chance = " + CHANCE + ";", firstStatement(sql),
                "the rate is the first statement, one number to change");
        Matcher m = Pattern.compile("(?m)^\\s*SET\\s+@thunder_stone_chance\\s*=").matcher(sql);
        assertTrue(m.find(), "the variable is assigned");
        assertFalse(m.find(), "assigned once");
    }

    @Test
    void theMigrationInsertsExactlyTheOneThunderRowAtTheVariableRate() throws Exception {
        String sql = migration();
        Pattern insert = Pattern.compile(
                "INSERT INTO drop_data \\(dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance\\)\\s*"
                        + "SELECT (\\d+), (\\d+), (\\d+), (\\d+), (\\d+), @thunder_stone_chance FROM DUAL");
        Matcher m = insert.matcher(sql);
        assertTrue(m.find(), "the migration inserts the Thunder Stone drop");
        assertEquals(THUNDER, Integer.parseInt(m.group(1)), "dropperid: the Thunder");
        assertEquals(THUNDER_STONE, Integer.parseInt(m.group(2)), "itemid: the Thunder Stone");
        assertEquals(1, Integer.parseInt(m.group(3)), "minimum_quantity");
        assertEquals(1, Integer.parseInt(m.group(4)), "maximum_quantity");
        assertEquals(0, Integer.parseInt(m.group(5)), "questid: a normal drop, not quest-only");

        assertFalse(m.find(), "one row, not two");
        assertEquals(1, statements(sql).split("INSERT INTO drop_data", -1).length - 1,
                "the migration inserts into drop_data once");
    }

    @Test
    void applyingTwiceAddsOneRow() throws Exception {
        String sql = migration();
        assertTrue(sql.contains("SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '"
                + ID + "')"));
        assertTrue(sql.contains("WHERE @apply AND NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = " + THUNDER
                        + " AND d.itemid = " + THUNDER_STONE + ")"),
                "a second apply, or a row already there, inserts nothing");
        assertTrue(sql.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.contains("START TRANSACTION") && sql.contains("COMMIT"));
    }

    @Test
    void aPreexistingRowIsLeftAloneAndNeverRecorded() throws Exception {
        String sql = migration();
        assertFalse(sql.contains("UPDATE "), "nothing existing changes");
        assertFalse(sql.contains("DELETE "), "the migration only adds");
        // the snapshot of the ids already there is taken before the insert; the record step only owns new ids
        int snapshot = sql.indexOf("INSERT INTO migration_drop_data_before");
        int insert = sql.indexOf("INSERT INTO drop_data (dropperid");
        int record = sql.indexOf("INSERT INTO mapleroot_integration_migration_rows");
        assertTrue(snapshot > 0 && insert > snapshot && record > insert, "snapshot, insert, then record");
        assertTrue(sql.contains("INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)\n"
                        + "SELECT '" + ID + "', 'drop_data', d.id"),
                "the inserted row id is recorded in the ledger");
        assertTrue(sql.contains("AND NOT EXISTS (SELECT 1 FROM migration_drop_data_before b WHERE b.id = d.id)"),
                "a row that was there before the insert is never recorded, so the rollback cannot remove it");
        assertTrue(sql.contains("INSERT INTO migration_drop_data_before SELECT id FROM drop_data WHERE dropperid = "
                        + THUNDER + " AND itemid = " + THUNDER_STONE),
                "the snapshot covers the pair, not the whole table");
        assertTrue(sql.contains("DROP TEMPORARY TABLE migration_drop_data_before"));
    }

    @Test
    void theRollbackRemovesOnlyTheRecordedRow() throws Exception {
        String rb = rollback();
        assertTrue(rb.contains("DELETE FROM drop_data WHERE id IN (\n"
                        + "  SELECT row_id FROM mapleroot_integration_migration_rows\n"
                        + "  WHERE migration_id = '" + ID + "' AND table_name = 'drop_data'\n)"),
                "the drop row goes by recorded id");
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '" + ID + "'"));
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
        String stmts = statements(rb);
        assertFalse(stmts.contains("itemid = " + THUNDER_STONE),
                "never by item id: a Thunder row someone added by hand stays");
        assertFalse(stmts.contains("UPDATE "), "the rollback touches nothing else");
        assertTrue(rb.contains("START TRANSACTION") && rb.contains("COMMIT"));
    }

    @Test
    void theDropperIsTheThunderThatStandsInKnightDistrict3() throws Exception {
        WzTestData.assumePresent("Mob.wz");
        assertTrue(Files.exists(WzTestData.dir().resolve("Mob.wz/" + THUNDER + ".img.xml")),
                THUNDER + " is a real mob");
        WzTestData.assumePresent("Map.wz");
        String map = Files.readString(WzTestData.dir().resolve("Map.wz/Map/Map2/271030205.img.xml"));
        String life = map.substring(map.indexOf("<imgdir name=\"life\">"));
        assertTrue(life.contains("<string name=\"id\" value=\"" + THUNDER + "\"/>"),
                "the Thunder spawns in Knight District 3 271030205");
    }
}
