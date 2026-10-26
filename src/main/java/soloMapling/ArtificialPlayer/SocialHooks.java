package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.qa.QaLog;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;

/**
 * 1.6.0 slice S3: the one place stock game code calls for the social layer's PQ-clear and trade taps (audit §3,
 * "missing taps"). Each hook is called through {@code MapleRootSolo.runHook}, so a failure never stops the player's
 * action; it does memory work on the calling thread and leaves the database to {@link BotEpisodes}' writer.
 *
 * <ul>
 *   <li>{@link #onPqClear} ({@code EventInstanceManager.clearPQ}): a PQ or boss instance was cleared. Publishes
 *   {@code EventType.PQ_CLEAR} on the event bus (for bot-bot bonds, slice S2) and writes "did KPQ together" for every
 *   bot and real player who cleared it together.</li>
 *   <li>{@link #onTradeDone} ({@code Trade.completeTrade}): a trade window went through. Publishes
 *   {@code EventType.TRADE_DONE}; for a bot and a real player it reads the trade against the price index
 *   ({@link #read}) and records one affinity event (GIFT, FAVOR, SCAM or TRADE) and one episode. A scam also starts
 *   it with that bot in the social gate ({@link SocialGate.Provocation#SCAM}).</li>
 * </ul>
 */
public final class SocialHooks {

    private static final Logger log = LoggerFactory.getLogger(SocialHooks.class);

    /** What one unit of an item is fairly worth (0 = nobody can price it); a seam for tests. */
    private static volatile ToLongFunction<Item> unitValue = soloMapling.Economy.PriceBook::fairValue;
    /** An item id's name; a seam for tests (the real one reads String.wz). */
    private static volatile java.util.function.IntFunction<String> itemNames = SocialHooks::itemName;
    private static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    private SocialHooks() {
    }

    // ── PQ and boss clears ──────────────────────────────────────────────────────────────────────

    /** A cleared instance: its short name as players say it ("KPQ", "zakum"), and whether it was a boss. */
    public record Clear(String name, boolean boss) {
    }

    /**
     * The clear an event script stands for, or null when it is not a group activity worth remembering (weddings, job
     * quests, area bosses, boats).
     */
    public static Clear clearOf(String eventName) {
        if (eventName == null) {
            return null;
        }
        String n = eventName.toLowerCase(Locale.ROOT);
        if (n.startsWith("wedding") || n.startsWith("areaboss") || n.matches("^(?:\\d|q\\d|4j|3rdjob|aran).*")) {
            return null;
        }
        return switch (n.replaceAll("_.*$", "")) {
            case "kerningpq" -> new Clear("KPQ", false);
            case "ludipq" -> new Clear("LPQ", false);
            case "orbispq" -> new Clear("OPQ", false);
            case "henesyspq" -> new Clear("HPQ", false);
            case "ellinpq" -> new Clear("EPQ", false);
            case "piratepq" -> new Clear("PPQ", false);
            case "amoriapq" -> new Clear("APQ", false);
            case "magatiapq" -> new Clear("MPQ", false);
            case "ludimazepq" -> new Clear("LMPQ", false);
            case "elnathpq" -> new Clear("the El Nath PQ", false);
            case "guildquest" -> new Clear("GPQ", false);
            case "cwkpq" -> new Clear("CWKPQ", false);
            case "zakumpq" -> new Clear("the zak prequest", false);
            case "horntailpq" -> new Clear("the HT prequest", false);
            case "bossrushpq" -> new Clear("boss rush", false);
            case "treasurepq" -> new Clear("treasure PQ", false);
            case "zakumbattle" -> new Clear("zakum", true);
            case "horntailbattle" -> new Clear("horntail", true);
            case "papulatusbattle" -> new Clear("papulatus", true);
            case "pinkbeanbattle" -> new Clear("pink bean", true);
            case "balrogbattle" -> new Clear("balrog", true);
            case "krexelbattle" -> new Clear("krexel", true);
            case "cygnusbattle" -> new Clear("cygnus", true);
            case "showabattle" -> new Clear("the showa boss", true);
            case "scargabattle" -> new Clear("scarga", true);
            default -> null;
        };
    }

