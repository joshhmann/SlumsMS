package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import client.Ring;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotBuddies;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.SerialWrites;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;
import tools.PacketCreator;
import tools.packets.WeddingPackets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botSitChair;

/**
 * 1.5.0 bot drama, part 1: romance between a real player and a bot. <b>Opt-in only</b>: a player who never typed
 * {@code @romance on} (the default) is never flirted with, asked out or proposed to by any bot, and the chat brain is
 * told not to flirt with them ({@link #promptLine}, {@link #replyAllowed}).
 *
 * <ul>
 *   <li><b>Dating:</b> a friend bot that likes the player a lot flirts and may ask them out ("yes" / "no" in map chat
 *   answers it); a player can ask a friend bot out ("wanna go out with me?"). A partner shows up for the player now and
 *   then (logs in, comes to their channel), follows them to a new map sometimes, sits with them on a couple chair,
 *   sends a cute whisper now and then (never while the player is busy, capped per player and world-wide), and is
 *   jealous of the player's rivals and of other bots who whisper them.</li>
 *   <li><b>Marriage:</b> the stock ring-box proposal works on a bot partner (any genders) and a partner proposes too;
 *   the wedding ({@link RomanceWedding}) writes the stock rows (marriages, rings, the player's ring) and a bot spouse
 *   logs in and out like any bot: spouse chat and the ring look handle an offline spouse. Divorce is the stock one (drop
 *   the ring) or {@code @romance divorce}.</li>
 *   <li><b>Drama:</b> neglect ({@code neglectDays} without standing on one map) ends a dating or engaged pair - a
 *   breakup megaphone, the ex turns rival, trash-talks (about the game), rebounds with another bot, and comes back as
 *   an old flame months later. Dating two bots: they find out and confront it in public chat. Gossip ("saw X with Y at
 *   Amoria") goes into nearby bots' memory and the partner's guild chat.</li>
 * </ul>
 * Every hostile or public part respects {@code @botdrama off} (then a breakup is a quiet whisper, no megaphone, no
 * rival); every megaphone goes through the megaphone gate. Cheap: everything runs only for players online with a
 * romance, from the director's tick every 30 s.
 */
public final class BotRomance {

    private static final Logger log = LoggerFactory.getLogger(BotRomance.class);

    public enum EndReason { NEGLECT, TWO_TIMING, ROMANCE_OFF, PLAYER, DIVORCE, RUNAWAY, GM }

    private static volatile RomanceStore store = new RomanceStore.Jdbc();
    private static final Map<Long, Romance> rows = new ConcurrentHashMap<>();
    private static final Set<Integer> optedIn = ConcurrentHashMap.newKeySet();
    private static volatile boolean loaded;
    private static volatile long loadTriedAt;
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static final SerialWrites writes = new SerialWrites(r -> writer.execute(r));
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile IntFunction<Character> bots = DramaKit::liveBot;
    private static volatile IntFunction<Character> players = DramaKit::onlinePlayer;
    /** Whether a bot slot is a roster member (stored, logs in and out); a seam for tests. */
    private static volatile java.util.function.IntPredicate roster = BotRoster::isMember;
    private static volatile long lastStepAt;
    /** Whether new-feelings notes go to bot memory (off in tests). */
    private static volatile boolean notesOn = true;

    /** A bot asked the player out and waits for a yes or a no: pair -> until. */
    private static final Map<Long, Long> askedOut = new ConcurrentHashMap<>();
    /** A bot proposed to the player (the stock request window is open): player id -> bot character id. */
    private static final Map<Integer, Integer> botProposals = new ConcurrentHashMap<>();
    /** Two-timing pairs already confronted: (player, older bot slot, newer bot slot). */
    private static final Set<String> confronted = ConcurrentHashMap.newKeySet();
    /** A partner warned a neglected player: pair -> when. */
    private static final Map<Long, Long> neglectWarnedAt = new ConcurrentHashMap<>();
    /** An ex's rebound: ex bot character id -> the new partner's name. */
    private static final Map<Integer, String> rebounds = new ConcurrentHashMap<>();
    /** Cute whispers per player today: player id -> {day, count}. */
    private static final Map<Integer, long[]> whispersToday = new ConcurrentHashMap<>();
    /** The last map each player with a romance stood on (to see a map change). */
    private static final Map<Integer, Integer> lastMap = new ConcurrentHashMap<>();

    private BotRomance() {
    }

    // ── switches and settings ──

    public static boolean enabled() {
        return DramaKit.enabled() && !"false".equalsIgnoreCase(System.getProperty("solomapling.drama.romance", "true"));
    }

    static long now() {
        return clock.getAsLong();
    }

    static long key(int charId, int botSlot) {
        return ((long) charId << 32) | (botSlot & 0xffffffffL);
    }

    static int slot(int botCharId) {
        return botCharId - BotStore.BOT_ID_BASE;
    }

    // ── loading ──

