package soloMapling.Economy.exchange.volatility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 1.6.1 volatility: market events, real supply and demand shocks on the item tickers (behind
 * {@code -Dslums.market.events}). Each changes the game itself, never the exchange: the exchange still mints and burns
 * nothing.
 * <pre>
 *   LEAF_RUSH    2 h   the Maple Leaf global drop chance x3 (-Dslums.market.leafRushMultiplier): a supply flood
 *   LEAF_BLIGHT  24 h  Maple Leaves stop dropping from monsters: a supply drought
 *   ROOTY_BONUS  24 h  Rooty's Maple Weapons menu adds "Rooty's Bonus": a Chaos Scroll 60% for 40 Maple Leaves
 *                      (-Dslums.market.rootyBonusLeaves): a new leaf sink, so a demand spike on LEAF (and more Chaos
 *                      Scrolls for CHAOS)
 * </pre>
 * Only the leaf class has events for now: CHAOS, WS and ELIXIR have no clean lever in existing content (Coco's chaos
 * craft is switched off on every server; the scrolls come from gachapon and boss prizes). Rush and Blight end each
 * other. Random events come about {@code perWeek} times a week; {@code announcePct} of them Cody announces in game (a
 * blue server notice, never Discord), the rest are secret: traders learn of them only from prices and rumours. Events
 * live in memory only: a restart ends them. {@code !market event|events|end} triggers and lists them.
 */
public final class MarketEvents {

    private static final Logger log = LoggerFactory.getLogger(MarketEvents.class);

    public static final int MAPLE_LEAF = 4001126;
    public static final int CHAOS_SCROLL = 2049100;
    static final long WEEK_MS = 7L * 24 * 3_600_000L;

    public enum Kind {
        LEAF_RUSH("Leaf Rush", "LEAF", 120, 40),
        LEAF_BLIGHT("Leaf Blight", "LEAF", 24 * 60, 25),
        ROOTY_BONUS("Rooty's Bonus", "LEAF", 24 * 60, 35);

        public final String label;
        public final String ticker;
        public final int defaultMinutes;
        final int weight;

        Kind(String label, String ticker, int defaultMinutes, int weight) {
            this.label = label;
            this.ticker = ticker;
            this.defaultMinutes = defaultMinutes;
            this.weight = weight;
        }

        /** "rush", "leaf_rush", "Leaf Rush", "blight", "rooty", "bonus" -> the kind; null when unknown. */
        public static Kind parse(String s) {
            if (s == null) {
                return null;
            }
            String k = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("'", "");
            return switch (k) {
                case "rush", "leaf_rush", "leafrush" -> LEAF_RUSH;
                case "blight", "leaf_blight", "leafblight", "drought" -> LEAF_BLIGHT;
                case "rooty", "bonus", "rooty_bonus", "rootys_bonus", "rootybonus" -> ROOTY_BONUS;
                default -> null;
            };
        }
    }

    /** One running event. */
    public record Active(Kind kind, long startedAt, long endsAt, boolean announced) {
        public long minutesLeft(long now) {
            return Math.max(0, (endsAt - now + 59_999) / 60_000);
        }
    }

    private static volatile MarketEvents instance;

    private final Map<Kind, Active> active = new EnumMap<>(Kind.class);
    private final List<Active> history = new ArrayList<>();
    private volatile MarketConfig config;
    private final LongSupplier clock;
    private final Consumer<String> announcer;

    MarketEvents(MarketConfig config, LongSupplier clock, Consumer<String> announcer) {
        this.config = config;
        this.clock = clock;
        this.announcer = announcer;
    }

    /** The server's events (always there; inert while {@code -Dslums.market.events} is off). */
    public static MarketEvents get() {
        MarketEvents m = instance;
        if (m == null) {
            synchronized (MarketEvents.class) {
                if (instance == null) {
                    instance = new MarketEvents(MarketConfig.fromSystem(), System::currentTimeMillis,
                            MarketEvents::serverNotice);
                }
                m = instance;
            }
        }
        return m;
    }

    /** Test seam: replace (or with null, drop) the server's events. */
    public static void useForTest(MarketEvents m) {
        instance = m;
    }

