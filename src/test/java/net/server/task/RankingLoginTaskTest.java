package net.server.task;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
