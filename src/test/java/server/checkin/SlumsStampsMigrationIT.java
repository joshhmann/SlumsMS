package server.checkin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 111: migration 039 and the stamp SQL against a real, throwaway MySQL 8 database. Skipped unless
 * {@code -Dslums.stamps.it.url=jdbc:mysql://127.0.0.1:PORT/DB?user=..&password=..} points at an empty scratch schema
 * (never a game database: it makes its own minimal tables). Converts vote points and Billion Coins in inventories,
 * hired merchants, storage and Duey at 6 stamps each, twice (no change the second time), then rolls back twice.
 */
class SlumsStampsMigrationIT {

    static final String URL = System.getProperty("slums.stamps.it.url");
    static final int COIN = 4001253;

    Connection db;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        db = DriverManager.getConnection(URL);
        exec("DROP TABLE IF EXISTS accounts, characters, inventoryitems, dueypackages, stamps_039_coins,"
                + " stamps_039_accounts, mapleroot_integration_migrations");
        exec("CREATE TABLE accounts (id INT PRIMARY KEY, name VARCHAR(13) NOT NULL, votepoints INT NOT NULL DEFAULT 0)");
        exec("CREATE TABLE characters (id INT PRIMARY KEY, accountid INT NOT NULL, gm TINYINT NOT NULL DEFAULT 0)");
        exec("CREATE TABLE dueypackages (PackageId INT UNSIGNED PRIMARY KEY, ReceiverId INT UNSIGNED NOT NULL)");
        exec("CREATE TABLE inventoryitems (inventoryitemid INT UNSIGNED NOT NULL AUTO_INCREMENT, type TINYINT UNSIGNED"
                + " NOT NULL, characterid INT DEFAULT NULL, accountid INT DEFAULT NULL, itemid INT NOT NULL DEFAULT 0,"
                + " inventorytype INT NOT NULL DEFAULT 0, position INT NOT NULL DEFAULT 0, quantity INT NOT NULL"
                + " DEFAULT 0, owner TINYTEXT NOT NULL, petid INT NOT NULL DEFAULT -1, flag INT NOT NULL, expiration"
                + " BIGINT NOT NULL DEFAULT -1, giftFrom VARCHAR(26) NOT NULL, PRIMARY KEY (inventoryitemid),"
                + " KEY CHARID (characterid))");
        // account 1: 3 vote points, 2 coins in a bag, 1 in a hired merchant, 4 in storage -> (3 + 7) * 6 = 60
        // account 2: 5 coins arriving by Duey -> 30;  account 3: staff with 1 vote point -> 6 (held value converts)
        // account 4: nothing -> 0;  a bot (no characters row) holds 9 coins -> removed, no stamps
        exec("INSERT INTO accounts VALUES (1, 'friend', 3), (2, 'pal', 0), (3, 'owner', 1), (4, 'quiet', 0)");
        exec("INSERT INTO characters VALUES (11, 1, 0), (12, 1, 0), (21, 2, 0), (31, 3, 6), (41, 4, 0)");
        exec("INSERT INTO dueypackages VALUES (500, 21)");
        item(1, 11, null, COIN, 2);
        item(6, 12, null, COIN, 1);
        item(2, null, 1, COIN, 4);
        item(9, 500, null, COIN, 5);
        item(1, 1_000_000_007, null, COIN, 9);
        item(1, 11, null, 4310000, 50);   // a Root Coin stays
        item(2, null, 1, 2000005, 10);    // other storage stays
        runFile("039-slums-stamps.sql");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private void item(int type, Integer chr, Integer acc, int itemId, int qty) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO inventoryitems (type, characterid, accountid,"
                + " itemid, quantity, owner, flag, giftFrom) VALUES (?, ?, ?, ?, ?, '', 0, '')")) {
            ps.setInt(1, type);
            ps.setObject(2, chr);
            ps.setObject(3, acc);
            ps.setInt(4, itemId);
            ps.setInt(5, qty);
            ps.executeUpdate();
        }
    }

    @Test
    void convertsAtSixAndAppliesOnce() throws Exception {
        for (int pass = 1; pass <= 2; pass++) {
            assertEquals(60, one("SELECT stamps FROM accounts WHERE id = 1"), "pass " + pass);
            assertEquals(30, one("SELECT stamps FROM accounts WHERE id = 2"));
            assertEquals(6, one("SELECT stamps FROM accounts WHERE id = 3"));
            assertEquals(0, one("SELECT stamps FROM accounts WHERE id = 4"));
            assertEquals(0, one("SELECT SUM(votepoints) FROM accounts"));
            assertEquals(0, one("SELECT COUNT(*) FROM inventoryitems WHERE itemid = " + COIN), "every coin removed");
            assertEquals(2, one("SELECT COUNT(*) FROM inventoryitems"), "nothing else touched");
            assertEquals(5, one("SELECT COUNT(*) FROM stamps_039_coins"));
            assertEquals(96, one("SELECT SUM(stamps) FROM stamps_039_accounts"));
            runFile("039-slums-stamps.sql");   // again: no change
        }
    }

    @Test
    void rollbackRestoresCoinsAndVotePointsTwiceSafely() throws Exception {
        runFile("039-slums-stamps.rollback.sql");
        runFile("039-slums-stamps.rollback.sql");
        assertEquals(0, one("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'stamps'"));
        assertEquals(4, one("SELECT SUM(votepoints) FROM accounts"));
        assertEquals(21, one("SELECT SUM(quantity) FROM inventoryitems WHERE itemid = " + COIN));
        assertEquals(7, one("SELECT COUNT(*) FROM inventoryitems"));
        assertEquals(0, one("SELECT COUNT(*) FROM mapleroot_integration_migrations"));
        runFile("039-slums-stamps.sql");   // and forward again
        assertEquals(60, one("SELECT stamps FROM accounts WHERE id = 1"));
    }

    @Test
    void theCreditSkipsStaffAndQaAccountsAndSpendNeverGoesNegative() throws Exception {
        exec("INSERT INTO accounts (id, name) VALUES (5, 'autoqa1')");
        for (int acc : new int[]{1, 3, 5}) {
            try (PreparedStatement ps = db.prepareStatement(SlumsStamps.CREDIT_SQL)) {
                ps.setInt(1, 6);
                ps.setInt(2, acc);
                ps.setInt(3, 0);
                ps.setInt(4, acc);
                assertEquals(acc == 1 ? 1 : 0, ps.executeUpdate(), "account " + acc);
            }
        }
        assertEquals(66, one("SELECT stamps FROM accounts WHERE id = 1"));
        assertEquals(6, one("SELECT stamps FROM accounts WHERE id = 3"), "a staff account earns nothing");
        try (PreparedStatement ps = db.prepareStatement(SlumsStamps.CREDIT_SQL)) {   // the auto-QA check's override
            ps.setInt(1, 1);
            ps.setInt(2, 5);
            ps.setInt(3, 1);
            ps.setInt(4, 5);
            assertEquals(1, ps.executeUpdate());
        }
        assertTrue(spend(1, 66));
        assertFalse(spend(1, 1), "never below zero");
        assertEquals(0, one("SELECT stamps FROM accounts WHERE id = 1"));
    }

    private boolean spend(int acc, int n) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(SlumsStamps.SPEND_SQL)) {
            ps.setInt(1, n);
            ps.setInt(2, acc);
            ps.setInt(3, n);
            return ps.executeUpdate() == 1;
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
