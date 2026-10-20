package soloMapling.ArtificialPlayer;

import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotRealStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real bots ticket 05: the stored build - which column each stat is written to and read from, the skill rows, the
 * round trip a restart makes, and a store that predates migration 005.
 */
class BotStoreRealStatsTest {

    @AfterEach
    void forgetSchema() {
        BotStore.resetRealStatsSchemaForTest();
    }

    private static BotRealStats stats() {
        // Every value distinct, so a swapped pair of columns cannot pass.
        TreeMap<Integer, BotRealStats.SkillLevel> skills = new TreeMap<>();
        skills.put(4001344, new BotRealStats.SkillLevel(20, 0));
        skills.put(4100000, new BotRealStats.SkillLevel(17, 0));
        skills.put(4121007, new BotRealStats.SkillLevel(12, 30));
        return new BotRealStats(41, 87, 5, 333, 2_101, 905, 1_999, 811, 3,
                new int[]{6, 0, 0, 0, 0, 0, 0, 0, 0, 1}, 123, skills);
    }

    private static BotStore.Persisted row(BotRealStats stats) {
        return new BotStore.Persisted(1077, "Real", BotTier.B.name(), 123, 4242, 910_000, Job.getById(412).getId(),
                3, 1, 30_040, 20_010, 100_000_002, 512, 640, 97, 98, 99, 100, 1_700_000_000_000L,
                1_700_000_001_000L, stats);
    }

    private static List<String> statColumns() {
        String sql = BotStore.UPDATE_STATS;
        String assignments = sql.substring(sql.indexOf("SET ") + 4, sql.indexOf(" WHERE"));
        return Arrays.stream(assignments.split(","))
                .map(assignment -> assignment.substring(0, assignment.indexOf(" =")).trim()).toList();
    }

    private static Object valueOf(String column, BotRealStats s) {
        return switch (column) {
            case "StatStr" -> s.str();
            case "StatDex" -> s.dex();
            case "StatInt" -> s.int_();
            case "StatLuk" -> s.luk();
            case "MaxHp" -> s.maxHp();
            case "MaxMp" -> s.maxMp();
            case "Hp" -> s.hp();
            case "Mp" -> s.mp();
            case "RemainingAp" -> s.remainingAp();
            case "Sp" -> s.encodeSp();
            case "BuildLevel" -> s.buildLevel();
            default -> throw new IllegalArgumentException("no field for column " + column);
        };
    }

    /** A statement that records every value bound to it. */
    private static PreparedStatement recording(Map<Integer, Object> bound) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(call -> bound.put(call.getArgument(0), call.getArgument(1))).when(ps).setString(anyInt(), anyString());
        doAnswer(call -> bound.put(call.getArgument(0), call.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
        return ps;
    }

    /** A connection that keeps the stat row and the skill rows a write produces, the way the tables would. */
    private static Connection storing(Map<Integer, Object> statRow, List<int[]> skillRows) throws SQLException {
        Connection con = mock(Connection.class);
        PreparedStatement update = recording(statRow);
        when(con.prepareStatement(BotStore.UPDATE_STATS)).thenReturn(update);
        PreparedStatement delete = mock(PreparedStatement.class);
        doAnswer(call -> {
            skillRows.clear();
            return 0;
        }).when(delete).executeUpdate();
        when(con.prepareStatement(BotStore.DELETE_SKILLS)).thenReturn(delete);
        Map<Integer, Object> pending = new HashMap<>();
        PreparedStatement insert = recording(pending);
        doAnswer(call -> {
            skillRows.add(new int[]{(int) pending.get(1), (int) pending.get(2), (int) pending.get(3), (int) pending.get(4)});
            return null;
        }).when(insert).addBatch();
        when(con.prepareStatement(BotStore.INSERT_SKILL)).thenReturn(insert);
        return con;
    }

    @Test
    void eachStatIsWrittenToTheColumnThatNamesIt() throws SQLException {
        BotRealStats stats = stats();
        Map<Integer, Object> bound = new TreeMap<>();

        BotStore.bindStats(recording(bound), stats);

        List<String> columns = statColumns();
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Object expected = valueOf(columns.get(i), stats);
            if (!expected.equals(bound.get(i + 1))) {
                wrong.add(columns.get(i) + ": expected " + expected + " but wrote " + bound.get(i + 1));
            }
        }
        assertAll(
                () -> assertEquals(11, columns.size()),
                () -> assertEquals(List.of(), wrong));
    }