    /** Test seam: events with their own config, clock and notice sink (install with {@link #useForTest}). */
    public static MarketEvents forTest(MarketConfig c, LongSupplier clock, Consumer<String> announcer) {
        return new MarketEvents(c, clock, announcer);
    }

    public MarketConfig config() {
        return config;
    }

    void setConfig(MarketConfig c) {
        config = c;
    }

    public boolean enabled() {
        return config.events();
    }

    // ---------------------------------------------------------------- game hooks (static, never throw)

    /**
     * The Maple Leaf's global drop chance (out of 1,000,000) right now: x the Rush multiplier, 0 in a Blight. Every
     * other item and every chance while events are off pass through unchanged. Called from the monster drop roll.
     */
    public static int globalDropChance(int itemId, int chance) {
        if (itemId != MAPLE_LEAF) {
            return chance;
        }
        try {
            MarketEvents m = instance;
            return m == null ? chance : m.leafChance(chance, m.clock.getAsLong());
        } catch (RuntimeException e) {
            return chance;
        }
    }

    /** Leaves Rooty asks for a Chaos Scroll 60% while Rooty's Bonus runs, else 0 (rooty.js). */
    public static int rootyBonusLeaves() {
        try {
            MarketEvents m = instance;
            return m != null && m.isActive(Kind.ROOTY_BONUS, m.clock.getAsLong()) ? m.config.rootyBonusLeaves() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Minutes of Rooty's Bonus left (rooty.js's line), 0 when none. */
    public static long rootyBonusMinutesLeft() {
        MarketEvents m = instance;
        if (m == null) {
            return 0;
        }
        long now = m.clock.getAsLong();
        Active a = m.activeOf(Kind.ROOTY_BONUS, now);
        return a == null ? 0 : a.minutesLeft(now);
    }

    // ---------------------------------------------------------------- state

    /** The leaf chance under the running events. */
    public synchronized int leafChance(int chance, long now) {
        if (!config.events()) {
            return chance;
        }
        prune(now);
        if (active.containsKey(Kind.LEAF_BLIGHT)) {
            return 0;
        }
        if (active.containsKey(Kind.LEAF_RUSH)) {
            return (int) Math.min(1_000_000L, Math.round(chance * config.leafRushMultiplier()));
        }
        return chance;
    }

    public synchronized boolean isActive(Kind k, long now) {
        prune(now);
        return config.events() && active.containsKey(k);
    }

    public synchronized Active activeOf(Kind k, long now) {
        prune(now);
        return config.events() ? active.get(k) : null;
    }

    /** The running events, soonest to end first. */
    public synchronized List<Active> active(long now) {
        prune(now);
        List<Active> out = new ArrayList<>(active.values());
        out.sort(java.util.Comparator.comparingLong(Active::endsAt));
        return out;
    }

    /** The last events (newest last), for {@code !market events}. */
    public synchronized List<Active> history() {
        return new ArrayList<>(history);
    }

    /**
     * Starts {@code k} for {@code minutes} (its default when 0 or less), announced or secret. A running event of the
     * same kind is extended to the later end; Rush and Blight end each other. Null when events are off.
     */
    public synchronized Active start(Kind k, int minutes, boolean announce, long now) {
        if (!config.events() || k == null) {
            return null;
        }
        prune(now);
        Kind opposite = k == Kind.LEAF_RUSH ? Kind.LEAF_BLIGHT : k == Kind.LEAF_BLIGHT ? Kind.LEAF_RUSH : null;
        if (opposite != null && active.containsKey(opposite)) {
            end(opposite, now);
        }
        long len = (minutes > 0 ? minutes : k.defaultMinutes) * 60_000L;
        Active old = active.get(k);
        Active a = new Active(k, old == null ? now : old.startedAt(), Math.max(now + len, old == null ? 0
                : old.endsAt()), announce || (old != null && old.announced()));
        active.put(k, a);
        history.add(a);
        while (history.size() > 50) {
            history.remove(0);
        }
        log.info("Market event {} on until {} ({})", k, java.time.Instant.ofEpochMilli(a.endsAt()),
                a.announced() ? "announced" : "secret");
        if (announce) {
            say(startLine(a, now));
        }
        return a;
    }

    /** Ends {@code k} now (true when it was running). */
    public synchronized boolean end(Kind k, long now) {
        Active a = active.remove(k);
        if (a == null) {
            return false;
        }
        log.info("Market event {} ended", k);
        if (a.announced()) {
            say(endLine(k));
        }
        return true;
    }

    private void prune(long now) {
        List<Kind> done = new ArrayList<>();
        for (Active a : active.values()) {
            if (a.endsAt() <= now) {
                done.add(a.kind());
            }
        }
        for (Kind k : done) {
            end(k, now);
        }
    }

    /**
     * The random schedule, called each tick: with probability perWeek x tick / week an event of a random kind (by
     * weight, not one already running) starts, announced {@code announcePct}% of the time. Returns it, or null.
     */
    public synchronized Active maybeRandom(long now, long tickMs, Random rng) {
        if (!config.events() || config.eventsPerWeek() <= 0) {
            return null;
        }
        prune(now);
        double p = config.eventsPerWeek() * (double) tickMs / WEEK_MS;
        if (rng.nextDouble() >= p) {
            return null;
        }
        int total = 0;
        for (Kind k : Kind.values()) {
            total += active.containsKey(k) ? 0 : k.weight;
        }
        if (total <= 0) {
            return null;
        }
        int r = rng.nextInt(total);
        for (Kind k : Kind.values()) {
            if (active.containsKey(k)) {
                continue;
            }
            r -= k.weight;
            if (r < 0) {
                return start(k, 0, rng.nextInt(100) < config.announcePct(), now);
            }
        }
        return null;
    }

    /**
     * Where an event pushes a ticker's price: +1 up, -1 down, 0 none (traders' reading of the event they know of).
     * Rush floods leaves (down); Blight dries them up (up); Rooty's Bonus makes leaves wanted (up) and adds Chaos
     * Scrolls (down a little).
     */
    public static double pressure(Kind k, String ticker) {
        return switch (k) {
            case LEAF_RUSH -> "LEAF".equals(ticker) ? -1 : 0;
            case LEAF_BLIGHT -> "LEAF".equals(ticker) ? 1 : 0;
            case ROOTY_BONUS -> "LEAF".equals(ticker) ? 1 : "CHAOS".equals(ticker) ? -0.5 : 0;
        };
    }

    /** The pressure of the running events on a ticker; {@code knownOnly}: only the announced ones. */
    public synchronized double pressure(String ticker, long now, boolean knownOnly) {
        prune(now);
        double p = 0;
        for (Active a : active.values()) {
            if (!knownOnly || a.announced()) {
                p += pressure(a.kind(), ticker);
            }
        }
        return p;
    }

    String startLine(Active a, long now) {
        long h = Math.max(1, Math.round((a.endsAt() - now) / 3_600_000.0));
        String hours = h == 1 ? "the next hour" : "the next " + h + " hours";
        return switch (a.kind()) {
            case LEAF_RUSH -> "[Cody] Leaf Rush! Monsters drop Maple Leaves " + trim(config.leafRushMultiplier())
                    + " times as often for " + hours + ".";
            case LEAF_BLIGHT -> "[Cody] Leaf Blight! Maple Leaves stop dropping from monsters for " + hours + ".";
            case ROOTY_BONUS -> "[Cody] Rooty's Bonus! For " + hours + " Rooty trades " + config.rootyBonusLeaves()
                    + " Maple Leaves for a Chaos Scroll 60% (Maple Weapons menu).";
        };
    }

    static String endLine(Kind k) {
        return "[Cody] " + k.label + " is over.";
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private void say(String line) {
        try {
            announcer.accept(line);
        } catch (RuntimeException e) {
            log.warn("Market event notice failed: {}", line, e);
        }
    }

    /** A blue server notice to the home world: in game only (WorldEventFeed is not used, so Discord never sees it). */
    static void serverNotice(String text) {
        net.server.Server.getInstance().broadcastMessage(
                soloMapling.server.SoloMaplingConstants.GameConstants.homeWorld(),
                tools.PacketCreator.serverNotice(6, text));
    }
}
