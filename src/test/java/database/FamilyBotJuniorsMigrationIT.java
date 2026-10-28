package database;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ticket 53 (1.6.1): migration 041 lets a bot (no characters row) be a family junior, and its rollback puts the stock
 * foreign keys back. Skipped unless {@code -Dslums.family.it.url=jdbc:mysql://...} points at an empty scratch schema
 * (never a game database: it makes its own minimal tables). Applies twice, rolls back twice.
 */
class FamilyBotJuniorsMigrationIT {

    static final String URL = System.getProperty("slums.family.it.url");
    static final String BOT = "1000000007";

    @Test
    void applyTwiceThenRollbackTwice() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            exec(db, "DROP TABLE IF EXISTS family_entitlement, family_character, characters, family_041_fks,"
                    + " mapleroot_integration_migrations");
            exec(db, "CREATE TABLE characters (id INT PRIMARY KEY)");
            exec(db, "CREATE TABLE family_character (cid INT NOT NULL PRIMARY KEY, familyid INT NOT NULL, seniorid INT"
                    + " NOT NULL, reputation INT NOT NULL DEFAULT 0, todaysrep INT NOT NULL DEFAULT 0, totalreputation"
                    + " INT NOT NULL DEFAULT 0, reptosenior INT NOT NULL DEFAULT 0, precepts VARCHAR(200),"
                    + " lastresettime BIGINT NOT NULL DEFAULT 0, INDEX (cid, familyid))");
            exec(db, "ALTER TABLE family_character ADD CONSTRAINT family_character_ibfk_1 FOREIGN KEY (cid)"
                    + " REFERENCES characters (id) ON DELETE CASCADE");
            exec(db, "CREATE TABLE family_entitlement (id INT AUTO_INCREMENT PRIMARY KEY, charid INT NOT NULL,"
                    + " entitlementid INT NOT NULL, timestamp BIGINT NOT NULL DEFAULT 0, INDEX (charid))");
            exec(db, "INSERT INTO characters VALUES (1), (2)");
            exec(db, "INSERT INTO family_character (cid, familyid, seniorid) VALUES (1, 5, 0), (2, 5, 1)");
            assertThrows(SQLException.class, () -> exec(db, "INSERT INTO family_character (cid, familyid, seniorid)"
                    + " VALUES (" + BOT + ", 5, 1)"), "the stock key refuses a bot");

            for (int i = 0; i < 2; i++) {
                script(db, "041-family-bot-juniors.sql");
            }
            assertEquals(0, count(db, "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA ="
                    + " DATABASE() AND TABLE_NAME = 'family_character' AND REFERENCED_TABLE_NAME = 'characters'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM family_041_fks"));
            exec(db, "INSERT INTO family_character (cid, familyid, seniorid) VALUES (" + BOT + ", 5, 2)");
            exec(db, "INSERT INTO family_entitlement (charid, entitlementid) VALUES (1, 3)");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id ="
                    + " '041-family-bot-juniors'"));

            for (int i = 0; i < 2; i++) {
                script(db, "041-family-bot-juniors.rollback.sql");
            }
            assertEquals(2, count(db, "SELECT COUNT(*) FROM family_character"), "the bot junior's row goes");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM family_entitlement"), "player rows stay");
            assertEquals(1, count(db, "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE"
                    + " CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'family_character' AND CONSTRAINT_NAME ="
                    + " 'family_character_ibfk_1' AND DELETE_RULE = 'CASCADE'"), "the key is back as it was");
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id ="
                    + " '041-family-bot-juniors'"));
            assertThrows(SQLException.class, () -> exec(db, "INSERT INTO family_character (cid, familyid, seniorid)"
                    + " VALUES (" + BOT + ", 5, 1)"));
        }
    }

    static void script(Connection db, String name) throws Exception {
        String sql = Files.readString(Path.of("database/migrations", name));
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.stripLeading().startsWith("--")) {
                clean.append(line).append('\n');
            }
        }
        for (String stmt : clean.toString().split(";\\s*\n")) {
            if (!stmt.isBlank()) {
                exec(db, stmt);
            }
        }
    }

    static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    static int count(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
