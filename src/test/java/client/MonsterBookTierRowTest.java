package client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The two halves of the Monster Book tier fix that a relogin test cannot see: the row a returning account never had,
 * and what the character keeps in memory when the save fails.
 *
 * <p>Runs against a scratch MySQL only. {@code monsterbook_stats} has no DDL in this repository - it comes from the
 * server's external schema - so the table below is a reconstruction: the account key the login query filters on, plus
 * the nine counter columns its SELECT names. State that, and any schema mismatch, when reporting the run.
 *
 * <p>{@code -Dslums.monsterbook.it.url=jdbc:mysql://127.0.0.1:3306/<scratch db>?user=root} points it at a database
 * that may be dropped and recreated.
 */
class MonsterBookTierRowTest {

    private static final String URL = System.getProperty("slums.monsterbook.it.url");

    private static final String CREATE_TABLE = "CREATE TABLE monsterbook_stats ("
            + " accountid int NOT NULL PRIMARY KEY,"
            + " Tier1 int NOT NULL DEFAULT 0, Tier2 int NOT NULL DEFAULT 0, Tier3 int NOT NULL DEFAULT 0,"
            + " Tier4 int NOT NULL DEFAULT 0, Tier5 int NOT NULL DEFAULT 0, Tier6 int NOT NULL DEFAULT 0,"
            + " Tier7 int NOT NULL DEFAULT 0, Tier8 int NOT NULL DEFAULT 0, Tier9 int NOT NULL DEFAULT 0)";

    @BeforeEach
    void reset() throws SQLException {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given (-Dslums.monsterbook.it.url)");
        try (Connection con = DriverManager.getConnection(URL); Statement st = con.createStatement()) {
            st.execute("DROP TABLE IF EXISTS monsterbook_stats");
            st.execute(CREATE_TABLE);
        }
    }

    private static int rowCount(int accountid) throws SQLException {
        try (Connection con = DriverManager.getConnection(URL);
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM monsterbook_stats WHERE accountid = ?")) {
            ps.setInt(1, accountid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Character character(int accountid) {
        Character chr = Character.getDefault(mock(Client.class));
        try {
            Field accountidField = Character.class.getDeclaredField("accountid");
            accountidField.setAccessible(true);
            accountidField.setInt(chr, accountid);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Character.accountid is not writable by reflection", e);
        }
        return chr;
    }

    /** Runs {@code work} the way the game does: through the real database, reached via the mocked pool. */
    private static void throughTheRealDatabase(java.util.function.Consumer<Connection> work) throws SQLException {
        try (MockedStatic<tools.DatabaseConnection> pool = mockStatic(tools.DatabaseConnection.class)) {
            pool.when(tools.DatabaseConnection::getConnection).thenAnswer(invocation -> DriverManager.getConnection(URL));
            try (Connection con = DriverManager.getConnection(URL)) {
                work.accept(con);
            }
        }
    }

    @Test
    void aReloginOfAnAccountWithNoRowCreatesOneToSaveInto() throws SQLException {
        int accountid = 9164201;
        Character chr = character(accountid);

        throughTheRealDatabase(con -> {
            try {
                chr.loadTierProgress(con);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, rowCount(accountid), "the ACCOUNT sees every tier before relogin, yet has no row to save into");
    }

    @Test
    void anAccountThatAlreadyHasItsTiersKeepsItsSingleRow() throws SQLException {
        int accountid = 9164202;
        try (Connection con = DriverManager.getConnection(URL);
             PreparedStatement ps = con.prepareStatement("INSERT INTO monsterbook_stats (accountid, Tier3) VALUES (?, 4)")) {
            ps.setInt(1, accountid);
            ps.executeUpdate();
        }
        Character chr = character(accountid);

        throughTheRealDatabase(con -> {
            try {
                chr.loadTierProgress(con);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, rowCount(accountid), "a second relogin must not add a second row");
        assertEquals(4, chr.getTier3(), "the counters read back from the row");
    }

    @Test
    void progressThatCouldNotBeSavedIsTakenBackOutOfTheCharacter() throws SQLException {
        Character chr = character(9164203);
        int before = chr.getTier2();

        boolean saved;
        try (MockedStatic<tools.DatabaseConnection> pool = mockStatic(tools.DatabaseConnection.class)) {
            pool.when(tools.DatabaseConnection::getConnection).thenThrow(new SQLException("pool exhausted"));
            saved = chr.saveTierProgress(2, 1);
        }

        assertFalse(saved, "a failed write must be reported to the caller");
        assertEquals(before, chr.getTier2(), "the counter must not claim a tier the database does not have");
    }

    @Test
    void progressThatWasSavedStaysOnTheCharacter() throws SQLException {
        Character chr = character(9164204);
        int before = chr.getTier2();

        throughTheRealDatabase(con -> {
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO monsterbook_stats (accountid) VALUES (?)")) {
                ps.setInt(1, 9164204);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        boolean saved;
        try (MockedStatic<tools.DatabaseConnection> pool = mockStatic(tools.DatabaseConnection.class)) {
            pool.when(tools.DatabaseConnection::getConnection).thenAnswer(invocation -> DriverManager.getConnection(URL));
            saved = chr.saveTierProgress(2, 1);
        }

        assertTrue(saved, "the write goes to the account's own row");
        assertEquals(before + 1, chr.getTier2(), "and the character keeps the tier it just earned");
    }
}
