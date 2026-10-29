package server.checkin;

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

/**
 * Ticket 111 follow-up: migration 052 converts the Billion Coins that 039 missed, in wedding gifts (inventoryitems
 * type 8) and MTS listings, at 6 stamps each. Runs on a throwaway MySQL 8 given by
 * {@code -Dslums.stamps.it.url=jdbc:mysql://127.0.0.1:PORT/DB?user=..&password=..} (skipped otherwise; never a game
 * database, it makes its own minimal tables). Applies 039, then 052 twice, then rolls 052 back twice.
 */
class SlumsStampsGiftsMtsMigrationIT {

    static final String URL = System.getProperty("slums.stamps.it.url");
    static final int COIN = 4001253;

    Connection db;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        db = DriverManager.getConnection(URL);
        exec("DROP TABLE IF EXISTS accounts, characters, inventoryitems, dueypackages, mts_items, mts_cart, infinitystorage, stamps_052_bag,"
                + " stamps_039_coins, stamps_039_accounts, stamps_052_coins, stamps_052_mts, stamps_052_carts,"
                + " stamps_052_accounts, mapleroot_integration_migrations");
        exec("CREATE TABLE accounts (id INT PRIMARY KEY, name VARCHAR(13) NOT NULL, votepoints INT NOT NULL DEFAULT 0)");
        exec("CREATE TABLE characters (id INT PRIMARY KEY, accountid INT NOT NULL, gm TINYINT NOT NULL DEFAULT 0)");
        exec("CREATE TABLE dueypackages (PackageId INT UNSIGNED PRIMARY KEY, ReceiverId INT UNSIGNED NOT NULL)");
        exec("CREATE TABLE inventoryitems (inventoryitemid INT UNSIGNED NOT NULL AUTO_INCREMENT, type TINYINT UNSIGNED"
                + " NOT NULL, characterid INT DEFAULT NULL, accountid INT DEFAULT NULL, itemid INT NOT NULL DEFAULT 0,"
                + " inventorytype INT NOT NULL DEFAULT 0, position INT NOT NULL DEFAULT 0, quantity INT NOT NULL"
                + " DEFAULT 0, owner TINYTEXT NOT NULL, petid INT NOT NULL DEFAULT -1, flag INT NOT NULL, expiration"
                + " BIGINT NOT NULL DEFAULT -1, giftFrom VARCHAR(26) NOT NULL, PRIMARY KEY (inventoryitemid),"
                + " KEY CHARID (characterid))");
        exec("CREATE TABLE mts_items (id INT UNSIGNED NOT NULL AUTO_INCREMENT, tab INT NOT NULL DEFAULT 0, type INT NOT"
                + " NULL DEFAULT 0, itemid INT UNSIGNED NOT NULL DEFAULT 0, quantity INT NOT NULL DEFAULT 1, seller INT"
                + " NOT NULL DEFAULT 0, price INT NOT NULL DEFAULT 0, owner TINYTEXT, sellername VARCHAR(16),"
                + " transfer TINYINT(1) NOT NULL DEFAULT 0, PRIMARY KEY (id))");
        exec("CREATE TABLE mts_cart (id INT NOT NULL AUTO_INCREMENT, cid INT NOT NULL, itemid INT NOT NULL,"
                + " PRIMARY KEY (id))");
        exec("CREATE TABLE infinitystorage (id INT NOT NULL AUTO_INCREMENT, charid INT NOT NULL, itemid INT NOT NULL,"
                + " amount INT NOT NULL DEFAULT 0, type INT DEFAULT NULL, position INT DEFAULT NULL, PRIMARY KEY (id))");
        // account 1: char 11 has 2 coins in wedding gifts, char 12 has an MTS listing of 3 coins -> 5 * 6 = 30
        // account 2: 1 coin in a wedding gift and 4 in two listings -> 30; account 3: nothing, only other items
        // a bot (no characters row) has a gifted coin and a listing: removed, no stamps
        exec("INSERT INTO accounts VALUES (1, 'friend', 0), (2, 'pal', 0), (3, 'quiet', 0)");
        exec("INSERT INTO characters VALUES (11, 1, 0), (12, 1, 0), (21, 2, 0), (31, 3, 0)");
        gift(11, COIN, 2);
        gift(21, COIN, 1);
        gift(1_000_000_007, COIN, 6);
        gift(31, 1302000, 1);              // an ordinary wedding gift stays
        exec("INSERT INTO inventoryitems (type, characterid, itemid, quantity, owner, flag, giftFrom)"
                + " VALUES (1, 11, " + COIN + ", 7, '', 0, '')");   // 039's job, not 052's: left alone here
        exec("INSERT INTO infinitystorage (charid, itemid, amount) VALUES (31, " + COIN + ", 10), (12, " + COIN
                + ", 1), (1000000007, " + COIN + ", 8), (31, 4000000, 99)");   // acct 3: +60, acct 1: +6, bot: none
        listing(12, COIN, 3);
        listing(21, COIN, 1);
        listing(21, COIN, 3);
        listing(1_000_000_007, COIN, 2);
        listing(31, 1302000, 1);
        exec("INSERT INTO mts_cart (cid, itemid) SELECT 31, id FROM mts_items WHERE itemid = " + COIN + " AND seller = 12");
        exec("INSERT INTO mts_cart (cid, itemid) SELECT 31, id FROM mts_items WHERE itemid = 1302000");
        runFile("039-slums-stamps.sql");   // adds accounts.stamps (and takes the bag coin: 7 * 6 = 42 on account 1)
        runFile("052-stamps-gifts-mts.sql");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private void gift(int chr, int itemId, int qty) throws SQLException {
        exec("INSERT INTO inventoryitems (type, characterid, itemid, quantity, owner, flag, giftFrom) VALUES (8, " + chr
                + ", " + itemId + ", " + qty + ", '', 0, 'spouse')");
    }

