package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Migration 059 and the JDBC stores' two modes (axis columns present, or an unmigrated database): the SQL, the row
 * mapping against a mocked result set, the "do the columns exist" probe, and the migration scripts.
 */
class AxisColumnsTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "059-bot-relationship-axes";
    static final String[] NEW = {"Familiarity", "Trust", "Affection", "Respect", "Attraction", "Rivalry", "Resentment",
            "Obligation", "Stage", "HasAxes"};

    @AfterEach
    void tearDown() {
        SocialSim.reset();
    }

    @Test
    void theMigrationIsIdempotentLedgeredAndRollsBack() throws Exception {
        String sql = Files.readString(DIR.resolve(ID + ".sql"));
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        for (String table : new String[]{"botbond", "botaffinity"}) {
            assertTrue(sql.contains("ALTER TABLE " + table + " ADD COLUMN"), table);
            assertTrue(sql.contains("TABLE_NAME = '" + table + "' AND COLUMN_NAME = 'HasAxes'"), table + " guarded by information_schema");
            assertTrue(rollback.contains("ALTER TABLE " + table + " DROP COLUMN"), table);
        }
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(rollback.contains("migration_id='" + ID + "'"), "the rollback clears its ledger row");
        for (String col : NEW) {
            assertTrue(sql.contains("ADD COLUMN " + col + " "), "adds " + col);
            assertTrue(rollback.contains("DROP COLUMN " + col), "drops " + col);
            for (String statement : new String[]{BondStore.Jdbc.SELECT, BondStore.Jdbc.UPSERT, AffinityStore.Jdbc.SELECT,
                    AffinityStore.Jdbc.UPSERT}) {
                assertTrue(statement.contains(col), col + " in " + statement);
            }
        }
        assertTrue(sql.contains("DEFAULT 0"), "defaults keep old rows valid");
        for (String col : NEW) {
            assertEquals(2, count(sql, "ADD COLUMN " + col + " "), col + " is added to each of the two tables");
            assertEquals(2, count(rollback, "DROP COLUMN " + col), col + " is dropped from each of the two tables");
        }
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void withoutTheMigrationTheStoresUseTheOlderColumnsOnly() {
        for (String legacy : new String[]{BondStore.Jdbc.SELECT_LEGACY, BondStore.Jdbc.UPSERT_LEGACY,
                AffinityStore.Jdbc.SELECT_LEGACY, AffinityStore.Jdbc.UPSERT_LEGACY}) {
            for (String col : NEW) {
                assertFalse(legacy.contains(col), col + " must not be named when 059 is not applied: " + legacy);
            }
            assertTrue(legacy.contains("Score") && legacy.contains("Kind") && legacy.contains("Hostile")
                    && legacy.contains("UpdatedAt"));
        }
        assertTrue(AffinityStore.Jdbc.SELECT_LEGACY.contains("BotId < " + AffinityStore.DEFAME_MARK),
                "the defame mark row is still never loaded as a tie");
        assertTrue(AffinityStore.Jdbc.SELECT.contains("BotId < " + AffinityStore.DEFAME_MARK));
        // the placeholders match the columns
        assertEquals(count(BondStore.Jdbc.UPSERT, "?"), 16);
        assertEquals(count(AffinityStore.Jdbc.UPSERT, "?"), 16);
        assertEquals(count(BondStore.Jdbc.UPSERT_LEGACY, "?"), 6);
    }

    // ── row mapping ──

    private static ResultSet bondRow(int hasAxes) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("BotId")).thenReturn(1);
        when(rs.getInt("OtherId")).thenReturn(2);
        when(rs.getFloat("Score")).thenReturn(60f);
        when(rs.getInt("Score")).thenReturn(60);
        when(rs.getInt("Kind")).thenReturn(5);
        when(rs.getInt("Hostile")).thenReturn(0);
        when(rs.getLong("UpdatedAt")).thenReturn(123L);
        when(rs.getInt("HasAxes")).thenReturn(hasAxes);
        when(rs.getInt("Stage")).thenReturn(Stage.CLOSE_FRIEND.code());
        float[] axes = {0.9f, 0.7f, 0.8f, 0.4f, 0.1f, 0.2f, 0.05f, -0.3f};
        for (int i = 0; i < NEW.length - 2; i++) {
            when(rs.getFloat(NEW[i])).thenReturn(axes[i]);
        }
        return rs;
    }

    @Test
    void aRowWithAxesLoadsThemAndAStageAndARowWithoutDerivesThem() throws Exception {
        Bond stored = BondStore.Jdbc.read(bondRow(1), true);
        assertEquals(BotStore.BOT_ID_BASE + 1, stored.botId());
        assertEquals(BotStore.BOT_ID_BASE + 2, stored.otherId());
        assertEquals(0.9, stored.axes[Axis.FAM], 1e-6);
        assertEquals(-0.3, stored.axes[Axis.OBL], 1e-6);
        assertEquals(0.8, stored.axes[Axis.AFF], 1e-6);
        assertEquals(Stage.CLOSE_FRIEND, stored.stage, "the stored stage is the hysteresis memory: taken as is");

        Bond old = BondStore.Jdbc.read(bondRow(0), true); // HasAxes = 0: written before the migration
        assertEquals(Axes.legacy(60, 5, 0)[Axis.AFF], old.axes[Axis.AFF], 1e-12);
        assertEquals(Stage.FRIEND, old.stage, "derived from the axes, not from the (default 0) Stage column");

        Bond unmigrated = BondStore.Jdbc.read(bondRow(1), false); // the columns are not there: never asked for
        assertEquals(Axes.legacy(60, 5, 0)[Axis.AFF], unmigrated.axes[Axis.AFF], 1e-12);
        assertEquals(Stage.FRIEND, unmigrated.stage);
    }

    @Test
    void aTieRowMapsTheSameWay() throws Exception {
        ResultSet rs = bondRow(1);
        Tie tie = AffinityStore.Jdbc.read(rs, 42, true);
        assertEquals(42, tie.charId());
        assertEquals(1, tie.botId());
        assertEquals(0.7, tie.axes[Axis.TRU], 1e-6);
        assertEquals(Stage.CLOSE_FRIEND, tie.stage);
        Tie old = AffinityStore.Jdbc.read(bondRow(0), 42, true);
        assertEquals(Stage.FRIEND, old.stage);
        assertTrue(old.axes[Axis.AFF] > 0.9);
    }

    @Test
    void bindingWritesTheAxesInColumnOrderThenTheStageAndTheMarker() throws Exception {
        Bond b = new Bond(BotStore.BOT_ID_BASE + 1, BotStore.BOT_ID_BASE + 2, 33, 4, 1, 99L,
                new double[]{0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, -0.8}, Stage.ESTRANGED);
        PreparedStatement ps = mock(PreparedStatement.class);
        BondStore.Jdbc.bind(ps, b, true);
        InOrder in = inOrder(ps);
        in.verify(ps).setInt(1, 1);
        in.verify(ps).setInt(2, 2);
        in.verify(ps).setFloat(3, 33f);
        in.verify(ps).setLong(6, 99L);
        in.verify(ps).setFloat(7, 0.1f);
        in.verify(ps).setFloat(8, 0.2f);
        in.verify(ps).setFloat(13, 0.7f);
        in.verify(ps).setFloat(14, -0.8f);
        in.verify(ps).setInt(15, Stage.ESTRANGED.code());
        in.verify(ps).setInt(16, 1);

        PreparedStatement legacy = mock(PreparedStatement.class);
        BondStore.Jdbc.bind(legacy, b, false);
        verify(legacy, never()).setFloat(eq(7), anyFloat());
        verify(legacy, never()).setInt(eq(15), anyInt());
        verify(legacy).setLong(6, 99L);

        PreparedStatement tie = mock(PreparedStatement.class);
        AffinityStore.Jdbc.bind(tie, new Tie(3, 42, 20, 2, 0, 5L, new double[]{0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, -0.8},
                Stage.FRIEND), true);
        verify(tie).setInt(1, 3);
        verify(tie).setInt(2, 42);
        verify(tie).setFloat(9, 0.3f);
        verify(tie).setInt(15, Stage.FRIEND.code());
        verify(tie).setInt(16, 1);
    }

    // ── the column probe ──

    @Test
    void theProbeAsksOnceAndRecheckAfterAMissingColumnOnlyAfterTenMinutes() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SocialSim.useForTest(now::get, 1);
        AxisColumns.Availability a = new AxisColumns.Availability("botbond");
        AtomicInteger asked = new AtomicInteger();
        AxisColumns.Availability.Probe missing = sql -> {
            asked.incrementAndGet();
            assertTrue(sql.startsWith("SELECT Familiarity") && sql.endsWith("FROM botbond WHERE 1 = 0"), sql);
            throw new SQLException("Unknown column", "42S22", 1054);
        };
        assertFalse(a.present(missing));
        assertFalse(a.present(missing));
        assertEquals(1, asked.get(), "a 'no' is remembered");
        now.addAndGet(AxisColumns.RECHECK_MS + 1);
        AxisColumns.Availability.Probe there = sql -> asked.incrementAndGet();
        assertTrue(a.present(there), "the migration was applied meanwhile");
        assertTrue(a.present(missing), "a 'yes' is final");
        assertEquals(2, asked.get());
    }

    @Test
    void aBusyOrMissingTableIsNotRememberedAsNoColumns() {
        SocialSim.useForTest(() -> 5L, 1);
        AxisColumns.Availability a = new AxisColumns.Availability("botaffinity");
        AtomicInteger asked = new AtomicInteger();
        AxisColumns.Availability.Probe busy = sql -> {
            asked.incrementAndGet();
            throw new SQLException("share busy", "HY000", 1);
        };
        assertFalse(a.present(busy));
        assertFalse(a.present(busy));
        assertEquals(2, asked.get(), "asked again: nothing was learned");
        assertTrue(a.present(sql -> { }));
    }
}
