package database;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.transactions.TransactionService;
import tools.DatabaseConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 P0-1 fail-before at the public entry point ({@link TransactionService#processTransaction}), which exists on
 * the base too: a crash after the first item of a buyback must leave the transaction buyable again and the player's
 * mesos unchanged. On the 1.6.4 code the flag is set first and never reverted, so this fails there.
 * <p>
 * Real SQL against a scratch MySQL schema ({@code -Dslums.family.it.url}, never a game database); only the item add
 * (to inject the crash) and the character are stubbed.
 */
class BuybackEntryPointIT {

    static final String URL = System.getProperty("slums.family.it.url");
    static final int USER = 4242;

    @Test
    void aCrashAfterTheFirstItemLeavesTheBuybackUnusedAndTheMesosUntouched() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        int txId;
        try (Connection db = DriverManager.getConnection(URL)) {
            schema(db);
            txId = seed(db);
        }

        int limitBefore = config.YamlConfig.config.server.MAXIMUM_TRANSACTIONS_FOR_BUYBACK;
        config.YamlConfig.config.server.MAXIMUM_TRANSACTIONS_FOR_BUYBACK = 5; // unset in a bare config.yaml (0 = none listed)
        try {
            runBuyback(txId);
        } finally {
            config.YamlConfig.config.server.MAXIMUM_TRANSACTIONS_FOR_BUYBACK = limitBefore;
        }
    }

    private static void runBuyback(int txId) throws Exception {
        Character player = mock(Character.class);
        AtomicInteger mesoDelta = new AtomicInteger();
        when(player.getId()).thenReturn(USER);
        when(player.getMeso()).thenReturn(1_000_000);
        when(player.canHold(any())).thenReturn(true);
        doAnswer(inv -> mesoDelta.addAndGet(inv.getArgument(0))).when(player).gainMeso(anyInt(), anyBoolean());

        AtomicInteger adds = new AtomicInteger();
        try (MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class);
             MockedStatic<InventoryManipulator> inv = mockStatic(InventoryManipulator.class)) {
            dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> DriverManager.getConnection(URL));
            inv.when(() -> InventoryManipulator.add(any(), any(Item.class), anyBoolean())).thenAnswer(a -> {
                if (adds.incrementAndGet() == 2) {
                    throw new IllegalStateException("injected crash after the first item");
                }
                return true;
            });

            var result = TransactionService.processTransaction(player, 0);
            assertFalse(result.getResult(), "the buyback must report failure");
            assertEquals(2, adds.get(), "fixture: the buyback reached the items and crashed on the 2nd ("
                    + result.getMessage() + ")");
        }

        try (Connection db = DriverManager.getConnection(URL)) {
            assertEquals(0, intOf(db, "SELECT buyback_used FROM transactions WHERE transaction_id = " + txId),
                    "a crashed buyback must not stay marked used (1.6.4 sets it first and never reverts it)");
        }
        assertEquals(0, mesoDelta.get(), "no mesos taken (or taken and refunded)");
    }

    private static void schema(Connection db) throws Exception {
        exec(db, "SET FOREIGN_KEY_CHECKS = 0");
        exec(db, "DROP TABLE IF EXISTS transaction_equips, transaction_items, transactions, operation_journal,"
                + " mapleroot_integration_migrations");
        exec(db, "SET FOREIGN_KEY_CHECKS = 1");
        exec(db, "CREATE TABLE transactions (transaction_id int unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL,"
                + " transaction_date timestamp NULL DEFAULT CURRENT_TIMESTAMP, buyback_used tinyint(1) DEFAULT '0',"
                + " PRIMARY KEY (transaction_id)) ENGINE=InnoDB");
        exec(db, "CREATE TABLE transaction_items (transaction_item_id int unsigned NOT NULL AUTO_INCREMENT,"
                + " transaction_id int unsigned NOT NULL, type tinyint unsigned NOT NULL, itemid int NOT NULL,"
                + " inventorytype int NOT NULL, position int NOT NULL, quantity int NOT NULL, owner tinytext NOT NULL,"
                + " petid int NOT NULL DEFAULT '-1', flag int NOT NULL, expiration bigint NOT NULL DEFAULT '-1',"
                + " giftFrom varchar(26) NOT NULL, PRIMARY KEY (transaction_item_id)) ENGINE=InnoDB");
        exec(db, "CREATE TABLE transaction_equips (transaction_equip_id int unsigned NOT NULL AUTO_INCREMENT,"
                + " transaction_item_id int unsigned NOT NULL, upgradeslots int NOT NULL DEFAULT 0, level int NOT NULL DEFAULT 0,"
                + " str int NOT NULL DEFAULT 0, dex int NOT NULL DEFAULT 0, `int` int NOT NULL DEFAULT 0, luk int NOT NULL DEFAULT 0,"
                + " hp int NOT NULL DEFAULT 0, mp int NOT NULL DEFAULT 0, watk int NOT NULL DEFAULT 0, matk int NOT NULL DEFAULT 0,"
                + " wdef int NOT NULL DEFAULT 0, mdef int NOT NULL DEFAULT 0, acc int NOT NULL DEFAULT 0, avoid int NOT NULL DEFAULT 0,"
                + " hands int NOT NULL DEFAULT 0, speed int NOT NULL DEFAULT 0, jump int NOT NULL DEFAULT 0, locked int NOT NULL DEFAULT 0,"
                + " vicious int unsigned NOT NULL DEFAULT 0, itemlevel int NOT NULL DEFAULT 1, itemexp int unsigned NOT NULL DEFAULT 0,"
                + " ringid int NOT NULL DEFAULT -1, PRIMARY KEY (transaction_equip_id)) ENGINE=InnoDB");
        Path journal = Path.of("database/migrations/066-operation-journal.sql");
        if (Files.exists(journal)) { // absent on the base: the 1.6.4 code never touches it
            String noComments = Files.readString(journal).lines().filter(l -> !l.trim().startsWith("--"))
                    .reduce("", (x, y) -> x + "\n" + y);
            for (String sql : noComments.split(";")) {
                String s = sql.trim();
                if (!s.isEmpty()) {
                    exec(db, s);
                }
            }
        }
    }

    private static int seed(Connection db) throws SQLException {
        exec(db, "INSERT INTO transactions (user_id, buyback_used) VALUES (" + USER + ", 0)");
        int tx = intOf(db, "SELECT MAX(transaction_id) FROM transactions");
        for (int itemId : List.of(4000000, 4000001)) {
            exec(db, "INSERT INTO transaction_items (transaction_id, type, itemid, inventorytype, position, quantity, owner,"
                    + " flag, giftFrom) VALUES (" + tx + ", " + InventoryType.ETC.getType() + ", " + itemId + ", " + InventoryType.ETC.getType()
                    + ", 1, 1, '', 0, '')");
        }
        return tx;
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static int intOf(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
