package soloMapling.Economy;

import soloMapling.server.ExecutorServiceManager;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 1.5.0 rc3: the world's daily economy meters (one number per meter per server day), kept in {@code economyday}
 * (migration 020) so a restart does not reset a world cap.
 * <ul>
 *   <li>{@link #BOT_BUY}: mesos bots paid real players today (FM shopper purchases and direct trades; owner decision
 *   2's world budget, {@link #tryBotBuy});</li>
 *   <li>{@link #GIFTS} and {@link #GIFT_MESOS}: friend gifts handed out (decision 4's world cap);</li>
 *   <li>{@link #TAX}: FM shop tax collected (decision 6, a sink);</li>
 *   <li>{@link #GODLIES}: bot scroll results past the godly line ({@link GearGrade});</li>
 *   <li>{@link #BOT_SHOP_SALES}: mesos real players paid bot shops (they go to the bots, a transfer).</li>
 * </ul>
 */
public final class EconomyMeters {

    public static final String BOT_BUY = "botbuy";
    public static final String GIFTS = "gifts";
    public static final String GIFT_MESOS = "giftmesos";
    public static final String TAX = "tax";
    public static final String GODLIES = "godlies";
    public static final String BOT_SHOP_SALES = "botshopsales";
    public static final String SINK_SHOP = "sinkshop";

    private static final Map<String, Long> today = new ConcurrentHashMap<>();
    private static volatile LocalDate day;
    private static volatile EconomyStore store = new EconomyStore.Jdbc();
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static volatile Supplier<LocalDate> dayClock = LocalDate::now;

    private EconomyMeters() {
    }

    private static void roll() {
        LocalDate now = dayClock.get();
        if (now.equals(day)) {
            return;
        }
        synchronized (EconomyMeters.class) {
            if (now.equals(day)) {
                return;
            }
            today.clear();
            today.putAll(store.meters(now));
            day = now;
        }
    }

    public static long get(String meter) {
        roll();
        return today.getOrDefault(meter, 0L);
    }

    /** Adds to a meter (negative to give back); the new value. */
    public static long add(String meter, long delta) {
        roll();
        long v = today.merge(meter, delta, Long::sum);
        if (v < 0) {
            today.put(meter, 0L);
            v = 0;
        }
        LocalDate d = day;
        long saved = v;
        EconomyStore st = store;
        writer.execute(() -> st.saveMeter(d, meter, saved));
        return v;
    }

    /**
     * Reserves {@code amount} of a daily cap: true and counted when {@code current + amount <= cap}, else false and
     * nothing changes.
     */
    public static synchronized boolean tryAdd(String meter, long amount, long cap) {
        roll();
        long cur = today.getOrDefault(meter, 0L);
        if (amount < 0 || cur + amount > cap) {
            return false;
        }
        add(meter, amount);
        return true;
    }

    // ---- owner decision 2: the world's daily budget for bots buying from players ----

    public static long botBuyBudget() {
        return Math.max(0, EconomyConfig.getLong("botBuying.worldDailyBudget", 40_000_000L));
    }

    /** Reserves mesos of today's world budget for one bot purchase from a real player. */
    public static boolean tryBotBuy(long mesos) {
        return tryAdd(BOT_BUY, Math.max(0, mesos), botBuyBudget());
    }

    /** Gives back a reservation whose purchase did not go through. */
    public static void releaseBotBuy(long mesos) {
        if (mesos > 0) {
            add(BOT_BUY, -mesos);
        }
    }

    /** "botbuy 1,200,000 of 40,000,000; gifts 3; tax 54,000 ..." */
    public static String line() {
        roll();
        StringBuilder sb = new StringBuilder("Economy today (" + day + "): bots paid players ")
                .append(String.format("%,d of %,d", get(BOT_BUY), botBuyBudget()));
        Map<String, Long> rest = new TreeMap<>(today);
        rest.remove(BOT_BUY);
        rest.forEach((k, v) -> sb.append(String.format("; %s %,d", k, v)));
        return sb.toString();
    }

    public static void useForTest(EconomyStore testStore, Executor testWriter, Supplier<LocalDate> testDay) {
        store = testStore == null ? new EconomyStore.Jdbc() : testStore;
        writer = testWriter == null ? ExecutorServiceManager::runAsync : testWriter;
        dayClock = testDay == null ? LocalDate::now : testDay;
        today.clear();
        day = null;
    }
}
