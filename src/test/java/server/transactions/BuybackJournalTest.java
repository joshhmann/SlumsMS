package server.transactions;

import client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P0-1: buyback is all-or-nothing, and boot reconcile settles what a crash left open. No DB: fakes model the commits. */
class BuybackJournalTest {

    static final int TX = 7, PLAYER = 42, PRICE = 1000;

    /** Rows and flags as the database holds them (committed state only). */
    static class FakeDb implements JournalStore {
        boolean flag;
        final List<String[]> rows = new ArrayList<>(); // id, kind, state, note
        boolean failClaim, failRelease;
        int releases;

        @Override
        public long claimBuyback(int playerId, int tx, String deltas) throws SQLException {
            if (failClaim) throw new SQLException("db down");
            if (flag) return -1;
            flag = true;
            rows.add(new String[]{String.valueOf(rows.size() + 1), "BUYBACK", "STARTED", null});
            return rows.size();
        }

        @Override
        public void releaseBuyback(long id, int tx, String note) throws SQLException {
            if (failRelease) throw new SQLException("db down");
            String[] r = rows.get((int) id - 1);
            if (!"STARTED".equals(r[2])) throw new SQLException("not STARTED");
            flag = false;
            r[2] = "ROLLED_BACK";
            r[3] = note;
            releases++;
        }

        @Override
        public long startRow(String kind, int actorId, long refId, String deltasJson) {
            throw new UnsupportedOperationException("buyback test");
        }

        @Override
        public void rollBackRow(long journalId, String kind, int actorId, String note) {
            throw new UnsupportedOperationException("buyback test");
        }

        @Override
        public List<OpenRow> openRows() {
            List<OpenRow> out = new ArrayList<>();
            for (String[] r : rows) if ("STARTED".equals(r[2])) out.add(new OpenRow(Long.parseLong(r[0]), r[1], PLAYER, TX));
            return out;
        }

        String state(int i) { return rows.get(i)[2]; }
    }

    static class Crash extends Error {
    }

    /** The character as the database holds it. */
    static class Persisted {
        int mesos = 5000;
        List<Integer> items = new ArrayList<>();
    }

    static class FakePlayer implements BuybackPlayer {
        final FakeDb db;
        final Persisted disk;
        int mesos;
        final List<Integer> bag;
        int failAddAt = -1, adds;
        boolean failSave, crashInSave;

        FakePlayer(FakeDb db, Persisted disk) {
            this.db = db;
            this.disk = disk;
            this.mesos = disk.mesos;
            this.bag = new ArrayList<>(disk.items);
        }

        public int getMeso() { return mesos; }
        public void gainMeso(int a) { mesos += a; }

        public int addItem(Item item) {
            if (adds++ == failAddAt) throw new IllegalStateException("injected fault");
            bag.add(item.getItemId());
            return item.getQuantity();
        }

        public void undoAdd(Item item, int q) { bag.remove((Integer) item.getItemId()); }

        public boolean save(long id) {
            if (crashInSave) throw new Crash();
            if (failSave) return false;
            // character write and APPLIED mark commit together
            disk.mesos = mesos;
            disk.items = new ArrayList<>(bag);
            db.rows.get((int) id - 1)[2] = "APPLIED";
            return true;
        }

        public Object saveLock() { return this; }
    }

    FakeDb db;
    Persisted disk;

    @BeforeEach
    void setUp() {
        db = new FakeDb();
        disk = new Persisted();
    }

    static List<Item> items() {
        return new ArrayList<>(List.of(new Item(2000000, (short) 0, (short) 3), new Item(2000001, (short) 0, (short) 1),
                new Item(2000002, (short) 0, (short) 2)));
    }

    server.responses.SelectionListStringResult run(FakePlayer p) {
        return TransactionService.grantBuyback(p, db, PLAYER, TX, PRICE, items());
    }

    @Test
    void happyPathFlagsAppliesGrantsAndCharges() {
        FakePlayer p = new FakePlayer(db, disk);
        var res = run(p);
        assertTrue(res.getResult(), res.getMessage());
        assertTrue(db.flag);
        assertEquals("APPLIED", db.state(0));
        assertEquals(List.of(2000000, 2000001, 2000002), disk.items);
        assertEquals(4000, disk.mesos);
    }

    @Test
    void faultAfterFirstItemRestoresEverything() {
        FakePlayer p = new FakePlayer(db, disk);
        p.failAddAt = 1;
        var res = run(p);
        assertFalse(db.flag, "flag back to 0");
        assertEquals("ROLLED_BACK", db.state(0));
        assertTrue(p.bag.isEmpty(), "first item taken back");
        assertEquals(5000, p.mesos);
        assertEquals(5000, disk.mesos);
        assertTrue(res.getMessage().contains("Nothing was charged"), res.getMessage());
        assertTrue(run(new FakePlayer(db, disk)).getMessage().startsWith("Bought back"), "can be bought back later");
    }

