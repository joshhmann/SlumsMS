package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 174: the two one-time snapshot reads - {@link BotRoster#all()} under {@code LOAD_LOCK} and
 * {@link BotStore#roster()} under {@code ROSTER_LOCK} - must not borrow from the connection pool. Every spawning bot
 * queues on those monitors, and on Java 21 a virtual thread blocked on a monitor pins its carrier, so a borrow that
 * had to wait for a pooled connection there would hold the bots for up to the pool's connection timeout while the
 * threads that could free a connection were pinned.
 *
 * <p>The pool these tests hand the reads is jammed: every borrow is counted and takes a second before failing. A read
 * that still borrowed would be slow and loud; a read on its own connection returns at once and leaves the count at
 * zero. The second pair of tests pins the other half of the behaviour - a read that cannot reach the database still
 * fails as an {@link IllegalStateException} with the query's exception as its cause, never as a silent empty map.
 */
class BotSnapshotReadTest {

    /** How long a jammed borrow waits before it gives up: long enough that a blocking read is unmistakable. */
    private static final long JAM_MS = 1_000;

    private static final int BOT_ID = BotStore.FIRST_BOT_ID;

    /** A pool that is jammed: every borrow is counted and waits, as a borrower on a saturated pool does. */
    private static final class JammedPool implements BotStore.ConnectionSource {
        final AtomicInteger borrows = new AtomicInteger();

        @Override
        public Connection get() throws SQLException {
            borrows.incrementAndGet();
            try {
                Thread.sleep(JAM_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new SQLException("the test pool is jammed");
        }
    }

    /** A source whose read fails, as an unreachable database does. */
    private static final class BrokenSource implements BotStore.ConnectionSource {
        @Override
        public Connection get() throws SQLException {
            throw new SQLException("the database is unreachable");
        }
    }

    @AfterEach
    void restoreRealSources() {
        BotRoster.useConnectionSourceForTest(null);
        BotStore.useConnectionSourceForTest(null);
        BotStore.forgetRosterForTest();
    }

    @Test
    void theRosterSnapshotNeverBorrowsFromThePool() throws Exception {
        JammedPool pool = new JammedPool();
        // The writes stay on the jammed pool; only the one-time read is pointed elsewhere. A read that went through
        // the store's connection source would take a jammed borrow and throw.
        BotRoster.useConnectionSourceForTest(pool);
        BotRoster.useSnapshotSourceForTest(rosterRows(
                List.of(rosterRow(BOT_ID, "Steady"), rosterRow(BOT_ID + 1, "Curious"))));

        long start = System.nanoTime();
        BotRoster.Member first = BotRoster.member(BOT_ID);
        BotRoster.Member second = BotRoster.member(BOT_ID + 1);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertAll("the roster read comes from its own connection, not from the pool it is not gated to",
                () -> assertNotNull(first, "the row the snapshot returned is in the map"),
                () -> assertEquals("Steady", first.personality()),
                () -> assertNotNull(second, "every row of the snapshot is read"),
                () -> assertEquals(0, pool.borrows.get(), "no pool connection may be borrowed for the read"),
                () -> assertTrue(elapsedMs < JAM_MS,
                        "the read must not wait on a jammed pool: took " + elapsedMs + " ms"));
    }

    @Test
    void theStoreSnapshotNeverBorrowsFromThePool() throws Exception {
        JammedPool pool = new JammedPool();
        BotStore.useConnectionSourceForTest(pool);
        // The BotId column holds the slot, not the character id (Persisted.botId is the offset from BOT_ID_BASE).
        BotStore.useSnapshotSourceForTest(storeRows(List.of(storeRow(BOT_ID - BotStore.BOT_ID_BASE, "Stored"))));
        BotStore.forgetRosterForTest();

        long start = System.nanoTime();
        BotStore.Persisted row = BotStore.persistedFor(BOT_ID);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertAll("the store's cold read comes from its own connection, not from the pool",
                () -> assertNotNull(row, "the stored bot the snapshot returned is found"),
                () -> assertEquals(BOT_ID - BotStore.BOT_ID_BASE, row.botId),
                () -> assertEquals("Stored", row.name),
                () -> assertEquals(0, pool.borrows.get(), "no pool connection may be borrowed for the read"),
                () -> assertTrue(elapsedMs < JAM_MS,
                        "the read must not wait on a jammed pool: took " + elapsedMs + " ms"));
    }

    @Test
    void aRosterReadThatCannotReachTheDatabaseStillFailsLoudly() {
        BotRoster.useSnapshotSourceForTest(new BrokenSource());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> BotRoster.member(BOT_ID));

        assertAll("the roster read fails the same way it did from the pool: loudly, with the query's exception",
                () -> assertEquals("Could not read the bot roster", thrown.getMessage()),
                () -> assertInstanceOf(SQLException.class, thrown.getCause()),
                () -> assertEquals("the database is unreachable", thrown.getCause().getMessage()));
    }

    @Test
    void aStoreReadThatCannotReachTheDatabaseStillFailsLoudly() {
        BotStore.useSnapshotSourceForTest(new BrokenSource());
        BotStore.forgetRosterForTest();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> BotStore.persistedFor(BOT_ID));

        assertAll("the store's cold read fails the same way it did from the pool: loudly, with the query's exception",
                () -> assertEquals("Could not read the bot store", thrown.getMessage()),
                () -> assertInstanceOf(SQLException.class, thrown.getCause()),
                () -> assertEquals("the database is unreachable", thrown.getCause().getMessage()));
    }

    /** A source whose roster query returns these rows, as the pool's would. */
    private static BotStore.ConnectionSource rosterRows(List<Map<String, Object>> rows) throws SQLException {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rs = rows(rows);
        when(select.executeQuery()).thenReturn(rs);
        when(con.prepareStatement(eq(BotRoster.SELECT_ALL))).thenReturn(select);
        return () -> con;
    }

    /** A source whose store query returns these rows, as the pool's would. */
    private static BotStore.ConnectionSource storeRows(List<Map<String, Object>> rows) throws SQLException {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rs = rows(rows);
        when(select.executeQuery()).thenReturn(rs);
        when(con.prepareStatement(eq(BotStore.SELECT_ROSTER))).thenReturn(select);
        return () -> con;
    }

    /** A result set over maps of column name to value: reads come back as the type the column holds. */
    @SuppressWarnings("unchecked")
    private static ResultSet rows(List<Map<String, Object>> rows) throws SQLException {
        Deque<Map<String, Object>> pending = new ArrayDeque<>(rows);
        Map<String, Object>[] current = new Map[1];
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenAnswer(call -> (current[0] = pending.poll()) != null);
        when(rs.getInt(anyString())).thenAnswer(call -> ((Number) current[0].get(call.getArgument(0))).intValue());
        when(rs.getLong(anyString())).thenAnswer(call -> ((Number) current[0].get(call.getArgument(0))).longValue());
        when(rs.getString(anyString())).thenAnswer(call -> (String) current[0].get(call.getArgument(0)));
        return rs;
    }

    /** One {@code botroster} row. */
    private static Map<String, Object> rosterRow(int botId, String personality) {
        Map<String, Object> row = new HashMap<>();
        row.put("BotId", botId);
        row.put("Status", "ACTIVE");
        row.put("Personality", personality);
        row.put("Persona", null);
        row.put("PlayWindowStart", 0);
        row.put("PlayWindowLen", 0);
        row.put("SessionMinutes", 0);
        row.put("HomeMapId", 0);
        row.put("Activity", "");
        row.put("ActivityState", null);
        row.put("LastLoginAt", 0L);
        row.put("LastLogoutAt", 0L);
        row.put("EnrolledAt", 0L);
        return row;
    }

    /**
     * One {@code botstore} row without the migration 005/007 columns: the result set has no metadata, so the read
     * takes the "no real stats, no stash" path and reads only the twenty columns the table always had.
     */
    private static Map<String, Object> storeRow(int botId, String name) {
        Map<String, Object> row = new HashMap<>();
        row.put("BotId", botId);
        row.put("Name", name);
        row.put("Tier", BotTier.B.name());
        row.put("Level", 57);
        row.put("Exp", 4_242L);
        row.put("Meso", 910_000);
        row.put("Job", 420);
        row.put("Skin", 3);
        row.put("Gender", 1);
        row.put("Hair", 30_040);
        row.put("Face", 20_010);
        row.put("MapId", 100_000_002);
        row.put("PosX", 512);
        row.put("PosY", 640);
        row.put("EquipSlots", 97);
        row.put("UseSlots", 98);
        row.put("SetupSlots", 99);
        row.put("EtcSlots", 100);
        row.put("CreatedAt", 1_700_000_000_000L);
        row.put("SavedAt", 1_700_000_001_000L);
        return row;
    }
}
