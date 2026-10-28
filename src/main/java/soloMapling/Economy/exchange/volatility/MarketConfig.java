package soloMapling.Economy.exchange.volatility;

import java.util.concurrent.TimeUnit;

/**
 * 1.6.1 volatility (owner 2026-10-28: "how do we make this shit volatile as fuck"): the knobs. JVM properties only
 * (config.yaml is strict). All three parts are off by default (live), on in TEST's start script.
 * <pre>
 *   -Dslums.exchange.traders=false            trader bots: a few regulars trade the item tickers with their own
 *                                             leaves and mesos (needs -Dslums.exchange=true)
 *   -Dslums.exchange.traders.count=16         how many bots of the roster are traders (Achiever/Killer-leaning)
 *   -Dslums.exchange.traders.minMinutes=4     each trader acts every min..max minutes (per-bot jitter)
 *   -Dslums.exchange.traders.maxMinutes=12
 *   -Dslums.market.events=false               market events: Leaf Rush, Leaf Blight, Rooty's Bonus (random + GM)
 *   -Dslums.market.events.perWeek=2           random events a week (leaf class), on average
 *   -Dslums.market.events.announcePct=60      how many random events Cody announces (the rest are secret)
 *   -Dslums.market.leafRushMultiplier=3       Leaf Rush: Maple Leaf drop chance x this
 *   -Dslums.market.rootyBonusLeaves=40        Rooty's Bonus: leaves for one Chaos Scroll 60% at Rooty
 *   -Dslums.market.rumours=false              market rumours in bot chat and megaphones
 *   -Dslums.market.rumours.gapMinutes=20      at least this long between two rumours (world-wide)
 *   -Dslums.market.rumours.fakePct=35         chance a rumour is made up
 *   -Dslums.market.rumours.megaphonePct=30    chance a rumour goes out as a megaphone (else map chat)
 *   -Dslums.market.rumours.actPct=40          chance a trader that heard a rumour acts on it
 * </pre>
 */
public record MarketConfig(boolean traders, int traderCount, long minActMs, long maxActMs, boolean events,
                           double eventsPerWeek, int announcePct, double leafRushMultiplier, int rootyBonusLeaves,
                           boolean rumours, long rumourGapMs, int fakePct, int megaphonePct, int actOnRumourPct) {

    public static final String TRADERS = "slums.exchange.traders";
    public static final String EVENTS = "slums.market.events";
    public static final String RUMOURS = "slums.market.rumours";

    public MarketConfig {
        traderCount = Math.max(0, Math.min(200, traderCount));
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
    }

    private static int pct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /** Everything on, the defaults (tests, the TEST server). */
    public static MarketConfig allOn() {
        return new MarketConfig(true, 16, TimeUnit.MINUTES.toMillis(4), TimeUnit.MINUTES.toMillis(12), true, 2, 60, 3,
                40, true, TimeUnit.MINUTES.toMillis(20), 35, 30, 40);
    }

    public static MarketConfig fromSystem() {
        MarketConfig d = allOn();
        return new MarketConfig(Boolean.getBoolean(TRADERS), (int) l(TRADERS + ".count", d.traderCount),
                TimeUnit.MINUTES.toMillis(l(TRADERS + ".minMinutes", 4)),
                TimeUnit.MINUTES.toMillis(l(TRADERS + ".maxMinutes", 12)),
                Boolean.getBoolean(EVENTS), dbl(EVENTS + ".perWeek", d.eventsPerWeek),
                (int) l(EVENTS + ".announcePct", d.announcePct), dbl("slums.market.leafRushMultiplier",
                d.leafRushMultiplier), (int) l("slums.market.rootyBonusLeaves", d.rootyBonusLeaves),
                Boolean.getBoolean(RUMOURS), TimeUnit.MINUTES.toMillis(l(RUMOURS + ".gapMinutes", 20)),
                (int) l(RUMOURS + ".fakePct", d.fakePct), (int) l(RUMOURS + ".megaphonePct", d.megaphonePct),
                (int) l(RUMOURS + ".actPct", d.actOnRumourPct));
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
