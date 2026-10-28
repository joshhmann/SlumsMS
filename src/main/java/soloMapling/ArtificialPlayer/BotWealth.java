package soloMapling.ArtificialPlayer;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.Gachapon.BotGacha;
import soloMapling.qa.QaLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Ticket 56B (1.5.0): a soft ceiling on how many mesos one bot holds, scaled by its level, so bot wealth does not
 * pile up and inflate the economy.
 * <p>
 * <b>The ceiling</b> is {@code max(floor, perLevelSq x level^2) x the world's meso rate}: at 10x, 1.08m at level 30,
 * 5.9m at 70, 9.7m at 90, 17.3m at 120 (ticket 56's "3x a band's savings goal").
 * <p>
 * <b>Above it</b> a bot stops hoarding and spends:
 * <ul>
 *   <li><b>Intake slows, then stops.</b> Mob mesos it takes from the world ({@link BotYieldCaps}) are cut in
 *   proportion as it climbs from the ceiling to the hard line ({@code hardFactor} x the ceiling, default 2x), and
 *   stop at the hard line: a meso drop it refuses stays on the floor for a player, an offscreen share is
 *   forfeited.</li>
 *   <li><b>Scrolls:</b> half of what it holds over the ceiling is added to its daily scroll budget
 *   ({@code ScrollBudget}), still one scroll at a time and at most 12 a day.</li>
 *   <li><b>Gachapon:</b> standing in a town with a machine, it buys tickets ({@link BotGacha}), more of them than a
 *   bot under its ceiling would.</li>
 *   <li><b>Upkeep:</b> at a town stop at most every {@code decayMinutes} (10), it loses {@code decayShare} (10%) of
 *   what is still over the ceiling: repairs, fees, things it bought and lost. Burned, ledgered, never paid to
 *   anyone. Repeated stops bring it back to within 1% of the ceiling, where upkeep stops.</li>
 * </ul>
 * Potions and ammo ({@code BotShopping}) are not limited by a rich bot's mesos already (its kit size is), so they
 * are unchanged. FM shopper bots hold nothing between purchases ({@code FMBotWallet}) and are never over.
 * <p>
 * 1.6.0: the whole ceiling is multiplied by {@link PlayerWealthIndex#multiplier()} (1 to 10) so bots track how rich
 * real players are.
 * <p>
 * Tuned with {@code -Dsolomapling.*} properties (the server's YAML is strict): {@code botWealthCeiling=false}
 * switches it off; {@code botWealthPerLevelSq} (120), {@code botWealthFloor} (100000), both per 1x meso rate;
 * {@code botWealthHardFactor} (2.0); {@code botWealthDecayShare} (0.10); {@code botWealthDecayMinutes} (10).
 */
public final class BotWealth {

    private static final Logger log = LoggerFactory.getLogger(BotWealth.class);

    static final long DEFAULT_PER_LEVEL_SQ = 120;
    static final long DEFAULT_FLOOR = 100_000;
    static final double DEFAULT_HARD_FACTOR = 2.0;
    static final double DEFAULT_DECAY_SHARE = 0.10;
    static final long DEFAULT_DECAY_MINUTES = 10;
    static final long STEP_MIN_MS = 45_000, STEP_SPREAD_MS = 45_000;

    /** One bot's spending pace. Guarded by its own monitor. */
    static final class State {
        long nextStepAt;
        long nextDecayAt;
        long paidTotal; // upkeep this bot has paid since the server started (the bot watch nets it out)
    }

    private static final Map<Integer, State> states = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    // ledger since the server started
    static final AtomicLong intakeRefused = new AtomicLong();       // meso drops left on the floor
    static final AtomicLong intakeCutMesos = new AtomicLong();      // offscreen mesos not credited
    static final AtomicLong decayed = new AtomicLong();             // mesos burned as upkeep
    static final AtomicLong decayEvents = new AtomicLong();

    private BotWealth() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.botWealthCeiling", "true"));
    }

    // ---- the ceiling ----

    /** The ceiling at a level and meso rate, scaled by the player wealth index's multiplier (1.6.0, never under 1). */
    public static long ceiling(int level, int mesoRate) {
        double mult = PlayerWealthIndex.multiplier();
        long base = baseCeiling(level, mesoRate);
        return mult <= 1.0 ? base : (long) Math.min(Long.MAX_VALUE / 4.0, base * mult);
    }

    /** The formula alone, without the player wealth multiplier: the yardstick the index measures players by. */
    static long baseCeiling(int level, int mesoRate) {
        long perSq = Math.max(0, Long.getLong("solomapling.botWealthPerLevelSq", DEFAULT_PER_LEVEL_SQ));
        long floor = Math.max(0, Long.getLong("solomapling.botWealthFloor", DEFAULT_FLOOR));
        long lv = Math.max(1, level);
        long base = Math.max(floor, perSq * lv * lv);
        return base * Math.max(1, mesoRate);
    }

    public static long ceiling(Character bot) {
        return ceiling(bot.getLevel(), mesoRate(bot));
    }

    public static long hardLine(long ceiling) {
        double f = Math.max(1.0, doubleProperty("solomapling.botWealthHardFactor", DEFAULT_HARD_FACTOR));
        return (long) Math.min(Long.MAX_VALUE / 2.0, ceiling * f);
    }

    /** 1 at or under the ceiling, falling to 0 at the hard line. */
    public static double intakeFactor(long held, long ceiling) {
        if (held <= ceiling) {
            return 1.0;
        }
        long hard = hardLine(ceiling);
        if (held >= hard || hard <= ceiling) {
            return 0.0;
        }
        return 1.0 - (double) (held - ceiling) / (hard - ceiling);
    }

    /** How far over its ceiling the bot is (0 when under, or when this is not a bot or the ceiling is off). */
    public static long excess(Character bot) {
        if (!applies(bot)) {
            return 0;
        }
        return Math.max(0, (long) bot.getMeso() - ceiling(bot));
    }

    static boolean applies(Character chr) {
        return chr != null && enabled() && BotHelpers.isBot(chr);
    }

    /**
     * Extra scroll budget for a bot over its ceiling (half of the excess), with no character at hand; 0 when the
     * world's meso rate is not known (no server).
     */
    public static long scrollBonus(int level, long held) {
        int rate = worldMesoRate();
        if (!enabled() || rate <= 0) {
            return 0;
        }
        return Math.max(0, (held - ceiling(level, rate)) / 2);
    }

    // ---- intake (called by BotYieldCaps) ----

    /** Whether a bot may walk to a meso drop at all: not once it stands at its hard line. */
    static boolean mayTakeMesos(Character bot) {
        if (!applies(bot)) {
            return true;
        }
        return intakeFactor(bot.getMeso(), ceiling(bot)) > 0;
    }

    /** A meso drop on the floor: all or nothing, taken with the intake factor's chance. */
    static boolean takeDropMesos(Character bot, Random rng) {
        if (!applies(bot)) {
            return true;
        }
        double f = intakeFactor(bot.getMeso(), ceiling(bot));
        if (f >= 1.0) {
            return true;
        }
        if (f <= 0 || rng.nextDouble() >= f) {
            intakeRefused.incrementAndGet();
            return false;
        }
        return true;
    }

    /** An offscreen meso share: cut to the intake factor. */
    static long cutMesos(Character bot, long mesos) {
        if (mesos <= 0 || !applies(bot)) {
            return mesos;
        }
        double f = intakeFactor(bot.getMeso(), ceiling(bot));
        if (f >= 1.0) {
            return mesos;
        }
        long kept = (long) Math.floor(mesos * Math.max(0, f));
        intakeCutMesos.addAndGet(mesos - kept);
        return kept;
    }

    // ---- spending (TrainingBot's town stop) ----

    /**
     * A grinding bot standing in town (its town stop and store browse): about once a minute, a bot over its ceiling
     * plays the gachapon if its town has one, then pays upkeep on what is still over. A bot under it may still play
     * now and then, as a player would ({@link BotGacha}).
     */
    public static void townTick(Character bot) {
        if (bot == null || !BotHelpers.isBot(bot)) {
            return;
        }
        try {
            State st = states.computeIfAbsent(bot.getId(), id -> new State());
            long now = clock.getAsLong();
            synchronized (st) {
                if (now < st.nextStepAt) {
                    return;
                }
                st.nextStepAt = now + STEP_MIN_MS + ThreadLocalRandom.current().nextLong(STEP_SPREAD_MS);
            }
            step(bot, ThreadLocalRandom.current(), false);
        } catch (RuntimeException e) {
            log.warn("Bot wealth step failed for bot {} ({})", bot.getId(), bot.getName(), e);
        }
    }

    /** Mesos this bot has paid as wealth upkeep since the server started (0 if none). */
    public static long upkeepPaid(int botId) {
        State st = states.get(botId);
        if (st == null) {
            return 0;
        }
        synchronized (st) {
            return st.paidTotal;
        }
    }

    /** One spending step now; {@code forceDecay} ignores the upkeep pace (a GM or QA step). The lines it did. */
    public static List<String> step(Character bot, Random rng, boolean forceDecay) {
        List<String> out = new ArrayList<>();
        boolean over = enabled() && excess(bot) > 0;
        String played = BotGacha.maybePlay(bot, over, rng);
        if (played != null) {
            out.add(played);
        }
        if (!over || !upkeepDue(excess(bot), ceiling(bot))) {
            return out;
        }
        State st = states.computeIfAbsent(bot.getId(), id -> new State());
        long now = clock.getAsLong();
        synchronized (st) {
            if (!forceDecay && now < st.nextDecayAt) {
                return out;
            }
            st.nextDecayAt = now + Math.max(0, Long.getLong("solomapling.botWealthDecayMinutes",
                    DEFAULT_DECAY_MINUTES)) * 60_000L;
        }
        long excess = excess(bot);
        double share = Math.min(1.0, Math.max(0, doubleProperty("solomapling.botWealthDecayShare",
                DEFAULT_DECAY_SHARE)));
        long burn = upkeep(excess, ceiling(bot), share);
        if (burn > 0 && burn <= Integer.MAX_VALUE && BotHoldings.spendMeso(bot, (int) burn)) {
            synchronized (st) {
                st.paidTotal += burn;
            }
            decayed.addAndGet(burn);
            decayEvents.incrementAndGet();
            soloMapling.Economy.stats.EconomyCounters.recordUpkeep(burn);
            String line = String.format("%s paid %,d upkeep (%.0f%% of %,d over its ceiling %,d); holds %,d",
                    bot.getName(), burn, share * 100, excess, ceiling(bot), bot.getMeso());
            out.add(line);
            log.info("[Wealth] {}", line);
            if (QaLog.on(QaLog.Topic.WEALTH)) {
                QaLog.any(QaLog.Topic.WEALTH, line);
            }
        }
        return out;
    }

    /**
     * Whether a bot this far over its ceiling pays upkeep. rc6 (owner, QA t-wc-1): 10% of the excess never takes it
     * to zero, so a bot "over by 1,078 of 12,000,000" paid a few mesos forever. Within 1% of the ceiling it pays
     * nothing; the auto-QA ga-2 run calls that settled too ({@code QaScenarios.Ga2BotWealth.settled}).
     */
    public static boolean upkeepDue(long excess, long ceiling) {
        return excess > 0 && excess * 100 > ceiling;
    }

    /** The upkeep a bot pays on {@code excess} over {@code ceiling}: {@code share} of it, or 0 within 1%. */
    static long upkeep(long excess, long ceiling, double share) {
        return upkeepDue(excess, ceiling) ? (long) Math.ceil(excess * share) : 0;
    }

    // ---- read-outs ----

    /** "Lv 72 Bob: 14,200,000 held, ceiling 6,220,800 (hard 12,441,600): 228% of it, intake 0%, over by 7,979,200" */
    public static String line(Character bot) {
        long c = ceiling(bot);
        long held = bot.getMeso();
        double f = intakeFactor(held, c);
        return String.format("Lv %d %s: %,d held, ceiling %,d (hard %,d): %d%% of it, mob meso intake %d%%%s",
                bot.getLevel(), bot.getName(), held, c, hardLine(c), c == 0 ? 0 : Math.round(100.0 * held / c),
                Math.round(f * 100), held > c ? String.format(", over by %,d", held - c) : "");
    }

    /** The ceiling's ledger since the server started. */
    public static String ledgerLine() {
        return String.format("wealth ceiling %s: %,d meso drops refused, %,d offscreen mesos cut, %,d burned as upkeep"
                        + " (%,d times), %,d spent on gachapon tickets",
                enabled() ? "on" : "OFF", intakeRefused.get(), intakeCutMesos.get(), decayed.get(), decayEvents.get(),
                soloMapling.Gachapon.GachaStats.botTicketMesos());
    }

    // ---- helpers ----

    static int mesoRate(Character bot) {
        try {
            return Math.max(1, bot.getWorldServer().getMesoRate());
        } catch (RuntimeException e) {
            int w = worldMesoRate();
            return w > 0 ? w : 1;
        }
    }

    private static volatile int cachedWorldRate;

    /** The home world's meso rate (bots live there; Bera is world 1, there is no world 0), or 0 with no server. */
    static int worldMesoRate() {
        int r = cachedWorldRate;
        if (r > 0) {
            return r;
        }
        try {
            var world = net.server.Server.getInstance().getWorld(soloMapling.server.SoloMaplingConstants.GameConstants.homeWorld());
            if (world != null) {
                r = Math.max(1, world.getMesoRate());
                cachedWorldRate = r;
                return r;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no server in this process
        }
        return 0;
    }

    private static double doubleProperty(String key, double fallback) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Test seam: a clock (null = the system clock), a clean ledger and pace. */
    public static void resetForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
        states.clear();
        intakeRefused.set(0);
        intakeCutMesos.set(0);
        decayed.set(0);
        decayEvents.set(0);
        cachedWorldRate = 0;
    }
}
