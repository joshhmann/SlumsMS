package database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Migration 054: the 1.6.0.2 HUD tip's 29151 mark is taken off characters who own no starter chair, so StarterChairs
 * gives the chairs at the next login. Runs on a throwaway MySQL 8 given by
 * {@code -Dslums.chairs.it.url=jdbc:mysql://127.0.0.1:PORT/DB?user=..&password=..} (skipped otherwise; never a game
 * database, it makes its own minimal tables). Applies 054 twice, then rolls it back twice.
 */
class StarterChairRepairMigrationIT {

    static final String URL = System.getProperty("slums.chairs.it.url");
    static final String TIP = "'uitip=1.6.0.2'";
    static final String CHAIRS = "'chairs=1.6.0'";
    static final int RELAXER = 3010000;
    static final int FISHING_CHAIR = 3011000;
    static final int BOT = 1_000_000_007;

    Connection db;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        db = DriverManager.getConnection(URL);
        exec("DROP TABLE IF EXISTS characters, area_info, inventoryitems, dueypackages, mts_items, infinitystorage,"
                + " starter_chair_054_rows, mapleroot_integration_migrations");
        exec("CREATE TABLE characters (id INT PRIMARY KEY, accountid INT NOT NULL)");
        exec("CREATE TABLE area_info (id INT NOT NULL AUTO_INCREMENT, charid INT NOT NULL, area INT NOT NULL,"
                + " info VARCHAR(200) NOT NULL, PRIMARY KEY (id), KEY idx_charid (charid)) ENGINE=MyISAM");
        exec("CREATE TABLE dueypackages (PackageId INT UNSIGNED PRIMARY KEY, ReceiverId INT UNSIGNED NOT NULL)");
        exec("CREATE TABLE inventoryitems (inventoryitemid INT UNSIGNED NOT NULL AUTO_INCREMENT, type TINYINT UNSIGNED"
                + " NOT NULL, characterid INT DEFAULT NULL, accountid INT DEFAULT NULL, itemid INT NOT NULL DEFAULT 0,"
                + " quantity INT NOT NULL DEFAULT 1, PRIMARY KEY (inventoryitemid))");
        exec("CREATE TABLE mts_items (id INT UNSIGNED NOT NULL AUTO_INCREMENT, itemid INT UNSIGNED NOT NULL DEFAULT 0,"
                + " seller INT NOT NULL DEFAULT 0, PRIMARY KEY (id))");
        exec("CREATE TABLE infinitystorage (id INT NOT NULL AUTO_INCREMENT, charid INT NOT NULL, itemid INT NOT NULL,"
                + " amount INT NOT NULL DEFAULT 0, PRIMARY KEY (id))");
        // account 1: 11 lost the chairs (repaired, mark moved), 12 lost them and already has a 29153 tip mark
        // (repaired, mark deleted), 13 has a Relaxer in its bag (kept: 11's repair is not blocked by 13's item).
        // account 2: 21's Fishing Chair is in the account storage, 22 has a Relaxer on its hired merchant.
        // account 3: 31 has a Duey package with a chair, 32 has one in the Adventurer's Bag, 33 has one on the MTS.
        // 41 got the chairs properly (29151 'chairs=1.6.0'); a bot with a tip mark (no characters row) is left alone.
        exec("INSERT INTO characters VALUES (11, 1), (12, 1), (13, 1), (21, 2), (22, 2), (31, 3), (32, 3), (33, 3),"
                + " (41, 4)");
        for (int c : new int[] {11, 12, 13, 21, 22, 31, 32, 33, BOT}) {
            area(c, 29151, TIP);
        }
        area(12, 29153, TIP);
        area(41, 29151, CHAIRS);
        area(11, 29152, CHAIRS);      // the chair notice: never touched
        exec("INSERT INTO inventoryitems (type, characterid, accountid, itemid) VALUES"
                + " (1, 13, NULL, " + RELAXER + "), (2, NULL, 2, " + FISHING_CHAIR + "), (6, 22, NULL, " + RELAXER + "),"
                + " (9, 500, NULL, " + FISHING_CHAIR + "), (1, 11, NULL, 2000000), (2, NULL, 1, 4000000),"
                + " (1, 41, NULL, " + RELAXER + ")");
        exec("INSERT INTO dueypackages VALUES (500, 31)");
        exec("INSERT INTO infinitystorage (charid, itemid, amount) VALUES (32, " + RELAXER + ", 1)");
        exec("INSERT INTO mts_items (itemid, seller) VALUES (" + FISHING_CHAIR + ", 33)");
        runFile("054-starter-chair-repair.sql");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private void area(int chr, int area, String info) throws SQLException {
        exec("INSERT INTO area_info (charid, area, info) VALUES (" + chr + ", " + area + ", " + info + ")");
    }

