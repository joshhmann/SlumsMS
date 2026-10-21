package soloMapling.ArtificialPlayer.BotTypes;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An FM shopper bot's daily shopping allowance (1.4.2 economy lane).
 *
 * <p>An FM shopper never fights, so it never earns: the FM bots start at {@code BotStore.INITIAL_MESO} (0) and
 * every purchase failed with "buy failed ... bot shop" (the buyer could not pay). The bot population as a whole
 * holds billions, and the FM bots stand in for its demand, so a shopper that is short tops up exactly the
 * shortfall of the item in hand - never more - from an allowance of {@link #DAILY_ALLOWANCE} per bot per UTC day.
 *
 * <ul>
 *   <li>Bought from an artificial FM merchant, the price leaves the world again (those shops pay nobody), so the
 *       top-up nets to zero.</li>
 *   <li>Bought from a real player, the payment is still bound by ticket 56A: 1.0x the normal value and
 *       5,000,000 per seller account per day ({@code BotSellerCaps}).</li>
 *   <li>A top-up that did not end in a purchase is taken back ({@link #refund}).</li>
 * </ul>
 * The allowance lives in memory: a restart resets the day's count.
 */
final class FMBotWallet {

    static final long DAILY_ALLOWANCE = 10_000_000L;

    private static final Map<Integer, long[]> usedToday = new ConcurrentHashMap<>();   // botId -> {epochDay, used}

    private FMBotWallet() {
    }

    /**
     * How much to add to a wallet holding {@code held} so it can pay {@code price}: 0 when it already can, -1 when
     * the bot's allowance for today cannot cover the shortfall. Reserves the amount it returns.
     */
    static long reserveShortfall(int botId, long held, long price) {
        long shortfall = price - Math.max(0, held);
        if (shortfall <= 0) {
            return 0;
        }
        long day = today();
        long[] granted = {-1};
        usedToday.compute(botId, (id, used) -> {
            long[] row = used == null || used[0] != day ? new long[]{day, 0} : used;
            if (row[1] + shortfall <= DAILY_ALLOWANCE) {
                row[1] += shortfall;
                granted[0] = shortfall;
            }
            return row;
        });
        return granted[0];
    }

    /** Gives back a reservation whose purchase did not go through. */
    static void refund(int botId, long amount) {
        if (amount <= 0) {
            return;
        }
        long day = today();
        usedToday.computeIfPresent(botId, (id, row) -> {
            if (row[0] == day) {
                row[1] = Math.max(0, row[1] - amount);
            }
            return row;
        });
    }

    /** Allowance a bot has left today. */
    static long remaining(int botId) {
        long[] row = usedToday.get(botId);
        return row == null || row[0] != today() ? DAILY_ALLOWANCE : DAILY_ALLOWANCE - row[1];
    }

    static void clearForTest() {
        usedToday.clear();
    }

    private static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }
}
