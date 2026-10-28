package soloMapling.achievements;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AchievementsMigrationTest {
    static final Path DIR = Path.of("database/migrations");

    @Test
    void migration043IsIdempotentHasARollbackAndNoForeignKey() throws Exception {
        String up = Files.readString(DIR.resolve("043-achievements.sql"));
        String down = Files.readString(DIR.resolve("043-achievements.rollback.sql"));
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS character_achievements"));
        assertTrue(up.contains("PRIMARY KEY (charid, achievement)"));
        assertFalse(up.toUpperCase().contains("FOREIGN KEY") || up.toUpperCase().contains("REFERENCES"),
                "a bot has no characters row");
        assertTrue(up.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('043-achievements')"));
        assertTrue(down.contains("DROP TABLE IF EXISTS character_achievements"));
        assertTrue(down.contains("migration_id = '043-achievements'"));
        assertTrue(Files.readString(Path.of("docs/release/1.6.1.conf")).contains("043-achievements"));
    }
}
