package client;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Ticket 164: a Monster Book tier that completes on an account with no {@code monsterbook_stats} row is lost.
 * {@code Character.UpdateTier1..9} only ever ran {@code UPDATE ... WHERE accountid = ?} and the only row creation was
 * {@code INSERT IGNORE ...} inside {@code insertNewChar}, so every account older than that code - and every account
 * whose character was created before it - wrote tiers into nothing. Login reads them back with a SUM, which is 0
 * (NULL) for an account with no row, and cards cap at five copies, so the loss is permanent.
 *
 * <p>Runs only against a scratch MySQL: {@code -Dslums.monsterbook.it.url=jdbc:mysql://...} (no MySQL given, the
 * test is reported as skipped, never failed). The connection the code asks the pool for is a real driver connection,
 * so the statement, its parameters and the table are all exercised for real.
 */
class MonsterBookTierPersistenceTest {

    static final String URL = System.getProperty("slums.monsterbook.it.url");

    /**
     * The schema the statements in {@code Character} already imply - one row per account, nine NOT NULL tier
     * counters - reconstructed here because the repo carries no DDL for this external table. The primary key is what
     * makes {@code accountid} unique, which is exactly what {@code INSERT IGNORE} in {@code insertNewChar} relies on.
     */
    static final String CREATE_TABLE = "CREATE TABLE monsterbook_stats (accountid int NOT NULL,"
            + " Tier1 int NOT NULL DEFAULT 0, Tier2 int NOT NULL DEFAULT 0, Tier3 int NOT NULL DEFAULT 0,"
            + " Tier4 int NOT NULL DEFAULT 0, Tier5 int NOT NULL DEFAULT 0, Tier6 int NOT NULL DEFAULT 0,"
            + " Tier7 int NOT NULL DEFAULT 0, Tier8 int NOT NULL DEFAULT 0, Tier9 int NOT NULL DEFAULT 0,"
            + " PRIMARY KEY (accountid))";

    /** Verbatim the read in {@code Character.loadCharFromDB} - SUM over the rows, so no row reads back as 0. */
    static final String RELOGIN_READ = "SELECT SUM(Tier1) AS Total1, SUM(Tier2) AS Total2, SUM(Tier3) AS Total3,"
            + " SUM(Tier4) AS Total4, SUM(Tier5) AS Total5, SUM(Tier6) AS Total6, SUM(Tier7) AS Total7,"
            + " SUM(Tier8) AS Total8, SUM(Tier9) AS Total9 FROM `monsterbook_stats` WHERE accountid= ?";

    static void reset() throws SQLException {
        try (Connection db = DriverManager.getConnection(URL);
             Statement st = db.createStatement()) {
            st.execute("DROP TABLE IF EXISTS monsterbook_stats");
            st.execute(CREATE_TABLE);
        }
    }

    /** One total from the login read, for an account nobody has inserted a row for. */
    static int reloginTotal(int accountid, String column) throws SQLException {
        try (Connection db = DriverManager.getConnection(URL);
             PreparedStatement ps = db.prepareStatement(RELOGIN_READ)) {
            ps.setInt(1, accountid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(column) : 0;
            }
        }
    }

    static int rowCount(int accountid) throws SQLException {
        try (Connection db = DriverManager.getConnection(URL);
             PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM monsterbook_stats WHERE accountid = ?")) {
            ps.setInt(1, accountid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    static Character character(int accountid) throws Exception {
        Character chr = Character.getDefault(mock(Client.class));
        Field field = Character.class.getDeclaredField("accountid");
        field.setAccessible(true);
        field.setInt(chr, accountid);
        return chr;
    }

    static void saveThroughTheRealDatabase(Character chr, int tier) {
        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenAnswer(invocation -> DriverManager.getConnection(URL));
            chr.AddTier1(tier);
            chr.UpdateTier1();
        }
    }

    @Test
    void tierCompletedOnAnAccountWithNoRowSurvivesARelogin() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given (-Dslums.monsterbook.it.url)");
        reset();

        int accountid = 9164001;
        Character chr = character(accountid);
        saveThroughTheRealDatabase(chr, 3);

        assertEquals(3, reloginTotal(accountid, "Total1"),
                "Tier1 written for an account with no monsterbook_stats row must be there at relogin");
    }

    @Test
    void everyTierColumnSurvivesARelogin() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given (-Dslums.monsterbook.it.url)");
        reset();

        int[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int tier = 1; tier <= 9; tier++) {
            int accountid = 9164100 + tier;
            Character chr = character(accountid);
            addTier(chr, tier, values[tier - 1]);
            updateTier(chr, tier);
            assertEquals(values[tier - 1], reloginTotal(accountid, "Total" + tier),
                    "Tier" + tier + " must survive the relogin for an account with no row");
            assertEquals(1, rowCount(accountid), "one stats row per account");
        }
    }

    static void addTier(Character chr, int tier, int points) {
        switch (tier) {
            case 1 -> chr.AddTier1(points);
            case 2 -> chr.AddTier2(points);
            case 3 -> chr.AddTier3(points);
            case 4 -> chr.AddTier4(points);
            case 5 -> chr.AddTier5(points);
            case 6 -> chr.AddTier6(points);
            case 7 -> chr.AddTier7(points);
            case 8 -> chr.AddTier8(points);
            default -> chr.AddTier9(points);
        }
    }

    static void updateTier(Character chr, int tier) {
        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenAnswer(invocation -> DriverManager.getConnection(URL));
            switch (tier) {
                case 1 -> chr.UpdateTier1();
                case 2 -> chr.UpdateTier2();
                case 3 -> chr.UpdateTier3();
                case 4 -> chr.UpdateTier4();
                case 5 -> chr.UpdateTier5();
                case 6 -> chr.UpdateTier6();
                case 7 -> chr.UpdateTier7();
                case 8 -> chr.UpdateTier8();
                default -> chr.UpdateTier9();
            }
        }
    }
}
