package soloMapling.Economy.exchange.volatility;

import java.util.concurrent.TimeUnit;

/**
 * 1.6.1 volatility (owner 2026-10-28: "how do we make this shit volatile as fuck"): the knobs. JVM properties only
 * (config.yaml is strict). All three parts are off by default (live), on in TEST's start script.
 * <pre>
 *   -Dslums.exchange.traders=false            trader bots: a few regulars trade the item tickers with their own
 *                                             leaves and mesos (needs -Dslums.exchange=true)
 *   -Dslums.exchange.traders.pct=0            1.6.2: this share of the bots in the game are traders (25..50 when
 *                                             given); 0 keeps the count below (the 1.6.1 behaviour)
 *   -Dslums.exchange.traders.count=16         how many bots of the roster are traders (Achiever/Killer-leaning); with
 *                                             pct given it is a hard cap (default 200: the pct decides)
 *   -Dslums.exchange.traders.nx=false         1.6.2: trader bots also trade NX for mesos inside the NX band (they
 *                                             sell only the NX they bought: nothing is granted). 1.6.3: with the NX
 *                                             desk on they trade at the desk's quotes instead of the order book.
 *   -Dslums.exchange.traders.nx.perBotHour=2000  1.6.3: NX one trader bot may trade at the desk in an hour, both
 *                                             sides together (0: bots do not trade the desk)
 *   -Dslums.exchange.traders.nx.worldHour=20000  1.6.3: the same for all traders together, whatever the population
 *   -Dslums.exchange.traders.minMinutes=4     each trader acts every min..max minutes (per-bot jitter)
 *   -Dslums.exchange.traders.maxMinutes=12
 *   -Dslums.market.events=false               market events: Leaf Rush, Leaf Blight, Rooty's Bonus (GM tools) and the
 *                                             guild-war market news Cody announces
 *   -Dslums.market.events.random=false        also start events at random (owner 2026-10-28: off; GM-only tools)
 *   -Dslums.market.events.perWeek=2           random events a week (leaf class), on average, when random is on
 *   -Dslums.market.events.announcePct=60      how many random events Cody announces (the rest are secret)
 *   -Dslums.market.leafRushMultiplier=3       Leaf Rush: Maple Leaf drop chance x this
 *   -Dslums.market.rootyBonusLeaves=40        Rooty's Bonus: leaves for one Chaos Scroll 60% at Rooty
 *   -Dslums.market.rooty.cap=2                Rooty's Bonus: Chaos Scrolls per character per bonus event
 *   -Dslums.market.rumours=false              market rumours in bot chat and megaphones
 *   -Dslums.market.rumours.gapMinutes=20      at least this long between two rumours (world-wide)
 *   -Dslums.market.rumours.fakePct=35         chance a rumour is made up
 *   -Dslums.market.rumours.megaphonePct=30    chance a rumour goes out as a megaphone (else map chat)
 *   -Dslums.market.rumours.actPct=40          chance a trader that heard a rumour acts on it
 * </pre>
 */