    @Test
    void aStoredBuildSurvivesTheRoundTripARestartMakes() throws SQLException {
        BotRealStats written = stats();
        Map<Integer, Object> statRow = new HashMap<>();
        List<int[]> skillRows = new ArrayList<>();

        BotStore.writeRealStats(row(written), storing(statRow, skillRows));

        // Read back exactly what was written, column by column.
        List<String> columns = statColumns();
        ResultSet rs = mock(ResultSet.class);
        for (int i = 0; i < columns.size(); i++) {
            Object value = statRow.get(i + 1);
            if (value instanceof String text) {
                when(rs.getString(columns.get(i))).thenReturn(text);
            } else {
                when(rs.getInt(columns.get(i))).thenReturn((Integer) value);
            }
        }
        BotRealStats read = BotStore.readStats(rs);
        TreeMap<Integer, BotRealStats.SkillLevel> skills = new TreeMap<>();
        for (int[] skillRow : skillRows) {
            assertEquals(1077, skillRow[0], "skill rows are keyed by the bot's slot");
            skills.put(skillRow[1], new BotRealStats.SkillLevel(skillRow[2], skillRow[3]));
        }
        BotRealStats restored = read.withSkills(skills);

        assertAll(
                () -> assertEquals(1077, statRow.get(12), "the stat row is the bot's own"),
                () -> assertEquals(written.str(), restored.str()),
                () -> assertEquals(written.dex(), restored.dex()),
                () -> assertEquals(written.int_(), restored.int_()),
                () -> assertEquals(written.luk(), restored.luk()),
                () -> assertEquals(written.maxHp(), restored.maxHp()),
                () -> assertEquals(written.maxMp(), restored.maxMp()),
                () -> assertEquals(written.hp(), restored.hp()),
                () -> assertEquals(written.mp(), restored.mp()),
                () -> assertEquals(written.remainingAp(), restored.remainingAp()),
                () -> assertArrayEquals(written.sp(), restored.sp()),
                () -> assertEquals(written.buildLevel(), restored.buildLevel()),
                () -> assertEquals(written.skills(), restored.skills()));
    }

    @Test
    void aSecondWriteReplacesTheSkillSetRatherThanAddingToIt() throws SQLException {
        Map<Integer, Object> statRow = new HashMap<>();
        List<int[]> skillRows = new ArrayList<>();
        Connection con = storing(statRow, skillRows);
        BotStore.writeRealStats(row(stats()), con);

        TreeMap<Integer, BotRealStats.SkillLevel> fewer = new TreeMap<>();
        fewer.put(4001344, new BotRealStats.SkillLevel(20, 0));
        BotStore.writeRealStats(row(stats().withSkills(fewer)), con);

        assertEquals(1, skillRows.size());
    }

    @Test
    void aRowWithoutABuildIsReadAsNoBuild() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("BuildLevel")).thenReturn(0);
        when(rs.wasNull()).thenReturn(true);

        assertNull(BotStore.readStats(rs), "a NULL BuildLevel is a bot to back-fill, not a level-0 build");
    }

    @Test
    void aBotWithoutAKeptBuildWritesNothingOverItsColumns() throws SQLException {
        Connection con = mock(Connection.class);

        BotStore.writeRealStats(row(null), con);

        verify(con, never()).prepareStatement(anyString());
    }

    @Test
    void aStoreWithoutMigration005KeepsWorkingAndStopsTrying() throws SQLException {
        Connection con = mock(Connection.class);
        PreparedStatement update = mock(PreparedStatement.class);
        when(update.executeUpdate()).thenThrow(new SQLException("Unknown column 'StatStr'", "42S22", 1054));
        when(con.prepareStatement(anyString())).thenReturn(update);

        assertDoesNotThrow(() -> BotStore.writeRealStats(row(stats()), con));
        Connection later = mock(Connection.class);
        BotStore.writeRealStats(row(stats()), later);

        verify(later, never()).prepareStatement(anyString());
    }

    @Test
    void anyOtherErrorStillFailsTheWrite() throws SQLException {
        Connection con = mock(Connection.class);
        PreparedStatement update = mock(PreparedStatement.class);
        when(update.executeUpdate()).thenThrow(new SQLException("Deadlock found", "40001", 1213));
        when(con.prepareStatement(anyString())).thenReturn(update);

        SQLException thrown = assertThrows(SQLException.class, () -> BotStore.writeRealStats(row(stats()), con));
        assertTrue(BotStore.isContention(thrown), "so the store's retry still sees the deadlock");
    }

    @Test
    void spIsStoredInTheSameFormAsTheCharactersTable() {
        assertAll(
                () -> assertEquals("6,0,0,0,0,0,0,0,0,1", stats().encodeSp()),
                () -> assertArrayEquals(new int[]{6, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                        BotRealStats.decodeSp("6,0,0,0,0,0,0,0,0,1")),
                () -> assertArrayEquals(new int[10], BotRealStats.decodeSp(null)),
                () -> assertArrayEquals(new int[]{3, 0, 0, 0, 0, 0, 0, 0, 0, 0}, BotRealStats.decodeSp("3,x")));
    }

    @Test
    void theMigrationAddsEveryColumnTheStoreWritesAndRollsThemBack() throws Exception {
        String apply = Files.readString(Path.of("database/migrations/005-bot-real-stats.sql"));
        String rollback = Files.readString(Path.of("database/migrations/005-bot-real-stats.rollback.sql"));
        for (String column : statColumns()) {
            assertTrue(apply.contains("ADD COLUMN " + column + " "), "005 adds " + column);
            assertTrue(rollback.contains("DROP COLUMN " + column), "the rollback drops " + column);
        }
        assertAll(
                () -> assertTrue(apply.contains("CREATE TABLE IF NOT EXISTS botskills")),
                () -> assertTrue(apply.contains("PRIMARY KEY (BotId, SkillId)")),
                () -> assertTrue(apply.contains("ENGINE = InnoDB")),
                () -> assertTrue(apply.contains("migration_id='005-bot-real-stats'")),
                () -> assertTrue(rollback.contains("DROP TABLE IF EXISTS botskills")),
                () -> assertTrue(rollback.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id='005-bot-real-stats'")));
    }
}
