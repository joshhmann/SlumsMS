package server.checkin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 t-ck-1: the QA account's last claim moves past the cooldown so a nightly run can claim; the streak goes on. */
class DailyCheckinRewindTest {

    private static final long NOW = 1_800_000_000_000L;
    private final Map<Integer, DailyCheckinStreak> rows = new HashMap<>();

    private void fakeStore() {
        DailyCheckin.useForTest(new DailyCheckin.Store() {
            @Override
            public DailyCheckinStreak load(int accountId) {
                return rows.getOrDefault(accountId, DailyCheckinStreak.NEW);
            }

            @Override
            public void save(int accountId, DailyCheckinStreak streak) {
                rows.put(accountId, streak);
            }
        });
    }

    @AfterEach
    void restore() {
        DailyCheckin.useForTest(null);
    }

    @Test
    void aClaimWithinADayIsMovedBackAndTheNextDayOpens() throws Exception {
        fakeStore();
        rows.put(7, new DailyCheckinStreak(3, 0b111, NOW - 3_600_000));
        assertEquals(0, rows.get(7).claimableDay(NOW));
        DailyCheckinStreak s = DailyCheckin.rewindForQa(7, NOW);
        assertEquals(4, s.claimableDay(NOW), "the streak goes on to day 4");
        assertEquals(s, DailyCheckin.streakForQa(7));
        assertTrue(NOW - s.lastClaim() > DailyCheckinStreak.PERIOD_MS);
    }

    @Test
    void anOpenClaimOrANewAccountIsLeftAlone() throws Exception {
        fakeStore();
        DailyCheckinStreak old = new DailyCheckinStreak(2, 0b11, NOW - DailyCheckinStreak.PERIOD_MS - 5);
        rows.put(8, old);
        assertEquals(old, DailyCheckin.rewindForQa(8, NOW));
        assertEquals(DailyCheckinStreak.NEW, DailyCheckin.rewindForQa(9, NOW));
        assertTrue(!rows.containsKey(9), "nothing written for an account that never claimed");
    }
}
