package net.server.task;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingLoginTaskTest {

    @Test
    void anAccountThatNeverLoggedInCountsAsLastSeenBeforeAnyUpdate() {
        // The bot template's account (SMBotBase) has a NULL lastlogin; it used to abort the hourly update.
        assertEquals(0, RankingLoginTask.lastLoginMillis(null));
    }

    @Test
    void aRecordedLoginIsItsTimestamp() {
        assertEquals(1_700_000_000_000L, RankingLoginTask.lastLoginMillis(new Timestamp(1_700_000_000_000L)));
    }

    @Test
    void theBotTemplateIsLeftOutOfTheRankings() {
        String enabled = System.getProperty("solomapling.enabled");
        String template = System.getProperty("solomapling.template-id");
        try {
            System.setProperty("solomapling.enabled", "true");
            System.setProperty("solomapling.template-id", "4321");
            assertEquals(4321, RankingLoginTask.unrankedCharacterId());
        } finally {
            restore("solomapling.enabled", enabled);
            restore("solomapling.template-id", template);
        }
    }

    @Test
    void withSoloMaplingOffNoCharacterIsLeftOut() {
        String enabled = System.getProperty("solomapling.enabled");
        try {
            System.clearProperty("solomapling.enabled");
            assertEquals(0, RankingLoginTask.unrankedCharacterId());
        } finally {
            restore("solomapling.enabled", enabled);
        }
    }

    @Test
    void everyRankingQueryFiltersTheTemplateBeforeTheJob() {
        for (int job : new int[]{-1, 1}) {
            String sql = RankingLoginTask.charSelectSql(job);
            assertTrue(sql.contains("c.world = ? AND c.id <> ? "), sql);
        }
        assertTrue(RankingLoginTask.charSelectSql(1).indexOf("c.id <> ?") < RankingLoginTask.charSelectSql(1).indexOf("c.job DIV 100 = ?"));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
