package database;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6.1 rebirth2-seal-blocked: rebirth 2 needs a Von Leon's Seal, which only Plunk (3004006) hands out after a clear,
 * and only while he stands in Von Leon's room (3004006.js picks the reward by his map). He was in neither boss room his
 * script serves, so 070 places him in 211070100 and 271040100 for worlds 0 and 1.
 */
class PlunkBossRewardRoomsMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "070-plunk-boss-reward-rooms";
    static final int PLUNK = 3004006;
    static final int VON_LEON_ROOM = 211070100;
    static final int CYGNUS_ROOM = 271040100;

    /** One placement row of the migration: map, life, type, x, ground y, foothold. */
    record Row(int map, int life, String type, int x, int y, int fh) {
    }

    private static String migration() throws Exception {
        return Files.readString(DIR.resolve(ID + ".sql"));
    }

    private static String rollback() throws Exception {
        return Files.readString(DIR.resolve(ID + ".rollback.sql"));
    }

    private static List<Row> rows(String sql) {
        Pattern row = Pattern.compile("SELECT (\\d+)(?: AS map)?, (\\d+)(?: AS life)?, '(\\w)'(?: AS type)?, "
                + "(-?\\d+)(?: AS x)?, (-?\\d+)(?: AS y)?, (\\d+)(?: AS fh)?, -1");
        List<Row> out = new ArrayList<>();
        Matcher m = row.matcher(sql);
        while (m.find()) {
            out.add(new Row(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))));
        }
        return out;
    }

    @Test
    void theMigrationPlacesPlunkInBothBossRoomsAndNothingElse() throws Exception {
        String sql = migration();
        List<Row> rows = rows(sql);
        assertEquals(List.of(new Row(VON_LEON_ROOM, PLUNK, "n", -1800, -181, 1),
                new Row(CYGNUS_ROOM, PLUNK, "n", -800, 115, 1)), rows);
        assertTrue(sql.contains("FROM (SELECT 0 AS world UNION ALL SELECT 1) w"), "worlds 0 and 1");
        assertTrue(sql.contains("AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life)"),
                "an NPC already standing there is not placed twice");
        assertFalse(sql.contains("UPDATE"), "nothing existing changes");
        assertFalse(sql.contains("DELETE"), "nothing existing changes");
    }

    @Test
    void theMigrationIsLedgeredRecordsItsRowsAndApplyingTwiceAddsNothing() throws Exception {
        String sql = migration();
        assertTrue(sql.contains("SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "')"));
        assertTrue(sql.contains("WHERE @apply"));
        assertTrue(sql.contains("INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)\nSELECT '" + ID + "', 'plife'"));
        assertTrue(sql.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.contains("START TRANSACTION") && sql.contains("COMMIT"));
    }

    @Test
    void theRollbackRemovesOnlyTheRowsTheMigrationRecorded() throws Exception {
        String rb = rollback();
        assertTrue(rb.contains("DELETE FROM plife WHERE id IN (\n  SELECT row_id FROM mapleroot_integration_migration_rows\n"
                + "  WHERE migration_id = '" + ID + "' AND table_name = 'plife'\n)"));
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
        assertFalse(rb.contains("DELETE FROM plife WHERE life"), "never by NPC id: a Plunk placed by hand stays");
    }

    @Test
    void plunksRewardScriptServesBothRooms() throws Exception {
        String script = Files.readString(Path.of("scripts/npc/3004006.js"));
        assertTrue(script.contains("case " + VON_LEON_ROOM + ":"), "Von Leon's reward branch");
        // 1.6.7 bagrewards: the Seal is now one entry of a reward list rolled per player, so a source-text match on the
        // old gainItem call is gone. That the Seal is in every Von Leon band is asserted where it belongs, by rolling
        // the script: scripting.PlunkBossRewardBagTest.vonLeonsNineRandomBandsAreTheOnesTheScriptAlwaysGave.
        assertTrue(script.contains("case " + CYGNUS_ROOM + ":"), "Cygnus's reward branch");
    }

    @Test
    void eachSpotStandsOnTheRoomsFloor() throws Exception {
        WzTestData.assumePresent("Map.wz");
        for (Row r : rows(migration())) {
            Path map = WzTestData.dir().resolve("Map.wz/Map/Map2/" + r.map() + ".img.xml");
            String xml = Files.readString(map);
            Matcher fh = Pattern.compile("<imgdir name=\"" + r.fh() + "\">((?:\\s*<int name=\"\\w+\" value=\"-?\\d+\"/>)+)")
                    .matcher(xml.substring(xml.indexOf("<imgdir name=\"foothold\">")));
            assertTrue(fh.find(), "foothold " + r.fh() + " in " + r.map());
            String f = fh.group(1);
            int x1 = intAttr(f, "x1"), x2 = intAttr(f, "x2"), y1 = intAttr(f, "y1"), y2 = intAttr(f, "y2");
            assertTrue(Math.min(x1, x2) <= r.x() && r.x() <= Math.max(x1, x2), r + " lies over foothold " + r.fh());
            assertEquals(y1, y2, "a flat foothold");
            assertEquals(y1, r.y(), r + " stands at the foothold's height");
        }
    }

    private static int intAttr(String block, String name) {
        Matcher m = Pattern.compile("<int name=\"" + name + "\" value=\"(-?\\d+)\"/>").matcher(block);
        assertTrue(m.find(), name);
        return Integer.parseInt(m.group(1));
    }
}
