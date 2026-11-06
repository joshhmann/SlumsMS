package server.maps;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.transactions.JournalStore;
import server.transactions.OperationJournal;
import tools.DatabaseConnection;

import java.awt.Point;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 1.7.0 P0-2: a hired merchant's close and a purchase from it are all-or-nothing (RFC 2.9). No MySQL: the database is
 * the recording {@link MerchantFakeDb} (writes count as durable only once their transaction commits) and the journal
 * table is {@link FakeJournal}; the character save is a stub that runs the carried writes on a fake connection and
 * commits, fails, or "crashes".
 */
class MerchantJournalTest {

    static final int OWNER = 77, BUYER = 5;

    /** The journal table as committed: id, kind, state. */
    static class FakeJournal implements JournalStore {
        final List<String[]> rows = new ArrayList<>();
        boolean failStart, failRollBack;
        int rollBacks;
        final List<String> rollBackKinds = new ArrayList<>();

        @Override
        public long claimBuyback(int playerId, int transactionId, String deltasJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseBuyback(long journalId, int transactionId, String note) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long startRow(String kind, int actorId, long refId, String deltasJson) throws SQLException {
            if (failStart) throw new SQLException("db down");
            rows.add(new String[]{String.valueOf(rows.size() + 1), kind, "STARTED"});
            return rows.size();
        }

        @Override
        public void rollBackRow(long id, String kind, int actorId, String note) throws SQLException {
            if (failRollBack) throw new SQLException("db down");
            String[] r = rows.get((int) id - 1);
            if (!"STARTED".equals(r[2])) throw new SQLException("not STARTED");
            r[2] = "ROLLED_BACK";
            rollBacks++;
            rollBackKinds.add(kind);
        }

        @Override
        public List<OpenRow> openRows() {
            List<OpenRow> out = new ArrayList<>();
            for (String[] r : rows) if ("STARTED".equals(r[2])) out.add(new OpenRow(Long.parseLong(r[0]), r[1], OWNER, OWNER));
            return out;
        }

        /** What the carried "UPDATE operation_journal SET state = APPLIED" does once its transaction commits. */
        void applyAll() {
            for (String[] r : rows) if ("STARTED".equals(r[2])) r[2] = "APPLIED";
        }

        String state(int i) { return rows.get(i)[2]; }

        long started() { return rows.stream().filter(r -> "STARTED".equals(r[2])).count(); }
    }

    static class Crash extends Error {
    }

    enum Save { COMMIT, FAIL, CRASH }

    MerchantFakeDb db;
    FakeJournal journal;
    MockedStatic<DatabaseConnection> dbc;
    MockedStatic<InventoryManipulator> inv;
    MockedStatic<Inventory> invStatic;
    Save ownerSave = Save.COMMIT, buyerSave = Save.COMMIT;
    AtomicInteger buyerMesos;

