package soloMapling.ArtificialPlayer.BotScrollSystem;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Ticket 11: what a bot may spend on scrolls and how much it may scroll, per server day.
 *
 * <p>Scrolls are a bot's own spending, paid from the mesos it earned (never from the FM shopper allowance):
 * <ul>
 *   <li><b>Reserve:</b> it keeps {@code 100,000 + 5,000 x level} for pots and ammo and never spends below it.</li>
 *   <li><b>Daily cap:</b> at most {@code 15,000 x level} a day on scrolls (lv 30: 450k, lv 70: 1.05m,
 *   lv 120: 1.8m), and no single purchase over a quarter of what it holds above the reserve, so a bot
 *   never sinks its whole purse into scrolls.</li>
 *   <li><b>Counts:</b> at most {@value #MAX_BUYS} scrolls bought a day, {@value #MAX_FROM_PLAYERS} of them
 *   from real players' shops (on top of ticket 56A's 5m per seller account and 1.0x price ceiling), and at most
 *   {@value #MAX_ROLLS} scrolls used a day.</li>
 * </ul>
 * Counts live in memory: a restart starts the day again. That can only let a bot spend more of its own mesos (a
 * sink) or buy a few more scrolls from players (still inside 56A's persisted per-seller cap): it never creates
 * mesos. Tuned with {@code -Dsolomapling.botScrollPerLevel} (15000), {@code -Dsolomapling.botScrollRollsPerDay}
 * ({@value #MAX_ROLLS}) and switched off with {@code -Dsolomapling.botScrolling=false}.
 */
public final class ScrollBudget {

    static final long RESERVE_BASE = 100_000;
    static final long RESERVE_PER_LEVEL = 5_000;
    static final long DEFAULT_PER_LEVEL = 15_000;
    static final double MAX_SHARE_OF_SPARE = 0.25;
    static final int MAX_BUYS = 12;
    static final int MAX_FROM_PLAYERS = 3;
    static final int MAX_ROLLS = 20;

    /** One bot's day. Guarded by its own monitor. */
    static final class Day {
        long epochDay;
        long spent;
        int bought;
        int fromPlayers;
        int rolls;

        void rollTo(long today) {
            if (epochDay != today) {
                epochDay = today;
                spent = 0;
                bought = 0;
                fromPlayers = 0;
                rolls = 0;
            }
        }
    }

    private static final Map<Integer, Day> days = new ConcurrentHashMap<>();
    private static volatile LongSupplier dayClock = () -> LocalDate.now().toEpochDay();

    private ScrollBudget() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.botScrolling", "true"));
    }

    static long reserve(int level) {
        return RESERVE_BASE + RESERVE_PER_LEVEL * Math.max(1, level);
    }

    static long dailyCap(int level) {
        return Long.getLong("solomapling.botScrollPerLevel", DEFAULT_PER_LEVEL) * Math.max(1, level);
    }

    static int maxRolls() {
        return Math.max(0, Integer.getInteger("solomapling.botScrollRollsPerDay", MAX_ROLLS));
    }

    private static Day day(int botId) {
        Day d = days.computeIfAbsent(botId, id -> new Day());
        synchronized (d) {
            d.rollTo(dayClock.getAsLong());
        }
        return d;
    }

    /** The most this bot would pay for one scroll right now (0 = it is not buying today). */
    public static long maxPrice(int botId, int level, long mesosHeld, boolean fromPlayer) {
        Day d = day(botId);
        synchronized (d) {
            if (d.bought >= MAX_BUYS || (fromPlayer && d.fromPlayers >= MAX_FROM_PLAYERS)) {
                return 0;
            }
            long spare = mesosHeld - reserve(level);
            if (spare <= 0) {
                return 0;
            }
            // 56B: a bot over its wealth ceiling may spend half of the excess on scrolls on top of its daily cap
            long cap = dailyCap(level) + soloMapling.ArtificialPlayer.BotWealth.scrollBonus(level, mesosHeld);
            return Math.max(0, Math.min(cap - d.spent, (long) (spare * MAX_SHARE_OF_SPARE)));
        }
    }

    /** Reserves one purchase at {@code price}; false when the budget, the reserve or a count says no. */
    public static boolean tryBuy(int botId, int level, long mesosHeld, long price, boolean fromPlayer) {
        if (price <= 0) {
            return false;
        }
        Day d = day(botId);
        synchronized (d) {
            if (price > maxPrice(botId, level, mesosHeld, fromPlayer)) {
                return false;
            }
            d.spent += price;
            d.bought++;
            if (fromPlayer) {
                d.fromPlayers++;
            }
            return true;
        }
    }

    /** Gives back a reservation whose purchase did not go through. */
    public static void refund(int botId, long price, boolean fromPlayer) {
        Day d = day(botId);
        synchronized (d) {
            d.spent = Math.max(0, d.spent - price);
            d.bought = Math.max(0, d.bought - 1);
            if (fromPlayer) {
                d.fromPlayers = Math.max(0, d.fromPlayers - 1);
            }
        }
    }

    /** Claims one scroll use for today; false once the bot used its {@link #maxRolls()} today. */
    public static boolean tryRoll(int botId) {
        Day d = day(botId);
        synchronized (d) {
            if (d.rolls >= maxRolls()) {
                return false;
            }
            d.rolls++;
            return true;
        }
    }

    public static int rollsLeft(int botId) {
        Day d = day(botId);
        synchronized (d) {
            return Math.max(0, maxRolls() - d.rolls);
        }
    }

    /** "spent 120,000 of 450,000 today, 2 bought (0 from players), 3 of 20 used" for one bot. */
    public static String status(int botId, int level) {
        Day d = day(botId);
        synchronized (d) {
            return String.format("spent %,d of %,d today, %d bought (%d from players), %d of %d used; reserve %,d",
                    d.spent, dailyCap(level), d.bought, d.fromPlayers, d.rolls, maxRolls(), reserve(level));
        }
    }

    public static void forget(int botId) {
        days.remove(botId);
    }

    // ---- test seams ----

    static void useDayClockForTest(LongSupplier clock) {
        dayClock = clock == null ? () -> LocalDate.now().toEpochDay() : clock;
    }

    static void clearForTest() {
        days.clear();
    }
}
