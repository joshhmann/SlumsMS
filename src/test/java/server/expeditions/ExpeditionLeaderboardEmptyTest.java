package server.expeditions;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 b096: a bot-only clear (no player boss-log rows) or a top party with no rows threw IndexOutOfBounds. */
class ExpeditionLeaderboardEmptyTest {

    private static ExpeditionBossLogRecord row(long duration) {
        return new ExpeditionBossLogRecord(1, 7, "Ann", "VONLEON", new Timestamp(0), 100, duration, "p", true);
    }

    private static LeaderboardRecord top(Long duration) {
        LeaderboardRecord r = new LeaderboardRecord("t", "a", "b");
        if (duration != null) {
            r.addRecord(row(duration));
        }
        return r;
    }

    @Test
    void aClearWithNoPlayerRowsIsNoRecord() {
        assertFalse(assertDoesNotThrow(() -> ExpeditionLeaderboard.isNewRecord(List.of(top(5000L)), List.of())));
        assertFalse(assertDoesNotThrow(() -> ExpeditionLeaderboard.isNewRecord(List.of(), List.of())));
    }

    @Test
    void aTopPartyWithoutRowsIsSkipped() {
        assertTrue(assertDoesNotThrow(() -> ExpeditionLeaderboard.isNewRecord(List.of(top(null)), List.of(row(10)))));
        assertFalse(ExpeditionLeaderboard.isNewRecord(List.of(top(null), top(5L)), List.of(row(10))));
    }

    @Test
    void theFastestStillWins() {
        assertTrue(ExpeditionLeaderboard.isNewRecord(List.of(), List.of(row(10))));
        assertTrue(ExpeditionLeaderboard.isNewRecord(List.of(top(20L)), List.of(row(10))));
        assertFalse(ExpeditionLeaderboard.isNewRecord(List.of(top(5L)), List.of(row(10))));
    }
}
