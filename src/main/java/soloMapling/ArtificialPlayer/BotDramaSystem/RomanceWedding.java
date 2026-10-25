package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import client.Ring;
import client.inventory.Equip;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples;
import soloMapling.ArtificialPlayer.BotCoupleSystem.OutfitLoans;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;
import tools.PacketCreator;
import tools.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A real wedding between a player and the bot they are engaged to (1.5.0 bot drama), in Amoria where the player stands.
 * The stock Cathedral/Chapel instances need two real players (reservations, NPC talks, an event instance on both), so
 * the ceremony is staged in Amoria town, but what it leaves behind is the stock marriage: the engagement tokens go, the
 * {@code rings} pair is created ({@code Ring.createRing}), the player gets their wedding ring (with its ring id, so the
 * ring look shows) and both carry the marriage ring - exactly what the stock vows step does
 * ({@code RingActionHandler.giveMarriageRings}). The {@code marriages} row came with the engagement.
 *
 * <p>Guests: the player's online friend bots come (up to three) and a few new faces fill it to five; one officiates.
 * The player says "i do" in map chat (it goes on by itself after a while). Drama, never for a player with
 * {@code @botdrama off}: a jealous ex (or a bot with a crush) shouts "I OBJECT", now and then the bot runs from the altar
 * (no marriage, still engaged, try again later), and an ex or a rival may crash the reception.
 */
public final class RomanceWedding {

    private static final Logger log = LoggerFactory.getLogger(RomanceWedding.class);

    public static final int AMORIA = 680000000;
    static final int TUXEDO = 1050113, WEDDING_DRESS = 1051114;
    static final short OVERALL_SLOT = -105;
    static final int GUESTS = 5;

    /** Forced drama for the next wedding (GM / !qa). */
    public enum Drama { OBJECTION, RUNAWAY, CRASH }

    private static final Set<Drama> forced = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean noRandomRunaway; // QA rm-4: the next wedding is staged for the vows and the crash
    private static volatile Ceremony current;

    private static final Pattern I_DO = Pattern.compile("(?i)\\b(i do|i will|yes|yess+|ya|yeah|of course|ofc|absolutely)\\b");

    static final class Ceremony {
        final int playerId;
        final int botId;
        final int channel;
        final long startedAt;
        final List<Integer> guests = new ArrayList<>();          // generated guests: they leave the world after
        final Map<Integer, DramaKit.Home> friends = new HashMap<>(); // the player's friend bots: they go home after
        final Set<String> fired = new HashSet<>();
        final DramaKit.Home botHome;
        volatile int objector;
        volatile boolean saidIDo;
        volatile long vowsAskedAt;
        volatile boolean married;
        volatile boolean over;
        volatile long pronouncedAt;
        boolean objection, runaway, crash;
        ScheduledFuture<?> beat;

        Ceremony(int playerId, int botId, int channel, long startedAt, DramaKit.Home botHome) {
            this.playerId = playerId;
            this.botId = botId;
            this.channel = channel;
            this.startedAt = startedAt;
            this.botHome = botHome;
        }
    }

    private RomanceWedding() {
    }

    public static boolean running() {
        Ceremony c = current;
        return c != null && !c.over;
    }

    public static void force(Drama d) {
        forced.add(d);
    }

    /**
     * QA rm-4: the staged wedding is watched for MARRIED and CRASH, and the random runaway beat (8%, at +38 s at the
     * latest) would end it before either. Turns that beat off for the next wedding only; a {@link #force(Drama) forced}
     * runaway still runs away.
     */
    public static void noRandomRunaway() {
        noRandomRunaway = true;
    }

    /** The runaway beat of the next wedding: a forced one wins, a staged one has the random roll off. */
    public static boolean runawayBeat(boolean allowed, double roll) {
        boolean forcedRunaway = forced.remove(Drama.RUNAWAY);
        boolean off = noRandomRunaway;
        noRandomRunaway = false;
        return allowed && (forcedRunaway
                || (!off && roll < DramaKit.prop("wedding.runawayChance", 0.08)));
    }

