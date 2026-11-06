package server.transactions;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MerchantFakeDb;
import tools.DatabaseConnection;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/** P0-2: what boot reconcile does with the merchant kinds, and the SQL of the store behind it. */
class MerchantReconcileTest {

    /** Records which rows were rolled back; rows listed in {@code failing} refuse. */
    static class Store implements JournalStore {
        final List<OpenRow> open = new ArrayList<>();
        final List<Long> rolledBack = new ArrayList<>();
        final List<Long> failing = new ArrayList<>();

        @Override public long claimBuyback(int p, int t, String d) { throw new UnsupportedOperationException(); }

        @Override public void releaseBuyback(long id, int t, String note) { rolledBack.add(id); }

        @Override public long startRow(String k, int a, long r, String d) { throw new UnsupportedOperationException(); }

        @Override
        public void rollBackRow(long id, String kind, int actor, String note) throws SQLException {
            if (failing.contains(id)) throw new SQLException("db down");
            rolledBack.add(id);
        }

        @Override public List<OpenRow> openRows() { return open; }
    }

    @Test
    void everyStartedMerchantRowIsRolledBackAndAnUnknownKindIsLeftAlone() {
        Store s = new Store();
        s.open.add(new JournalStore.OpenRow(1, OperationJournal.MERCHANT_CLOSE, 7, 7));
        s.open.add(new JournalStore.OpenRow(2, OperationJournal.MERCHANT_BUY, 8, 7));
        s.open.add(new JournalStore.OpenRow(3, "SOMETHING_ELSE", 9, 9));
        s.open.add(new JournalStore.OpenRow(4, OperationJournal.BUYBACK, 10, 11));
        s.open.add(new JournalStore.OpenRow(5, OperationJournal.FREDRICK_RETRIEVE, 12, 12));
        s.open.add(new JournalStore.OpenRow(6, OperationJournal.MERCHANT_TAKEBACK, 7, 7));

        OperationJournal.reconcile(s);

        assertEquals(List.of(1L, 2L, 4L, 5L, 6L), s.rolledBack);
        assertFalse(s.rolledBack.contains(3L), "an unknown kind stays untouched");
    }

    @Test
    void aRowThatCannotBeSettledDoesNotStopTheOthers() {
        Store s = new Store();
        s.open.add(new JournalStore.OpenRow(1, OperationJournal.MERCHANT_CLOSE, 7, 7));
        s.open.add(new JournalStore.OpenRow(2, OperationJournal.MERCHANT_BUY, 8, 7));
        s.failing.add(1L);

        OperationJournal.reconcile(s);

        assertEquals(List.of(2L), s.rolledBack);
    }

    @Test
    void theJdbcStoreStartsARowInOneCommitAndRollsAMerchantCloseBackWithItsFlag() throws Exception {
        MerchantFakeDb db = new MerchantFakeDb();
        try (MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class)) {
            dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> db.open());
            JdbcJournalStore store = new JdbcJournalStore();

            long id = store.startRow(OperationJournal.MERCHANT_CLOSE, 7, 7, "{}");
            assertEquals(1, id);
            assertTrue(db.anyCommitted("INSERT INTO operation_journal"));

            store.rollBackRow(id, OperationJournal.MERCHANT_CLOSE, 7, "boot reconcile");
            MerchantFakeDb.Conn tx = db.conns.get(1);
            assertEquals(1, tx.commits);
            assertTrue(tx.committedAny("HasMerchant"), "the flag clears with the row");
            assertTrue(tx.committedAny("UPDATE operation_journal"));
        }
    }

    @Test
    void aMerchantBuyRollbackTouchesNoFlagAndAFailedMarkRollsEverythingBack() throws Exception {
        MerchantFakeDb db = new MerchantFakeDb();
        try (MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class)) {
            dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> db.open());
            JdbcJournalStore store = new JdbcJournalStore();

            store.rollBackRow(3, OperationJournal.MERCHANT_BUY, 8, "boot reconcile");
            assertFalse(db.anyRan("HasMerchant"));

            db.failOn = "UPDATE operation_journal";
            assertThrows(SQLException.class, () -> store.rollBackRow(4, OperationJournal.MERCHANT_CLOSE, 7, "x"));
            MerchantFakeDb.Conn failed = db.conns.get(1);
            assertEquals(0, failed.commits);
            assertEquals(1, failed.rollbacks);
            assertFalse(failed.committedAny("HasMerchant"), "the flag change rolled back with the failed mark");
        }
    }
}
