package soloMapling.Gachapon;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotWealth;
import soloMapling.qa.QaLog;

import java.time.LocalDate;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 1.5.0: bots play the gachapon too. A bot standing in a town with a machine ({@code maps:} in the pool file) buys a
 * ticket with its own mesos and plays it there, as a remote ticket would. The ticket's mesos are burned: a sink, never
 * paid to anyone. The prize goes into its holdings; a rare or jackpot may get a server-wide notice under the bot
 * limits of {@link GachaAnnouncer}.
 * <p>
 * How often, so it reads like a few players and not a slot machine farm:
 * <ul>
 *   <li><b>Over its wealth ceiling</b> ({@link BotWealth}): a ticket each town step (about one a minute while it
 *   stands in town) while it is still over, up to {@code botGachaSpenderPerDay} (11, a v83 ticket bundle) a
 *   day.</li>
 *   <li><b>Under it:</b> only a "gacha fan" bot (about a third, fixed per bot) plays, with a
 *   {@code botGachaCasualChance} (2%) chance per town step, at most {@code botGachaCasualPerDay} (2) a day, and only
 *   when it keeps half its ceiling after paying.</li>
 *   <li><b>World-wide</b> at most {@code botGachaPerHour} (120) bot tickets an hour.</li>
 *   <li>It needs a free slot in every tab, so the prize always lands.</li>
 * </ul>
 * Ticket price {@code botGachaTicketMesos} (1,000,000 mesos, about what a v83 ticket fetched at 10x). Off with
 * {@code -Dsolomapling.botGacha=false}. Daily counts live in memory: a restart can only let a bot spend more.
 */
public final class BotGacha {

    private static final Logger log = LoggerFactory.getLogger("server.gachapon.Gachapon");

    static final long DEFAULT_TICKET = 1_000_000;
    static final int DEFAULT_SPENDER_PER_DAY = 11;
    static final int DEFAULT_CASUAL_PER_DAY = 2;
    static final double DEFAULT_CASUAL_CHANCE = 0.02;
    static final int DEFAULT_PER_HOUR = 120;
    static final int FAN_PERCENT = 35;

    static final class Day {
        long epochDay;
        int tickets;
    }

    private static final Map<Integer, Day> days = new ConcurrentHashMap<>();
    private static final AtomicLong hourStart = new AtomicLong();
    private static final AtomicInteger hourCount = new AtomicInteger();
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile LongSupplier dayClock = () -> LocalDate.now().toEpochDay();

