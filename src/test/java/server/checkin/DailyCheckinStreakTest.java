package server.checkin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyCheckinStreakTest {

    private static final long DAY = DailyCheckinStreak.PERIOD_MS;
    private static final long T0 = 1_800_000_000_000L;

    @Test
    void aNewAccountCanClaimDayOneRightAway() {
        assertEquals(1, DailyCheckinStreak.NEW.claimableDay(T0));
        assertEquals(0, DailyCheckinStreak.NEW.cooldownSeconds(T0));
    }

    @Test
    void aClaimStartsA24HourCooldownThenTheNextDayUnlocks() {
        DailyCheckinStreak s = DailyCheckinStreak.NEW.claimed(1, T0);

        assertEquals(0, s.claimableDay(T0 + DAY - 1));
        assertEquals(1, s.cooldownSeconds(T0 + DAY - 1));
        assertEquals(1, s.viewDay(T0 + 1), "during the cooldown the window shows the day just claimed");
        assertEquals(2, s.claimableDay(T0 + DAY));
        assertEquals(0b1, s.claimedMask());
    }

    @Test
    void missingMoreThanADayResetsTheStreak() {
        DailyCheckinStreak s = DailyCheckinStreak.NEW.claimed(1, T0).claimed(2, T0 + DAY);

        assertEquals(3, s.claimableDay(T0 + 2 * DAY + DAY - 1));
        assertEquals(1, s.claimableDay(T0 + 3 * DAY), "48 hours after the last claim the cycle starts over");
        assertEquals(0, s.normalized(T0 + 3 * DAY).claimedMask());
        assertEquals(0b1, s.claimed(1, T0 + 3 * DAY).claimedMask());
    }

    @Test
    void finishingDay28StartsANewCycle() {
        DailyCheckinStreak s = DailyCheckinStreak.NEW;
        long t = T0;
        for (int d = 1; d <= DailyCheckinStreak.CYCLE_DAYS; d++, t += DAY) {
            assertEquals(d, s.claimableDay(t));
            s = s.claimed(d, t);
        }
        assertEquals((1 << 28) - 1, s.claimedMask());
        assertEquals(1, s.claimableDay(t), "after day 28 the next claim is day 1 of a new cycle");
    }
}