    @BeforeEach
    void setUp() {
        db = new MerchantFakeDb();
        journal = new FakeJournal();
        buyerMesos = new AtomicInteger(10_000);
        dbc = mockStatic(DatabaseConnection.class);
        dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> db.open());
        inv = mockStatic(InventoryManipulator.class);
        inv.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), any())).thenReturn(true);
        inv.when(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), anyBoolean())).thenReturn(true);
        inv.when(() -> InventoryManipulator.addById(any(Character.class), anyInt(), anyShort(), any(), anyInt(), anyShort(), anyLong())).thenReturn(true);
        invStatic = mockStatic(Inventory.class);
        invStatic.when(() -> Inventory.checkSpotsAndOwnership(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        dbc.close();
        inv.close();
        invStatic.close();
    }

    private boolean saveWith(Save mode, Character.SaveExtra extra) throws SQLException {
        if (mode == Save.CRASH) throw new Crash();
        if (mode == Save.FAIL) return false;
        Connection con = db.open();
        con.setAutoCommit(false);
        try {
            extra.run(con);
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            return false;
        }
        journal.applyAll();
        return true;
    }

    private Character buyer() throws SQLException {
        Character b = mock(Character.class);
        Client c = mock(Client.class);
        when(b.getId()).thenReturn(BUYER);
        when(b.getName()).thenReturn("Buyer");
        when(b.getClient()).thenReturn(c);
        when(c.getPlayer()).thenReturn(b);
        when(b.getMeso()).thenAnswer(a -> buyerMesos.get());
        doAnswer(a -> buyerMesos.addAndGet(a.getArgument(0))).when(b).gainMeso(anyInt(), anyBoolean());
        when(b.saveCharToDB(anyBoolean(), any(Character.SaveExtra.class)))
                .thenAnswer(a -> saveWith(buyerSave, a.getArgument(1)));
        return b;
    }

    private Character owner(HiredMerchant[] holder) throws SQLException {
        Character o = mock(Character.class);
        Client c = mock(Client.class);
        World w = mock(World.class);
        when(w.getPlayerStorage()).thenReturn(mock(PlayerStorage.class));
        when(o.getId()).thenReturn(OWNER);
        when(o.getPosition()).thenReturn(new Point(0, 0));
        when(o.getClient()).thenReturn(c);
        when(c.getPlayer()).thenReturn(o);
        when(c.getWorldServer()).thenReturn(w);
        when(c.getChannelServer()).thenReturn(mock(net.server.channel.Channel.class));
        when(o.getHiredMerchant()).thenAnswer(a -> holder[0]);
        when(o.saveCharToDB(anyBoolean(), any(Character.SaveExtra.class)))
                .thenAnswer(a -> saveWith(ownerSave, a.getArgument(1)));
        return o;
    }

    private HiredMerchant shop(Character owner) {
        HiredMerchant shop = new HiredMerchant(owner, "test", 5030000);
        shop.setJournalStore(journal);
        shop.addItem(new PlayerShopItem(new Item(4000000, (short) 0, (short) 5), (short) 3, 100));
        shop.setOpen(true);
        return shop;
    }

    private static int bundles(HiredMerchant shop) {
        return shop.getItems().get(0).getBundles();
    }

    private long durableFlagWrites() {
        return db.conns.stream().flatMap(c -> c.durable.stream()).filter(q -> q.contains("HasMerchant")).count();
    }

    // ---- buy ----

    @Test
    void aSaleCommitsBuyerStockAndOwnerMesosInOneTransactionWithTheJournalApplied() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        Character b = buyer();

        shop.buy(b.getClient(), 0, (short) 1);

        assertEquals(10_000 - 100, buyerMesos.get());
        assertEquals(2, bundles(shop));
        assertEquals("APPLIED", journal.state(0));
        assertEquals(0, journal.started());
        MerchantFakeDb.Conn saleTx = db.conns.stream().filter(c -> c.committedAny("MerchantMesos")).findFirst().orElseThrow();
        assertTrue(saleTx.committedAny("INSERT INTO `inventoryitems`"), "stock and owner mesos commit together");
        assertTrue(saleTx.committedAny("operation_journal"), "and with the APPLIED mark");
        assertEquals(1, saleTx.commits);
    }

    @Test
    void aFailedSaveRefundsTheBuyerAndPutsTheItemBackInTheShop() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        HiredMerchant shop = shop(owner(h));
        Character b = buyer();
        buyerSave = Save.FAIL;

        shop.buy(b.getClient(), 0, (short) 1);

        assertEquals(10_000, buyerMesos.get(), "mesos refunded");
        assertEquals(3, bundles(shop), "the bundle is back on sale");
        assertTrue(shop.getItems().get(0).isExist());
        inv.verify(() -> InventoryManipulator.removeById(any(Client.class), any(), eq(4000000), eq(5), eq(false), eq(false)));
        assertEquals("ROLLED_BACK", journal.state(0));
        assertFalse(db.anyCommitted("MerchantMesos"));
        assertFalse(db.anyCommitted("INSERT INTO `inventoryitems`"));
    }

    @Test
    void aSqlErrorInTheCarriedWritesRollsTheWholeSaleBack() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        HiredMerchant shop = shop(owner(h));
        Character b = buyer();
        db.failOn = "MerchantMesos";

        shop.buy(b.getClient(), 0, (short) 1);

        assertEquals(10_000, buyerMesos.get());
        assertEquals(3, bundles(shop));
        assertEquals("ROLLED_BACK", journal.state(0));
        assertFalse(db.anyCommitted("INSERT INTO `inventoryitems`"), "the stock rows rolled back with the failed credit");
        assertTrue(db.conns.stream().anyMatch(c -> c.rollbacks == 1));
    }

    @Test
    void ifTheJournalRowCannotBeWrittenTheSaleIsRefusedBeforeAnythingChanges() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        HiredMerchant shop = shop(owner(h));
        Character b = buyer();
        journal.failStart = true;

        shop.buy(b.getClient(), 0, (short) 1);

        assertEquals(10_000, buyerMesos.get());
        assertEquals(3, bundles(shop));
        verify(b, never()).saveCharToDB(anyBoolean(), any(Character.SaveExtra.class));
        inv.verify(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), anyBoolean()), never());
    }

    @Test
    void aCrashAfterTheClaimBeforeTheCommitLeavesAStartedRowThatBootRollsBack() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        HiredMerchant shop = shop(owner(h));
        Character b = buyer();
        buyerSave = Save.CRASH;

        assertThrows(Crash.class, () -> shop.buy(b.getClient(), 0, (short) 1));
        assertEquals("STARTED", journal.state(0));
        assertFalse(db.anyCommitted("MerchantMesos"), "nothing reached the database");

        OperationJournal.reconcile(journal);

        assertEquals("ROLLED_BACK", journal.state(0));
        assertEquals(0, journal.started());
        assertEquals(List.of(OperationJournal.MERCHANT_BUY), journal.rollBackKinds);
    }

    @Test
    void aCrashAfterTheCommitIsLeftAloneAndNothingIsAppliedTwice() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        HiredMerchant shop = shop(owner(h));
        Character b = buyer();
        shop.buy(b.getClient(), 0, (short) 1);   // committed, APPLIED; then the process dies

        OperationJournal.reconcile(journal);

        assertEquals("APPLIED", journal.state(0));
        assertEquals(0, journal.rollBacks);
        long creditCommits = db.conns.stream().flatMap(c -> c.durable.stream()).filter(q -> q.contains("MerchantMesos")).count();
        assertEquals(1, creditCommits, "the owner was credited exactly once");
    }

    // ---- close ----

    @Test
    void closingHandsTheGoodsToTheOwnerAndEmptiesFredrickInOneCommit() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        h[0] = shop;

        shop.closeOwnerMerchant(o);

        inv.verify(() -> InventoryManipulator.addById(eq(o), eq(4000000), eq((short) 15), any(), eq(-1), anyShort(), anyLong()));
        assertEquals("APPLIED", journal.state(0));
        assertEquals(OperationJournal.MERCHANT_CLOSE, journal.rows.get(0)[1]);
        assertTrue(shop.getItems().isEmpty());
        MerchantFakeDb.Conn tx = db.conns.stream().filter(c -> c.committedAny("HasMerchant")).findFirst().orElseThrow();
        assertTrue(tx.committedAny("DELETE FROM `inventorymerchant`"), "Fredrick emptied in the same commit as the flag");
        assertTrue(tx.committedAny("operation_journal"));
        assertFalse(tx.committedAny("INSERT INTO `inventoryitems`"), "nothing re-stored: the owner has it");
        assertEquals(1, durableFlagWrites());
        verify(o).setHasMerchant(false);
    }

    @Test
    void ifTheOwnersSaveFailsTheGoodsGoToFredrickNotNowhere() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        h[0] = shop;
        ownerSave = Save.FAIL;

        shop.closeOwnerMerchant(o);

        inv.verify(() -> InventoryManipulator.removeById(any(Client.class), any(), eq(4000000), eq(15), eq(false), eq(false)));
        assertEquals("ROLLED_BACK", journal.state(0));
        MerchantFakeDb.Conn tx = db.conns.stream().filter(c -> c.committedAny("HasMerchant")).findFirst().orElseThrow();
        assertTrue(tx.committedAny("INSERT INTO `inventoryitems`"), "the line is in Fredrick's tables");
        assertTrue(tx.committedAny("INSERT INTO `fredstorage`"));
        assertEquals(1, durableFlagWrites());
        assertTrue(shop.getItems().isEmpty());
    }

    @Test
    void ifEverythingFailsNothingChangesAndTheCloseCanBeRetried() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        h[0] = shop;
        ownerSave = Save.FAIL;
        db.failOn = "INSERT INTO `inventoryitems`";

        shop.closeOwnerMerchant(o);

        assertEquals(1, shop.getItems().size(), "the goods are still in the shop");
        assertEquals(0, durableFlagWrites(), "the flag is untouched");
        verify(o, never()).setHasMerchant(anyBoolean());
        verify(o, atLeastOnce()).dropMessage(eq(1), contains("try again"));

        db.failOn = null;
        ownerSave = Save.COMMIT;
        shop.closeOwnerMerchant(o);   // the retry

        assertEquals("APPLIED", journal.state(journal.rows.size() - 1));
        assertEquals(1, durableFlagWrites(), "the flag cleared exactly once");
        assertTrue(shop.getItems().isEmpty());
    }

    @Test
    void aCrashAfterTheClaimBeforeTheCloseCommitsLeavesFredrickIntactAndBootRollsTheRowBack() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        h[0] = shop;
        ownerSave = Save.CRASH;

        assertThrows(Crash.class, () -> shop.closeOwnerMerchant(o));
        assertEquals("STARTED", journal.state(0));
        assertFalse(db.anyCommitted("DELETE FROM `inventorymerchant`"), "Fredrick's rows were never touched");

        OperationJournal.reconcile(journal);

        assertEquals(0, journal.started());
        assertEquals("ROLLED_BACK", journal.state(0));
        assertEquals(List.of(OperationJournal.MERCHANT_CLOSE), journal.rollBackKinds);
    }

    @Test
    void aCrashAfterTheCloseCommittedIsNotUndone() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        h[0] = shop;
        shop.closeOwnerMerchant(o);   // committed and APPLIED, then the process dies

        OperationJournal.reconcile(journal);

        assertEquals("APPLIED", journal.state(0));
        assertEquals(0, journal.rollBacks);
        assertEquals(1, durableFlagWrites());
    }

    // ---- autosave race (P0-2) ----

    private static final class Sink implements java.util.function.Consumer<String> {
        final List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        @Override public void accept(String l) { lines.add(l); }
    }

    /**
     * The owner's autosave holds row locks on its characters row while a sale tries to credit MerchantMesos on that
     * row: MySQL kills one side with SQLState 40001. Modelled by a deadlock on the credit statement, with a real
     * thread holding the buyer's save monitor first (the sale must wait for it, not interleave).
     */
    @Test
    void aPurchaseHitByADeadlockWithTheOwnersAutosaveIsRefusedAndUndoneThenRetried() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        Character b = buyer();
        Sink sink = new Sink();
        MarketLog.tap = sink;
        try {
            java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
            Thread autosave = new Thread(() -> {
                synchronized (b) {   // saveCharToDB is synchronized on the character
                    holding.countDown();
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                }
            });
            autosave.start();
            assertTrue(holding.await(5, java.util.concurrent.TimeUnit.SECONDS));

            db.failOn = "MerchantMesos";
            db.failState = "40001";
            shop.buy(b.getClient(), 0, (short) 1);
            autosave.join();

            assertEquals(10_000, buyerMesos.get(), "buyer mesos unchanged");
            inv.verify(() -> InventoryManipulator.removeById(any(Client.class), any(), eq(4000000), eq(5), eq(false), eq(false)));
            assertEquals(3, bundles(shop), "shop stock unchanged");
            assertTrue(shop.getItems().get(0).isExist());
            assertFalse(db.anyCommitted("MerchantMesos"), "owner MerchantMesos unchanged in the database");
            assertFalse(db.anyCommitted("INSERT INTO `inventoryitems`"), "no stock rows committed");
            verify(o, never()).mirrorMerchantMesosCredit(anyInt());
            assertEquals("ROLLED_BACK", journal.state(0));
            assertEquals(0, journal.started());
            verify(b, atLeastOnce()).dropMessage(eq(1), contains("not charged"));
            assertEquals(List.of(MarketLog.refusedLine("merchant", "real", "real", "save-failed")), sink.lines);

            db.failOn = null;
            db.failState = null;
            shop.buy(b.getClient(), 0, (short) 1);

            assertEquals(9_900, buyerMesos.get(), "the retry sells exactly once");
            assertEquals(2, bundles(shop));
            assertEquals("APPLIED", journal.state(1));
            long credits = db.conns.stream().flatMap(c -> c.durable.stream()).filter(q -> q.contains("MerchantMesos")).count();
            assertEquals(1, credits);
            assertEquals(MarketLog.saleLine("merchant", "real", "real"), sink.lines.get(1));
        } finally {
            MarketLog.tap = null;
        }
    }

    @Test
    void theSaleLogLinesCarryNoNamesIdsItemsOrMesos() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        HiredMerchant shop = shop(owner(h));
        Character b = buyer();
        Sink sink = new Sink();
        MarketLog.tap = sink;
        try {
            shop.buy(b.getClient(), 0, (short) 1);
            shop.buy(b.getClient(), 99, (short) 1);
        } finally {
            MarketLog.tap = null;
        }
        assertEquals(List.of("[Market] merchant sale committed: buyer=real owner=real",
                "[Market] merchant sale refused: reason=bad-line buyer=real owner=real"), sink.lines);
        for (String l : sink.lines) {
            assertFalse(l.matches(".*\\d.*"), "no digits at all, so no id or meso amount: " + l);
            assertFalse(l.contains("Buyer"));
        }
    }

    // ---- kill the JVM mid-close (P0-2) ----

    /** The real SQL of the store over the fake database, reading its open rows from the fake journal table. */
    private final class BootStore extends server.transactions.JdbcJournalStore {
        @Override
        public List<OpenRow> openRows() {
            return journal.openRows();
        }

        @Override
        public void rollBackRow(long id, String kind, int actorId, String note) throws SQLException {
            super.rollBackRow(id, kind, actorId, note);   // the real SQL: HasMerchant + the journal UPDATE, one commit
            journal.rollBackRow(id, kind, actorId, note); // and the fake table follows
        }
    }

    @Test
    void killingTheJvmMidCloseThenBootLeavesNoStartedRowAndTheGoodsWhereTheyWere() throws Exception {
        HiredMerchant[] h = new HiredMerchant[1];
        Character o = owner(h);
        HiredMerchant shop = shop(o);
        h[0] = shop;
        ownerSave = Save.CRASH;   // STARTED written, goods handed over in memory, the process dies before the commit

        assertThrows(Crash.class, () -> shop.closeOwnerMerchant(o));
        assertEquals(1, journal.started(), "the dead close left its STARTED row");
        assertFalse(db.anyCommitted("DELETE FROM `inventorymerchant`"), "Fredrick's rows were not touched");
        assertFalse(db.anyCommitted("DELETE `inventoryitems`"));
        assertFalse(db.anyCommitted("HasMerchant"));

        // --- boot ---
        db.conns.clear();
        OperationJournal.reconcile(new BootStore());

        assertEquals(0, journal.started(), "no STARTED rows after boot");
        assertEquals("ROLLED_BACK", journal.state(0));
        assertFalse(db.anyCommitted("DELETE FROM `inventorymerchant`"), "the goods are still in the merchant/Fredrick tables");
        assertFalse(db.anyCommitted("INSERT INTO `inventoryitems`"), "and nothing was stored twice");
        assertFalse(db.anyCommitted("DELETE `inventoryitems`"));
        long flagWrites = db.conns.stream().flatMap(c -> c.durable.stream()).filter(q -> q.contains("HasMerchant = 0")).count();
        assertEquals(1, flagWrites, "HasMerchant is cleared once: the merchant died with the JVM, the goods wait with Fredrick");
        assertTrue(db.conns.stream().anyMatch(c -> c.committedAny("HasMerchant") && c.committedAny("UPDATE operation_journal")),
                "flag and journal flip in one commit");
    }
}
