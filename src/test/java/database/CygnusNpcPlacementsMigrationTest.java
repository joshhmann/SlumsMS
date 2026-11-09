package database;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.8 card cygnus-fight-unreachable: the Cygnus expedition could not be started or advanced because its recruiter
 * 3003970 (creates the CYGNUS expedition) and its summoner 3003903 (spawns 8850011) stood on no map. 071 places them
 * in the Cygnus Garden 271040000 (recruiter, on the arrival ledge) and Cygnus's Chamber 271040100 (summoner, at the
 * spot the climb uses) for worlds 0 and 1, and records what it added so the rollback is exact.
 */
class CygnusNpcPlacementsMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "071-cygnus-npcs";
    static final int RECRUITER = 3003970;
    static final int SUMMONER = 3003903;
    static final int GARDEN = 271040000;
    static final int CHAMBER = 271040100;
    /** Two NPC sprites side by side are about 50 px; closer than this to a portal and one of them is unusable. */
    static final int PORTAL_CLEARANCE = 60;

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
    void theMigrationPlacesTheRecruiterAndTheSummonerAndNothingElse() throws Exception {
        String sql = migration();
        assertEquals(List.of(new Row(GARDEN, RECRUITER, "n", -1000, 108, 1),
                new Row(CHAMBER, SUMMONER, "n", -104, 115, 1)), rows(sql), "one row per NPC, no others");
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
        assertTrue(sql.contains("INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)\nSELECT '"
                + ID + "', 'plife'"));
        assertTrue(sql.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.contains("START TRANSACTION") && sql.contains("COMMIT"));
    }

    @Test
    void theRollbackRemovesOnlyTheRowsTheMigrationRecorded() throws Exception {
        String rb = rollback();
        assertTrue(rb.contains("DELETE FROM plife WHERE id IN (\n  SELECT row_id FROM mapleroot_integration_migration_rows\n"
                + "  WHERE migration_id = '" + ID + "' AND table_name = 'plife'\n)"));
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '" + ID + "'"));
        assertTrue(rb.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
        assertFalse(rb.contains("DELETE FROM plife WHERE life"), "never by NPC id: a hand-placed copy stays");
    }

    @Test
    void bothNpcsExistAndTheirScriptsAreTheOnesThatDriveTheFight() throws Exception {
        WzTestData.assumePresent("Npc.wz");
        for (Row r : rows(migration())) {
            assertTrue(Files.exists(WzTestData.dir().resolve("Npc.wz/" + r.life() + ".img.xml")), r.life() + " has no Npc.wz sprite");
        }
        String recruiter = Files.readString(Path.of("scripts/npc/" + RECRUITER + ".js"));
        assertTrue(recruiter.contains("ExpeditionType.CYGNUS"), "the recruiter creates the Cygnus expedition");
        String summoner = Files.readString(Path.of("scripts/npc/" + SUMMONER + ".js"));
        assertTrue(summoner.contains("spawnMonsterOnGroundBelow(8850011"), "the summoner spawns her");
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

    @Test
    void noNpcStandsOnTopOfAPortal() throws Exception {
        WzTestData.assumePresent("Map.wz");
        for (Row r : rows(migration())) {
            Map<String, Integer> portals = portalsOf(r.map());
            assertFalse(portals.isEmpty(), "map " + r.map() + " has portals");
            for (Map.Entry<String, Integer> p : portals.entrySet()) {
                assertTrue(Math.abs(p.getValue() - r.x()) >= PORTAL_CLEARANCE,
                        r.life() + " at x " + r.x() + " is " + Math.abs(p.getValue() - r.x()) + " px from portal "
                                + p.getKey() + " (x " + p.getValue() + ")");
            }
        }
    }

    @Test
    void theRecruiterStandsWhereTheGateDropsPlayers() throws Exception {
        WzTestData.assumePresent("Map.wz");
        Row recruiter = rows(migration()).stream().filter(r -> r.life() == RECRUITER).findFirst().orElseThrow();
        assertEquals(GARDEN, recruiter.map());
        int arrival = portalsOf(GARDEN).get("out00");
        assertTrue(Math.abs(arrival - recruiter.x()) <= 200,
                "the gate lands players on out00 (x " + arrival + "); the recruiter is at x " + recruiter.x());
        // the chamber door must not be a walk-in trigger next to him
        assertTrue(Math.abs(portalsOf(GARDEN).get("in00") - recruiter.x()) > 500, "kept away from the chamber door");
    }

    /** Portal name -> x, for every portal in the map image. */
    private static Map<String, Integer> portalsOf(int mapId) throws Exception {
        String xml = Files.readString(WzTestData.dir().resolve("Map.wz/Map/Map2/" + mapId + ".img.xml"));
        String portalBlock = xml.substring(xml.indexOf("<imgdir name=\"portal\">"));
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher block = Pattern.compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>").matcher(portalBlock);
        while (block.find()) {
            String b = block.group(1);
            if (!b.contains("<string name=\"pn\"")) {
                continue;    // past the portal list: the reactor, back and life sections carry no portal name
            }
            out.put(strAttr(b, "pn"), intAttr(b, "x"));
        }
        return out;
    }

    private static String strAttr(String block, String name) {
        Matcher m = Pattern.compile("<string name=\"" + name + "\" value=\"([^\"]*)\"/>").matcher(block);
        assertTrue(m.find(), name);
        return m.group(1);
    }

    private static int intAttr(String block, String name) {
        Matcher m = Pattern.compile("<int name=\"" + name + "\" value=\"(-?\\d+)\"/>").matcher(block);
        assertTrue(m.find(), name);
        return Integer.parseInt(m.group(1));
    }
}
