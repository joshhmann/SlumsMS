package server.checkin;

/**
 * One account's Daily Check-In streak and its rules (pure: no clock, no database).
 *
 * <p>A claim unlocks 24 hours after the previous one. Claiming within 48 hours continues the streak; letting more
 * than 48 hours pass, or finishing day 28, starts a new cycle at day 1.
 *
 * @param day         the last day claimed this cycle (0 = none yet)
 * @param claimedMask bit {@code d - 1} set when day {@code d} was claimed this cycle
 * @param lastClaim   epoch millis of the last claim, 0 when the account never claimed
 */
public record DailyCheckinStreak(int day, int claimedMask, long lastClaim) {

    public static final int CYCLE_DAYS = 28;
    static final long PERIOD_MS = 86_400_000L;

    public static final DailyCheckinStreak NEW = new DailyCheckinStreak(0, 0, 0);

    /** The streak as it stands at {@code now}: a lapsed or finished cycle starts over. */
    public DailyCheckinStreak normalized(long now) {
        if (lastClaim <= 0) {
            return day == 0 && claimedMask == 0 ? this : NEW;
        }
        long elapsed = now - lastClaim;
        if (elapsed >= PERIOD_MS && (elapsed >= 2 * PERIOD_MS || day >= CYCLE_DAYS)) {
            return new DailyCheckinStreak(0, 0, lastClaim);
        }
        return this;
    }

    /** The day that can be claimed at {@code now} (1..28), or 0 while the 24-hour cooldown runs. */
    public int claimableDay(long now) {
        if (lastClaim > 0 && now - lastClaim < PERIOD_MS) {
            return 0;
        }
        return normalized(now).day + 1;
    }

    /** The streak after claiming {@code claimDay} at {@code now}; the caller checked it is the claimable day. */
    public DailyCheckinStreak claimed(int claimDay, long now) {
        DailyCheckinStreak base = normalized(now);
        return new DailyCheckinStreak(claimDay, base.claimedMask | (1 << (claimDay - 1)), now);
    }

    /** Whole seconds until the next claim unlocks; 0 when one is available now. */
    public long cooldownSeconds(long now) {
        if (lastClaim <= 0) {
            return 0;
        }
        long remain = lastClaim + PERIOD_MS - now;
        return remain > 0 ? (remain + 999) / 1000 : 0;
    }

    /** The day the window shows as current: the claimable one, or during the cooldown the last one claimed. */
    public int viewDay(long now) {
        int claimable = claimableDay(now);
        return claimable > 0 ? claimable : day;
    }
}
