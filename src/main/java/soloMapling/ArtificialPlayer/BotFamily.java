package soloMapling.ArtificialPlayer;

import client.Character;
import client.Family;
import client.FamilyEntry;
import config.YamlConfig;
import net.server.channel.handlers.AcceptFamilyHandler;
import net.server.coordinator.world.InviteCoordinator;
import net.server.world.World;
import server.maps.MapleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingUtilities;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 1.6.1 ticket 53 (owner 2026-10-28: "regulars may be your juniors"): roster bots in the Family system, as juniors of
 * players only. A bot never has juniors of its own and never invites anyone.
 *
 * <ul>
 *   <li><b>Accepting:</b> a player's "Add Junior" on a bot is answered after a human delay (10-60 s). The bot says yes
 *   only to a player it has interacted with: one it knows ({@link BotRecognition#knows}, a friend), one in its party,
 *   or one who just talked with it (3+ lines); never when wary of them or already in a family. Then a roll
 *   ({@code slums.family.acceptChance}, 0.8).</li>
 *   <li><b>Offering:</b> v83 has no "be my senior" request, so after a real chat with a player it already knew, a bot
 *   may rarely whisper that it would like to be their junior. Once per bot and player a week, once per player a day
 *   across all bots, at most {@code slums.family.offersPerHour} (3) a world an hour, a 10% roll, only when the
 *   player has room for a junior, is at least the bot's level and within 20 levels, and the social gate allows a
 *   whisper ({@code @quiet}).</li>
 *   <li><b>Rep:</b> a bot junior's kills and level-ups give its senior reputation like a player's, capped a day
 *   ({@link FamilyEntry#botJuniorDailyRep}). Saved when the bot logs out.</li>
 * </ul>
 */
public final class BotFamily {

    private static final Logger log = LoggerFactory.getLogger(BotFamily.class);

    /** Why a bot did or did not accept (logged; the tests read it). */
    enum Verdict { ACCEPT, OFF, IN_FAMILY, WARY, STRANGER, ROLL }

    /** Why a bot did or did not offer (logged; the tests read it). */
    public enum Gate { OK, OFF, NOT_VISIBLE, AWAY, IN_FAMILY, STRANGER, WARY, SHORT_CHAT, LEVELS, PLAYER_FULL, TOO_SOON,
        PLAYER_DAILY_CAP, WORLD_CAP, ROLL }

    /**
     * 1.6.1 promise audit ("now and then one asks to be yours"): for this long after a bot whispered its offer, the
     * player's Add Junior on it works from anywhere in the world (it may have walked off the map by the time they open
     * the Family window) and it says yes: it asked.
     */
    static final long OFFER_WINDOW_MS = TimeUnit.MINUTES.toMillis(30);

    /** What a bot's offer is checked against. */
    record OfferCheck(boolean enabled, boolean visibleRealPlayer, boolean botHasFamily, boolean known, boolean wary,
                      int playerLines, int botLevel, int playerLevel, int playerJuniors, long pairAskedAt,
                      int playerAskedToday, int worldLastHour, double roll, long now) {
    }

    static final List<String> OFFER_LINES = List.of(
            "hey would u be my senior in family? u can add me as ur junior (family window > add junior)",
            "can i be ur junior? family button > add junior, i'd accept :)",
            "ur fun to talk to lol. if u want a junior in family im down, just add me",
            "random q but do u have a family? i need a senior, add me as junior if u want");
    static final List<String> ACCEPT_LINES = List.of("ty senior :)", "yay ok im ur junior now", "family time lol",
            "ok senior, ill make u proud", "accepted! ty");

    private static final Map<Long, Long> pairAsked = new ConcurrentHashMap<>();
    private static final Map<Integer, Deque<Long>> playerAsked = new ConcurrentHashMap<>();
    private static final Deque<Long> worldOffers = new ArrayDeque<>();
    private static volatile long certainUntil;

    private BotFamily() {
    }

    static boolean enabled() {
        return YamlConfig.config.server.USE_FAMILY_SYSTEM && !Boolean.getBoolean("slums.family.botsOff");
    }

    static double setting(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty("slums.family." + key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Auto-QA / !qa staging: for {@code ms} every bot that is not wary accepts at once (self-expires). */
    public static void certainFor(long ms) {
        certainUntil = ms <= 0 ? 0 : System.currentTimeMillis() + ms;
    }

    static boolean certain() {
        return certainUntil > System.currentTimeMillis();
    }

    // ── the rules ──

    /** Whether a bot accepts a player's junior invite. */
    static Verdict accepts(boolean enabled, boolean botHasFamily, BotBuddies.Familiarity f, boolean certain, double roll) {
        if (!enabled) {
            return Verdict.OFF;
        }
        if (botHasFamily) {
            return Verdict.IN_FAMILY;
        }
        if (f.wary()) {
            return Verdict.WARY;
        }
        if (certain) {
            return Verdict.ACCEPT;
        }
        if (!(f.known() || f.sameParty() || f.playerLines() >= 3)) {
            return Verdict.STRANGER; // only players it has interacted with
        }
        return roll < setting("acceptChance", 0.8) ? Verdict.ACCEPT : Verdict.ROLL;
    }

    /** Whether a bot may whisper this player an offer to be their junior, and if not, why not. */
    static Gate offerGate(OfferCheck c) {
        if (!c.enabled()) {
            return Gate.OFF;
        }
        if (!c.visibleRealPlayer()) {
            return Gate.NOT_VISIBLE;
        }
        if (c.botHasFamily()) {
            return Gate.IN_FAMILY;
        }
        if (c.wary()) {
            return Gate.WARY;
        }
        if (!c.known()) {
            return Gate.STRANGER;
        }
        if (c.playerLines() < 3) {
            return Gate.SHORT_CHAT;
        }
        if (c.botLevel() <= 10 || c.playerLevel() < c.botLevel() || c.playerLevel() - c.botLevel() > 20) {
            return Gate.LEVELS; // the stock invite's rules: junior above 10, within 20; the senior is not below
        }
        if (c.playerJuniors() >= 2) {
            return Gate.PLAYER_FULL;
        }
        if (c.pairAskedAt() > 0 && c.now() - c.pairAskedAt() < TimeUnit.DAYS.toMillis(7)) {
            return Gate.TOO_SOON;
        }
        if (c.playerAskedToday() >= 1) {
            return Gate.PLAYER_DAILY_CAP;
        }
        if (c.worldLastHour() >= (int) setting("offersPerHour", 3)) {
            return Gate.WORLD_CAP;
        }
        return c.roll() < setting("offerChance", 0.1) ? Gate.OK : Gate.ROLL;
    }

    // ── a player invites a bot ──

    /** FamilyAddHandler: {@code player} asked {@code bot} to be their junior (the invite is already registered). */
    public static void onInvite(Character bot, Character player) {
        if (bot == null || player == null) {
            return;
        }
        boolean fast = certain();
        boolean asked = offeredRecently(bot.getId(), player.getId(), System.currentTimeMillis());
        long delay = fast ? ThreadLocalRandom.current().nextLong(1, 3)
                : asked ? ThreadLocalRandom.current().nextLong(3, 13) // it asked: it is waiting for the invite
                : ThreadLocalRandom.current().nextLong((long) setting("acceptDelayMinSec", 10),
                (long) setting("acceptDelayMaxSec", 60) + 1);
        int botId = bot.getId(), playerId = player.getId();
        later.accept(TimeUnit.SECONDS.toMillis(delay), () -> {
            try {
                decide(botId, playerId);
            } catch (RuntimeException e) {
                log.warn("Bot {} could not answer {}'s family invite", bot.getName(), player.getName(), e);
            }
        });
    }

    static void decide(int botId, int playerId) {
        decide(liveBot(botId), onlinePlayer(playerId));
    }

    /** The bot answers the player's pending invite now. Returns its verdict (null when nothing was pending). */
    static Verdict decide(Character bot, Character player) {
        int botId = bot == null ? 0 : bot.getId(), playerId = player == null ? 0 : player.getId();
        if (bot == null || player == null || !InviteCoordinator.hasInvite(InviteCoordinator.InviteType.FAMILY, botId)) {
            if (bot != null) {
                InviteCoordinator.removeInvite(InviteCoordinator.InviteType.FAMILY, botId);
            }
            return null; // one of them left, or the invite expired
        }
        Verdict v = accepts(enabled(), bot.getFamily() != null, BotBuddies.familiarityOf(bot, playerId, player),
                certain() || offeredRecently(botId, playerId, System.currentTimeMillis()),
                ThreadLocalRandom.current().nextDouble());
        boolean yes = v == Verdict.ACCEPT;
        AcceptFamilyHandler.answer(bot, player, yes);
        log.info("Bot {} {} {}'s family invite ({})", bot.getName(), yes ? "accepted" : "declined", player.getName(), v);
        if (yes && bot.getFamily() != null) {
            BotWhispers.send(player, bot.getName(), bot.getClient().getChannel() - 1,
                    ACCEPT_LINES.get(ThreadLocalRandom.current().nextInt(ACCEPT_LINES.size())), true);
        }
        return v;
    }

    // ── a bot offers ──

    /** BotConversation: a chat between a bot and a player ended; rarely, the bot asks to be their junior. */
    public static void afterChat(Character bot, Character player, int playerLines) {
        if (bot == null || player == null || playerLines < 3 || !enabled() || !BotHelpers.isBot(bot)) {
            return; // the cheap checks first
        }
        long delay = ThreadLocalRandom.current().nextLong(30, 121);
        int botId = bot.getId(), playerId = player.getId();
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                maybeOffer(botId, playerId, playerLines);
            } catch (RuntimeException e) {
                log.warn("Bot {} could not decide on a family offer", botId, e);
            }
        }), delay, TimeUnit.SECONDS);
    }

    static Gate maybeOffer(int botId, int playerId, int playerLines) {
        return maybeOffer(liveBot(botId), onlinePlayer(playerId), playerLines, false);
    }

    /**
     * Auto-QA t-fy-2 (the offer, end to end): the bot offers to {@code player} now as if they had just had a real chat
     * it remembers (known, 3 lines) and the dice said yes, skipping only the rarity caps (unit-tested); every other
     * gate (same map, levels, room, the whisper being delivered) is real. The player's Add Junior then has to work.
     */
    public static Gate offerForQa(Character bot, Character player) {
        return maybeOffer(bot, player, 3, true);
    }

    static Gate maybeOffer(Character bot, Character player, int playerLines, boolean staged) {
        if (bot == null || player == null) {
            return Gate.NOT_VISIBLE;
        }
        int botId = bot.getId(), playerId = player.getId();
        if (bot.getMap() == null || bot.getMap() != player.getMap()) {
            return Gate.AWAY; // the chat ended and one of them left: "add me" would point at someone who is gone
        }
        long now = System.currentTimeMillis();
        BotBuddies.Familiarity real = BotBuddies.familiarityOf(bot, playerId, player);
        BotBuddies.Familiarity f = staged ? new BotBuddies.Familiarity(true, real.wary(), playerLines, false, 0.5) : real;
        FamilyEntry pe = player.getFamilyEntry();
        int juniors = pe == null ? 0 : (int) pe.getJuniors().stream().filter(j -> j != null).count();
        Long asked = pairAsked.get(pairKey(botId, playerId));
        OfferCheck check = new OfferCheck(enabled(), BotHelpers.isVisibleRealPlayer(player), bot.getFamily() != null,
                f.known(), f.wary(), playerLines, bot.getLevel(), player.getLevel(), juniors,
                staged || asked == null ? 0 : asked,
                staged ? 0 : recent(playerAsked.computeIfAbsent(playerId, k -> new ArrayDeque<>()), now, TimeUnit.DAYS.toMillis(1)),
                staged ? 0 : recent(worldOffers, now, TimeUnit.HOURS.toMillis(1)),
                staged ? 0.0 : ThreadLocalRandom.current().nextDouble(), now);
        Gate gate = offerGate(check);
        if (gate != Gate.OK) {
            return gate;
        }
        String line = OFFER_LINES.get(ThreadLocalRandom.current().nextInt(OFFER_LINES.size()));
        if (!BotWhispers.send(player, bot.getName(), bot.getClient().getChannel() - 1, line,
                SocialGate.Act.WHISPER)) {
            return Gate.NOT_VISIBLE; // @quiet, busy or the whisper budget: no offer, nothing noted
        }
        pairAsked.put(pairKey(botId, playerId), now);
        synchronized (BotFamily.class) {
            playerAsked.computeIfAbsent(playerId, k -> new ArrayDeque<>()).addLast(now);
            worldOffers.addLast(now);
        }
        log.info("Bot {} offered to be {}'s family junior", bot.getName(), player.getName());
        return gate;
    }

    /** Whether {@code bot} is wary of {@code player} (then it never offers or accepts). For QA staging. */
    public static boolean waryOf(Character bot, Character player) {
        return BotBuddies.familiarityOf(bot, player.getId(), player).wary();
    }

    /** Whether {@code botId} whispered {@code playerId} an offer within {@link #OFFER_WINDOW_MS}. */
    public static boolean offeredRecently(int botId, int playerId, long now) {
        Long at = pairAsked.get(pairKey(botId, playerId));
        return at != null && now - at <= OFFER_WINDOW_MS;
    }

    /** Tests: note an offer as made at {@code at}. */
    static void noteOfferForTest(int botId, int playerId, long at) {
        pairAsked.put(pairKey(botId, playerId), at);
    }

    /** QA teardown / tests: forget the offer between them. */
    public static void forgetOffer(int botId, int playerId) {
        pairAsked.remove(pairKey(botId, playerId));
    }

    private static synchronized int recent(Deque<Long> times, long now, long window) {
        while (!times.isEmpty() && now - times.peekFirst() > window) {
            times.pollFirst();
        }
        return times.size();
    }

    private static long pairKey(int botId, int playerId) {
        return ((long) botId << 32) ^ (playerId & 0xffffffffL);
    }

    // ── Summon Family on a bot junior (1.6.1 promise audit) ──

    /** Why a regular cannot come now (null: free). DramaKit's commitments; tests replace it. */
    static volatile BiFunction<Character, Character, String> busyFor =
            soloMapling.ArtificialPlayer.BotDramaSystem.DramaKit::whyNotFreeToCome;
    /** Brings a regular to the summoner; tests replace it. */
    static volatile BiPredicate<Character, Character> comeTo =
            soloMapling.ArtificialPlayer.BotDramaSystem.DramaKit::comeWhenCalled;
    /** Runs the answer after a delay in ms; tests run it at once. */
    static volatile BiConsumer<Long, Runnable> later = (ms, r) -> soloMapling.server.BotTiming.after(ms, r);

    /**
     * Whether a bot junior ({@code bot}, a regular or an owned alt) would come when {@code summoner} uses Summon
     * Family: null when it will say yes, else the summoner's line (and nothing is spent). A regular comes unless it is
     * in a commitment it can't drop ({@code DramaKit.whyNotFreeToCome}: an event or party quest, a scene, a party with
     * another player, a service job, lying dead). An owned alt travels with its owner: it comes to its owner, and to
     * nobody else.
     */
    public static String summonRefusal(Character bot, Character summoner) {
        if (bot == null) {
            return "That character can't be summoned.";
        }
        if (bot.isOwnedAlt()) {
            Character owner = bot.getOwnedAltOwner();
            if (owner != null && summoner != null && owner.getId() == summoner.getId()) {
                return null;
            }
            return bot.getName() + " is traveling with " + (owner == null ? "their owner" : owner.getName())
                    + " and can't come. Nothing was spent.";
        }
        if (!enabled()) {
            return bot.getName() + " can't come right now. Nothing was spent.";
        }
        String why = busyFor.apply(bot, summoner);
        return why == null ? null : bot.getName() + " can't come right now (" + why + "). Nothing was spent.";
    }

    /**
     * FamilyUseHandler: the summon request (already registered and paid for) reached a bot junior. It answers as a
     * player clicking Yes would, after a moment; if it got busy in that moment, it says no and the summoner gets the
     * reputation back (the stock decline path).
     */
    public static void onSummon(Character bot, Character summoner) {
        if (bot == null || summoner == null) {
            return;
        }
        long delay = certain() ? 300 : ThreadLocalRandom.current().nextLong(2_000, 6_001);
        later.accept(delay, () -> {
            try {
                answerSummon(bot, summoner);
            } catch (RuntimeException e) {
                log.warn("Bot {} could not answer {}'s family summon", bot.getName(), summoner.getName(), e);
            }
        });
    }

    /** The bot answers the waiting summon now. Returns whether it came. */
    static boolean answerSummon(Character bot, Character summoner) {
        boolean yes = summonRefusal(bot, summoner) == null;
        boolean came = net.server.channel.handlers.FamilySummonResponseHandler.answer(bot, yes);
        log.info("Bot {} {} {}'s family summon", bot.getName(), came ? "came to" : "did not come to", summoner.getName());
        if (came && !bot.isOwnedAlt()) {
            try {
                BotWhispers.send(summoner, bot.getName(), bot.getClient().getChannel() - 1,
                        SUMMON_LINES.get(ThreadLocalRandom.current().nextInt(SUMMON_LINES.size())), true);
            } catch (RuntimeException e) {
                log.debug("Bot {} could not whisper {}", bot.getName(), summoner.getName(), e);
            }
        }
        return came;
    }

    static final List<String> SUMMON_LINES = List.of("omw senior", "coming!", "here, what's up?", "on my way :)",
            "ok coming, what do u need?");

    /** Takes a bot junior that said yes to {@code inviter}'s map. True when it got there. */
    public static boolean arrive(Character bot, Character inviter) {
        if (bot.isOwnedAlt()) {
            int ch = inviter.getClient().getChannel();
            if (bot.getClient().getChannel() != ch && !BotChannels.moveToChannel(bot, ch)) {
                return false;
            }
            MapleMap map = BotChannels.map(ch, inviter.getMapId());
            if (map == null) {
                return false;
            }
            if (bot.getMapId() != map.getId()) {
                bot.changeMap(map, map.findClosestPortal(inviter.getPosition()));
            }
            return bot.getMapId() == map.getId();
        }
        return comeTo.test(bot, inviter);
    }

    // ── a bot logs in or out ──

    /** BotGeneration: link a logging-in bot to its Family entry, if it is someone's junior. */
    public static void onBotLogin(Character bot) {
        if (bot == null || !YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return;
        }
        try {
            FamilyEntry entry = entryOf(bot.getWorldServer(), bot.getId());
            if (entry != null) {
                entry.setCharacter(bot);
                entry.announceToSenior(tools.PacketCreator.sendFamilyLoginNotice(bot.getName(), true), true);
            }
        } catch (RuntimeException e) {
            log.warn("Could not link bot {} to its family", bot.getName(), e);
        }
    }

    /** BotGeneration: a bot leaving saves its (and an offline senior's) reputation and unlinks. */
    public static void onBotLogout(Character bot) {
        FamilyEntry entry = bot == null ? null : bot.getFamilyEntry();
        if (entry == null) {
            return;
        }
        try {
            entry.announceToSenior(tools.PacketCreator.sendFamilyLoginNotice(bot.getName(), false), true);
            if (entry.saveReputation()) {
                entry.savedSuccessfully();
            }
            FamilyEntry senior = entry.getSenior();
            if (senior != null && senior.getChr() == null && senior.saveReputation()) {
                senior.savedSuccessfully();
            }
            entry.setCharacter(null);
            bot.setFamilyEntry(null);
        } catch (RuntimeException e) {
            log.warn("Could not save the family reputation of bot {}", bot.getName(), e);
        }
    }

    static FamilyEntry entryOf(World world, int charId) {
        if (world == null) {
            return null;
        }
        for (Family f : world.getFamilies()) {
            FamilyEntry e = f.getEntryByID(charId);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static Character liveBot(int charId) {
        BotSM bot = CharacterStorage.getBotById(charId);
        return bot == null ? null : bot.getChr();
    }

    private static Character onlinePlayer(int playerId) {
        World world = SoloMaplingUtilities.world();
        Character chr = world == null ? null : world.getPlayerStorage().getCharacterById(playerId);
        return chr == null || BotHelpers.isBot(chr) || !chr.isLoggedinWorld() ? null : chr;
    }
}