    @Test
    void clearsTheTipMarkOnlyForCharactersWithoutAChairAndAppliesOnce() throws Exception {
        for (int pass = 1; pass <= 2; pass++) {
            assertNull(info(11, 29151), "pass " + pass);
            assertEquals("uitip=1.6.0.2", info(11, 29153), "moved to the tip's own key");
            assertNull(info(12, 29151));
            assertEquals(1, one("SELECT COUNT(*) FROM area_info WHERE charid = 12 AND area = 29153"), "no duplicate");
            for (int c : new int[] {13, 21, 22, 31, 32, 33, BOT}) {
                assertEquals("uitip=1.6.0.2", info(c, 29151), "char " + c + " owns a chair (or is a bot): untouched");
                assertNull(info(c, 29153));
            }
            assertEquals("chairs=1.6.0", info(41, 29151));
            assertEquals("chairs=1.6.0", info(11, 29152));
            assertEquals(2, one("SELECT COUNT(*) FROM starter_chair_054_rows"));
            assertEquals(1, one("SELECT COUNT(*) FROM starter_chair_054_rows WHERE charid = 11 AND action = 'moved'"));
            assertEquals(1, one("SELECT COUNT(*) FROM starter_chair_054_rows WHERE charid = 12 AND action = 'deleted'"));
            runFile("054-starter-chair-repair.sql");   // again: no change
        }
    }

    @Test
    void rollbackPutsTheMarksBackTwiceSafely() throws Exception {
        long rows = one("SELECT COUNT(*) FROM area_info");
        runFile("054-starter-chair-repair.rollback.sql");
        runFile("054-starter-chair-repair.rollback.sql");
        assertEquals("uitip=1.6.0.2", info(11, 29151));
        assertNull(info(11, 29153), "the moved mark goes");
        assertEquals("uitip=1.6.0.2", info(12, 29151));
        assertEquals("uitip=1.6.0.2", info(12, 29153), "12's own earlier tip mark stays");
        assertEquals(rows + 1, one("SELECT COUNT(*) FROM area_info"));
        assertEquals(0, one("SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id LIKE '054%'"));
        assertEquals(0, one("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name = 'starter_chair_054_rows'"));
        runFile("054-starter-chair-repair.sql");   // and forward again
        assertNull(info(11, 29151));
        assertNull(info(12, 29151));
    }

    @Test
    void rollbackAfterTheGameSavedAndGaveTheChairsKeepsThem() throws Exception {
        // the game rewrites a character's area_info with new ids on save; 11 logged in and got the chairs
        exec("DELETE FROM area_info WHERE charid = 11");
        area(11, 29152, CHAIRS);
        area(11, 29153, TIP);
        area(11, 29151, CHAIRS);
        exec("DELETE FROM area_info WHERE charid = 12 AND area = 29153");
        area(12, 29153, TIP);
        runFile("054-starter-chair-repair.rollback.sql");
        assertEquals("chairs=1.6.0", info(11, 29151), "a character who got the chairs keeps the chair mark");
        assertNull(info(11, 29153));
        assertEquals("uitip=1.6.0.2", info(12, 29151), "restored by character, not by the old row id");
    }

    String info(int chr, int area) throws SQLException {
        try (ResultSet rs = db.createStatement().executeQuery(
                "SELECT info FROM area_info WHERE charid = " + chr + " AND area = " + area)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    void exec(String sql) throws SQLException {
        db.createStatement().execute(sql);
    }

    long one(String sql) throws SQLException {
        try (ResultSet rs = db.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    void runFile(String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(Path.of("database/migrations", name))) {
            if (!line.strip().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        for (String stmt : sb.toString().split(";")) {
            if (!stmt.isBlank()) {
                exec(stmt);
            }
        }
    }
}