    /** Reads every romance and opt-in once (boot or first use); a failed read is tried again after a minute. */
    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (BotRomance.class) {
            long now = now();
            if (loaded || (loadTriedAt > 0 && now - loadTriedAt < TimeUnit.MINUTES.toMillis(1))) {
                return;
            }
            loadTriedAt = now;
            List<Romance> all;
            Set<Integer> in;
            try {
                all = store.loadAll();
                in = all == null ? null : store.loadOptedIn();
            } catch (RuntimeException e) {
                all = null;
                in = null;
            }
            if (all == null || in == null) {
                log.warn("Bot romance could not be read; trying again in a minute (nothing romantic happens meanwhile)");
                return;
            }
            for (Romance r : all) {
                rows.putIfAbsent(key(r.charId(), r.botId()), r);
            }
            optedIn.addAll(in);
            loaded = true;
        }
    }

    static boolean loaded() {
        return loaded;
    }

    private static void save(Romance r) {
        rows.put(key(r.charId(), r.botId()), r);
        if (!r.staged()) {
            writes.submit(r.charId(), () -> store.save(r));
        }
    }

    private static void forget(Romance r) {
        rows.remove(key(r.charId(), r.botId()));
        if (!r.staged()) {
            writes.submit(r.charId(), () -> store.delete(r.charId(), r.botId()));
        }
    }

    // ── queries (memory only) ──

    /** Whether the player turned {@code @romance on} (off by default). */
    public static boolean romanceOn(int playerId) {
        return optedIn.contains(playerId);
    }

    /** The player's romance with this bot (any stage), or null. */
    public static Romance with(int playerId, int botCharId) {
        return rows.get(key(playerId, slot(botCharId)));
    }

    /** The player's current partners (dating, engaged or married), oldest first. */
    public static List<Romance> partners(int playerId) {
        List<Romance> out = new ArrayList<>();
        for (Romance r : rows.values()) {
            if (r.charId() == playerId && r.stage().together()) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(Romance::startedAt));
        return out;
    }

    /** Every romance of the player, partners and exes. */
    public static List<Romance> all(int playerId) {
        List<Romance> out = new ArrayList<>();
        for (Romance r : rows.values()) {
            if (r.charId() == playerId) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(Romance::startedAt));
        return out;
    }

    /** The player this bot is with now (dating, engaged or married), or null. A bot is with one player at a time. */
    public static Romance ofBot(int botCharId) {
        if (rows.isEmpty() || !BotHelpers.isArtificial(botCharId)) {
            return null;
        }
        int s = slot(botCharId);
        for (Romance r : rows.values()) {
            if (r.botId() == s && r.stage().together()) {
                return r;
            }
        }
        return null;
    }

    /** Whether a bot is taken: with a player, or in a bot couple. */
    public static boolean taken(int botCharId) {
        return ofBot(botCharId) != null || BotCouples.coupleOf(botCharId) != null;
    }

    /** The wedding ring a bot married to a player wears (0 = none): the couple sweep leaves it on. */
    public static int ringWornBy(int botCharId) {
        Romance r = ofBot(botCharId);
        return r != null && r.stage() == Romance.Stage.MARRIED ? r.ringItemId() : 0;
    }

    // ── @romance ──

    /**
     * {@code @romance on|off}. Off ends every dating or engaged romance of the player quietly (no megaphone, no
     * grudge); a marriage stays (divorce is a separate choice).
     */
    public static List<String> setRomance(Character player, boolean on) {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        int id = player.getId();
        long now = now();
        if (on) {
            optedIn.add(id);
        } else {
            optedIn.remove(id);
            for (Romance r : partners(id)) {
                if (r.stage() == Romance.Stage.MARRIED) {
                    out.add("You are still married to " + nameOf(r.botCharId()) + ": @romance divorce to end it.");
                } else {
                    end(r, EndReason.ROMANCE_OFF, false);
                    out.add("You and " + nameOf(r.botCharId()) + " are no longer together.");
                }
            }
            askedOut.keySet().removeIf(k -> (int) (k >>> 32) == id);
            botProposals.remove(id);
        }
        writes.submit(id, () -> store.setOptedIn(id, on, now));
        qa(player.getMapId(), "@romance " + (on ? "on" : "off") + " " + player.getName());
        return out;
    }

    // ── ticket 59: the conversation layer's gates (public views of the romance rules) ──

    /** Whether a line asks a bot out ("wanna go out", "be my gf"). */
    public static boolean asksOutLine(String line) {
        return RomanceRules.asksOut(line);
    }

    /** Whether this bot may flirt with this player now: romance on, dating, or a crush the rules allow. */
    public static boolean mayFlirtWith(int botCharId, int playerId) {
        if (!enabled() || !romanceOn(playerId)) {
            return false;
        }
        Romance r = with(playerId, botCharId);
        if (r != null && r.stage().together()) {
            return true;
        }
        return RomanceRules.mayFlirt(true, BotAffinity.state(botCharId, playerId), BotAffinity.score(botCharId, playerId),
                taken(botCharId));
    }

    // ── the chat brain ──

    /** What the chat prompt adds about romance with this player; "" when there is nothing to say. */
    public static String promptLine(int botCharId, Character player) {
        if (player == null || !enabled() || BotHelpers.isBot(player)) {
            return "";
        }
        Romance r = with(player.getId(), botCharId);
        String name = player.getName();
        if (!romanceOn(player.getId())) {
            return " Never flirt with or hit on " + name + ": keep it friendly.";
        }
        if (r != null && r.stage().together()) {
            String what = switch (r.stage()) {
                case MARRIED -> "married to";
                case ENGAGED -> "engaged to";
                default -> "dating";
            };
            return " You are " + what + " " + name + " in the game: be flirty, cheesy and dramatic in a cute way. Keep it"
                    + " PG-13: never sexual or explicit.";
        }
        if (r != null && r.stage() == Romance.Stage.EX) {
            return " " + name + " is your ex: be salty and a bit dramatic about the game, never about real life, never"
                    + " explicit.";
        }
        Romance mine = ofBot(botCharId);
        if (mine != null) {
            return " You are taken (you are with " + nameOf(mine.charId()) + "): if " + name + " flirts, say so kindly.";
        }
        if (RomanceRules.mayFlirt(true, BotAffinity.state(botCharId, player.getId()), BotAffinity.score(botCharId,
                player.getId()), taken(botCharId))) {
            return " You have a little crush on " + name + ": you may flirt a bit, cheesy and cute, PG-13, never"
                    + " explicit.";
        }
        return "";
    }

    /**
     * Whether a model reply may go to the player: a flirt only to a player with romance on who dates the bot or whom it
     * may flirt with; nothing explicit or off-limits ever (also checked in the brain).
     */
    public static boolean replyAllowed(Character bot, Character player, String reply) {
        if (reply == null || bot == null || player == null) {
            return true;
        }
        if (!ContentGuard.ok(reply)) {
            return false;
        }
        if (!ContentGuard.flirty(reply)) {
            return true;
        }
        if (!romanceOn(player.getId())) {
            return false;
        }
        Romance r = with(player.getId(), bot.getId());
        if (r != null && r.stage().together()) {
            return true;
        }
        return RomanceRules.mayFlirt(true, BotAffinity.state(bot.getId(), player.getId()),
                BotAffinity.score(bot.getId(), player.getId()), taken(bot.getId()));
    }

    /**
     * A player's line to a bot (map chat or whisper, through the chat brain): asking it out or to marry. The bot's
     * answer, or null when it is not about romance.
     */
    public static String onPlayerLine(Character bot, Character player, String line) {
        if (!enabled() || bot == null || player == null || BotHelpers.isBot(player)) {
            return null;
        }
        ensureLoaded();
        if (!loaded) {
            return null;
        }
        Map<String, String> t = Map.of("PLAYER", player.getName(), "BOT", bot.getName());
        if (RomanceRules.asksOut(line)) {
            if (!romanceOn(player.getId())) {
                player.dropMessage(5, "(Bots only date players who turned romance on: @romance on)");
                return DramaKit.pick("RomanceNotInterested", t);
            }
            Romance r = with(player.getId(), bot.getId());
            if (r != null && r.stage().together()) {
                return DramaKit.pick("RomanceAlreadyTogether", t);
            }
            Romance other = ofBot(bot.getId());
            if (other != null || BotCouples.coupleOf(bot.getId()) != null) {
                return DramaKit.pick("RomanceTaken", t);
            }
            if (RomanceRules.saysYes(true, BotAffinity.state(bot.getId(), player.getId()),
                    BotAffinity.score(bot.getId(), player.getId()), false)) {
                startDating(bot, player, false);
                return DramaKit.pick("RomanceSaidYes", t);
            }
            qa(player.getMapId(), "ASKED OUT " + bot.getName() + " by " + player.getName() + ": no (not close enough)");
            return DramaKit.pick("RomanceSaidNo", t);
        }
        if (RomanceRules.asksToMarry(line) && romanceOn(player.getId())) {
            Romance r = with(player.getId(), bot.getId());
            if (r != null && r.stage() == Romance.Stage.DATING) {
                return DramaKit.pick("RomanceRingHint", t);
            }
        }
        return null;
    }

    /**
     * A player's map chat line (the general chat hook, packet thread): the answer to a bot that just asked them out.
     * True when it answered a question (the line is not also routed to the bots' chat).
     */
    public static boolean onPlayerChat(Character player, String line) {
        if (askedOut.isEmpty() || player == null) {
            return false;
        }
        long now = now();
        for (Map.Entry<Long, Long> e : askedOut.entrySet()) {
            if ((int) (e.getKey() >>> 32) != player.getId()) {
                continue;
            }
            if (now > e.getValue()) {
                askedOut.remove(e.getKey());
                continue;
            }
            int botCharId = BotStore.BOT_ID_BASE + (int) (e.getKey() & 0xffffffffL);
            Character bot = bots.apply(botCharId);
            if (bot == null || bot.getMapId() != player.getMapId()) {
                continue;
            }
            Boolean yes = RomanceRules.yesNo(line);
            if (yes == null) {
                return false;
            }
            askedOut.remove(e.getKey());
            ExecutorServiceManager.runAsync(() -> answered(bot, player, yes));
            return true;
        }
        return false;
    }

    private static void answered(Character bot, Character player, boolean yes) {
        Map<String, String> t = Map.of("PLAYER", player.getName());
        if (yes && romanceOn(player.getId()) && ofBot(bot.getId()) == null) {
            startDating(bot, player, false);
            DramaKit.say(bot, "RomanceYay", t);
        } else {
            DramaKit.say(bot, "RomanceRejected", t);
            qa(player.getMapId(), "REJECTED " + bot.getName() + " by " + player.getName());
        }
    }

    // ── on sight ──

    /**
     * A roaming bot noticed a real player near it (driver thread; memory only): a partner says hi or proposes, an ex
     * trash-talks or comes back as an old flame, a bot with a crush flirts or asks them out. True when it did something.
     */
    public static boolean onSight(BotSM sm, Character player) {
        if (!enabled() || !loaded || sm == null || sm.getChr() == null || player == null) {
            return false;
        }
        Character bot = sm.getChr();
        if (bot.getMapId() != player.getMapId() || !romanceOn(player.getId())) {
            return false;
        }
        long now = now();
        int pid = player.getId();
        long pair = key(pid, slot(bot.getId()));
        Romance r = rows.get(pair);
        Random rng = ThreadLocalRandom.current();
        if (r != null && r.stage().together()) {
            if (r.stage() == Romance.Stage.DATING && RomanceRules.readyToPropose(r, now) && rng.nextDouble() < 0.2
                    && DramaKit.take("rm:propose:" + pair, now, TimeUnit.DAYS.toMillis(1))) {
                ExecutorServiceManager.runAsync(() -> botProposes(bot, player));
                return true;
            }
            if (DramaKit.take("rm:hi:" + pair, now, TimeUnit.MINUTES.toMillis(30))) {
                ExecutorServiceManager.runAsync(() -> DramaKit.say(bot, "RomancePartnerHello",
                        Map.of("PLAYER", player.getName())));
                return true;
            }
            return false;
        }
        if (r != null && r.stage() == Romance.Stage.EX) {
            if (RomanceRules.oldFlame(r, now) && ofBot(bot.getId()) == null && rng.nextDouble() < 0.3
                    && DramaKit.take("rm:flame:" + pair, now, TimeUnit.DAYS.toMillis(7))) {
                ExecutorServiceManager.runAsync(() -> oldFlame(bot, player));
                return true;
            }
            if (!BotAffinity.optedOut(pid) && rng.nextDouble() < 0.5
                    && DramaKit.take("rm:ex:" + pair, now, TimeUnit.MINUTES.toMillis(30))) {
                String rebound = rebounds.get(bot.getId());
                ExecutorServiceManager.runAsync(() -> DramaKit.say(bot, rebound != null && rng.nextBoolean()
                        ? "RomanceExRebound" : "RomanceExTaunt", tokens(player, bot, rebound)));
                return true;
            }
            return false;
        }
        AffinityState state = BotAffinity.state(bot.getId(), pid);
        double score = BotAffinity.score(bot.getId(), pid);
        boolean botTaken = taken(bot.getId());
        if (partners(pid).isEmpty() && RomanceRules.mayAskOut(true, state, score, botTaken, false)
                && rng.nextDouble() < 0.3 && DramaKit.take("rm:ask:" + pair, now, TimeUnit.DAYS.toMillis(1))
                && DramaKit.hourly("rm-ask", 6, now)) {
            ExecutorServiceManager.runAsync(() -> askOut(bot, player));
            return true;
        }
        if (RomanceRules.mayFlirt(true, state, score, botTaken)
                && DramaKit.take("rm:flirt:" + pair, now, TimeUnit.MINUTES.toMillis(20))
                && DramaKit.hourly("rm-flirt", 30, now)) {
            ExecutorServiceManager.runAsync(() -> {
                String line = DramaKit.say(bot, "RomanceFlirt", Map.of("PLAYER", player.getName()));
                qa(bot.getMapId(), "FLIRT " + bot.getName() + " -> " + player.getName() + ": " + line);
            });
            return true;
        }
        return false;
    }

    private static Map<String, String> tokens(Character player, Character bot, String other) {
        Map<String, String> t = new HashMap<>();
        t.put("PLAYER", player.getName());
        t.put("BOT", bot.getName());
        if (other != null) {
            t.put("OTHER", other);
        }
        t.put("MAP", DramaKit.mapName(player));
        return t;
    }

    /** The bot asks the player out; "yes" or "no" in map chat within two minutes answers. */
    public static String askOut(Character bot, Character player) {
        if (!romanceOn(player.getId())) {
            return player.getName() + " does not have @romance on";
        }
        long pair = key(player.getId(), slot(bot.getId()));
        askedOut.put(pair, now() + TimeUnit.MINUTES.toMillis(2));
        String line = DramaKit.say(bot, "RomanceAskOut", Map.of("PLAYER", player.getName()));
        qa(bot.getMapId(), "ASK OUT " + bot.getName() + " -> " + player.getName() + ": " + line + " (waiting for yes/no)");
        return bot.getName() + " asked " + player.getName() + " out: " + line;
    }

    private static void oldFlame(Character bot, Character player) {
        Map<String, String> t = Map.of("PLAYER", player.getName());
        DramaKit.say(bot, "RomanceOldFlame", t);
        BotAffinity.stage(bot.getId(), player.getId(), AffinityState.FRIEND); // the grudge is gone; feelings are back
        qa(bot.getMapId(), "OLD FLAME " + bot.getName() + " is back for " + player.getName());
        soloMapling.server.BotTiming.after(TimeUnit.SECONDS.toMillis(8), () -> {
            if (romanceOn(player.getId()) && partners(player.getId()).isEmpty() && ofBot(bot.getId()) == null) {
                askOut(bot, player);
            }
        });
    }

    // ── together ──

    /** The bot and the player are dating now. A second partner arms the two-timing confrontation. */
    public static Romance startDating(Character bot, Character player, boolean staged) {
        ensureLoaded();
        long now = now();
        Romance before = with(player.getId(), bot.getId());
        Romance r = new Romance(player.getId(), slot(bot.getId()), Romance.Stage.DATING, now, now, 0, 0, 0,
                staged || (before != null && before.staged()) || !roster.test(slot(bot.getId())));
        save(r);
        BotAffinity.record(bot.getId(), player.getId(), AffinityEvent.ROMANCE, AffinityEvent.ROMANCE.delta(),
                player.getName(), bot.getName(), player.getMapId());
        note(bot.getId(), player.getId(), "we are dating <3");
        int others = partners(player.getId()).size() - 1;
        qa(player.getMapId(), "DATING " + bot.getName() + " + " + player.getName() + (others > 0 ? " (two-timing: "
                + others + " other partner(s))" : ""));
        return r;
    }

    /** A note in the bot's memory about the player (its chat and gossip carry it). Off the caller's thread. */
    static void note(int botCharId, int playerId, String text) {
        if (!notesOn || !BotHelpers.isArtificial(botCharId)) {
            return;
        }
        writer.execute(() -> {
            try {
                BotMemory.remember(slot(botCharId), playerId, List.of(text), BotMemory.Source.DIRECT, 0, 0, now());
            } catch (RuntimeException e) {
                log.debug("romance note", e);
            }
        });
    }

    /**
     * Ends a romance now. {@code drama}: a breakup megaphone (through the gate), the ex turns rival and later rebounds;
     * without it (or for a player with {@code @botdrama off}) the bot only whispers. An engaged pair's stock engagement
     * ends too (the player must be online); a marriage only through {@link #divorced}.
     */
    public static boolean end(Romance r, EndReason why, boolean drama) {
        if (r == null || !r.stage().together()) {
            return false;
        }
        long now = now();
        Character player = players.apply(r.charId());
        Character bot = bots.apply(r.botCharId());
        if (r.stage() == Romance.Stage.ENGAGED) {
            if (player == null) {
                return false; // the stock engagement can only be undone with the player here
            }
            endEngagement(player, bot, r.botCharId());
        }
        Romance ex = r.withStage(Romance.Stage.EX, now);
        save(ex);
        neglectWarnedAt.remove(key(r.charId(), r.botId()));
        if (bot != null) {
            bot.setPartnerId(-1);
            bot.setMarriageItemId(-1);
            bot.addMarriageRing(null);
        }
        boolean loud = drama && !BotAffinity.optedOut(r.charId()) && BotAffinity.hostilityEnabled();
        String name = player != null ? player.getName() : "#" + r.charId();
        Map<String, String> t = new HashMap<>();
        t.put("PLAYER", name == null ? "" : name);
        if (bot != null) {
            t.put("BOT", bot.getName());
        }
        if (loud && bot != null) {
            boolean sent = WorldEventMegaphones.getInstance().offerEvent(bot, "DramaBreakup", t,
                    "drama:breakup:" + r.charId() + ":" + r.botId() + ":" + now);
            if (player != null) {
                DramaKit.whisper(bot, player, DramaKit.pick(why == EndReason.TWO_TIMING ? "RomanceDumpedCheater"
                        : "RomanceDumped", t));
            }
            if (player != null) {
                BotAffinity.stage(bot.getId(), player.getId(), AffinityState.RIVAL);
            }
            note(r.botCharId(), r.charId(), why == EndReason.TWO_TIMING ? "my ex, cheated on me" : "my ex, ignored me");
            qa(player == null ? 0 : player.getMapId(), "BREAKUP " + (bot.getName()) + " dumps " + name + " ("
                    + why.name().toLowerCase() + "): megaphone " + (sent ? "sent" : "queued/gated") + ", ex is a rival now");
            scheduleRebound(bot);
        } else {
            if (bot != null && player != null) {
                DramaKit.whisper(bot, player, DramaKit.pick("RomanceQuietBreakup", t));
            }
            qa(player == null ? 0 : player.getMapId(), "BREAKUP quiet " + r.botCharId() + " / " + name + " ("
                    + why.name().toLowerCase() + ")");
        }
        return true;
    }

    /** The stock engagement of a player and a bot ends: tokens gone, partner ids cleared, the marriages row deleted. */
    static void endEngagement(Character player, Character bot, int botCharId) {
        try {
            if (player.getPartnerId() == botCharId) {
                net.server.world.World w = player.getWorldServer();
                if (w.getRelationshipId(player.getId()) > 0) {
                    w.deleteRelationship(player.getId(), botCharId);
                }
                int token = player.getMarriageItemId();
                removeTokens(player, token);
                player.setPartnerId(-1);
                player.setMarriageItemId(-1);
                player.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(0, 0));
            }
        } catch (RuntimeException e) {
            log.warn("Could not end {}'s engagement to bot {}", player.getName(), botCharId, e);
        }
        if (bot != null) {
            bot.setPartnerId(-1);
            bot.setMarriageItemId(-1);
        }
    }

    /** Removes every engagement token the player holds (and the given one, a carat ring for a carat box). */
    static void removeTokens(Character player, int recorded) {
        for (int id = 4031357; id <= 4031364; id++) {
            removeAll(player, id);
        }
        if (recorded > 0 && !RomanceRules.engagementToken(recorded) && recorded / 1000 == 1112) {
            removeAll(player, recorded);
            removeAll(player, recorded + 1);
        }
    }

    private static void removeAll(Character player, int itemId) {
        InventoryType type = InventoryType.getByType((byte) (itemId / 1000000));
        if (type == null) {
            return;
        }
        int n = player.getInventory(type).countById(itemId);
        if (n > 0) {
            InventoryManipulator.removeById(player.getClient(), type, itemId, n, false, false);
        }
    }

    private static void scheduleRebound(Character ex) {
        long delay = QaLog.on(QaLog.Topic.ROMANCE) ? TimeUnit.SECONDS.toMillis(20)
                : TimeUnit.MINUTES.toMillis(2 + ThreadLocalRandom.current().nextInt(9));
        int exId = ex.getId();
        soloMapling.server.BotTiming.after(delay, () -> {
            Character me = bots.apply(exId);
            if (me == null || taken(exId) || me.getMap() == null) {
                return;
            }
            Character pick = null;
            for (Character c : me.getMap().getAllPlayers()) {
                if (BotHelpers.isBot(c) && c.getId() != exId && !taken(c.getId()) && DramaKit.freeForScene(c)) {
                    pick = c;
                    break;
                }
            }
            if (pick == null) {
                for (BotSM sm : CharacterStorage.getAllBots().values()) {
                    Character c = sm.getChr();
                    if (c != null && c.getId() != exId && DramaKit.channelOf(c) == DramaKit.channelOf(me)
                            && !taken(c.getId()) && DramaKit.freeForScene(c) && Math.abs(c.getLevel() - me.getLevel()) <= 30) {
                        pick = c;
                        break;
                    }
                }
            }
            if (pick == null) {
                return;
            }
            boolean staged = !roster.test(slot(exId)) || !roster.test(slot(pick.getId()));
            if (BotCouples.form(exId, pick.getId(), BotCouples.Stage.DATING, staged, now()) != null) {
                rebounds.put(exId, pick.getName());
                qa(me.getMapId(), "REBOUND " + me.getName() + " is dating " + pick.getName() + " now");
            }
        });
    }

    // ── engagement and marriage (stock ring boxes) ──

    /**
     * {@code RingActionHandler}: a player proposed to a bot with a ring box (the stock checks passed: levels, free
     * slots, nobody married or engaged, same map). The bot answers in a few seconds like a player would.
     */
    public static void proposalToBot(Character player, Character bot, int boxItemId) {
        ensureLoaded();
        long delay = QaLog.on(QaLog.Topic.ROMANCE) ? 2 : 3 + ThreadLocalRandom.current().nextInt(4);
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                answerProposal(player, bot, boxItemId);
            } catch (RuntimeException e) {
                log.warn("Bot {} could not answer {}'s proposal", bot.getName(), player.getName(), e);
                player.setMarriageItemId(-1);
                player.sendPacket(WeddingPackets.OnMarriageResult((byte) 0));
            }
        }), delay, TimeUnit.SECONDS);
    }

    static void answerProposal(Character player, Character bot, int boxItemId) {
        Romance r = with(player.getId(), bot.getId());
        AffinityState state = BotAffinity.state(bot.getId(), player.getId());
        Map<String, String> t = Map.of("PLAYER", player.getName());
        if (!RomanceRules.acceptsRing(romanceOn(player.getId()), r, state) || player.getMarriageItemId() != boxItemId
                || !player.haveItem(boxItemId) || player.getPartnerId() > 0 || bot.getPartnerId() > 0) {
            player.dropMessage(1, bot.getName() + " has politely declined your engagement request.");
            player.sendPacket(WeddingPackets.OnMarriageResult((byte) 0));
            player.setMarriageItemId(-1);
            DramaKit.say(bot, r == null || !r.stage().together() ? "RomanceRingNotDating" : "RomanceRingNo", t);
            qa(player.getMapId(), "PROPOSAL to " + bot.getName() + " by " + player.getName() + ": declined ("
                    + (r == null ? "not dating" : r.stage().label()) + ", romance " + romanceOn(player.getId()) + ")");
            return;
        }
        int token = RomanceRules.engagementTokenOf(boxItemId);
        if (!InventoryManipulator.checkSpace(player.getClient(), token, 1, "")) {
            player.dropMessage(5, "You don't have a ETC slot available right now!");
            player.sendPacket(WeddingPackets.OnMarriageResult((byte) 0));
            player.setMarriageItemId(-1);
            return;
        }
        InventoryManipulator.removeById(player.getClient(), InventoryType.USE, boxItemId, 1, false, false);
        engage(player, bot, player.getId(), bot.getId(), token, token + 1, boxItemId, r);
        DramaKit.say(bot, "RomanceRingYes", t);
    }

    /** Both sides of a stock engagement, as {@code RingActionHandler} does for two players. */
    private static void engage(Character player, Character bot, int groomId, int brideId, int playerToken, int botToken,
                               int boxItemId, Romance r) {
        int marriageId = player.getWorldServer().createRelationship(groomId, brideId);
        player.setPartnerId(bot.getId());
        bot.setPartnerId(player.getId());
        player.setMarriageItemId(playerToken);
        bot.setMarriageItemId(botToken);
        InventoryManipulator.addById(player.getClient(), playerToken, (short) 1);
        player.sendPacket(WeddingPackets.OnMarriageResult(marriageId, player, false));
        player.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(bot.getId(), bot.getMapId()));
        long now = now();
        Romance engaged = (r == null ? new Romance(player.getId(), slot(bot.getId()), Romance.Stage.DATING, now, now, 0,
                0, 0, !roster.test(slot(bot.getId()))) : r).withStage(Romance.Stage.ENGAGED, now)
                .withRing(RomanceRules.weddingRingOf(boxItemId), 0);
        save(engaged);
        note(bot.getId(), player.getId(), "we are engaged!!");
        qa(player.getMapId(), "ENGAGED " + player.getName() + " + " + bot.getName() + " (marriage #" + marriageId
                + ", token " + playerToken + ", ring " + engaged.ringItemId() + ")");
    }

    /**
     * The partner proposes: the stock proposal window opens on the player's screen (accept or decline). Needs what the
     * stock proposal needs (level 50 both, nobody engaged or married, a free ETC slot).
     */
    public static String botProposes(Character bot, Character player) {
        Romance r = with(player.getId(), bot.getId());
        if (r == null || r.stage() != Romance.Stage.DATING || !romanceOn(player.getId())) {
            return bot.getName() + " is not dating " + player.getName();
        }
        String problem = player.isMarried() || player.getPartnerId() > 0 ? player.getName() + " is engaged or married"
                : player.getMarriageItemId() > 0 ? player.getName() + " has a proposal open"
                : player.getLevel() < 50 || bot.getLevel() < 50 ? "both must be level 50+ (stock rule)"
                : bot.getPartnerId() > 0 ? bot.getName() + " is engaged already"
                : !InventoryManipulator.checkSpace(player.getClient(), 4031358, 1, "") ? "no free ETC slot" : null;
        if (problem != null) {
            qa(player.getMapId(), "BOT PROPOSAL " + bot.getName() + " -> " + player.getName() + " held: " + problem);
            return problem;
        }
        bot.setMarriageItemId(2240000);
        botProposals.put(player.getId(), bot.getId());
        DramaKit.say(bot, "RomanceBotProposes", Map.of("PLAYER", player.getName()));
        player.sendPacket(WeddingPackets.onMarriageRequest(bot.getName(), bot.getId()));
        qa(player.getMapId(), "BOT PROPOSAL " + bot.getName() + " -> " + player.getName() + ": the proposal window is open");
        return bot.getName() + " proposed to " + player.getName() + " (accept in the window)";
    }

    /**
     * {@code RingActionHandler} mode 2 where the proposer is a bot: the player accepted or declined. True when it was a
     * bot's proposal (handled here).
     */
    public static boolean playerAnsweredBot(Character player, Character bot, boolean accepted) {
        Integer asked = botProposals.remove(player.getId());
        if (asked == null || asked != bot.getId()) {
            return false;
        }
        Map<String, String> t = Map.of("PLAYER", player.getName());
        bot.setMarriageItemId(-1);
        Romance r = with(player.getId(), bot.getId());
        if (!accepted || r == null || r.stage() != Romance.Stage.DATING || player.getPartnerId() > 0) {
            DramaKit.say(bot, "RomanceProposalTurnedDown", t);
            qa(player.getMapId(), "BOT PROPOSAL " + bot.getName() + " -> " + player.getName() + ": declined");
            return true;
        }
        int token = RomanceRules.engagementTokenOf(2240000);
        engage(player, bot, bot.getId(), player.getId(), token + 1, token, 2240000, r);
        DramaKit.say(bot, "RomanceYay", t);
        return true;
    }

    /** The wedding made it real: the stock rings are created and the row says married. */
    static Romance married(Romance r, int botRingId, long now) {
        Romance m = r.withStage(Romance.Stage.MARRIED, now).withRing(r.ringItemId(), botRingId);
        save(m);
        return m;
    }

    /**
     * A stock divorce or broken engagement ({@code RingActionHandler.breakMarriage / breakEngagement}) between a
     * player and a bot: the bot side follows (its ring comes off, the row turns ex). The player's side is done by the
     * stock code.
     */
    public static void onStockBreak(Character player, int botCharId, boolean marriage) {
        if (player == null || !BotHelpers.isArtificial(botCharId)) {
            return;
        }
        ensureLoaded();
        Romance r = with(player.getId(), botCharId);
        Character bot = bots.apply(botCharId);
        if (bot != null) {
            bot.setPartnerId(-1);
            bot.setMarriageItemId(-1);
            bot.addMarriageRing(null);
            BotCouples.takeOffCoupleRings(bot, 0);
        }
        if (r != null && r.stage().together()) {
            save(r.withStage(Romance.Stage.EX, now()));
            if (bot != null) {
                DramaKit.say(bot, marriage ? "RomanceDivorced" : "RomanceEngagementOff", Map.of("PLAYER",
                        player.getName()));
            }
            note(botCharId, player.getId(), marriage ? "my ex, we divorced" : "my ex, broke off our engagement");
        }
        qa(player.getMapId(), (marriage ? "DIVORCE " : "ENGAGEMENT OFF ") + player.getName() + " / " + botCharId);
    }

    /**
     * {@code @romance divorce}: the stock way - the player's wedding ring (in the equip inventory) is dropped and the
     * marriage rows go - for a marriage to a bot. What happened.
     */
    public static String divorce(Character player) {
        int partner = player.getPartnerId();
        if (partner <= 0 || !BotHelpers.isArtificial(partner)) {
            return "You are not married to a bot (a player marriage: drop your wedding ring, the stock way).";
        }
        Ring ring = player.getMarriageRing();
        if (ring == null) {
            return "You are engaged, not married: drop your engagement box to break it off.";
        }
        Item inEquip = player.getInventory(InventoryType.EQUIP).findById(ring.getItemId());
        if (inEquip == null) {
            return "Take your wedding ring off first (it must be in your equip inventory), then @romance divorce again.";
        }
        net.server.channel.handlers.RingActionHandler.breakMarriageRing(player, ring.getItemId());
        return "Divorced. Your ring is gone.";
    }

    /**
     * GM / !qa cleanup: the player's stock engagement or marriage to this bot ends without drama (a staged bot that is
     * about to leave the world must not stay anyone's spouse). Rows, rings, tokens and partner ids all go.
     */
    public static void undoStock(Character player, int botCharId) {
        if (player == null) {
            return;
        }
        if (player.getPartnerId() == botCharId) {
            try {
                net.server.world.World w = player.getWorldServer();
                if (w.getRelationshipId(player.getId()) > 0) {
                    w.deleteRelationship(player.getId(), botCharId);
                }
                Ring ring = player.getMarriageRing();
                if (ring != null) {
                    Ring.removeRing(ring);
                    removeRingItem(player, ring.getItemId());
                }
                removeTokens(player, player.getMarriageItemId());
                player.setPartnerId(-1);
                player.setMarriageItemId(-1);
                player.addMarriageRing(null);
                player.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(0, 0));
            } catch (RuntimeException e) {
                log.warn("Could not undo {}'s engagement or marriage to bot {}", player.getName(), botCharId, e);
            }
        }
        Romance r = with(player.getId(), botCharId);
        if (r != null && r.stage().together()) {
            save(r.withStage(Romance.Stage.EX, now()));
        }
    }

    private static void removeRingItem(Character player, int itemId) {
        Item worn = player.getInventory(InventoryType.EQUIPPED).findById(itemId);
        if (worn != null) {
            short dst = (short) player.getInventory(InventoryType.EQUIP).getNextFreeSlot();
            if (dst > 0) {
                InventoryManipulator.unequip(player.getClient(), worn.getPosition(), dst);
            }
        }
        Item item = player.getInventory(InventoryType.EQUIP).findById(itemId);
        if (item != null) {
            InventoryManipulator.removeFromSlot(player.getClient(), InventoryType.EQUIP, item.getPosition(), (short) 1,
                    false);
        }
    }

    // ── a bot logs in or out ──

    /** A bot joins the world: an engaged or married bot knows its player again (spouse chat, the ring look). */
    public static void onBotLogin(Character bot) {
        if (!enabled() || bot == null) {
            return;
        }
        try {
            ensureLoaded();
            Romance r = ofBot(bot.getId());
            if (r == null || r.stage() == Romance.Stage.DATING) {
                return;
            }
            bot.setPartnerId(r.charId());
            bot.setMarriageItemId(r.stage() == Romance.Stage.MARRIED ? r.ringItemId() : 4031358);
            if (r.stage() == Romance.Stage.MARRIED && r.botRingId() > 0) {
                Ring ring = Ring.loadFromDb(r.botRingId());
                if (ring != null) {
                    ring.equip();
                    bot.addMarriageRing(ring);
                }
                BotCouples.wearRing(bot, r.ringItemId());
            }
        } catch (RuntimeException e) {
            log.warn("Bot {} could not take up its romance", bot.getName(), e);
        }
    }

    /** {@code SpouseChatHandler}: the player's spouse (or fiance) is a bot that is online: it answers in spouse chat. */
    public static void spouseChat(Character player, Character bot, String line) {
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        if (sm == null) {
            return;
        }
        qa(player.getMapId(), "SPOUSE CHAT " + player.getName() + " -> " + bot.getName() + ": " + line);
        BotConversation.talk(sm, player, line, reply -> player.sendPacket(PacketCreator.OnCoupleMessage(bot.getName(),
                reply, true)));
    }

    // ── a player changes map ──

    /** A player entered a map: a partner may follow them there now and then (the romance part of a map change). */
    static void onMapChanged(Character player, int from) {
        if (!enabled() || !loaded || !romanceOn(player.getId()) || player.getEventInstance() != null) {
            return;
        }
        long now = now();
        for (Romance r : partners(player.getId())) {
            Character bot = bots.apply(r.botCharId());
            if (bot == null || bot.getMapId() != from || DramaKit.channelOf(bot) != DramaKit.channelOf(player)
                    || !DramaKit.freeForSceneWith(bot, player)) {
                if (bot != null && bot.getMapId() == from) {
                    qa(player.getMapId(), "FOLLOW held: " + bot.getName() + " is busy (" + whyNotFree(bot) + ")");
                }
                continue;
            }
            boolean qa = QaLog.on(QaLog.Topic.ROMANCE);
            if ((!qa && ThreadLocalRandom.current().nextDouble() >= DramaKit.prop("romance.followChance", 0.35))
                    || !DramaKit.take("rm:follow:" + player.getId(), now, qa ? TimeUnit.SECONDS.toMillis(30)
                    : DramaKit.minutes("romance.followGapMinutes", 15))) {
                continue;
            }
            int map = player.getMapId();
            soloMapling.server.BotTiming.after(3_000 + ThreadLocalRandom.current().nextInt(3_000), () -> follow(bot,
                    player, map));
            break;
        }
    }

    private static void follow(Character bot, Character player, int map) {
        if (player.getMapId() != map || !DramaKit.freeForSceneWith(bot, player)) {
            return;
        }
        boolean grind = CharacterStorage.getBotById(bot.getId()) instanceof TrainingBot && MapMobIndex.level(map) >= 0;
        if (!DramaKit.bringTo(bot, player, 40, grind)) {
            return;
        }
        DramaKit.say(bot, "RomanceFollow", Map.of("PLAYER", player.getName()));
        qa(map, "FOLLOW " + bot.getName() + " followed " + player.getName() + " to " + map);
        if (grind) {
            int id = bot.getId();
            soloMapling.server.BotTiming.after(DramaKit.minutes("romance.followHoldMinutes", 10),
                    () -> soloMapling.worldevents.WorldEventSteer.unhold(id));
        }
    }

    /** Why a partner could not follow (the QA log). */
    static String whyNotFree(Character bot) {
        if (bot.getEventInstance() != null) {
            return "in an event";
        }
        if (DramaKit.inScene(bot.getId())) {
            return "in a scene: " + DramaKit.sceneOf(bot.getId());
        }
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        if (!(sm instanceof TrainingBot) && !(sm instanceof soloMapling.ArtificialPlayer.BotTypes.SocialBot)) {
            return "a " + (sm == null ? "gone" : sm.getClass().getSimpleName()) + " bot";
        }
        return "with another player, trading or following someone";
    }

    // ── jealousy ──

    /** Another bot whispered the player: an online partner gets jealous now and then. */
    public static void onBotWhispered(int fromBotId, Character player) {
        if (!enabled() || !loaded || player == null || !romanceOn(player.getId())) {
            return;
        }
        long now = now();
        for (Romance r : partners(player.getId())) {
            if (r.botCharId() == fromBotId) {
                continue;
            }
            Character partner = bots.apply(r.botCharId());
            if (partner == null || !DramaKit.take("rm:jealous-w:" + player.getId(), now, TimeUnit.HOURS.toMillis(2))) {
                continue;
            }
            String other = nameOf(fromBotId);
            Map<String, String> t = new HashMap<>();
            t.put("PLAYER", player.getName());
            if (other != null) {
                t.put("OTHER", other);
            }
            soloMapling.server.BotTiming.after(8_000, () -> DramaKit.whisper(partner, player,
                    DramaKit.pick("RomanceJealousWhisper", t)));
            qa(player.getMapId(), "JEALOUS " + partner.getName() + ": " + other + " whispered " + player.getName());
            return;
        }
    }

    // ── the step ──

    /** From the population director's tick: at most every 30 s, off the tick's thread. */
    public static void maybeStep(long now) {
        if (!enabled() || now - lastStepAt < TimeUnit.SECONDS.toMillis(30)) {
            return;
        }
        lastStepAt = now;
        ExecutorServiceManager.runAsync(() -> {
            try {
                step(now(), ThreadLocalRandom.current());
            } catch (RuntimeException e) {
                log.warn("Bot romance step failed", e);
            }
        });
    }

    /** GM / !qa: the step now on this thread. */
    public static void stepNow() {
        step(now(), ThreadLocalRandom.current());
    }

    static void step(long now, Random rng) {
        ensureLoaded();
        if (!loaded || rows.isEmpty()) {
            return;
        }
        Map<Integer, List<Romance>> byPlayer = new HashMap<>();
        for (Romance r : rows.values()) {
            if (r.stage().together()) {
                byPlayer.computeIfAbsent(r.charId(), k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<Integer, List<Romance>> e : byPlayer.entrySet()) {
            Character player = players.apply(e.getKey());
            if (player == null) {
                continue;
            }
            List<Romance> mine = e.getValue();
            mine.sort(Comparator.comparingLong(Romance::startedAt));
            for (Romance r : mine) {
                try {
                    tend(r, player, now, rng);
                } catch (RuntimeException ex) {
                    log.debug("romance tend", ex);
                }
            }
            if (mine.size() >= 2 && romanceOn(player.getId())) {
                twoTiming(player, mine.get(mine.size() - 2), mine.get(mine.size() - 1), now, false);
            }
        }
        long cutoff = now;
        askedOut.values().removeIf(t -> t < cutoff);
    }

    /** One partner and their online player, once per step. */
    static void tend(Romance r, Character player, long now, Random rng) {
        long pair = key(r.charId(), r.botId());
        Character bot = bots.apply(r.botCharId());
        boolean on = romanceOn(player.getId());
        if (bot == null) {
            if (on && roster.test(r.botId()) && !r.staged()
                    && DramaKit.take("rm:showup:" + pair, now, TimeUnit.MINUTES.toMillis(30)) && rng.nextDouble() < 0.7
                    && BotPopulationDirector.logInBounded(r.botId())) {
                qa(player.getMapId(), "SHOW UP partner " + r.botCharId() + " logs in for " + player.getName());
            }
            neglectCheck(r, player, null, now);
            return;
        }
        if (!on) {
            return;
        }
        boolean sameMap = bot.getMapId() == player.getMapId() && DramaKit.channelOf(bot) == DramaKit.channelOf(player);
        if (sameMap) {
            if (now - r.togetherAt() > TimeUnit.MINUTES.toMillis(5)) {
                Romance t = r.together(now);
                rows.put(pair, t);
                if (now - r.togetherAt() > TimeUnit.MINUTES.toMillis(30)) {
                    save(t);
                }
                r = t;
            }
            neglectWarnedAt.remove(pair);
            BotAffinity.record(bot.getId(), player.getId(), AffinityEvent.ROMANCE, AffinityEvent.ROMANCE.delta(),
                    player.getName(), bot.getName(), player.getMapId());
            coupleChair(bot, player);
            jealousOfRivals(bot, player, now);
            gossip(r, bot, player, now, rng);
        } else if (DramaKit.channelOf(bot) != DramaKit.channelOf(player) && DramaKit.freeForScene(bot)
                && DramaKit.take("rm:hop:" + pair, now, TimeUnit.MINUTES.toMillis(10))) {
            BotChannels.moveToChannel(bot, DramaKit.channelOf(player));
        }
        cuteWhisper(bot, player, now, rng);
        neglectCheck(r, player, bot, now);
    }

    private static void neglectCheck(Romance r, Character player, Character bot, long now) {
        if (!RomanceRules.neglected(r, now)) {
            return;
        }
        long pair = key(r.charId(), r.botId());
        Long warned = neglectWarnedAt.get(pair);
        if (warned == null) {
            if (bot != null) {
                neglectWarnedAt.put(pair, now);
                DramaKit.whisper(bot, player, DramaKit.pick("RomanceNeglectWarning", Map.of("PLAYER", player.getName())));
                qa(player.getMapId(), "NEGLECT warning " + bot.getName() + " -> " + player.getName());
            }
            return;
        }
        if (now - warned >= DramaKit.minutes("romance.neglectGraceMinutes", 30) && bot != null) {
            end(r, EndReason.NEGLECT, true);
        }
    }

    private static void coupleChair(Character bot, Character player) {
        int chair = player.getChair();
        if (chair <= 0 || bot.getChair() == chair || !isCoupleChair(chair) || bot.getPosition() == null
                || player.getPosition() == null || bot.getPosition().distance(player.getPosition()) > 500
                || !(CharacterStorage.getBotById(bot.getId()) instanceof soloMapling.ArtificialPlayer.BotTypes.SocialBot)
                || !DramaKit.freeForScene(bot)
                || !DramaKit.take("rm:chair:" + player.getId(), now(), TimeUnit.MINUTES.toMillis(5))) {
            return;
        }
        try {
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot);
            java.awt.Point at = new java.awt.Point(player.getPosition());
            at.translate(-30, 0);
            bot.changeMap(player.getMap(), at);
            botSitChair(bot, chair);
            DramaKit.say(bot, "RomanceChair", Map.of("PLAYER", player.getName()));
            qa(player.getMapId(), "CHAIR " + bot.getName() + " sits with " + player.getName() + " on " + chair);
        } catch (RuntimeException e) {
            log.debug("couple chair", e);
        }
    }

    static boolean isCoupleChair(int chair) {
        for (int c : BotCouples.COUPLE_CHAIRS) {
            if (c == chair) {
                return true;
            }
        }
        return false;
    }

    private static void jealousOfRivals(Character partner, Character player, long now) {
        if (partner.getMap() == null) {
            return;
        }
        for (Character c : partner.getMap().getAllPlayers()) {
            if (BotHelpers.isBot(c) && c.getId() != partner.getId() && BotAffinity.hostile(c.getId(), player.getId())
                    && DramaKit.take("rm:jealous-r:" + player.getId(), now, TimeUnit.MINUTES.toMillis(30))) {
                DramaKit.say(partner, "RomanceJealousRival", Map.of("PLAYER", player.getName(), "OTHER", c.getName()));
                qa(player.getMapId(), "JEALOUS " + partner.getName() + " at rival " + c.getName() + " of " + player.getName());
                return;
            }
        }
    }

    /** "saw X with Y at Amoria": a bot watching them writes it down (gossip passes it on) and tells its guild. */
    private static void gossip(Romance r, Character partner, Character player, long now, Random rng) {
        if (BotAffinity.optedOut(player.getId()) || partner.getMap() == null
                || !DramaKit.take("rm:gossip:" + key(r.charId(), r.botId()), now,
                QaLog.on(QaLog.Topic.ROMANCE) ? TimeUnit.MINUTES.toMillis(1) : DramaKit.hours("romance.gossipHours", 6))) {
            return;
        }
        List<Character> watchers = new ArrayList<>();
        for (Character c : partner.getMap().getAllPlayers()) {
            if (BotHelpers.isBot(c) && c.getId() != partner.getId() && !DramaKit.inScene(c.getId())) {
                watchers.add(c);
            }
        }
        if (watchers.isEmpty()) {
            DramaKit.release("rm:gossip:" + key(r.charId(), r.botId()));
            return;
        }
        Character watcher = watchers.get(rng.nextInt(watchers.size()));
        String text = "saw " + player.getName() + " with " + partner.getName() + " at " + DramaKit.mapName(player);
        note(watcher.getId(), player.getId(), text);
        String line = DramaKit.pick("RomanceGossip", Map.of("PLAYER", player.getName(), "BOT", partner.getName(), "MAP",
                DramaKit.mapName(player)));
        if (line != null && watcher.getGuildId() > 0) {
            soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.sayInGuild(watcher, line);
        }
        qa(player.getMapId(), "GOSSIP " + watcher.getName() + " saw " + player.getName() + " with " + partner.getName()
                + (watcher.getGuildId() > 0 ? " (told its guild)" : ""));
    }

    private static void cuteWhisper(Character bot, Character player, long now, Random rng) {
        if (busy(player) || rng.nextDouble() >= 0.25) {
            return;
        }
        long day = Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
        long[] today = whispersToday.computeIfAbsent(player.getId(), k -> new long[]{day, 0});
        synchronized (today) {
            if (today[0] != day) {
                today[0] = day;
                today[1] = 0;
            }
            if (today[1] >= (long) DramaKit.prop("romance.whispersPerDay", 4)) {
                return;
            }
            if (!DramaKit.take("rm:whisper:" + player.getId(), now, DramaKit.minutes("romance.whisperGapMinutes", 40))
                    || !DramaKit.hourly("rm-whisper", (int) DramaKit.prop("romance.whispersPerHour", 30), now)) {
                return;
            }
            today[1]++;
        }
        String line = DramaKit.pick("RomanceCuteWhisper", Map.of("PLAYER", player.getName(), "MAP",
                DramaKit.mapName(bot)));
        DramaKit.whisper(bot, player, line);
        qa(player.getMapId(), "WHISPER " + bot.getName() + " -> " + player.getName() + ": " + line);
    }

    /** The busy hold-off: no cute whisper in an event or PQ, a trade, a shop or game room, or an NPC chat. */
    static boolean busy(Character player) {
        try {
            return player.getEventInstance() != null || player.getTrade() != null || player.getPlayerShop() != null
                    || player.getMiniGame() != null || (player.getClient() != null && player.getClient().getCM() != null)
                    || BotConversation.partnerOf(player.getId()) >= 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ── two-timing ──

    /**
     * A player dating two bots: they find out (both online, a while after the second started) and confront it on the
     * player's map in public chat; the one cheated on dumps the player (sometimes both do). {@code force} (GM / !qa)
     * skips the wait. With {@code @botdrama off} there is no scene: the newer one ends it by whisper.
     */
    static String twoTiming(Character player, Romance older, Romance newer, long now, boolean force) {
        String k = player.getId() + ":" + older.botId() + ":" + newer.botId();
        if (confronted.contains(k)) {
            return "already confronted";
        }
        Character a = bots.apply(older.botCharId());
        Character b = bots.apply(newer.botCharId());
        if (a == null || b == null) {
            return "both partners must be online";
        }
        long wait = QaLog.on(QaLog.Topic.ROMANCE) ? TimeUnit.SECONDS.toMillis(30)
                : DramaKit.minutes("romance.findOutMinutes", 20);
        if (!force && now - newer.startedAt() < wait) {
            return "they have not found out yet";
        }
        confronted.add(k);
        if (BotAffinity.optedOut(player.getId()) || !BotAffinity.hostilityEnabled()) {
            end(newer, EndReason.TWO_TIMING, false);
            return "quiet: " + b.getName() + " ended it by whisper (@botdrama off)";
        }
        if (!DramaKit.freeForScene(a) || !DramaKit.freeForScene(b)) {
            confronted.remove(k);
            return "a partner is busy";
        }
        DramaKit.Home homeA = DramaKit.homeOf(a);
        DramaKit.Home homeB = DramaKit.homeOf(b);
        DramaKit.busy(a.getId(), "two-timing scene");
        DramaKit.busy(b.getId(), "two-timing scene");
        boolean aHere = a.getMapId() == player.getMapId() || DramaKit.bringTo(a, player, -70, false);
        boolean bHere = b.getMapId() == player.getMapId() || DramaKit.bringTo(b, player, 70, false);
        if (!aHere || !bHere) {
            DramaKit.unbusy(a.getId());
            DramaKit.unbusy(b.getId());
            confronted.remove(k);
            return "could not bring both to " + player.getName();
        }
        Map<String, String> ta = Map.of("PLAYER", player.getName(), "OTHER", b.getName());
        Map<String, String> tb = Map.of("PLAYER", player.getName(), "OTHER", a.getName());
        boolean both = ThreadLocalRandom.current().nextDouble() < 0.3;
        qa(player.getMapId(), "TWO-TIMING " + player.getName() + " dates " + a.getName() + " and " + b.getName()
                + ": confrontation on " + player.getMapId());
        soloMapling.server.BotTiming.chain()
                .pause(1_500).run(() -> DramaKit.say(a, "RomanceCaught1", ta))
                .pause(3_500).run(() -> DramaKit.say(b, "RomanceCaught2", tb))
                .pause(3_500).run(() -> DramaKit.say(a, "RomanceCaught3", ta))
                .pause(3_500).run(() -> DramaKit.say(b, "RomanceCaught4", tb))
                .pause(3_000).run(() -> {
                    end(older, EndReason.TWO_TIMING, true);
                    if (both) {
                        end(newer, EndReason.TWO_TIMING, true);
                    } else {
                        DramaKit.say(b, "RomanceCaughtStays", tb);
                    }
                })
                .pause(15_000).run(() -> {
                    DramaKit.unbusy(a.getId());
                    DramaKit.unbusy(b.getId());
                    DramaKit.sendHome(a, homeA);
                    DramaKit.sendHome(b, homeB);
                })
                .start();
        return "confrontation: " + a.getName() + " vs " + b.getName() + (both ? " (both dump " + player.getName() + ")" : "");
    }

    // ── a player changes map (from BotDrama) ──

    /** Remembers the player's map; returns the one they came from, or -1 (first sighting or same map). */
    static int movedFrom(Character player) {
        Integer before = lastMap.put(player.getId(), player.getMapId());
        if (lastMap.size() > 5_000) {
            lastMap.clear();
        }
        return before == null || before == player.getMapId() ? -1 : before;
    }

    // ── GM ──

    public static List<String> describe(int playerId) {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        long now = now();
        out.add("romance " + (romanceOn(playerId) ? "ON" : "off") + ", @botdrama " + (BotAffinity.optedOut(playerId)
                ? "off" : "on"));
        for (Romance r : all(playerId)) {
            Character bot = bots.apply(r.botCharId());
            out.add(nameOf(r.botCharId()) + (bot != null ? "*" : "") + ": " + r.stage().label() + " for "
                    + TimeUnit.MILLISECONDS.toHours(now - r.startedAt()) + " h, together "
                    + TimeUnit.MILLISECONDS.toMinutes(now - r.togetherAt()) + " min ago"
                    + (r.ringItemId() > 0 ? ", ring " + r.ringItemId() : "") + (r.staged() ? ", staged" : "")
                    + (RomanceRules.neglected(r, now) ? ", NEGLECTED" : "") + (RomanceRules.oldFlame(r, now) ? ", old flame" : ""));
        }
        if (out.size() == 1) {
            out.add("no romance yet");
        }
        return out;
    }

    /** GM / !qa: moves a romance's clock back (neglect, a proposal, an old flame) without waiting days. */
    public static boolean backdate(int playerId, int botCharId, long byMs) {
        Romance r = with(playerId, botCharId);
        if (r == null) {
            return false;
        }
        Romance b = r.backdated(r.startedAt() - byMs, r.togetherAt() - byMs, r.endedAt() > 0 ? r.endedAt() - byMs : 0);
        save(b);
        neglectWarnedAt.remove(key(playerId, slot(botCharId)));
        return true;
    }

    /** GM / !qa: confront a two-timing player now. */
    public static String confrontNow(Character player) {
        List<Romance> mine = partners(player.getId());
        if (mine.size() < 2) {
            return player.getName() + " dates fewer than two bots";
        }
        Romance older = mine.get(mine.size() - 2);
        Romance newer = mine.get(mine.size() - 1);
        confronted.remove(player.getId() + ":" + older.botId() + ":" + newer.botId());
        return twoTiming(player, older, newer, now(), true);
    }

    /** GM / !qa: a cute whisper now (caps skipped, the busy hold-off still holds). */
    public static String whisperNow(Character player) {
        for (Romance r : partners(player.getId())) {
            Character bot = bots.apply(r.botCharId());
            if (bot != null) {
                if (busy(player) && BotConversation.partnerOf(player.getId()) != bot.getId()) {
                    // rc6 (QA t-rm-1): chatting with this partner itself is no reason to hold its whisper
                    return "held: you are busy (event, trade, shop, NPC chat or mid-chat with another bot)";
                }
                String line = DramaKit.pick("RomanceCuteWhisper", Map.of("PLAYER", player.getName(), "MAP",
                        DramaKit.mapName(bot)));
                DramaKit.whisper(bot, player, line);
                return bot.getName() + " whispers: " + line;
            }
        }
        return "no online partner";
    }

    /** GM / !qa: ends a romance now with full drama. */
    public static String breakUpNow(int playerId, int botCharId, EndReason why) {
        Romance r = with(playerId, botCharId);
        if (r == null || !r.stage().together()) {
            return "they are not together";
        }
        if (r.stage() == Romance.Stage.MARRIED) {
            return "married: use @romance divorce (or drop the ring)";
        }
        neglectWarnedAt.put(key(playerId, slot(botCharId)), 0L);
        return end(r, why, true) ? "broken up (" + why.name().toLowerCase() + ")" : "could not (the player must be online)";
    }

    /** GM / !qa: an old flame now (the ex must be on the player's map). */
    public static String flameNow(Character player, Character bot) {
        Romance r = with(player.getId(), bot.getId());
        if (r == null || r.stage() != Romance.Stage.EX) {
            return bot.getName() + " is not " + player.getName() + "'s ex";
        }
        oldFlame(bot, player);
        return "old flame: " + bot.getName() + " is back";
    }

    /** Staged rows (memory only) of a bot that leaves: forget them. */
    public static void forgetBot(int botCharId) {
        int s = slot(botCharId);
        rows.values().removeIf(r -> r.botId() == s && r.staged());
        rebounds.remove(botCharId);
    }

    static String nameOf(int botCharId) {
        String n = BotBuddies.nameOf(botCharId);
        return n == null ? "#" + botCharId : n;
    }

    static String nameOfPlayer(int charId) {
        Character c = players.apply(charId);
        return c != null ? c.getName() : client.Character.getNameById(charId);
    }

    static void qa(int mapId, String line) {
        DramaKit.qa(QaLog.Topic.ROMANCE, mapId, line);
    }

    // ── test seams ──

    static void useForTest(RomanceStore testStore, IntFunction<Character> testBots, IntFunction<Character> testPlayers,
                           LongSupplier testClock) {
        store = testStore != null ? testStore : new RomanceStore.Jdbc();
        bots = testBots != null ? testBots : DramaKit::liveBot;
        players = testPlayers != null ? testPlayers : DramaKit::onlinePlayer;
        clock = testClock != null ? testClock : System::currentTimeMillis;
        writer = testStore != null ? Runnable::run : ExecutorServiceManager::runAsync;
        notesOn = testStore == null;
        roster = testStore != null ? id -> false : BotRoster::isMember;
        rows.clear();
        optedIn.clear();
        askedOut.clear();
        botProposals.clear();
        confronted.clear();
        neglectWarnedAt.clear();
        rebounds.clear();
        whispersToday.clear();
        lastMap.clear();
        loaded = false;
        loadTriedAt = 0;
        lastStepAt = 0;
    }

    static void putForTest(Romance r) {
        rows.put(key(r.charId(), r.botId()), r);
    }

    static void optInForTest(int playerId) {
        optedIn.add(playerId);
    }
}
