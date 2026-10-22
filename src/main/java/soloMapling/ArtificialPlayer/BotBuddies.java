package soloMapling.ArtificialPlayer;

import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingConstants;
import soloMapling.server.SoloMaplingUtilities;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Ticket 49: bots as buddies. A player can add a bot by name (online or not), the bot answers like a person would
 * after a short delay, a bot buddy shows online with its channel while the population director has it logged in, buddy
 * chat reaches it, and now and then a bot that knows a player well sends them a request itself.
 *
 * <p>Storage is the stock {@code buddies} table, no migration. The player's own rows are stock (the player's save
 * writes them; {@link BuddyList#loadFromDb} resolves a bot's name from the bot store). The bot's side of a pair is a
 * row with {@code characterid} = the bot:
 * <ul>
 *   <li>{@code pending = 0}: they are buddies ({@link Link#MUTUAL});</li>
 *   <li>{@code pending = 1}: the player asked and the bot has not answered ({@link Link#REQUESTED}) - the stock
 *       offline-request shape, answered at the bot's next login;</li>
 *   <li>{@code pending = 2}: bookkeeping, the kind and epoch day in {@code group} ({@code "sent:20381"}): the bot sent
 *       a request, the player declined or deleted the bot, or the bot turned the player down. Stock code never reads a
 *       bot's rows, and a player's buddy count only counts {@code pending = 0}.</li>
 * </ul>
 * Only bot-player pairs are ever written, never bot-bot, and only for real players who asked or were asked.
 *
 * <p>System properties ({@code solomapling.buddy.*}): {@code baseAccept} (0.2) a stranger's chance;
 * {@code knownBonus} (0.45) the bot holds its own friendly note about them (ticket 26); {@code talkBonus} (0.25)
 * they are talking right now (3+ lines; half for fewer); {@code partyBonus} (0.3) same party; all times the bot's
 * social trait; {@code maxPlayerBuddies} (20) per bot; {@code acceptDelayMinSec}/{@code acceptDelayMaxSec} (10/180);
 * {@code refusalDays} (1) a turned-down player's re-asks are ignored this long; {@code offerChance} (0.15) after a good
 * chat with a player it knows; {@code offerMinLines} (3); {@code offersPerPlayerPerDay} (1, all bots together);
 * {@code offersPerHour} (6, the whole world); {@code offerRetryDays} (7) one retry when a request was ignored.
 */
public final class BotBuddies {

    private static final Logger log = LoggerFactory.getLogger(BotBuddies.class);

    static final String GROUP = "Default Group";

    /** What the bot's side of a pair says. */
    public enum Link {
        /** They are buddies. */
        MUTUAL,
        /** The player asked; the bot has not answered yet. */
        REQUESTED,
        /** The bot sent the player a request (once). */
        SENT,
        /** The bot sent its one retry. */
        RESENT,
        /** The player said no to the bot's request. */
        DECLINED,
        /** The player removed the bot. */
        DELETED,
        /** The bot turned the player's request down. */
        REFUSED
    }

    /** The bot's side of one pair. {@code day} is the epoch day of a bookkeeping row, 0 otherwise. */
    public record Row(int botCharId, int playerId, Link link, long day, String playerName) {
        Row(int botCharId, int playerId, Link link, long day) {
            this(botCharId, playerId, link, day, null);
        }

        int pending() {
            return switch (link) {
                case MUTUAL -> 0;
                case REQUESTED -> 1;
                default -> 2;
            };
        }

        String group() {
            return link == Link.MUTUAL || link == Link.REQUESTED ? GROUP
                    : link.name().toLowerCase(Locale.ROOT) + ":" + day;
        }

        /** A stored row back to a link, or null for a row this class did not write. */
        static Row of(int botCharId, int playerId, int pending, String group, String playerName) {
            if (pending == 0) {
                return new Row(botCharId, playerId, Link.MUTUAL, 0, playerName);
            }
            if (pending == 1) {
                return new Row(botCharId, playerId, Link.REQUESTED, 0, playerName);
            }
            if (group == null) {
                return null;
            }
            int colon = group.indexOf(':');
            try {
                Link link = Link.valueOf((colon < 0 ? group : group.substring(0, colon)).toUpperCase(Locale.ROOT));
                long day = colon < 0 ? 0 : Long.parseLong(group.substring(colon + 1));
                return new Row(botCharId, playerId, link, day, playerName);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The {@code buddies} rows this class reads and writes. Swapped by tests. */
    interface Rows {
        /** The bot's side of every pair, with the player's name; rows of deleted characters are dropped. */
        List<Row> ofBot(int botCharId);

        /** The bot's side of one pair, or null. */
        Row pair(int botCharId, int playerId);

        /** Replaces the bot's side of the pair. */
        void put(Row row);

        /** Forgets the bot's side of the pair. */
        void remove(int botCharId, int playerId);

        /** How many players this bot is buddies with. */
        int mutualCount(int botCharId);

        /** How many requests bots sent this player on this day. */
        int sentOn(int playerId, long day);

        /** Whether the player's own list (as saved) holds the bot. */
        boolean playerHas(int playerId, int botCharId);

        /** Drops the bot from the player's saved list. */
        void removePlayerSide(int playerId, int botCharId);

        /** Every bot with a row of its own, read once so a bot login without buddies costs no query. */
        Set<Integer> botsWithRows();
    }

    /** Who a bot is, as a buddy-list add finds it. */
    public record Target(int charId, String name) {
    }

    /** What the bot weighs when a player asks. */
    record Familiarity(boolean known, boolean wary, int playerLines, boolean sameParty, double social) {
    }

    /** Why a bot did or did not send a request (logged; the tests read it). */
    enum Gate {
        OK, NOT_VISIBLE, STRANGER, WARY, SHORT_CHAT, ALREADY, CLOSED, TOO_SOON, PLAYER_DAILY_CAP, WORLD_CAP, BOT_FULL,
        PLAYER_FULL, ROLL
    }

    /** What a bot-sent request is checked against. */
    record OfferCheck(boolean visibleRealPlayer, boolean known, boolean wary, int playerLines, Row existing, long today,
                      int sentToPlayerToday, int sentWorldLastHour, int botMutual, boolean playerListFull,
                      boolean playerHasBot, double social, double roll) {
    }

    /** How well a bot knows a player (memory, chat, party, personality). Swapped by tests. */
    interface Judge {
        Familiarity of(Character bot, int playerId, Character player);
    }

    private static volatile Rows rows;
    private static volatile IntFunction<String> names;
    private static volatile IntFunction<Character> players = BotBuddies::onlinePlayerInWorld;
    private static volatile Judge judge = BotBuddies::familiarity;
    private static volatile java.util.function.DoubleSupplier rolls = () -> ThreadLocalRandom.current().nextDouble();
    private static final Deque<Long> worldOffers = new ArrayDeque<>();
    /** Pairs with a decision already scheduled, so a re-sent request does not roll twice. */
    private static final Set<Long> deciding = ConcurrentHashMap.newKeySet();
    /** Per player: when their last buddy-chat line went to a bot. */
    private static final Map<Integer, Long> lastBuddyChat = new ConcurrentHashMap<>();

    private BotBuddies() {
    }

    // ── settings ──

    static double setting(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty("solomapling.buddy." + key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int maxPlayerBuddies() {
        return (int) setting("maxPlayerBuddies", 20);
    }

    static long today(long now) {
        return Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
    }

    // ── the rules ──

    /** The chance a bot says yes to a player's request. */
    static double acceptChance(Familiarity f) {
        if (f.wary()) {
            return 0;
        }
        double p = setting("baseAccept", 0.2);
        if (f.known()) {
            p += setting("knownBonus", 0.45);
        }
        double talk = setting("talkBonus", 0.25);
        if (f.playerLines() >= 3) {
            p += talk;
        } else if (f.playerLines() > 0) {
            p += talk / 2;
        }
        if (f.sameParty()) {
            p += setting("partyBonus", 0.3);
        }
        // The social trait runs 0.05-0.40 (BotPersonality); 0.2, the default, leaves the chance as it is.
        p *= 0.7 + 1.5 * Math.max(0, Math.min(0.4, f.social()));
        return Math.max(0, Math.min(0.95, p));
    }

    /**
     * Whether the bot accepts: never when wary of the player, never past its buddy cap, never when it turned the
     * same player down lately (asking again and again does not wear a person down); otherwise the roll.
     */
    static boolean accepts(Familiarity f, int botMutual, Row existing, long today, double roll) {
        if (f.wary() || botMutual >= maxPlayerBuddies()) {
            return false;
        }
        if (existing != null && existing.link() == Link.REFUSED
                && today - existing.day() < (long) setting("refusalDays", 1)) {
            return false;
        }
        return roll < acceptChance(f);
    }

    /** Whether a bot may send this player a request after a chat, and if not, why not. */
    static Gate offerGate(OfferCheck c) {
        if (!c.visibleRealPlayer()) {
            return Gate.NOT_VISIBLE;
        }
        if (c.wary()) {
            return Gate.WARY;
        }
        if (!c.known()) {
            return Gate.STRANGER;
        }
        if (c.playerLines() < (int) setting("offerMinLines", 3)) {
            return Gate.SHORT_CHAT;
        }
        if (c.playerHasBot()) {
            return Gate.ALREADY;
        }
        Row e = c.existing();
        if (e != null) {
            switch (e.link()) {
                case MUTUAL, REQUESTED, REFUSED -> {
                    return Gate.ALREADY;
                }
                case DECLINED, DELETED, RESENT -> {
                    return Gate.CLOSED;
                }
                case SENT -> {
                    if (c.today() - e.day() < (long) setting("offerRetryDays", 7)) {
                        return Gate.TOO_SOON;
                    }
                }
            }
        }
        if (c.sentToPlayerToday() >= (int) setting("offersPerPlayerPerDay", 1)) {
            return Gate.PLAYER_DAILY_CAP;
        }
        if (c.sentWorldLastHour() >= (int) setting("offersPerHour", 6)) {
            return Gate.WORLD_CAP;
        }
        if (c.botMutual() >= maxPlayerBuddies()) {
            return Gate.BOT_FULL;
        }
        if (c.playerListFull()) {
            return Gate.PLAYER_FULL;
        }
        double chance = setting("offerChance", 0.15) * (0.7 + 1.5 * Math.max(0, Math.min(0.4, c.social())));
        return c.roll() < chance ? Gate.OK : Gate.ROLL;
    }

    /**
     * Which online bot buddy a buddy-chat line is for: the one it names, else the only one online, else the one the
     * player is mid-chat with; null when it is not clear (then no bot answers - several bots never pile on).
     */
    static Target addressee(String line, List<Target> online, int talkingTo) {
        if (online.isEmpty()) {
            return null;
        }
        String text = " " + (line == null ? "" : line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")) + " ";
        Target named = null;
        for (Target t : online) {
            if (text.contains(" " + t.name().toLowerCase(Locale.ROOT) + " ")) {
                if (named != null) {
                    return null;
                }
                named = t;
            }
        }
        if (named != null) {
            return named;
        }
        if (online.size() == 1) {
            return online.get(0);
        }
        for (Target t : online) {
            if (t.charId() == talkingTo) {
                return t;
            }
        }
        return null;
    }

    // ── names ──

    /** A bot's name by character id: its live name, else its stored one; null when no such bot exists. */
    public static String nameOf(int charId) {
        IntFunction<String> resolver = names;
        if (resolver != null) {
            return resolver.apply(charId);
        }
        if (!BotHelpers.isArtificial(charId)) {
            return null;
        }
        BotSM live = CharacterStorage.getBotById(charId);
        if (live != null && live.getChr() != null) {
            return live.getChr().getName();
        }
        try {
            BotStore.Persisted row = BotStore.persistedFor(charId);
            return row == null ? null : row.name;
        } catch (RuntimeException e) {
            log.warn("Could not read the bot store to name buddy {}", charId, e);
            return null;
        }
    }

    /** The bot a player means by this name in their world, online or not, or null when it is no bot's name. */
    public static Target resolve(Character player, String name) {
        if (name == null || player == null || player.getWorld() != SoloMaplingConstants.GameConstants.homeWorld()) {
            return null;
        }
        World world = SoloMaplingUtilities.world();
        Character online = world == null ? null : world.getPlayerStorage().getCharacterByName(name);
        if (online != null) {
            return BotHelpers.isBot(online) && BotHelpers.isArtificial(online.getId())
                    ? new Target(online.getId(), online.getName()) : null;
        }
        try {
            BotStore.Persisted row = BotStore.persistedByName(name);
            return row == null ? null : new Target(BotStore.BOT_ID_BASE + row.botId, row.name);
        } catch (RuntimeException e) {
            log.warn("Could not read the bot store to find {}", name, e);
            return null;
        }
    }

    // ── a player asks a bot ──

    /**
     * A player added a bot to their list. Records the request on the bot's side and has an online bot answer after a
     * human delay (an offline one answers at its next login). Returns the channel to show the bot on - its channel if
     * they already are buddies and it is online, -1 otherwise.
     */
    public static int playerRequests(Character player, Target bot) {
        if (player == null || bot == null || BotHelpers.isBot(player)) {
            return -1;
        }
        Character live = liveBot(bot.charId());
        try {
            Row existing = rows().pair(bot.charId(), player.getId());
            if (existing != null && existing.link() == Link.MUTUAL) {
                // The bot already has them (the player removed it from a list that was not saved): no question.
                if (live != null) {
                    live.getBuddylist().put(new BuddylistEntry(player.getName(), GROUP, player.getId(),
                            player.getClient().getChannel(), true));
                    return live.getClient().getChannel();
                }
                return -1;
            }
            if (existing == null || existing.link() == Link.SENT || existing.link() == Link.RESENT
                    || existing.link() == Link.DECLINED || existing.link() == Link.DELETED
                    || (existing.link() == Link.REFUSED
                    && today(System.currentTimeMillis()) - existing.day() >= (long) setting("refusalDays", 1))) {
                rows().put(new Row(bot.charId(), player.getId(), Link.REQUESTED, 0));
            } else if (existing.link() == Link.REFUSED) {
                return -1; // asked again too soon after a no: ignored, like a person would
            }
        } catch (RuntimeException e) {
            log.warn("Could not record {}'s buddy request to bot {}", player.getName(), bot.name(), e);
            return -1;
        }
        if (live != null) {
            scheduleDecision(live, player.getId(), player.getName());
        }
        return -1;
    }

    private static void scheduleDecision(Character bot, int playerId, String playerName) {
        long pair = pairKey(bot.getId(), playerId);
        if (!deciding.add(pair)) {
            return;
        }
        long min = (long) setting("acceptDelayMinSec", 10);
        long max = Math.max(min + 1, (long) setting("acceptDelayMaxSec", 180));
        long delay = ThreadLocalRandom.current().nextLong(min, max);
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                decide(bot.getId(), playerId, playerName);
            } catch (RuntimeException e) {
                log.warn("Bot {} could not answer {}'s buddy request", bot.getName(), playerName, e);
            } finally {
                deciding.remove(pair);
            }
        }), delay, TimeUnit.SECONDS);
    }

    /** The bot answers one request, if it is still online and the player still wants it. */
    static void decide(int botCharId, int playerId, String playerName) {
        Character bot = liveBot(botCharId);
        if (bot == null) {
            return; // logged out meanwhile: the pending row is answered at its next login
        }
        Row existing = rows().pair(botCharId, playerId);
        if (existing == null || existing.link() != Link.REQUESTED) {
            return;
        }
        Character player = onlinePlayer(playerId);
        boolean stillWanted = player != null ? player.getBuddylist().contains(botCharId)
                : rows().playerHas(playerId, botCharId);
        if (!stillWanted) {
            rows().remove(botCharId, playerId);
            return;
        }
        Familiarity f = judge.of(bot, playerId, player);
        long today = today(System.currentTimeMillis());
        if (!accepts(f, rows().mutualCount(botCharId), null, today, rolls.getAsDouble())) {
            // Silent, like a person who just never clicks accept; the player's entry stays as it would.
            rows().put(new Row(botCharId, playerId, Link.REFUSED, today));
            log.info("Bot {} turned down {}'s buddy request ({})", bot.getName(), playerName, f);
            return;
        }
        rows().put(new Row(botCharId, playerId, Link.MUTUAL, 0));
        String name = player != null ? player.getName() : playerName;
        int playerChannel = player != null ? player.getClient().getChannel() : -1;
        bot.getBuddylist().put(new BuddylistEntry(name, GROUP, playerId, playerChannel, true));
        if (player != null) {
            // What the stock accept (BuddylistModifyHandler mode 2) does for the asker: the bot shows online.
            bot.getWorldServer().buddyChanged(playerId, botCharId, bot.getName(), bot.getClient().getChannel(),
                    BuddyList.BuddyOperation.ADDED);
        }
        log.info("Bot {} accepted {}'s buddy request ({})", bot.getName(), name, f);
    }

    private static Familiarity familiarity(Character bot, int playerId, Character player) {
        int slot = bot.getId() - BotStore.BOT_ID_BASE;
        boolean known = BotRecognition.knows(slot, playerId);
        boolean wary = BotChatActions.wary(slot, playerId);
        int lines = BotConversation.playerLines(bot.getId(), playerId);
        boolean sameParty = player != null && bot.getParty() != null && player.getParty() != null
                && bot.getParty().getId() == player.getParty().getId();
        BotRoster.Member member = BotRoster.member(slot);
        BotPersonality.Traits traits = member == null ? null : member.traits();
        return new Familiarity(known, wary, lines, sameParty, traits == null ? 0.2 : traits.social());
    }

    // ── the player answers or removes ──

    /** The player accepted a bot's request (stock mode 2 already put the bot on their list). */
    public static void playerAccepted(Character player, int botCharId) {
        if (player == null || !BotHelpers.isArtificial(botCharId)) {
            return;
        }
        ExecutorServiceManager.runAsync(() -> {
            try {
                recordAccept(player, botCharId);
            } catch (RuntimeException e) {
                log.warn("Could not record {} accepting bot {}", player.getName(), botCharId, e);
            }
        });
    }

    static void recordAccept(Character player, int botCharId) {
        rows().put(new Row(botCharId, player.getId(), Link.MUTUAL, 0));
        Character bot = liveBot(botCharId);
        if (bot != null && !bot.getBuddylist().containsVisible(player.getId())) {
            bot.getBuddylist().put(new BuddylistEntry(player.getName(), GROUP, player.getId(),
                    player.getClient().getChannel(), true));
        }
    }

    /**
     * The player removed a bot from their list, or said no to its request (the client's deny is a delete). Both sides
     * go, and the bot remembers not to ask again.
     */
    public static void playerRemoved(Character player, int botCharId) {
        if (player == null || !BotHelpers.isArtificial(botCharId) || BotHelpers.isBot(player)) {
            return;
        }
        Character bot = liveBot(botCharId);
        if (bot != null) {
            bot.getBuddylist().remove(player.getId());
        }
        int playerId = player.getId();
        ExecutorServiceManager.runAsync(() -> {
            try {
                recordRemoval(playerId, botCharId);
            } catch (RuntimeException e) {
                log.warn("Could not record {} removing bot {}", player.getName(), botCharId, e);
            }
        });
    }

    static void recordRemoval(int playerId, int botCharId) {
        Row existing = rows().pair(botCharId, playerId);
        long today = today(System.currentTimeMillis());
        if (existing == null || existing.link() == Link.REQUESTED) {
            rows().remove(botCharId, playerId); // they withdrew their own request
        } else if (existing.link() == Link.SENT || existing.link() == Link.RESENT) {
            rows().put(new Row(botCharId, playerId, Link.DECLINED, today));
        } else if (existing.link() == Link.MUTUAL) {
            rows().put(new Row(botCharId, playerId, Link.DELETED, today));
        }
        rows().removePlayerSide(playerId, botCharId);
    }

    // ── a bot logs in or out ──

    /**
     * A bot joined the world: it loads its player buddies, shows online on their lists, and answers the requests that
     * came while it was away. Off the caller's thread.
     */
    public static void onBotLogin(Character bot) {
        if (bot == null || !BotHelpers.isArtificial(bot.getId())) {
            return;
        }
        ExecutorServiceManager.runAsync(() -> {
            try {
                for (Row request : loadBuddies(bot)) {
                    scheduleDecision(bot, request.playerId(), request.playerName());
                }
            } catch (RuntimeException e) {
                log.warn("Could not load the buddies of bot {}", bot.getName(), e);
            }
        });
    }

    /** Puts the bot's player buddies on its list and shows it online to them; returns the requests to answer. */
    static List<Row> loadBuddies(Character bot) {
        if (!rows().botsWithRows().contains(bot.getId())) {
            return List.of();
        }
        BuddyList list = bot.getBuddylist();
        for (int id : list.getBuddyIds()) {
            list.remove(id); // a template's list, if any, is not this bot's
        }
        list.setCapacity(Math.max(list.getCapacity(), maxPlayerBuddies()));
        List<Row> requests = new ArrayList<>();
        for (Row row : rows().ofBot(bot.getId())) {
            if (row.link() == Link.MUTUAL) {
                Character player = players.apply(row.playerId());
                list.put(new BuddylistEntry(row.playerName(), GROUP, row.playerId(),
                        player == null ? -1 : player.getClient().getChannel(), true));
            } else if (row.link() == Link.REQUESTED) {
                requests.add(row);
            }
        }
        int[] ids = list.getBuddyIds();
        if (ids.length > 0) {
            bot.getWorldServer().loggedOn(bot.getName(), bot.getId(), bot.getClient().getChannel(), ids);
        }
        return requests;
    }

    /** A bot is leaving the world: it shows offline on its buddies' lists. */
    public static void onBotLogout(Character bot) {
        if (bot == null || !BotHelpers.isArtificial(bot.getId())) {
            return;
        }
        int[] ids = bot.getBuddylist().getBuddyIds();
        if (ids.length == 0) {
            return;
        }
        try {
            bot.getWorldServer().loggedOff(bot.getName(), bot.getId(), bot.getClient().getChannel(), ids);
        } catch (RuntimeException e) {
            log.warn("Could not show bot {} offline to its buddies", bot.getName(), e);
        }
    }

    // ── a bot asks a player ──

    /**
     * A chat between a bot and a player ended. Rarely, a bot that already knew the player and just had a real chat
     * with them sends a buddy request a little later. Never to a hidden GM, a player who said no or removed it, more
     * than once a day per player across all bots, or past the world's hourly cap.
     */
    public static void afterChat(Character bot, Character player, int playerLines) {
        if (bot == null || player == null || !BotHelpers.isArtificial(bot.getId())
                || !BotHelpers.isVisibleRealPlayer(player)) {
            return;
        }
        if (playerLines < (int) setting("offerMinLines", 3)) {
            return; // the cheap check first: almost every chat stops here without a query
        }
        long delay = ThreadLocalRandom.current().nextLong(20, 91);
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                maybeOffer(bot.getId(), player.getId(), playerLines);
            } catch (RuntimeException e) {
                log.warn("Bot {} could not decide on asking {} to be buddies", bot.getName(), player.getName(), e);
            }
        }), delay, TimeUnit.SECONDS);
    }

    /**
     * Ticket 58: a bot that counts this player as a friend may ask them to be buddies when it sees them, without a
     * chat first. Every other gate stays: the player's daily cap, the world's hourly cap, retries, full lists, the
     * roll. What happened, for the log.
     */
    public static String offerAsFriend(int botCharId, int playerId) {
        Gate gate = maybeOffer(botCharId, playerId, (int) setting("offerMinLines", 3));
        return "buddy offer: " + gate.name().toLowerCase(Locale.ROOT);
    }

    static Gate maybeOffer(int botCharId, int playerId, int playerLines) {
        Character bot = liveBot(botCharId);
        Character player = onlinePlayer(playerId);
        if (bot == null || player == null) {
            return Gate.NOT_VISIBLE;
        }
        long now = System.currentTimeMillis();
        long today = today(now);
        Familiarity f = judge.of(bot, playerId, player);
        OfferCheck check = new OfferCheck(BotHelpers.isVisibleRealPlayer(player), f.known(), f.wary(), playerLines,
                rows().pair(botCharId, playerId), today, rows().sentOn(playerId, today), worldOffersLastHour(now),
                rows().mutualCount(botCharId), player.getBuddylist().isFull(),
                player.getBuddylist().contains(botCharId), f.social(), rolls.getAsDouble());
        Gate gate = offerGate(check);
        if (gate != Gate.OK) {
            return gate;
        }
        noteWorldOffer(now);
        Row existing = check.existing();
        rows().put(new Row(botCharId, playerId, existing != null && existing.link() == Link.SENT ? Link.RESENT
                : Link.SENT, today));
        int botChannel = bot.getClient().getChannel();
        bot.getBuddylist().put(new BuddylistEntry(player.getName(), GROUP, playerId, player.getClient().getChannel(), true));
        // The stock request a player receives: the client shows the accept / deny box.
        player.getBuddylist().addBuddyRequest(player.getClient(), botCharId, bot.getName(), botChannel);
        log.info("Bot {} sent {} a buddy request", bot.getName(), player.getName());
        return gate;
    }

    private static synchronized int worldOffersLastHour(long now) {
        while (!worldOffers.isEmpty() && now - worldOffers.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
            worldOffers.pollFirst();
        }
        return worldOffers.size();
    }

    private static synchronized void noteWorldOffer(long now) {
        worldOffers.addLast(now);
    }

    // ── buddy chat ──

    /**
     * A player's buddy-chat line: the bot buddy it is for (see {@link #addressee}) reads it as a conversation line and
     * answers in buddy chat, through the same chat brain, gates and limits as a whisper. At most one bot per line.
     */
    public static void buddyChat(Character player, int[] recipients, String line) {
        if (player == null || recipients == null || BotHelpers.isBot(player)) {
            return;
        }
        List<Target> online = new ArrayList<>();
        World world = player.getWorldServer();
        for (int id : recipients) {
            if (!BotHelpers.isArtificial(id)) {
                continue;
            }
            Character bot = world.getPlayerStorage().getCharacterById(id);
            if (bot != null && BotHelpers.isBot(bot) && bot.getBuddylist().containsVisible(player.getId())) {
                online.add(new Target(bot.getId(), bot.getName()));
            }
        }
        if (online.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastBuddyChat.get(player.getId());
        if (last != null && now - last < 1500) {
            return; // a burst of lines: the bot is still reading the last one
        }
        Target to = addressee(line, online, BotConversation.partnerOf(player.getId()));
        if (to == null) {
            return;
        }
        BotSM brain = CharacterStorage.getBotById(to.charId());
        if (brain == null) {
            return;
        }
        lastBuddyChat.put(player.getId(), now);
        String botName = to.name();
        BotConversation.talk(brain, player, line,
                reply -> player.sendPacket(PacketCreator.multiChat(botName, reply, 0)));
    }

    // ── GM read-out ──

    /** A bot's player buddies, or a player's bot buddies, one line each. */
    public static List<String> describe(int charId) {
        List<String> out = new ArrayList<>();
        if (BotHelpers.isArtificial(charId)) {
            for (Row row : rows().ofBot(charId)) {
                out.add(row.link().name().toLowerCase(Locale.ROOT) + " " + row.playerName() + " (" + row.playerId()
                        + ")" + (row.day() > 0 ? " day " + row.day() : ""));
            }
        } else {
            Character player = onlinePlayer(charId);
            if (player != null) {
                for (BuddylistEntry e : player.getBuddylist().getBuddies()) {
                    if (BotHelpers.isArtificial(e.getCharacterId())) {
                        Row row = rows().pair(e.getCharacterId(), charId);
                        out.add(e.getName() + " (" + e.getCharacterId() + "): "
                                + (row == null ? "no answer" : row.link().name().toLowerCase(Locale.ROOT))
                                + (e.getChannel() > 0 ? ", online ch " + e.getChannel() : ", offline"));
                    }
                }
            }
        }
        return out;
    }

    // ── plumbing ──

    private static long pairKey(int botCharId, int playerId) {
        return ((long) botCharId << 32) | (playerId & 0xffffffffL);
    }

    private static Character liveBot(int charId) {
        BotSM bot = CharacterStorage.getBotById(charId);
        return bot == null ? null : bot.getChr();
    }

    private static Character onlinePlayer(int playerId) {
        return players.apply(playerId);
    }

    private static Character onlinePlayerInWorld(int playerId) {
        World world = SoloMaplingUtilities.world();
        Character chr = world == null ? null : world.getPlayerStorage().getCharacterById(playerId);
        return chr == null || BotHelpers.isBot(chr) || !chr.isLoggedinWorld() ? null : chr;
    }

    static Rows rows() {
        Rows r = rows;
        if (r == null) {
            synchronized (BotBuddies.class) {
                if (rows == null) {
                    rows = new JdbcRows(BotDbGate::connection);
                }
                r = rows;
            }
        }
        return r;
    }

    /** Test seams: a fake table, names, online players, judge and dice (null restores the real ones). */
    static void useForTest(Rows testRows, IntFunction<String> testNames, IntFunction<Character> testPlayers,
                           Judge testJudge, java.util.function.DoubleSupplier testRolls) {
        rows = testRows;
        names = testNames;
        players = testPlayers != null ? testPlayers : BotBuddies::onlinePlayerInWorld;
        judge = testJudge != null ? testJudge : BotBuddies::familiarity;
        rolls = testRolls != null ? testRolls : () -> ThreadLocalRandom.current().nextDouble();
        deciding.clear();
        lastBuddyChat.clear();
        synchronized (BotBuddies.class) {
            worldOffers.clear();
        }
    }

    /** The {@code buddies} table. */
    static final class JdbcRows implements Rows {
        static final String OF_BOT = "SELECT b.buddyid, b.pending, b.`group`, c.name FROM buddies b "
                + "LEFT JOIN characters c ON c.id = b.buddyid WHERE b.characterid = ?";
        static final String PAIR = "SELECT pending, `group` FROM buddies WHERE characterid = ? AND buddyid = ?";
        static final String DELETE_PAIR = "DELETE FROM buddies WHERE characterid = ? AND buddyid = ?";
        static final String INSERT = "INSERT INTO buddies (characterid, buddyid, pending, `group`) VALUES (?, ?, ?, ?)";
        static final String MUTUAL = "SELECT COUNT(*) FROM buddies WHERE characterid = ? AND pending = 0";
        static final String SENT_ON = "SELECT COUNT(*) FROM buddies WHERE buddyid = ? AND characterid > ? AND pending = 2"
                + " AND (`group` = ? OR `group` = ?)";
        static final String BOTS = "SELECT DISTINCT characterid FROM buddies WHERE characterid > ?";

        private final BotStore.ConnectionSource connections;
        private volatile Set<Integer> bots;

        JdbcRows(BotStore.ConnectionSource connections) {
            this.connections = connections;
        }

        @Override
        public List<Row> ofBot(int botCharId) {
            List<Row> out = new ArrayList<>();
            List<Integer> orphans = new ArrayList<>();
            try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement(OF_BOT)) {
                ps.setInt(1, botCharId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int playerId = rs.getInt(1);
                        String name = rs.getString(4);
                        if (name == null) {
                            orphans.add(playerId); // the player's character was deleted (or it is not a player)
                            continue;
                        }
                        Row row = Row.of(botCharId, playerId, rs.getInt(2), rs.getString(3), name);
                        if (row != null) {
                            out.add(row);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read the buddies of bot " + botCharId, e);
            }
            for (int orphan : orphans) {
                remove(botCharId, orphan);
            }
            return out;
        }

        @Override
        public Row pair(int botCharId, int playerId) {
            try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement(PAIR)) {
                ps.setInt(1, botCharId);
                ps.setInt(2, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Row.of(botCharId, playerId, rs.getInt(1), rs.getString(2), null) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read bot " + botCharId + "'s row for " + playerId, e);
            }
        }

        @Override
        public void put(Row row) {
            try (Connection con = connections.get()) {
                boolean auto = con.getAutoCommit();
                con.setAutoCommit(false);
                try (PreparedStatement del = con.prepareStatement(DELETE_PAIR);
                     PreparedStatement ins = con.prepareStatement(INSERT)) {
                    del.setInt(1, row.botCharId());
                    del.setInt(2, row.playerId());
                    del.executeUpdate();
                    ins.setInt(1, row.botCharId());
                    ins.setInt(2, row.playerId());
                    ins.setInt(3, row.pending());
                    ins.setString(4, row.group());
                    ins.executeUpdate();
                    con.commit();
                } catch (SQLException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(auto);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not write bot " + row.botCharId() + "'s row for "
                        + row.playerId(), e);
            }
            Set<Integer> known = bots;
            if (known != null) {
                known.add(row.botCharId());
            }
        }

        @Override
        public void remove(int botCharId, int playerId) {
            delete(botCharId, playerId);
        }

        @Override
        public int mutualCount(int botCharId) {
            return count(MUTUAL, ps -> ps.setInt(1, botCharId));
        }

        @Override
        public int sentOn(int playerId, long day) {
            return count(SENT_ON, ps -> {
                ps.setInt(1, playerId);
                ps.setInt(2, BotStore.BOT_ID_BASE);
                ps.setString(3, "sent:" + day);
                ps.setString(4, "resent:" + day);
            });
        }

        @Override
        public boolean playerHas(int playerId, int botCharId) {
            try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement(PAIR)) {
                ps.setInt(1, playerId);
                ps.setInt(2, botCharId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read player " + playerId + "'s list", e);
            }
        }

        @Override
        public void removePlayerSide(int playerId, int botCharId) {
            delete(playerId, botCharId);
        }

        @Override
        public Set<Integer> botsWithRows() {
            Set<Integer> known = bots;
            if (known != null) {
                return known;
            }
            synchronized (this) {
                if (bots == null) {
                    Set<Integer> loaded = ConcurrentHashMap.newKeySet();
                    try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement(BOTS)) {
                        ps.setInt(1, BotStore.BOT_ID_BASE);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                loaded.add(rs.getInt(1));
                            }
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("Could not read which bots have buddies", e);
                    }
                    bots = loaded;
                }
                return bots;
            }
        }

        private void delete(int characterId, int buddyId) {
            try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement(DELETE_PAIR)) {
                ps.setInt(1, characterId);
                ps.setInt(2, buddyId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not delete buddies row " + characterId + "/" + buddyId, e);
            }
        }

        private interface Binder {
            void bind(PreparedStatement ps) throws SQLException;
        }

        private int count(String sql, Binder binder) {
            try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not count buddies rows", e);
            }
        }
    }
}