    private void listing(int seller, int itemId, int qty) throws SQLException {
        exec("INSERT INTO mts_items (itemid, quantity, seller, price, owner, sellername) VALUES (" + itemId + ", " + qty
                + ", " + seller + ", 100, '', 'x')");
    }

    @Test
    void convertsGiftsAndListingsAtSixAndAppliesOnce() throws Exception {
        for (int pass = 1; pass <= 2; pass++) {
            assertEquals(42 + 30 + 6, one("SELECT stamps FROM accounts WHERE id = 1"), "pass " + pass);
            assertEquals(30, one("SELECT stamps FROM accounts WHERE id = 2"));
            assertEquals(60, one("SELECT stamps FROM accounts WHERE id = 3"));
            assertEquals(0, one("SELECT COUNT(*) FROM infinitystorage WHERE itemid = " + COIN));
            assertEquals(1, one("SELECT COUNT(*) FROM infinitystorage"), "other bag items stay");
            assertEquals(0, one("SELECT COUNT(*) FROM inventoryitems WHERE itemid = " + COIN), "every coin removed");
            assertEquals(1, one("SELECT COUNT(*) FROM inventoryitems"), "the ordinary gift stays");
            assertEquals(0, one("SELECT COUNT(*) FROM mts_items WHERE itemid = " + COIN));
            assertEquals(1, one("SELECT COUNT(*) FROM mts_items"), "the ordinary listing stays");
            assertEquals(1, one("SELECT COUNT(*) FROM mts_cart"), "carts of removed listings go");
            assertEquals(126, one("SELECT SUM(stamps) FROM stamps_052_accounts"));
            runFile("052-stamps-gifts-mts.sql");   // again: no change
        }
    }

    @Test
    void rollbackPutsEverythingBackTwiceSafely() throws Exception {
        runFile("052-stamps-gifts-mts.rollback.sql");
        runFile("052-stamps-gifts-mts.rollback.sql");
        assertEquals(0, one("SELECT stamps FROM accounts WHERE id = 3"));
        assertEquals(19, one("SELECT SUM(amount) FROM infinitystorage WHERE itemid = " + COIN));
        assertEquals(42, one("SELECT stamps FROM accounts WHERE id = 1"), "only 039's stamps remain");
        assertEquals(0, one("SELECT stamps FROM accounts WHERE id = 2"));
        assertEquals(9, one("SELECT SUM(quantity) FROM inventoryitems WHERE type = 8 AND itemid = " + COIN));
        assertEquals(9, one("SELECT SUM(quantity) FROM mts_items WHERE itemid = " + COIN));
        assertEquals(2, one("SELECT COUNT(*) FROM mts_cart"), "both carts back");
        assertEquals(0, one("SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id LIKE '052%'"));
        runFile("052-stamps-gifts-mts.sql");   // and forward again
        assertEquals(78, one("SELECT stamps FROM accounts WHERE id = 1"));
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