    /** {@code EventInstanceManager.clearPQ}: the instance's script name and everyone in it. */
    public static void onPqClear(String eventName, List<Character> members) {
        Clear clear = clearOf(eventName);
        if (clear == null || members == null || members.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        List<Character> bots = new ArrayList<>();
        List<Character> players = new ArrayList<>();
        for (Character c : members) {
            if (c == null) {
                continue;
            }
            (BotHelpers.isArtificial(c.getId()) ? bots : players).add(c);
        }
        publish(() -> EventFactory.createPqClearEvent(players.isEmpty() ? bots.get(0) : players.get(0), clear.name(),
                members.size()));
        for (Character bot : bots) {
            for (Character player : players) {
                BotEpisodes.together(bot.getId(), player.getId(), clear, now);
            }
        }
        if (QaLog.on(QaLog.Topic.MEMORY) && !bots.isEmpty() && !players.isEmpty()) { // !qa me-1
            QaLog.any(QaLog.Topic.MEMORY, "PQ_CLEAR " + clear.name() + ": " + bots.size() + " bot(s) with "
                    + players.size() + " player(s)");
        }
    }

    // ── trades ──────────────────────────────────────────────────────────────────────────────────

    /** How a finished bot-player trade went for the bot. */
    public enum Outcome {
        /** The player gave and the bot gave nothing back. */
        GIFT,
        /** The bot gave and got nothing (its own gift, or a sale it was paid for elsewhere): a plain trade. */
        BOT_GAVE,
        /** Both gave, about fairly. */
        TRADE,
        /** The bot got clearly more than it gave: the player did it a favor. */
        FAVOR,
        /** The bot got clearly less than it gave against the price index: it was short-changed. */
        SCAM
    }

    /** A scam needs the bot to give at least this much (mesos, by the price index) ... */
    static long scamMin() {
        return Long.getLong("solomapling.memory.scamMin", 100_000L);
    }

    /** ... and to get at most this share of it back. Conservative: only obvious cases. */
    static double scamShare() {
        try {
            return Double.parseDouble(System.getProperty("solomapling.memory.scamShare", "0.25"));
        } catch (NumberFormatException e) {
            return 0.25;
        }
    }

    /**
     * The trade's outcome for the bot, pure. {@code gave} / {@code got} are what the bot gave and got, by the price
     * index, in mesos; {@code priced} is false when an item on either side has no price (then it is never a scam or a
     * favor, only a trade). A scam: both sides gave something, the bot gave at least {@link #scamMin} and got at most
     * {@link #scamShare} of it. A favor: the bot got at least twice what it gave and 50k more.
     */
    public static Outcome classify(boolean botGave, boolean playerGave, long gave, long got, boolean priced) {
        if (playerGave && !botGave) {
            return Outcome.GIFT;
        }
        if (botGave && !playerGave) {
            return Outcome.BOT_GAVE;
        }
        if (!priced) {
            return Outcome.TRADE;
        }
        if (gave >= scamMin() && got <= gave * scamShare()) {
            return Outcome.SCAM;
        }
        if (got >= 2 * gave && got - gave >= 50_000) {
            return Outcome.FAVOR;
        }
        return Outcome.TRADE;
    }

    /** One side's offer: the items it put in and the mesos it put in. */
    public record Side(List<Item> items, long mesos) {
        boolean gave() {
            return !items.isEmpty() || mesos > 0;
        }
    }

    /** A bot-player trade, read: both offers, what each is worth to the price index, and the outcome. */
    public record Read(Side bot, Side player, long gave, long got, boolean priced, Outcome outcome) {
    }

    /** Reads a trade from the bot's side against the price index ({@code PriceBook.fairValue} per unit). */
    public static Read read(Side bot, Side player) {
        long[] gave = value(bot);
        long[] got = value(player);
        boolean priced = gave[1] == 0 && got[1] == 0;
        return new Read(bot, player, gave[0], got[0], priced,
                classify(bot.gave(), player.gave(), gave[0], got[0], priced));
    }

    /** {total value in mesos, how many items had no price}. */
    private static long[] value(Side side) {
        long total = side.mesos();
        long unpriced = 0;
        for (Item item : side.items()) {
            long unit;
            try {
                unit = unitValue.applyAsLong(item);
            } catch (RuntimeException e) {
                unit = 0;
            }
            if (unit <= 0) {
                unpriced++;
                continue;
            }
            total += unit * Math.max(1, soloMapling.Economy.PriceBook.units(item));
        }
        return new long[]{total, unpriced};
    }

    /**
     * {@code Trade.completeTrade}, just before the items change hands: {@code aItems} / {@code bItems} are what each
     * side put in, {@code aReceives} / {@code bReceives} the mesos each receives ({@code Trade.getExchangeMesos}).
     */
    public static void onTradeDone(Character a, List<Item> aItems, int aReceives, Character b, List<Item> bItems,
                                   int bReceives) {
        if (a == null || b == null) {
            return;
        }
        publish(() -> EventFactory.createTradeDoneEvent(a, b));
        boolean aBot = BotHelpers.isArtificial(a.getId());
        if (aBot == BotHelpers.isArtificial(b.getId())) {
            return; // two players or two bots: the bus event is all
        }
        Character bot = aBot ? a : b;
        Character player = aBot ? b : a;
        // what x put in: its items, and the mesos the other side receives
        Side botSide = new Side(copy(aBot ? aItems : bItems), Math.max(0, aBot ? bReceives : aReceives));
        Side playerSide = new Side(copy(aBot ? bItems : aItems), Math.max(0, aBot ? aReceives : bReceives));
        tradeDone(bot, player, botSide, playerSide);
    }

    /** The bot-player part of a finished trade (also {@code !qa me-2}'s staged trade). Returns the read. */
    public static Read tradeDone(Character bot, Character player, Side botSide, Side playerSide) {
        Read r = read(botSide, playerSide);
        AffinityEvent event = switch (r.outcome()) {
            case GIFT -> AffinityEvent.GIFT;
            case FAVOR -> AffinityEvent.FAVOR;
            case SCAM -> AffinityEvent.SCAM;
            case TRADE, BOT_GAVE -> AffinityEvent.TRADE;
        };
        boolean counted = BotAffinity.record(bot, player, event);
        if (r.outcome() == Outcome.SCAM && !BotAffinity.optedOut(player.getId())) {
            // 1.6.0 S5 ledger: a scam starts it with this bot even when the affinity event was cooling down
            SocialGate.playerStarted(bot.getId(), player.getId(), SocialGate.Provocation.SCAM);
        }
        BotEpisodes.Episode episode = episodeOf(r);
        long now = clock.getAsLong();
        if (episode != null && !(r.outcome() == Outcome.SCAM && BotAffinity.optedOut(player.getId()))) {
            BotEpisodes.trade(bot.getId(), player.getId(), episode, now);
        }
        if (QaLog.on(QaLog.Topic.MEMORY)) { // !qa me-2
            QaLog.any(QaLog.Topic.MEMORY, "TRADE " + r.outcome().name() + " bot " + bot.getName() + " <- "
                    + player.getName() + ": gave " + r.gave() + ", got " + r.got() + (r.priced() ? "" : " (unpriced)")
                    + ", affinity " + event.key() + (counted ? "" : " (not counted)"));
        }
        return r;
    }

    /** The note a trade leaves for the bot, or null for a bot's own gift (nothing happened to it). */
    static BotEpisodes.Episode episodeOf(Read r) {
        String got = what(r.player());
        String gave = what(r.bot());
        return switch (r.outcome()) {
            case GIFT -> new BotEpisodes.Episode(BotMemory.Kind.GIFT, "gave me " + got, 6);
            case FAVOR -> new BotEpisodes.Episode(BotMemory.Kind.FAVOR, "gave me a good deal: " + got + " for "
                    + gave, 5);
            case SCAM -> new BotEpisodes.Episode(BotMemory.Kind.SCAM, "scammed me on a trade: my " + gave + " for "
                    + got, 9);
            case TRADE -> r.player().items().isEmpty()
                    ? new BotEpisodes.Episode(BotMemory.Kind.TRADE, "bought my " + gave, 3)
                    : new BotEpisodes.Episode(BotMemory.Kind.TRADE, "traded me " + got, 4);
            case BOT_GAVE -> null;
        };
    }

    /** One side's offer in words: "a Red Craven", "200 Ilbi Throwing-Stars", "50k mesos", "a claw and 1m mesos". */
    static String what(Side side) {
        List<String> parts = new ArrayList<>();
        if (!side.items().isEmpty()) {
            Item first = side.items().get(0);
            String name = itemNames.apply(first.getItemId());
            if (name == null || name.isBlank()) {
                name = "an item";
            } else if (first.getQuantity() > 1 && !constants.inventory.ItemConstants.isRechargeable(first.getItemId())) {
                name = first.getQuantity() + " " + name;
            } else {
                name = ("aeiou".indexOf(java.lang.Character.toLowerCase(name.charAt(0))) >= 0 ? "an " : "a ") + name;
            }
            parts.add(side.items().size() > 1 ? name + " and more" : name);
        }
        if (side.mesos() > 0) {
            parts.add(soloMapling.Economy.PriceBook.shorthand(side.mesos()) + " mesos");
        }
        return parts.isEmpty() ? "nothing" : String.join(" and ", parts);
    }

    private static List<Item> copy(List<Item> items) {
        List<Item> out = new ArrayList<>();
        if (items != null) {
            for (Item item : items) {
                if (item != null) {
                    out.add(item);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String itemName(int itemId) {
        try {
            return server.ItemInformationProvider.getInstance().getName(itemId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static void publish(java.util.function.Supplier<soloMapling.server.EventMessageSystem.GameEvent> event) {
        try {
            EventBus.getInstance().publish(event.get());
        } catch (RuntimeException e) {
            log.debug("Could not publish a social event", e); // a character without a map (tests, a logout)
        }
    }

    /** Test seam: item values and names and the clock (nulls restore the real ones). */
    public static void useForTest(ToLongFunction<Item> testValue, java.util.function.IntFunction<String> testNames,
                                  java.util.function.LongSupplier testClock) {
        unitValue = testValue != null ? testValue : soloMapling.Economy.PriceBook::fairValue;
        itemNames = testNames != null ? testNames : SocialHooks::itemName;
        clock = testClock != null ? testClock : System::currentTimeMillis;
    }
}
