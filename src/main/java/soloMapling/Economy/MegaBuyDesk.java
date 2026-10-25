package soloMapling.Economy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1.6.0 ticket 86 (owner: "Player can use a smega to request to purchase an item and a playerbot will whisper them").
 * A player's buy megaphone ("B> red whip 5m", "lf ilbis", "wtb 60% glove dex") reaches {@link #hear}, which
 * <ol>
 *   <li>parses it ({@link MegaBuyParser}) and ignores everything that is not a buy request (a sell megaphone never
 *   gets an offer: bots are not buyers here, and no new meso source is added);</li>
 *   <li>looks for bots that really have the item ({@link Stock}: a bag item a bot trades in person, a merchant bot's
 *   item for sale, an item on an open bot shop's shelf) and matches the words to exactly one item
 *   ({@link MegaBuyMatcher}); no stock, no answer, never an invented item;</li>
 *   <li>lets exactly one bot claim the request ({@link #claim}), after the rate limits ({@link Limits}) allow it;</li>
 *   <li>a human-like 20-120 s later, if the bot still has the item and the player is still around, the bot whispers
 *   one offer priced inside {@link PriceBand}'s sell band: it takes the player's stated price when the band allows,
 *   counters when it is close, and says nothing when the player is far below. A bag bot also remembers the price as a
 *   quote ({@link BotDirectTrade#rememberQuote}), so the existing trade and haggle flow carries on from there.</li>
 * </ol>
 * Every decision is logged as {@code [MegaBuy] ...} and, while a run listens, to the TRADE topic of the QA log.
 * The pieces that touch the game sit behind {@link World} so the logic is tested without a server.
 * <p>
 * The buy/sell grammar, the item-name rule (exact, else exactly one hit) and the one-bot-answers claim follow
 * nutnnut/Cosmic (722719702, b00401fda, 5342622c3). AGPL-3.0.
 */
public final class MegaBuyDesk {

    private static final Logger log = LoggerFactory.getLogger(MegaBuyDesk.class);

    public enum Source { BAG, MERCHANT, SHOP }

    /**
     * Something a bot has for sale. {@code ask}: the price it states (a merchant's or shop's posted price), 0 for a bag
     * item (the band decides). {@code where}: how the player gets there, as the bot says it. {@code handle}: the game
     * object behind it (the bag Item), opaque here.
     */
    public record Stock(Source source, int botId, String botName, int channel, int itemId, String itemName, int qty,
                        long fair, long ask, String where, Object handle) {
        Stock withFair(long f) {
            return new Stock(source, botId, botName, channel, itemId, itemName, qty, f, ask, where, handle);
        }
    }

    /** The player who shouted. */
    public record Asker(int id, String name, int channel, Object handle) {
    }

    public enum Outcome { NOT_A_BUY, DISABLED, RATE_LIMITED, NO_ITEM, NO_STOCK, FAR_BELOW, CLAIM_LOST, SCHEDULED }

    /** The game side: stock, whispers, quotes, timing. */
    public interface World {
        long now();

        double roll();

        /** A whole number in [lo, hi]. */
        int between(int lo, int hi);

        /** Run {@code task} after {@code delayMs}, off the game thread. */
        void later(long delayMs, Runnable task);

        /** Every sellable thing every online bot has, as it is now (called off the game thread). */
        List<Stock> stock(Asker asker);

        /** The same stock again right before the whisper: is the bot still there with the item. */
        boolean stillHas(Stock stock);

        boolean askerOnline(Asker asker);

        boolean friend(int botId, int playerId);

        /** What a bag item is worth (the price board's fair value for the whole stack); read only for the sellers. */
        long fairOf(Stock stock);

        /** Sends the whisper from the bot; false when it could not. */
        boolean whisper(Stock stock, Asker asker, String line);

        /** Remember the price a bag bot named, for the existing trade flow. */
        void quote(Stock stock, Asker asker, long price, int rounds);
    }

    // ---- tuning (system properties; defaults are the owner-agreed shape) ----

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.megaBuy", "true"));
    }

    static long delayMinMs() {
        return Long.getLong("solomapling.megaBuyDelayMinMs", 20_000);
    }

    static long delayMaxMs() {
        return Long.getLong("solomapling.megaBuyDelayMaxMs", 120_000);
    }

    /** Below this fraction of the lowest price the bot would take, the bot does not answer at all. */
    static double farBelow() {
        return Double.parseDouble(System.getProperty("solomapling.megaBuyFarBelow", "0.6"));
    }

    // ---- rate limits ----

    /** Per player and server-wide caps on offers, and a rest for each bot. */
    public static final class Limits {
        final long playerGapMs;
        final int playerPerHour;
        final long serverWindowMs;
        final int serverPerWindow;
        final long botRestMs;
        private final Map<Integer, Deque<Long>> byPlayer = new HashMap<>();
        private final Deque<Long> server = new java.util.ArrayDeque<>();
        private final Map<Integer, Long> botLast = new HashMap<>();

        public Limits(long playerGapMs, int playerPerHour, long serverWindowMs, int serverPerWindow, long botRestMs) {
            this.playerGapMs = playerGapMs;
            this.playerPerHour = playerPerHour;
            this.serverWindowMs = serverWindowMs;
            this.serverPerWindow = serverPerWindow;
            this.botRestMs = botRestMs;
        }

        public static Limits defaults() {
            return new Limits(Long.getLong("solomapling.megaBuyPlayerGapMs", 180_000),
                    Integer.getInteger("solomapling.megaBuyPlayerPerHour", 4),
                    Long.getLong("solomapling.megaBuyServerWindowMs", 600_000),
                    Integer.getInteger("solomapling.megaBuyServerPerWindow", 8),
                    Long.getLong("solomapling.megaBuyBotRestMs", 900_000));
        }

        /** True (and counted) when the player, the server and the bot are all inside their limits. */
        public synchronized boolean tryTake(int playerId, int botId, long now) {
            Deque<Long> mine = byPlayer.computeIfAbsent(playerId, k -> new java.util.ArrayDeque<>());
            while (!mine.isEmpty() && now - mine.peekFirst() > 3_600_000) {
                mine.pollFirst();
            }
            while (!server.isEmpty() && now - server.peekFirst() > serverWindowMs) {
                server.pollFirst();
            }
            if (!mine.isEmpty() && now - mine.peekLast() < playerGapMs || mine.size() >= playerPerHour
                    || server.size() >= serverPerWindow) {
                return false;
            }
            Long last = botLast.get(botId);
            if (last != null && now - last < botRestMs) {
                return false;
            }
            mine.addLast(now);
            server.addLast(now);
            botLast.put(botId, now);
            if (botLast.size() > 2000) {
                botLast.values().removeIf(t -> now - t >= botRestMs);
            }
            return true;
        }

        /** Whether the player alone is out of requests (so a chat reaction can still answer them). */
        public synchronized boolean playerLimited(int playerId, long now) {
            Deque<Long> mine = byPlayer.get(playerId);
            return mine != null && !mine.isEmpty() && (now - mine.peekLast() < playerGapMs || mine.size() >= playerPerHour);
        }
    }

    // ---- state ----

    private final World world;
    private final Limits limits;
    private final Map<Long, Integer> claims = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();

    public MegaBuyDesk(World world, Limits limits) {
        this.world = world;
        this.limits = limits;
    }

    public Limits limits() {
        return limits;
    }

    /** True for the first bot to ask about a request, false for every later one: exactly one bot answers. */
    public boolean claim(long requestId, int botId) {
        return claims.putIfAbsent(requestId, botId) == null;
    }

    // ---- the entry ----

    /** A player's megaphone. Never throws. */
    public Outcome hear(Asker asker, String text) {
        try {
            return hearInternal(asker, text);
        } catch (RuntimeException e) {
            log.warn("[MegaBuy] {} failed on \"{}\"", asker == null ? "?" : asker.name(), text, e);
            return Outcome.NO_ITEM;
        }
    }

    private Outcome hearInternal(Asker asker, String text) {
        MegaBuyParser.Request req = MegaBuyParser.parse(text);
        if (req.kind() != MegaBuyParser.Kind.BUY) {
            if (req.kind() == MegaBuyParser.Kind.SELL) {
                note("IGNORE " + asker.name() + " is selling (" + req.query() + "): bots do not buy from megaphones");
            }
            return Outcome.NOT_A_BUY;
        }
        if (!enabled()) {
            return Outcome.DISABLED;
        }
        long now = world.now();
        if (limits.playerLimited(asker.id(), now)) {
            note("LIMIT " + asker.name() + " asked again too soon (\"" + req.query() + "\")");
            return Outcome.RATE_LIMITED;
        }
        List<Stock> all = world.stock(asker);
        // one name lookup per distinct item, not per bot
        Map<Integer, String> names = new HashMap<>();
        for (Stock s : all) {
            names.putIfAbsent(s.itemId(), s.itemName());
        }
        List<MegaBuyMatcher.Candidate> candidates = new ArrayList<>();
        names.forEach((id, n) -> candidates.add(new MegaBuyMatcher.Candidate(id, n)));
        MegaBuyMatcher.Result found = MegaBuyMatcher.match(req.words(), candidates);
        if (!found.matched()) {
            note((found.outcome() == MegaBuyMatcher.Outcome.AMBIGUOUS ? "AMBIGUOUS " : "NOSTOCK ") + asker.name()
                    + " wants \"" + req.query() + "\"");
            return found.outcome() == MegaBuyMatcher.Outcome.AMBIGUOUS ? Outcome.NO_ITEM : Outcome.NO_STOCK;
        }
        List<Stock> sellers = new ArrayList<>();
        for (Stock s : all) {
            if (s.itemId() == found.itemId() && (req.qty() <= 0 || s.qty() == req.qty())) {
                sellers.add(s);
            }
        }
        if (sellers.isEmpty()) {
            note("NOSTOCK " + asker.name() + " wants " + req.qty() + "x " + found.name() + ": nobody has that many");
            return Outcome.NO_STOCK;
        }
        // same channel first (the player can walk to them), then a shuffle so no bot is always first
        Collections.shuffle(sellers, new java.util.Random((long) (world.roll() * Long.MAX_VALUE)));
        sellers.sort((a, b) -> Boolean.compare(b.channel() == asker.channel(), a.channel() == asker.channel()));
        long id = requestIds.incrementAndGet();
        boolean sawFar = false;
        for (int i = 0; i < sellers.size(); i++) {
            Stock s = sellers.get(i).source() == Source.BAG ? sellers.get(i).withFair(world.fairOf(sellers.get(i)))
                    : sellers.get(i);
            Plan plan = plan(s, req.price(), world.friend(s.botId(), asker.id()));
            if (plan == null) {
                sawFar = true;
                continue;
            }
            if (!limits.tryTake(asker.id(), s.botId(), now)) {
                continue;
            }
            if (!claim(id, s.botId())) {
                return Outcome.CLAIM_LOST;
            }
            long delay = world.between((int) delayMinMs(), (int) delayMaxMs());
            note("CLAIM " + s.botName() + " answers " + asker.name() + " for " + found.name() + " in " + delay / 1000
                    + "s (" + s.source() + ", " + plan.price() + (req.price() > 0 ? ", they offered " + req.price() : "")
                    + ")");
            world.later(delay, () -> deliver(id, s, asker, plan, req));
            return Outcome.SCHEDULED;
        }
        if (sawFar) {
            note("FAR " + asker.name() + " offered " + req.price() + " for " + found.name() + ": too low for any bot");
            return Outcome.FAR_BELOW;
        }
        note("LIMIT " + asker.name() + " wants " + found.name() + ": rate limits or resting bots, no answer");
        return Outcome.RATE_LIMITED;
    }

    // ---- the price ----

    /** What the bot will say it wants. {@code rounds}: counters already used (1 when it countered the player). */
    public record Plan(long price, int rounds, boolean countered) {
    }

    /**
     * The bot's price for this stock given what the player offered ({@code offered} <= 0: nothing). Null when the player is
     * far below what the bot could ever take. Bag items use {@link PriceBand}'s sell band; a merchant's or shop's
     * posted price is the price, and the player only decides whether it is worth saying.
     */
    public static Plan plan(Stock s, long offered, boolean friend) {
        if (s.source() != Source.BAG) {
            long ask = Math.max(1, s.ask());
            if (offered > 0 && offered < ask * farBelow()) {
                return null;
            }
            return new Plan(ask, 0, false);
        }
        PriceBand.Band band = PriceBand.of(PriceBand.Side.BOT_SELLS, Math.max(1, s.fair()), friend);
        long open = PriceBand.opening(band);
        if (offered <= 0) {
            return new Plan(open, 0, false);
        }
        if (offered < band.low() * farBelow()) {
            return null;
        }
        PriceBand.Decision d = PriceBand.respond(band, open, offered, 0);
        long price = Math.max(band.low(), Math.min(band.high(), d.price()));
        return new Plan(price, d.kind() == PriceBand.Kind.ACCEPT ? 0 : 1, d.kind() != PriceBand.Kind.ACCEPT);
    }

    // ---- the whisper ----

    private void deliver(long id, Stock s, Asker asker, Plan plan, MegaBuyParser.Request req) {
        try {
            claims.remove(id);
            if (!world.askerOnline(asker)) {
                note("DROP " + asker.name() + " left before " + s.botName() + " answered");
                return;
            }
            if (!world.stillHas(s)) {
                note("DROP " + s.botName() + " no longer has " + s.itemName());
                return;
            }
            String line = line(s, asker, plan, req.price(), world);
            if (world.whisper(s, asker, line)) {
                if (s.source() == Source.BAG) {
                    world.quote(s, asker, plan.price(), plan.rounds());
                }
                note("OFFER " + s.botName() + " -> " + asker.name() + ": \"" + line + "\"");
            } else {
                note("DROP " + s.botName() + " could not whisper " + asker.name());
            }
        } catch (RuntimeException e) {
            log.warn("[MegaBuy] delivery for {} failed", asker.name(), e);
        }
    }

    private static final List<String> BAG_LINES = List.of(
            "hey i got a %1$s, %2$s if u want it. %3$s, just trade me",
            "saw ur smega, i have %1$s. %2$s? im %3$s, trade me",
            "got %1$s here, %2$s. %3$s, trade me when ur here",
            "i can sell u my %1$s for %2$s. %3$s, trade me");
    private static final List<String> BAG_COUNTER = List.of(
            "i have %1$s, cant do that low but %2$s works. %3$s, trade me",
            "%1$s i can do %2$s. %3$s, trade me",
            "closest i can go on %1$s is %2$s. %3$s, trade me");
    private static final List<String> BAG_ACCEPT = List.of(
            "i have %1$s, %2$s works for me. %3$s, trade me",
            "sure %2$s for the %1$s. %3$s, trade me",
            "got %1$s, %2$s is fine. %3$s, trade me");
    private static final List<String> MERCHANT_LINES = List.of(
            "i sell %1$s, %2$s. %3$s, trade me",
            "got %1$s for %2$s. %3$s, trade me");
    private static final List<String> SHOP_LINES = List.of(
            "i have %1$s in my shop for %2$s. %3$s",
            "my shop has %1$s, %2$s. %3$s",
            "%1$s is in my fm shop, %2$s. %3$s");

    static String line(Stock s, Asker asker, Plan plan, long offered, World w) {
        List<String> pool = switch (s.source()) {
            case MERCHANT -> MERCHANT_LINES;
            case SHOP -> SHOP_LINES;
            case BAG -> offered > 0 ? plan.countered() ? BAG_COUNTER : BAG_ACCEPT : BAG_LINES;
        };
        String name = s.itemName().toLowerCase(Locale.ROOT) + (s.qty() > 1 ? " x" + s.qty() : "");
        return String.format(Locale.ROOT, pool.get(w.between(0, pool.size() - 1)), name,
                PriceBook.shorthand(plan.price()), s.where());
    }

    private static void note(String line) {
        log.info("[MegaBuy] {}", line);
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.TRADE)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.TRADE, "MEGABUY " + line);
        }
    }
}