    /** Starts the wedding of the player and their fiance now; the problem, or null when it started. */
    public static synchronized String start(Character player) {
        if (!BotRomance.enabled()) {
            return "romance is switched off";
        }
        if (running()) {
            return "a wedding is already on";
        }
        Romance r = null;
        for (Romance p : BotRomance.partners(player.getId())) {
            if (p.stage() == Romance.Stage.ENGAGED) {
                r = p;
            }
        }
        if (r == null) {
            return "you are not engaged to a bot (propose with a ring box from Moony first)";
        }
        if (player.getMapId() != AMORIA) {
            return "come to Amoria (map " + AMORIA + ") first";
        }
        if (player.getPartnerId() != r.botCharId()) {
            return "the stock engagement is not on your character (partner " + player.getPartnerId() + ")";
        }
        int ringItem = r.ringItemId() > 0 ? r.ringItemId() : 1112803;
        if (!InventoryManipulator.checkSpace(player.getClient(), ringItem, 1, "")) {
            return "make room for the ring in your equip inventory";
        }
        Character bot = DramaKit.liveBot(r.botCharId());
        if (bot == null) {
            if (BotRoster.isMember(r.botId()) && BotPopulationDirector.logInBounded(r.botId())) {
                return BotRomance.nameOf(r.botCharId()) + " is on the way: try again in a few seconds";
            }
            return BotRomance.nameOf(r.botCharId()) + " is not around right now";
        }
        if (!DramaKit.freeForScene(bot)) {
            return bot.getName() + " is busy (a trade, an event, a party with someone) - try again soon";
        }
        DramaKit.Home home = DramaKit.homeOf(bot);
        if (!DramaKit.bringTo(bot, player, 40, false)) {
            return bot.getName() + " could not come to Amoria channel " + DramaKit.channelOf(player);
        }
        Ceremony c = new Ceremony(player.getId(), bot.getId(), DramaKit.channelOf(player), BotRomance.now(), home);
        DramaKit.busy(bot.getId(), "wedding");
        boolean allowed = !BotAffinity.optedOut(player.getId()) && BotAffinity.hostilityEnabled();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        c.objection = allowed && (forced.remove(Drama.OBJECTION) || rng.nextDouble() < DramaKit.prop("wedding.objectChance", 0.35));
        c.runaway = runawayBeat(allowed, rng.nextDouble());
        c.crash = allowed && (forced.remove(Drama.CRASH) || rng.nextDouble() < DramaKit.prop("wedding.crashChance", 0.3));
        current = c;
        try {
            OutfitLoans.lend(bot, OVERALL_SLOT, bot.getGender() == 1 ? WEDDING_DRESS : TUXEDO, TUXEDO, WEDDING_DRESS);
        } catch (RuntimeException e) {
            log.debug("wedding outfit", e);
        }
        gatherGuests(c, player);
        try {
            player.getWorldServer().broadcastPacket(PacketCreator.serverNotice(6, "[Wedding] " + player.getName() + " & "
                    + bot.getName() + " are getting married in Amoria (ch " + c.channel + ")!"));
        } catch (RuntimeException e) {
            log.debug("wedding notice", e);
        }
        c.beat = ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                step(c, BotRomance.now());
            } catch (RuntimeException e) {
                log.warn("Romance wedding step failed", e);
            }
        }, 2, 2, TimeUnit.SECONDS);
        qa("WEDDING begins: " + player.getName() + " & " + bot.getName() + ", " + c.friends.size() + " friend bots, "
                + c.guests.size() + " new guests; drama: objection " + c.objection + ", runaway " + c.runaway
                + ", crash " + c.crash);
        return null;
    }

    private static void gatherGuests(Ceremony c, Character player) {
        int n = 0;
        for (int id : BotAffinity.botsFeeling(player.getId(), AffinityState.FRIEND)) {
            if (n >= 3 || id == c.botId) {
                continue;
            }
            // auto-QA rm-4: an ex is the drama, not the crowd - recruited as a guest it came to the altar busy and
            // could not crash the reception ("nobody to crash"). It stays free and crashes it instead.
            Romance ex = BotRomance.with(player.getId(), id);
            if (ex != null && ex.stage() == Romance.Stage.EX) {
                continue;
            }
            Character friend = DramaKit.liveBot(id);
            if (friend != null && DramaKit.freeForScene(friend)) {
                DramaKit.Home home = DramaKit.homeOf(friend);
                if (DramaKit.bringTo(friend, player, (n % 2 == 0 ? -1 : 1) * (130 + 40 * n), false)) {
                    DramaKit.busy(id, "wedding guest");
                    c.friends.put(id, home);
                    n++;
                }
            }
        }
        int missing = Math.max(1, GUESTS - n);
        int base = BotStore.nextDynamicSlots(missing);
        Point at = player.getPosition() == null ? new Point(0, 0) : new Point(player.getPosition());
        for (int i = 0; i < missing; i++) {
            Point p = new Point(at.x + (i % 2 == 0 ? 1 : -1) * (100 + 45 * (i / 2 + n)), at.y);
            int id = BotGeneration.createBot(base + i, p, player.getMap());
            Character guest = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (guest != null) {
                BotTypeManager.convertBotType(guest, BotTypeManager.BotType.SOCIAL_BOT);
                c.guests.add(id);
            }
        }
    }

    private static boolean beat(Ceremony c, String name, long s, long at) {
        return s >= at && c.fired.add(name);
    }

    static void step(Ceremony c, long now) {
        if (c.over) {
            if (c.beat != null) {
                c.beat.cancel(false);
            }
            return;
        }
        Character player = DramaKit.onlinePlayer(c.playerId);
        Character bot = DramaKit.liveBot(c.botId);
        if (player == null || bot == null || player.getMapId() != AMORIA) {
            finish(c, "the couple left");
            return;
        }
        Character officiant = officiant(c);
        long s = (now - c.startedAt) / 1000;
        Map<String, String> t = Map.of("PLAYER", player.getName(), "BOT", bot.getName());
        if (beat(c, "open", s, 6)) {
            DramaKit.say(officiant, "WeddingOfficiant", t);
        }
        if (beat(c, "speak-now", s, 14)) {
            DramaKit.say(officiant, "WeddingSpeakNow", t);
        }
        long shift = 0;
        if (c.objection) {
            shift = 14;
            if (beat(c, "object", s, 18)) {
                object(c, player, bot);
            }
            if (beat(c, "object-reply", s, 24) && c.objector != 0) {
                DramaKit.say(bot, "WeddingObjectionReply", objectionTokens(c, player, bot));
            }
            if (beat(c, "object-officiant", s, 28)) {
                DramaKit.say(officiant, "WeddingObjectionOverruled", objectionTokens(c, player, bot));
            }
        }
        if (c.runaway && beat(c, "runaway", s, 24 + shift)) {
            DramaKit.say(bot, "WeddingRunaway", t);
            qa("RUNAWAY " + bot.getName() + " ran from the altar: no marriage, still engaged");
            player.dropMessage(5, bot.getName() + " ran away from the altar! You are still engaged: try again later "
                    + "with @romance wedding.");
            finish(c, "runaway");
            return;
        }
        if (beat(c, "bot-vows", s, 24 + shift)) {
            DramaKit.say(bot, "WeddingBotVows", t);
        }
        if (beat(c, "ask-player", s, 30 + shift)) {
            DramaKit.say(officiant, "WeddingAskPlayer", t);
            player.dropMessage(5, "Say \"i do\" in chat.");
            c.vowsAskedAt = now;
        }
        if (!c.married && c.vowsAskedAt > 0 && (c.saidIDo || now - c.vowsAskedAt > TimeUnit.SECONDS.toMillis(40))) {
            if (!c.saidIDo) {
                DramaKit.say(officiant, "WeddingTakenAsYes", t);
            }
            String problem = makeItReal(player, bot, now);
            if (problem != null) {
                qa("WEDDING failed: " + problem);
                player.dropMessage(5, "The wedding could not finish: " + problem);
                finish(c, problem);
                return;
            }
            c.married = true;
            c.pronouncedAt = now;
            DramaKit.say(officiant, "WeddingPronounce", t);
        }
        if (c.married) {
            long after = (now - c.pronouncedAt) / 1000;
            if (beat(c, "kiss", after, 3)) {
                DramaKit.say(bot, "WeddingJustMarried", t);
            }
            if (after >= 5 && after % 8 == 0 && !c.guests.isEmpty() && !c.fired.contains("cheer:" + after)) {
                c.fired.add("cheer:" + after);
                DramaKit.say(guest(c), "WeddingCheer", t);
            }
            if (c.crash && beat(c, "crash", after, 15)) {
                crash(c, player, bot);
            }
            if (after >= 50) {
                finish(c, "married");
            }
        }
    }

    /** The player's "i do" (the general chat hook). True when it answered the vows. */
    public static boolean onPlayerChat(Character player, String line) {
        Ceremony c = current;
        if (c == null || c.over || c.playerId != player.getId() || c.vowsAskedAt == 0 || c.saidIDo) {
            return false;
        }
        if (I_DO.matcher(line).find()) {
            c.saidIDo = true;
            qa("I DO " + player.getName());
            return true;
        }
        return false;
    }

    /**
     * The stock end state of a wedding: tokens gone, the rings pair, the player's ring (with its ring id), both
     * marriage rings, both marriage item ids. The problem, or null.
     * <p>
     * 1.5.0 rc6 bug hunt: the ring is delivered before anything is taken or recorded. This used to remove the tokens,
     * ignore whether the ring reached the player's inventory and mark the pair married regardless, so a full equip
     * tab (filled during the ceremony) lost the ring for good. Now space is checked again, a ring that can't be
     * delivered deletes the rows just written, and the tokens and the married state are left as they were.
     */
    static String makeItReal(Character player, Character bot, long now) {
        Romance r = BotRomance.with(player.getId(), bot.getId());
        if (r == null || r.stage() != Romance.Stage.ENGAGED || player.getPartnerId() != bot.getId()) {
            return "you are no longer engaged";
        }
        int ringItem = r.ringItemId() > 0 ? r.ringItemId() : 1112803;
        if (!ops.hasRoom(player, ringItem)) {
            return "make room for the ring in your equip inventory";
        }
        Equip ringEqp = ops.newRing(ringItem);
        if (ringEqp == null) {
            return "the ring has no item data";
        }
        Pair<Integer, Integer> rings = ops.createRings(ringItem, player, bot);
        if (rings.getLeft() <= 0 || rings.getRight() <= 0) {
            return "the rings could not be written";
        }
        ringEqp.setRingId(rings.getLeft());
        if (!ops.deliver(player, ringEqp)) {
            ops.deleteRings(rings.getLeft(), rings.getRight(), bot, ringItem);
            return "the ring could not be put in your inventory (make room in your equip inventory)";
        }
        ops.removeTokens(player, player.getMarriageItemId());
        ops.bond(r, player, bot, ringItem, rings, now);
        qa("MARRIED " + player.getName() + " & " + bot.getName() + ": rings " + rings.getLeft() + "/" + rings.getRight()
                + " (" + ringItem + "), marriage #" + player.getRelationshipId());
        return null;
    }

    /** The steps of {@link #makeItReal} that touch the DB, the item data and the inventory; tests replace them. */
    static class Ops {
        boolean hasRoom(Character player, int ringItem) {
            return InventoryManipulator.checkSpace(player, ringItem, 1, "");
        }

        Equip newRing(int ringItem) {
            return ItemInformationProvider.getInstance().getEquipById(ringItem) instanceof Equip e ? e : null;
        }

        Pair<Integer, Integer> createRings(int ringItem, Character player, Character bot) {
            return Ring.createRing(ringItem, player, bot);
        }

        boolean deliver(Character player, Equip ring) {
            return InventoryManipulator.addFromDrop(player, ring, false, -1);
        }

        void deleteRings(int playerRingId, int botRingId, Character bot, int ringItem) {
            Ring.removeRing(new Ring(playerRingId, botRingId, bot.getId(), ringItem, bot.getName()));
        }

        void removeTokens(Character player, int recorded) {
            BotRomance.removeTokens(player, recorded);
        }

        void bond(Romance r, Character player, Character bot, int ringItem, Pair<Integer, Integer> rings, long now) {
            player.addMarriageRing(Ring.loadFromDb(rings.getLeft()));
            player.setMarriageItemId(ringItem);
            player.broadcastMarriageMessage();
            Ring botRing = Ring.loadFromDb(rings.getRight());
            if (botRing != null) {
                botRing.equip();
                bot.addMarriageRing(botRing);
            }
            bot.setMarriageItemId(ringItem);
            BotCouples.wearRing(bot, ringItem);
            BotRomance.married(r, rings.getRight(), now);
            BotRomance.note(bot.getId(), player.getId(), "we got married in amoria <3");
        }
    }

    static Ops ops = new Ops();

    /** Who shouts "I OBJECT": an ex of the player, else a bot with a crush on them; brought to the altar. */
    private static void object(Ceremony c, Character player, Character bot) {
        Character who = null;
        for (Romance r : BotRomance.all(player.getId())) {
            Character ex = r.stage() == Romance.Stage.EX ? DramaKit.liveBot(r.botCharId()) : null;
            if (ex != null && ex.getId() != bot.getId() && (ex.getMapId() == AMORIA || DramaKit.freeForScene(ex))) {
                who = ex;
                break;
            }
        }
        if (who == null) {
            for (int id : BotAffinity.botsFeeling(player.getId(), AffinityState.FRIEND)) {
                Character crush = DramaKit.liveBot(id);
                if (crush != null && id != bot.getId() && !c.friends.containsKey(id) && DramaKit.freeForScene(crush)) {
                    who = crush;
                    break;
                }
            }
        }
        if (who == null && !c.guests.isEmpty()) {
            who = DramaKit.liveBot(c.guests.get(c.guests.size() - 1)); // a guest with a secret
        }
        if (who == null) {
            return;
        }
        if (who.getMapId() != AMORIA) {
            DramaKit.Home home = DramaKit.homeOf(who);
            if (!DramaKit.bringTo(who, player, 160, false)) {
                return;
            }
            c.friends.put(who.getId(), home);
            DramaKit.busy(who.getId(), "wedding objection");
        }
        c.objector = who.getId();
        DramaKit.say(who, "WeddingObjection", objectionTokens(c, player, bot));
        qa("OBJECTION " + who.getName() + " objects to " + player.getName() + " & " + bot.getName());
    }

    private static Map<String, String> objectionTokens(Ceremony c, Character player, Character bot) {
        Map<String, String> t = new HashMap<>();
        t.put("PLAYER", player.getName());
        t.put("BOT", bot.getName());
        String who = c.objector == 0 ? null : BotRomance.nameOf(c.objector);
        if (who != null) {
            t.put("OTHER", who);
        }
        return t;
    }

    /** An ex (or a rival) crashes the reception: one scene line, then it leaves. */
    private static void crash(Ceremony c, Character player, Character bot) {
        Character who = null;
        for (Romance r : BotRomance.all(player.getId())) {
            Character ex = r.stage() == Romance.Stage.EX ? DramaKit.liveBot(r.botCharId()) : null;
            if (ex == null || ex.getId() == c.objector) {
                continue;
            }
            if (DramaKit.freeForScene(ex)) {
                who = ex;
                break;
            }
            String scene = DramaKit.sceneOf(ex.getId()); // auto-QA rm-4: say why an ex cannot crash the reception
            qa("CRASH: " + ex.getName() + " cannot crash it: " + (scene == null ? "not free" : "busy (" + scene + ")"));
        }
        if (who == null) {
            for (int id : BotAffinity.botsFeeling(player.getId(), AffinityState.RIVAL)) {
                Character rival = DramaKit.liveBot(id);
                if (rival != null && DramaKit.freeForScene(rival)) {
                    who = rival;
                    break;
                }
            }
        }
        if (who == null) {
            qa("CRASH: nobody to crash the reception (no ex or rival online)");
            return;
        }
        DramaKit.Home home = DramaKit.homeOf(who);
        if (!DramaKit.bringTo(who, player, -170, false)) {
            return;
        }
        DramaKit.busy(who.getId(), "wedding crash");
        Character crasher = who;
        Map<String, String> t = Map.of("PLAYER", player.getName(), "BOT", bot.getName());
        DramaKit.say(crasher, "WeddingCrash", t);
        qa("CRASH " + crasher.getName() + " crashes the reception of " + player.getName() + " & " + bot.getName());
        soloMapling.server.BotTiming.after(7_000, () -> {
            DramaKit.say(crasher, "WeddingCrashLeave", t);
            DramaKit.unbusy(crasher.getId());
            DramaKit.sendHome(crasher, home);
        });
    }

    private static Character officiant(Ceremony c) {
        for (int id : c.guests) {
            Character g = DramaKit.liveBot(id);
            if (g != null) {
                return g;
            }
        }
        for (int id : c.friends.keySet()) {
            Character g = DramaKit.liveBot(id);
            if (g != null && id != c.objector) {
                return g;
            }
        }
        return null;
    }

    private static Character guest(Ceremony c) {
        List<Integer> all = new ArrayList<>(c.guests);
        all.addAll(c.friends.keySet());
        all.remove(Integer.valueOf(c.objector));
        if (all.isEmpty()) {
            return null;
        }
        return DramaKit.liveBot(all.get(ThreadLocalRandom.current().nextInt(all.size())));
    }

    /** The ceremony is over: new guests leave the world, friends go home, the couple's own clothes come back. */
    static synchronized void finish(Ceremony c, String why) {
        if (c.over) {
            return;
        }
        c.over = true;
        if (c.beat != null) {
            c.beat.cancel(false);
        }
        for (int id : c.guests) {
            Character g = BotHelpers.getCharFromChannelStorage(id);
            if (g != null) {
                BotSM sm = CharacterStorage.getBotById(id);
                if (sm != null) {
                    sm.setRunning(false);
                    sm.stopScheduledTask();
                }
                BotGeneration.removeBotFromServer(g);
            }
        }
        for (Map.Entry<Integer, DramaKit.Home> e : c.friends.entrySet()) {
            DramaKit.unbusy(e.getKey());
            DramaKit.sendHome(DramaKit.liveBot(e.getKey()), e.getValue());
        }
        Character bot = DramaKit.liveBot(c.botId);
        DramaKit.unbusy(c.botId);
        if (bot != null) {
            try {
                OutfitLoans.giveBack(bot, OVERALL_SLOT);
            } catch (RuntimeException e) {
                log.debug("wedding outfit back", e);
            }
            if ("runaway".equals(why)) {
                DramaKit.sendHome(bot, c.botHome);
            }
        }
        if (current == c) {
            current = null;
        }
        qa("WEDDING ends: " + why);
    }

    /** GM / !qa: stop the wedding on now. */
    public static int stop() {
        Ceremony c = current;
        if (c == null) {
            return 0;
        }
        finish(c, "stopped");
        return 1;
    }

    public static String describe() {
        Ceremony c = current;
        if (c == null || c.over) {
            return "no player wedding on";
        }
        return "wedding of " + c.playerId + " & " + BotRomance.nameOf(c.botId) + " for "
                + (BotRomance.now() - c.startedAt) / 1000 + " s; married " + c.married + ", objection " + c.objection
                + ", runaway " + c.runaway + ", crash " + c.crash + "; forced next: " + forced;
    }

    static void qa(String line) {
        DramaKit.qa(QaLog.Topic.ROMANCE, AMORIA, line);
    }

    static void resetForTest() {
        current = null;
        forced.clear();
        noRandomRunaway = false;
    }
}
