package database;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import server.transactions.JdbcJournalStore;
import server.transactions.OperationJournal;
import tools.DatabaseConnection;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.7.0 P0-2 against real SQL: the merchant journal methods of {@link JdbcJournalStore}, and a hired merchant's close
 * and a purchase from it through their public entry points ({@code forceClose}, {@code buy}). Skipped unless
 * {@code -Dslums.family.it.url} names a scratch MySQL schema (never a game database); it drops and rebuilds the few
 * tables it needs. Only the character save is stubbed: it runs the writes the merchant code carries on a real
 * connection and commits them (or fails), like {@code Character.saveCharToDB(true, extra)} would.
 */
class MerchantJournalIT {

    static final String URL = System.getProperty("slums.family.it.url");
    static final int OWNER = 77, BUYER = 5;

    @Test
    void theJournalStoreStartsRollsBackAndReconcilesMerchantRowsOnRealSql() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            schema(db);
        }
        try (MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class)) {
            dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> DriverManager.getConnection(URL));
            JdbcJournalStore store = new JdbcJournalStore();

            long buy = store.startRow(OperationJournal.MERCHANT_BUY, BUYER, OWNER, "{}");
            long close = store.startRow(OperationJournal.MERCHANT_CLOSE, OWNER, OWNER, "{}");
            assertEquals(2, store.openRows().size());
            assertEquals(OperationJournal.MERCHANT_BUY, store.openRows().get(0).kind());

            store.rollBackRow(buy, OperationJournal.MERCHANT_BUY, BUYER, "test");
            try (Connection db = DriverManager.getConnection(URL)) {
                assertEquals("ROLLED_BACK", text(db, "SELECT state FROM operation_journal WHERE id = " + buy));
                assertEquals(1, intOf(db, "SELECT HasMerchant FROM characters WHERE id = " + OWNER), "a buy rollback leaves the flag");
            }
            assertThrows(SQLException.class, () -> store.rollBackRow(buy, OperationJournal.MERCHANT_BUY, BUYER, "again"),
                    "a row that is no longer STARTED cannot be moved again");

            OperationJournal.reconcile(store);   // boot: the dead close is rolled back and its flag cleared
            try (Connection db = DriverManager.getConnection(URL)) {
                assertEquals(0, intOf(db, "SELECT COUNT(*) FROM operation_journal WHERE state = 'STARTED'"));
                assertEquals("ROLLED_BACK", text(db, "SELECT state FROM operation_journal WHERE id = " + close));
                assertEquals(0, intOf(db, "SELECT HasMerchant FROM characters WHERE id = " + OWNER));
            }
            assertTrue(store.openRows().isEmpty());
        }
    }

    @Test
    void aPurchaseCommitsStockOwnerMesosAndJournalTogetherAndAFailedSaveChangesNothing() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            schema(db);
        }
        AtomicInteger buyerMesos = new AtomicInteger(10_000);
        AtomicBoolean saveWorks = new AtomicBoolean(false);
        try (MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class);
             MockedStatic<InventoryManipulator> inv = mockStatic(InventoryManipulator.class)) {
            dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> DriverManager.getConnection(URL));
            inv.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), any())).thenReturn(true);
            inv.when(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), anyBoolean())).thenReturn(true);

            HiredMerchant shop = new HiredMerchant(ownerMock(), "it", 5030000);
            shop.addItem(new PlayerShopItem(new Item(4000000, (short) 0, (short) 5), (short) 3, 100));
            shop.setOpen(true);
            Character buyer = buyerMock(buyerMesos, saveWorks);

            shop.buy(buyer.getClient(), 0, (short) 1);   // the save fails: refused and undone
            assertEquals(10_000, buyerMesos.get());
            assertEquals(3, shop.getItems().get(0).getBundles());
            try (Connection db = DriverManager.getConnection(URL)) {
                assertEquals(0, intOf(db, "SELECT MerchantMesos FROM characters WHERE id = " + OWNER));
                assertEquals(0, intOf(db, "SELECT COUNT(*) FROM inventorymerchant"));
                assertEquals("ROLLED_BACK", text(db, "SELECT state FROM operation_journal ORDER BY id DESC LIMIT 1"));
                assertEquals(0, intOf(db, "SELECT COUNT(*) FROM operation_journal WHERE state = 'STARTED'"));
            }

            saveWorks.set(true);
            shop.buy(buyer.getClient(), 0, (short) 1);   // the retry sells once
            assertEquals(9_900, buyerMesos.get());
            assertEquals(2, shop.getItems().get(0).getBundles());
            try (Connection db = DriverManager.getConnection(URL)) {
                assertTrue(intOf(db, "SELECT MerchantMesos FROM characters WHERE id = " + OWNER) > 0, "owner credited");
                assertEquals(2, intOf(db, "SELECT bundles FROM inventorymerchant WHERE characterid = " + OWNER));
                assertEquals(1, intOf(db, "SELECT COUNT(*) FROM fredstorage WHERE cid = " + OWNER));
                assertEquals("APPLIED", text(db, "SELECT state FROM operation_journal ORDER BY id DESC LIMIT 1"));
                assertEquals(0, intOf(db, "SELECT COUNT(*) FROM operation_journal WHERE state = 'STARTED'"));
            }
        }
    }

    @Test
    void aForceCloseStoresTheGoodsWithFredrickAndClearsTheFlagInOneCommit() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            schema(db);
        }
        try (MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class)) {
            dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> DriverManager.getConnection(URL));
            HiredMerchant shop = new HiredMerchant(ownerMock(), "it", 5030000);
            shop.addItem(new PlayerShopItem(new Item(4000000, (short) 0, (short) 5), (short) 2, 100));
            shop.addItem(new PlayerShopItem(new Item(4000001, (short) 0, (short) 1), (short) 1, 250));
            shop.setOpen(true);

            shop.forceClose();

            try (Connection db = DriverManager.getConnection(URL)) {
                assertEquals(2, intOf(db, "SELECT COUNT(*) FROM inventorymerchant WHERE characterid = " + OWNER),
                        "both lines are with Fredrick");
                assertEquals(2, intOf(db, "SELECT COUNT(*) FROM inventoryitems WHERE type = 6 AND characterid = " + OWNER));
                assertEquals(1, intOf(db, "SELECT COUNT(*) FROM fredstorage WHERE cid = " + OWNER));
                assertEquals(0, intOf(db, "SELECT HasMerchant FROM characters WHERE id = " + OWNER));
            }
            shop.forceClose();   // a second close must not wipe or duplicate anything
            try (Connection db = DriverManager.getConnection(URL)) {
                assertEquals(2, intOf(db, "SELECT COUNT(*) FROM inventorymerchant WHERE characterid = " + OWNER));
            }
        }
    }

    private static Character ownerMock() {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getClient()).thenReturn(mock(Client.class));
        when(owner.getId()).thenReturn(OWNER);
        return owner;
    }

    private static Character buyerMock(AtomicInteger mesos, AtomicBoolean saveWorks) throws SQLException {
        Character b = mock(Character.class);
        Client c = mock(Client.class);
        when(b.getId()).thenReturn(BUYER);
        when(b.getName()).thenReturn("Buyer");
        when(b.getClient()).thenReturn(c);
        when(c.getPlayer()).thenReturn(b);
        when(b.getMeso()).thenAnswer(a -> mesos.get());
        doAnswer(a -> mesos.addAndGet(a.getArgument(0))).when(b).gainMeso(anyInt(), anyBoolean());
        when(b.saveCharToDB(anyBoolean(), any(Character.SaveExtra.class))).thenAnswer(a -> {
            if (!saveWorks.get()) {
                return false;
            }
            Character.SaveExtra extra = a.getArgument(1);
            try (Connection con = DriverManager.getConnection(URL)) {
                con.setAutoCommit(false);
                try {
                    extra.run(con);
                    con.commit();
                    return true;
                } catch (SQLException e) {
                    con.rollback();
                    return false;
                }
            }
        });
        return b;
    }

    private static void schema(Connection db) throws Exception {
        exec(db, "SET FOREIGN_KEY_CHECKS = 0");
        exec(db, "DROP TABLE IF EXISTS operation_journal, mapleroot_integration_migrations, characters, inventoryitems,"
                + " inventoryequipment, inventorymerchant, fredstorage");
        exec(db, "SET FOREIGN_KEY_CHECKS = 1");
        exec(db, "CREATE TABLE characters (id int NOT NULL PRIMARY KEY, HasMerchant tinyint(1) NOT NULL DEFAULT 0,"
                + " MerchantMesos int NOT NULL DEFAULT 0) ENGINE=InnoDB");
        exec(db, "CREATE TABLE inventoryitems (inventoryitemid int NOT NULL AUTO_INCREMENT PRIMARY KEY, type tinyint NOT NULL,"
                + " characterid int NULL, accountid int NULL, itemid int NOT NULL, inventorytype int NOT NULL,"
                + " position int NOT NULL, quantity int NOT NULL, owner tinytext NOT NULL, petid int NOT NULL DEFAULT -1,"
                + " flag int NOT NULL, expiration bigint NOT NULL DEFAULT -1, giftFrom varchar(26) NOT NULL) ENGINE=InnoDB");
        exec(db, "CREATE TABLE inventoryequipment (inventoryequipmentid int NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + " inventoryitemid int NOT NULL) ENGINE=InnoDB");
        exec(db, "CREATE TABLE inventorymerchant (inventorymerchantid int NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + " inventoryitemid int NOT NULL, characterid int NULL, bundles int NOT NULL) ENGINE=InnoDB");
        exec(db, "CREATE TABLE fredstorage (id int NOT NULL AUTO_INCREMENT PRIMARY KEY, cid int NOT NULL,"
                + " daynotes int NOT NULL, timestamp timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        Path journal = Path.of("database/migrations/066-operation-journal.sql");
        String noComments = Files.readString(journal).lines().filter(l -> !l.trim().startsWith("--"))
                .reduce("", (x, y) -> x + "\n" + y);
        for (String sql : noComments.split(";")) {
            String s = sql.trim();
            if (!s.isEmpty()) {
                exec(db, s);
            }
        }
        exec(db, "INSERT INTO characters (id, HasMerchant, MerchantMesos) VALUES (" + OWNER + ", 1, 0), (" + BUYER + ", 0, 0)");
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

    private static String text(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