public record MarketConfig(boolean traders, int traderCount, long minActMs, long maxActMs, boolean events,
                           double eventsPerWeek, int announcePct, double leafRushMultiplier, int rootyBonusLeaves,
                           boolean rumours, long rumourGapMs, int fakePct, int megaphonePct, int actOnRumourPct,
                           boolean randomEvents, int rootyCap, int tradersPct, boolean tradersNx) {

    public static final String TRADERS = "slums.exchange.traders";
    public static final String EVENTS = "slums.market.events";
    public static final String RUMOURS = "slums.market.rumours";
    /** How many of the bots in the game are traders (25..50; 0: the fixed {@code traderCount}, as in 1.6.1). */
    public static final String TRADERS_PCT = TRADERS + ".pct";
    /** A hard cap on the traders the share may name; without it a share covers every bot online. */
    public static final String TRADERS_COUNT = TRADERS + ".count";
    /** Trader bots trade NX too (1.6.2; off: the item tickers only, as in 1.6.1). */
    public static final String TRADERS_NX = TRADERS + ".nx";
    /**
     * 1.6.3 (b260): how much NX one trader bot may trade at the NX desk in one hour, buys and sells together, so a
     * bot cannot walk the rate with its own purse. 0 stops the bots trading the desk at all.
     */
    public static final String TRADERS_NX_PER_BOT_HOUR = TRADERS_NX + ".perBotHour";
    /**
     * 1.6.3 (b260): the same for all the trader bots together, the cap that keeps the whole bot population from
     * moving the rate whatever its size (at 20,000 NX an hour, a day at the cap is under 2.4% of the desk's rate).
     */
    public static final String TRADERS_NX_WORLD_HOUR = TRADERS_NX + ".worldHour";
    /** Defaults: 2,000 NX an hour for one bot, 20,000 for the world (see {@link #TRADERS_NX_WORLD_HOUR}). */
    public static final long BOT_NX_PER_BOT_HOUR = 2_000;
    public static final long BOT_NX_WORLD_HOUR = 20_000;
    /**
     * How much of the real drop chance a trader bot's off-screen scroll find uses (owner 2026-10-31 ~19:50 UTC: a
     * quarter, so CHAOS and WS stay rare instead of flooding the books; 1 = the real rate, 0 = no finds at all).
     */
    public static final String SCROLL_FIND_RATE = "slums.exchange.scrollFindRate";

    /** The 1.6.1 shape: a fixed count of traders, item tickers only. */
    public MarketConfig(boolean traders, int traderCount, long minActMs, long maxActMs, boolean events,
                        double eventsPerWeek, int announcePct, double leafRushMultiplier, int rootyBonusLeaves,
                        boolean rumours, long rumourGapMs, int fakePct, int megaphonePct, int actOnRumourPct,
                        boolean randomEvents, int rootyCap) {
        this(traders, traderCount, minActMs, maxActMs, events, eventsPerWeek, announcePct, leafRushMultiplier,
                rootyBonusLeaves, rumours, rumourGapMs, fakePct, megaphonePct, actOnRumourPct, randomEvents, rootyCap, 0,
                false);
    }

    public MarketConfig {
        tradersPct = tradersPct <= 0 ? 0 : Math.max(25, Math.min(50, tradersPct));
        // 1.6.2: with a share given, the roster decides and 200 is too small a cap (3,000 bots at 35% is 1,050); the
        // bound is a sanity limit, not an economy one
        traderCount = Math.max(0, Math.min(MAX_TRADERS, traderCount));
        minActMs = Math.max(TimeUnit.SECONDS.toMillis(30), minActMs);
        maxActMs = Math.max(minActMs, maxActMs);
        eventsPerWeek = Math.max(0, eventsPerWeek);
        announcePct = pct(announcePct);
        leafRushMultiplier = Math.max(1, Math.min(20, leafRushMultiplier));
        rootyBonusLeaves = Math.max(1, rootyBonusLeaves);
        rumourGapMs = Math.max(TimeUnit.MINUTES.toMillis(1), rumourGapMs);
        fakePct = pct(fakePct);
        megaphonePct = pct(megaphonePct);
        actOnRumourPct = pct(actOnRumourPct);
        rootyCap = Math.max(0, rootyCap);
    }

    /** The sanity bound on traders (see {@link #traderCount}). */
    public static final int MAX_TRADERS = 5_000;

    /** The same with random events on or off (tests, the simulation). */
    public MarketConfig withRandom(boolean on) {
        return new MarketConfig(traders, traderCount, minActMs, maxActMs, events, eventsPerWeek, announcePct,
                leafRushMultiplier, rootyBonusLeaves, rumours, rumourGapMs, fakePct, megaphonePct, actOnRumourPct, on,
                rootyCap, tradersPct, tradersNx);
    }

    /** The same with a different trader count and share (tests: the share's arithmetic without a 1,000-bot roster). */
    public MarketConfig withTraders(int count, int pct, boolean nx) {
        return new MarketConfig(traders, count, minActMs, maxActMs, events, eventsPerWeek, announcePct,
                leafRushMultiplier, rootyBonusLeaves, rumours, rumourGapMs, fakePct, megaphonePct, actOnRumourPct,
                randomEvents, rootyCap, pct, nx);
    }

    private static int pct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /** Every part on with the defaults (tests, the TEST server); random events stay off, as on TEST. */
    public static MarketConfig allOn() {
        return new MarketConfig(true, 16, TimeUnit.MINUTES.toMillis(4), TimeUnit.MINUTES.toMillis(12), true, 2, 60, 3,
                40, true, TimeUnit.MINUTES.toMillis(20), 35, 30, 40, false, 2, 0, false);
    }

    public static MarketConfig fromSystem() {
        MarketConfig d = allOn();
        int tradersPct = (int) l(TRADERS_PCT, 0);
        // the count is the 1.6.1 knob; with a share, it is only a cap and nobody set one, so the share decides
        long count = System.getProperty(TRADERS_COUNT) != null ? l(TRADERS_COUNT, d.traderCount)
                : tradersPct > 0 ? MAX_TRADERS : d.traderCount;
        return new MarketConfig(Boolean.getBoolean(TRADERS), (int) count,
                TimeUnit.MINUTES.toMillis(l(TRADERS + ".minMinutes", 4)),
                TimeUnit.MINUTES.toMillis(l(TRADERS + ".maxMinutes", 12)),
                Boolean.getBoolean(EVENTS), dbl(EVENTS + ".perWeek", d.eventsPerWeek),
                (int) l(EVENTS + ".announcePct", d.announcePct), dbl("slums.market.leafRushMultiplier",
                d.leafRushMultiplier), (int) l("slums.market.rootyBonusLeaves", d.rootyBonusLeaves),
                Boolean.getBoolean(RUMOURS), TimeUnit.MINUTES.toMillis(l(RUMOURS + ".gapMinutes", 20)),
                (int) l(RUMOURS + ".fakePct", d.fakePct), (int) l(RUMOURS + ".megaphonePct", d.megaphonePct),
                (int) l(RUMOURS + ".actPct", d.actOnRumourPct), Boolean.getBoolean(EVENTS + ".random"),
                (int) l("slums.market.rooty.cap", d.rootyCap), tradersPct, Boolean.getBoolean(TRADERS_NX));
    }

    /** Trader bots trade NX too (read where the exchange itself has only the flag: {@code placeBot}). */
    public static boolean tradersNxOn() {
        return Boolean.getBoolean(TRADERS_NX);
    }

    /** One bot's hourly desk allowance in NX, both sides together ({@link #TRADERS_NX_PER_BOT_HOUR}). */
    public static long tradersNxPerBotHour() {
        return Math.max(0, l(TRADERS_NX_PER_BOT_HOUR, BOT_NX_PER_BOT_HOUR));
    }

    /** All the trader bots' hourly desk allowance in NX together ({@link #TRADERS_NX_WORLD_HOUR}). */
    public static long tradersNxWorldHour() {
        return Math.max(0, l(TRADERS_NX_WORLD_HOUR, BOT_NX_WORLD_HOUR));
    }

    /**
     * The share of the real drop chance a trader's off-screen scroll find uses, 0..1 ({@link #SCROLL_FIND_RATE}): a
     * quarter unless the JVM says otherwise, never more than the real rate.
     */
    public static double scrollFindRate() {
        return Math.max(0, Math.min(1, dbl(SCROLL_FIND_RATE, 0.25)));
    }

    private static long l(String key, long dflt) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? dflt : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static double dbl(String key, double dflt) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? dflt : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
