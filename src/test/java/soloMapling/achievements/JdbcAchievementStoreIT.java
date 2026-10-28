package soloMapling.achievements;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket 93: the SQL store and migration 043 against a throwaway MySQL 8. Skipped unless
 * {@code -Dslums.achievements.it.url=jdbc:mysql://127.0.0.1:PORT/DB?user=..&password=..} names an empty scratch schema
 * (never a game database). Applies 043 twice, runs the claim rules and a service round trip, rolls 042 back twice.
 */
class JdbcAchievementStoreIT {
    static final String URL = System.getProperty("slums.achievements.it.url");
    Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        admin = DriverManager.getConnection(URL);
        exec("DROP TABLE IF EXISTS character_achievements, mapleroot_integration_migrations");
        runFile("043-achievements.sql");
        runFile("043-achievements.sql"); // twice: no change
        assertEquals(1, one("SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '043-achievements'"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null) {
            runFile("043-achievements.rollback.sql");
            runFile("043-achievements.rollback.sql");
            assertEquals(0, one("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                    + " AND table_name = 'character_achievements'"), "rollback removed the table");
            assertEquals(0, one("SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '043-achievements'"));
            admin.close();
        }
    }

    void exec(String sql) throws SQLException {
        try (Statement s = admin.createStatement()) {
            s.execute(sql);
        }
    }

    void runFile(String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(Path.of("database/migrations", name))) {
            if (!line.strip().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        for (String stmt : sb.toString().split(";")) {
            if (!stmt.isBlank()) {
                exec(stmt);
            }
        }
    }

    long one(String sql) throws SQLException {
        try (ResultSet rs = admin.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    JdbcAchievementStore store() {
        return new JdbcAchievementStore(() -> DriverManager.getConnection(URL));
    }

    @Test
    void claimsHappenOnceAndProgressNeverMovesAnEarnedRow() throws Exception {
        JdbcAchievementStore s = store();
        int bot = 1_000_000_042; // no characters table at all: a bot needs no foreign key
        s.saveProgress(bot, "fish-10", 3, 10);
        s.saveProgress(bot, "fish-10", 4, 11);
        assertEquals(4, s.load(bot).get("fish-10").progress());
        assertTrue(s.markEarned(bot, "fish-10", 10, 12));
        assertFalse(s.markEarned(bot, "fish-10", 10, 13), "a second claim finds it earned");
        s.saveProgress(bot, "fish-10", 1, 14);
        AchievementStore.Row r = s.load(bot).get("fish-10");
        assertEquals(AchievementStore.EARNED, r.state());
        assertEquals(10, r.progress(), "an earned row is not rewound");
        assertEquals(12, r.earnedAt());
        assertTrue(s.markGranted(bot, "fish-10", 15));
        assertFalse(s.markGranted(bot, "fish-10", 16));
        assertFalse(s.markEarned(bot, "fish-10", 10, 17), "granted stays granted");
        assertTrue(s.markEarned(7, "wedding", 1, 20), "an earn with no progress row inserts one");
        s.delete(7, "wedding");
        assertTrue(s.load(7).isEmpty());
    }

    @Test
    void theServiceRoundTripsThroughMysqlAndSurvivesARestart() throws Exception {
        MemoryStore unused = null;
        FakeHands hands = new FakeHands();
        GoalCatalog cat = GoalCatalogTest.shipped();
        Subject me = new Subject(5150, false, org.mockito.Mockito.mock(client.Character.class));
        AchievementService a = new AchievementService(cat, store(), hands, System::currentTimeMillis, Runnable::run);
        for (int i = 0; i < 7; i++) {
            a.handle(me, Event.of(Trigger.FISH), false);
        }
        AchievementService b = new AchievementService(cat, store(), hands, System::currentTimeMillis, Runnable::run);
        for (int i = 0; i < 3; i++) {
            b.handle(me, Event.of(Trigger.FISH), false);
        }
        Map<String, AchievementStore.Row> rows = store().load(5150);
        assertEquals(AchievementStore.GRANTED, rows.get("fish-10").state());
        assertEquals(1, hands.gives);
        assertEquals(10, rows.get("fish-100").progress(), "the long goal keeps counting");
    }
}