    private BotGacha() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.botGacha", "true"));
    }

    public static long ticketPrice() {
        return Math.max(1, Long.getLong("solomapling.botGachaTicketMesos", DEFAULT_TICKET));
    }

    /** About a third of bots like the gachapon; the rest only play to spend down excess wealth. Fixed per bot. */
    public static boolean isFan(int botId) {
        return Math.floorMod(botId * 2654435761L, 100) < FAN_PERCENT;
    }

    /**
     * Maybe buys and plays one ticket now. The line saying what happened, or null when the bot did not play.
     *
     * @param overCeiling the bot holds more than its wealth ceiling
     */
    public static String maybePlay(Character bot, boolean overCeiling, Random rng) {
        if (bot == null || !enabled() || bot.getMap() == null) {
            return null;
        }
        GachaPools.Town town = GachaPools.get().byMap(bot.getMapId());
        if (town == null) {
            return null;
        }
        if (!overCeiling && (!isFan(bot.getId())
                || rng.nextDouble() >= doubleProperty("solomapling.botGachaCasualChance", DEFAULT_CASUAL_CHANCE))) {
            return null;
        }
        return play(bot, town, overCeiling, rng, false);
    }

    /**
     * Buys and plays one ticket at {@code town} if the bot's limits allow ({@code force}: a GM's roll, free and
     * outside every limit). The line saying what happened, or why it did not play.
     */
    public static String play(Character bot, GachaPools.Town town, boolean overCeiling, Random rng, boolean force) {
        long price = ticketPrice();
        if (!force) {
            int perDay = overCeiling ? Integer.getInteger("solomapling.botGachaSpenderPerDay", DEFAULT_SPENDER_PER_DAY)
                    : Integer.getInteger("solomapling.botGachaCasualPerDay", DEFAULT_CASUAL_PER_DAY);
            Day d = day(bot.getId());
            synchronized (d) {
                if (d.tickets >= perDay) {
                    return note(bot, "has played its " + perDay + " tickets today");
                }
            }
            long ceiling = BotWealth.ceiling(bot);
            long keep = overCeiling ? 0 : ceiling / 2;
            if ((long) bot.getMeso() - price < keep) {
                return note(bot, String.format("would not keep %,d after a %,d ticket", keep, price));
            }
            if (!GachaMachine.botHasRoom(bot)) {
                return note(bot, "has no room for a prize");
            }
            if (!hourAllows()) {
                return note(bot, "world bot ticket limit for this hour reached");
            }
            if (price > Integer.MAX_VALUE || !BotHoldings.spendMeso(bot, (int) price)) {
                hourRelease();
                return note(bot, "could not pay for a ticket");
            }
            synchronized (d) {
                d.tickets++;
            }
            GachaStats.botTicket(price);
        } else if (!GachaMachine.botHasRoom(bot)) {
            return note(bot, "has no room for a prize");
        }
        GachaMachine.Result r = GachaMachine.playForBot(bot, town, force ? "gm roll" : "bot ticket", rng);
        String line;
        if (!r.ok()) {
            // paid but the prize did not land (a tab filled in between): the mesos stay burned, like a lost ticket
            line = String.format("%s bought a ticket at %s but the prize did not land: %s", bot.getName(), town.name(),
                    r.refusal());
        } else {
            line = String.format("%s %s at %s: %s %s x%d%s; holds %,d", bot.getName(),
                    force ? "rolled (GM, free)" : "bought a " + String.format("%,d", price) + " ticket",
                    town.name(), r.prize().tier().key(), GachaMachine.nameOf(r.prize().itemId()), r.prize().quantity(),
                    r.announced() ? " (announced)" : "", bot.getMeso());
        }
        log.info("[BotGacha] {}", line);
        if (QaLog.on(QaLog.Topic.GACHA)) {
            QaLog.any(QaLog.Topic.GACHA, line);
        }
        return line;
    }

    private static String note(Character bot, String why) {
        return bot.getName() + " did not play: " + why;
    }

    /** "3 of 11 tickets today" for one bot. */
    public static int ticketsToday(int botId) {
        Day d = day(botId);
        synchronized (d) {
            return d.tickets;
        }
    }

    private static Day day(int botId) {
        Day d = days.computeIfAbsent(botId, id -> new Day());
        synchronized (d) {
            long today = dayClock.getAsLong();
            if (d.epochDay != today) {
                d.epochDay = today;
                d.tickets = 0;
            }
        }
        return d;
    }

    private static synchronized boolean hourAllows() {
        long now = clock.getAsLong();
        if (now - hourStart.get() >= 3_600_000L) {
            hourStart.set(now);
            hourCount.set(0);
        }
        if (hourCount.get() >= Math.max(0, Integer.getInteger("solomapling.botGachaPerHour", DEFAULT_PER_HOUR))) {
            return false;
        }
        hourCount.incrementAndGet();
        return true;
    }

    private static synchronized void hourRelease() {
        hourCount.updateAndGet(c -> Math.max(0, c - 1));
    }

    private static double doubleProperty(String key, double fallback) {
        String v = System.getProperty(key);
        try {
            return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Test seam: clocks (null = real) and a clean slate. */
    static void resetForTest(LongSupplier testClock, LongSupplier testDay) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
        dayClock = testDay == null ? () -> LocalDate.now().toEpochDay() : testDay;
        days.clear();
        hourStart.set(0);
        hourCount.set(0);
    }
}