    @Test
    void saveReturningFalseRestoresEverything() {
        FakePlayer p = new FakePlayer(db, disk);
        p.failSave = true;
        var res = run(p);
        assertFalse(db.flag);
        assertEquals("ROLLED_BACK", db.state(0));
        assertTrue(p.bag.isEmpty());
        assertEquals(5000, p.mesos);
        assertTrue(disk.items.isEmpty());
        assertTrue(res.getMessage().contains("Nothing was charged"));
    }

    @Test
    void secondBuybackOfSameTransactionIsRefusedAndChangesNothing() {
        FakePlayer first = new FakePlayer(db, disk);
        FakePlayer second = new FakePlayer(db, disk); // loaded before the first finished
        assertTrue(run(first).getMessage().startsWith("Bought back"));
        var res = run(second);
        assertEquals("This transaction has already been bought back.", res.getMessage());
        assertTrue(second.bag.isEmpty());
        assertEquals(5000, second.mesos);
        assertEquals(1, db.rows.size(), "no second journal row");
        assertEquals(3, disk.items.size());
        assertEquals(4000, disk.mesos);
    }

    @Test
    void claimFailureChangesNothing() {
        db.failClaim = true;
        FakePlayer p = new FakePlayer(db, disk);
        var res = run(p);
        assertEquals("Failed to update the transaction. Aborted.", res.getMessage());
        assertTrue(p.bag.isEmpty());
        assertEquals(5000, p.mesos);
        assertFalse(db.flag);
    }

    @Test
    void failedReleaseLeavesStartedRowForBootButMemoryIsUndone() {
        FakePlayer p = new FakePlayer(db, disk);
        p.failSave = true;
        db.failRelease = true;
        run(p);
        assertTrue(p.bag.isEmpty());
        assertEquals(5000, p.mesos);
        assertEquals("STARTED", db.state(0));
        db.failRelease = false;
        OperationJournal.reconcile(db);
        assertFalse(db.flag);
        assertEquals("ROLLED_BACK", db.state(0));
    }

    @Test
    void bootReconcileRollsBackStartedBuybackLeavesAppliedAndUnknownAlone() {
        db.flag = true;
        db.rows.add(new String[]{"1", "BUYBACK", "STARTED", null});
        db.rows.add(new String[]{"2", "BUYBACK", "APPLIED", null});
        db.rows.add(new String[]{"3", "MYSTERY", "STARTED", null});
        OperationJournal.reconcile(db);
        assertFalse(db.flag);
        assertEquals("ROLLED_BACK", db.state(0));
        assertEquals("boot reconcile", db.rows.get(0)[3]);
        assertEquals("APPLIED", db.state(1));
        assertEquals("STARTED", db.state(2));
        assertEquals(1, db.releases);
    }

    @Test
    void crashAfterStartedRowBeforeSave() {
        FakePlayer p = new FakePlayer(db, disk);
        p.crashInSave = true;
        assertThrows(Crash.class, () -> run(p)); // process dies: no undo, no release
        assertTrue(db.flag);
        assertEquals("STARTED", db.state(0));

        OperationJournal.reconcile(db); // next boot
        assertFalse(db.flag);
        assertEquals("ROLLED_BACK", db.state(0));
        assertEquals(5000, disk.mesos, "persisted mesos are the pre-buyback ones");
        assertTrue(disk.items.isEmpty(), "persisted items are the pre-buyback ones");

        FakePlayer after = new FakePlayer(db, disk); // logs in from disk
        assertTrue(run(after).getMessage().startsWith("Bought back"));
        assertEquals("This transaction has already been bought back.", run(new FakePlayer(db, disk)).getMessage());
        assertEquals(3, disk.items.size());
        assertEquals(4000, disk.mesos);
    }

    @Test
    void crashAfterApplied() {
        assertTrue(run(new FakePlayer(db, disk)).getMessage().startsWith("Bought back"));
        // process dies here; next boot
        OperationJournal.reconcile(db);
        assertEquals("APPLIED", db.state(0));
        assertTrue(db.flag);
        FakePlayer after = new FakePlayer(db, disk);
        assertEquals("This transaction has already been bought back.", run(after).getMessage());
        assertEquals(List.of(2000000, 2000001, 2000002), disk.items, "items granted exactly once");
        assertEquals(4000, disk.mesos, "mesos taken exactly once");
        assertEquals(1, db.rows.size());
    }
}
