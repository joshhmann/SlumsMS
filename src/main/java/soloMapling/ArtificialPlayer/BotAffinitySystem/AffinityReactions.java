package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.PlayerBusy;
import soloMapling.ArtificialPlayer.SocialGate;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotBuddies;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Ticket 58: how a bot's feelings show. Only through behaviour - never a number, never a label.
 *
 * <p><b>Friends</b> greet the player, now and then send a buddy request or a party invite, and help (a buff they
 * really have). <b>Rivals and nemeses</b> trash talk, KS the player's mobs for a little while (only mobs within their
 * own reach on the map they are already on), refuse trades, parties and buddy requests, and a nemesis may defame.
 *
 * <p><b>Never harassment.</b> Everything happens only when the bot already stands near the player (the roaming bots'
 * player scan, observed maps only); a bot never changes map for a player. Each action has a per-pair cooldown and a
 * world-wide hourly cap, at most one action per sighting, a defame at most once a day per player across all bots.
 * {@code @botdrama off} removes every hostile action for that player, {@code -Dsolomapling.affinity.hostility=false}
 * for everyone.
 *
 * <p>1.6.0 (S5): every act goes through {@link SocialGate} - hostile ones only toward a player who started it with this
 * bot, quiet players get no buddy request or invite, a crew mate of a wronged bot only side-eyes - and its
 * cooldowns and caps are the gate's busy-server budget ({@code -Dsolomapling.affinity.<key>Min} / {@code PerHour}
 * still tune them): greet 40, invite 60, buddy 480, help 15, taunt 7, ks 10, defame 1440 minutes per pair (1.5.x: 120,
 * 180, 1440, 15, 20, 15, 1440); per hour world-wide taunts 60, KS sprees 30, invites 18 (1.5.x: 20, 10, 6).
 */
public final class AffinityReactions {

    private static final Logger log = LoggerFactory.getLogger(AffinityReactions.class);

    static final String PACK = "AffinityDialogue.yaml";
    static final String ROOT = "Affinity";
    /** How long a KS spree lasts, and how many of the player's mobs it takes at most. */
    static final long KS_SPREE_MS = TimeUnit.SECONDS.toMillis(90);
    static final int KS_SPREE_MOBS = 3;
    /** A KS target must be this close to the bot (it never walks across the map for it). */
    static final int KS_REACH_X = 350, KS_REACH_Y = 90;

    /** Party buffs a friendly bot may give, when it really holds the skill. */
    static final int[] HELP_BUFFS = {2301004 /* Bless */, 2311003 /* Holy Symbol */, 4101004, 4201003 /* Haste */,
            1301007 /* Hyper Body */, 2101001, 2201001 /* Meditation */, 3121002, 3221002 /* Sharp Eyes */};

    enum Action {
        GREET(SocialGate.Act.GREET), INVITE(SocialGate.Act.PARTY_INVITE), BUDDY(SocialGate.Act.BUDDY_REQUEST),
        HELP(SocialGate.Act.HELP), TAUNT(SocialGate.Act.TAUNT), KS(SocialGate.Act.KS_SPREE),
        DEFAME(SocialGate.Act.DEFAME),
        /** 1.6.0 S5: a crew mate of a bot the player wronged gives them a look (a line, never a KS). */
        SIDE_EYE(SocialGate.Act.SIDE_EYE);

        final SocialGate.Act act;

        Action(SocialGate.Act act) {
            this.act = act;
        }
    }

    /** Per player: the day of the last bot defame they got. */
    private static final Map<Integer, Long> defamedDay = new ConcurrentHashMap<>();
    /** Per bot: the KS spree it is on. */
    private static final Map<Integer, KsSpree> sprees = new ConcurrentHashMap<>();
    private static final Map<Long, Long> lastRefuseLine = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastNoTake = new ConcurrentHashMap<>();
    private static volatile long lastGossipLine;

    /**
     * A KS spree; {@code war}: called in by a rival for a KS war (1.5.0 bot drama), not the bot's own grudge.
     * {@code counted}: the mobs it has been credited with, so one mob is never taken twice.
     */
    record KsSpree(int playerId, int mapId, long until, int[] left, boolean war, Set<Integer> counted) {
        KsSpree(int playerId, int mapId, long until, int[] left) {
            this(playerId, mapId, until, left, false, ConcurrentHashMap.newKeySet());
        }

        KsSpree(int playerId, int mapId, long until, int[] left, boolean war) {
            this(playerId, mapId, until, left, war, ConcurrentHashMap.newKeySet());
        }
    }

    private AffinityReactions() {
    }

    // ── settings ──

    /** The pair's cooldown (the social gate's busy-server budget). */
    static long cooldownMs(Action a) {
        return TimeUnit.MINUTES.toMillis(SocialGate.pairMinutes(a.act));
    }

    /** The world-wide hourly cap (the social gate's busy-server budget). */
    static int perHour(Action a) {
        return SocialGate.perHour(a.act);
    }

    /**
     * Forgets this bot's cooldowns with every player and its spree: a staged bot is unstaged and its id comes back for
     * the next run. rc6 auto-QA triage (t-af-1): the same staged bot id met the same GM run after run, and its
     * greet/invite/buddy cooldowns (2-24 h) from the last run kept it silent.
     */
    public static void forgetBot(int botCharId) {
        SocialGate.forgetBotBudget(botCharId);
        sprees.remove(botCharId);
    }

    /** True (and the cooldown starts) when this pair may do this now and the world cap has room. */
    static boolean take(Action a, int botCharId, int playerId, long now) {
        return SocialGate.take(a.act, botCharId, playerId, now);
    }

    /**
     * 1.6.0 S5: the gate's rules for this act (quiet, busy, started it, crew) and then the budget. A rule's no is
     * logged (at most once a minute per pair) and spends no budget.
     */
    static boolean may(Action a, Character bot, Character player, long now) {
        SocialGate.Verdict v = SocialGate.check(bot, player, a.act);
        if (!v.ok()) {
            SocialGate.note(a.act, bot.getId(), bot.getName(), player, v);
            return false;
        }
        return take(a, bot.getId(), player.getId(), now);
    }

    // ── on sight ──

    /**
     * A roaming bot noticed a real player nearby (driver thread; memory only). If it feels something about them it
     * may do one thing about it, off the driver thread. True when it reacted (the caller skips its usual line).
     */
    public static boolean onSight(BotSM botSM, Character player) {
        if (botSM == null || player == null || botSM.getChr() == null
                || !BotAffinity.hasTies(player.getId()) && !SocialGate.wrongedAnyBot(player.getId())
                || !BotHelpers.isVisibleRealPlayer(player)) {
            return false;
        }
        Character bot = botSM.getChr();
        AffinityState state = BotAffinity.state(bot.getId(), player.getId());
        if (bot.getMapId() != player.getMapId()) {
            return false;
        }
        long now = BotAffinity.now();
        Action action = state == AffinityState.NEUTRAL ? pickSideEye(bot, player, now)
                : state == AffinityState.FRIEND ? pickFriendly(botSM, bot, player, now)
                : pickHostile(botSM, bot, player, state, now);
        if (action == null) {
            return false;
        }
        ExecutorServiceManager.runAsync(() -> {
            try {
                act(action, botSM, bot, player, state);
            } catch (RuntimeException e) {
                log.debug("Affinity action {} failed for {}", action, bot.getName(), e);
            }
        });
        return true;
    }

    /** What a bot does on noticing a player: the drama, affinity and gift hooks, in the roaming bots' order. */
    public interface Noticer {
        boolean noticed(BotSM botSM, Character bot, Character player);
    }

    /** The real hooks: a partner, ex or rival scene; then a friend/rival act; then maybe a friend's gift. */
    public static final Noticer HOOKS = (sm, bot, player) ->
            soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.onSight(sm, player) || onSight(sm, player)
                    || soloMapling.Economy.BotGifts.maybeGift(bot, player);

    /**
     * What a stationed (non-roaming) bot does on noticing a player: the drama and affinity hooks only. Gifts stay
     * with the roaming bots as designed (rc3, owner decision 4): giving them to every town bot too would change how
     * many mesos and items reach players, an economy call for the owner.
     */
    public static final Noticer STATIONED = (sm, bot, player) ->
            soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.onSight(sm, player) || onSight(sm, player);

    /**
     * rc6 auto-QA triage (t-af-1, 2026-10-23): the sighting hooks ran only from the roaming bots' movement driver
     * ({@code BotPlayerReaction}), and a staged friend is a social bot standing in town that never roams - in two runs
     * of four minutes next to the player it greeted, invited and buffed nobody (the one run that passed only acted after
     * the GM partied it and it became a follower). Stationed bots now call this from their own tick: the real players
     * within the same 300 x 200 box, one reaction at most. True when one reacted.
     */
    public static boolean noticePlayers(BotSM botSM, Character bot, java.util.List<Character> players, Noticer hooks) {
        if (botSM == null || bot == null || players == null) {
            return false;
        }
        for (Character p : players) {
            if (p == null || p.getId() == bot.getId() || PlayerBusy.isBusy(p) || SocialGate.quietBots(p.getId())) {
                continue;
            }
            if (hooks.noticed(botSM, bot, p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * GM / !qa af-2: the rival taunts the player now (its usual taunt, a roast from the player's game). rc6 auto-QA
     * triage: af-2's taunt came only on a later sighting, and a training bot fighting across the map often never stood
     * within reach again in the two minutes the check waits. Starts the taunt cooldown as a real one would.
     */
    public static boolean forceTaunt(BotSM botSM, Character bot, Character player) {
        AffinityState state = BotAffinity.state(bot.getId(), player.getId());
        if (!state.hostile() || !BotAffinity.hostilityEnabled() || BotAffinity.optedOut(player.getId())) {
            return false;
        }
        SocialGate.startCooldown(SocialGate.Act.TAUNT, bot.getId(), player.getId(), BotAffinity.now());
        ExecutorServiceManager.runAsync(() -> {
            try {
                act(Action.TAUNT, botSM, bot, player, state);
            } catch (RuntimeException e) {
                log.debug("Affinity taunt failed for {}", bot.getName(), e);
            }
        });
        return true;
    }

    /** The one friendly thing to do now, or null. The order is the rarest first. */
    static Action pickFriendly(BotSM botSM, Character bot, Character player, long now) {
        double roll = ThreadLocalRandom.current().nextDouble();
        boolean buddies = player.getBuddylist() != null && player.getBuddylist().contains(bot.getId());
        if (!buddies && roll < 0.35 && may(Action.BUDDY, bot, player, now)) {
            return Action.BUDDY;
        }
        if (canInvite(botSM, bot, player) && roll < 0.5 && may(Action.INVITE, bot, player, now)) {
            return Action.INVITE;
        }
        if (helpBuff(bot) != 0 && may(Action.HELP, bot, player, now)) {
            return Action.HELP;
        }
        if (!BotConversation.isTalking(bot.getId(), player.getId()) && may(Action.GREET, bot, player, now)) {
            return Action.GREET;
        }
        return null;
    }

    /**
     * 1.6.0 S5: a bot with no feelings of its own about the player, whose crew mate the player wronged lately: now and
     * then a look and a line ({@code CrewSideEye}), never a KS or a call-out. Only when the gate says the crew rule
     * holds; one map read when the player wronged nobody.
     */
    static Action pickSideEye(Character bot, Character player, long now) {
        if (!SocialGate.enabled() || !SocialGate.wrongedAnyBot(player.getId())
                || BotConversation.isTalking(bot.getId(), player.getId())
                || ThreadLocalRandom.current().nextDouble() >= sideEyeChance()) {
            return null;
        }
        return may(Action.SIDE_EYE, bot, player, now) ? Action.SIDE_EYE : null;
    }

    static double sideEyeChance() {
        try {
            return Double.parseDouble(System.getProperty("solomapling.social.sideEyeChance", "0.2"));
        } catch (NumberFormatException e) {
            return 0.2;
        }
    }

    /** The one petty thing to do now, or null. */
    static Action pickHostile(BotSM botSM, Character bot, Character player, AffinityState state, long now) {
        if (!BotAffinity.hostilityEnabled() || BotAffinity.optedOut(player.getId())) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble();
        if (state == AffinityState.NEMESIS && roll < 0.25 && mayDefame(player.getId(), now)
                && may(Action.DEFAME, bot, player, now)) {
            return Action.DEFAME;
        }
        if (botSM instanceof TrainingBot && roll < 0.6 && may(Action.KS, bot, player, now)) {
            return Action.KS;
        }
        if (may(Action.TAUNT, bot, player, now)) {
            return Action.TAUNT;
        }
        return null;
    }

    private static boolean canInvite(BotSM botSM, Character bot, Character player) {
        return (botSM instanceof TrainingBot || botSM instanceof SocialBot) && player.getParty() == null
                && (bot.getParty() == null || bot.getParty().getLeaderId() == bot.getId()
                && bot.getParty().getMembers().size() < 6);
    }

    private static void act(Action action, BotSM botSM, Character bot, Character player, AffinityState state) {
        if (!SocialGate.allow(bot, player, action.act)) { // 1.6.0 S5: busy, quiet and the target rules, at delivery
            return;
        }
        String detail = switch (action) {
            case GREET -> greet(botSM, bot, player);
            case BUDDY -> BotBuddies.offerAsFriend(bot.getId(), player.getId());
            case INVITE -> invite(bot, player);
            case HELP -> help(bot, player);
            case TAUNT -> { // 1.5.0 bot drama: a roast from the player's real game facts (game only)
                String roast = soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.enabled()
                        ? soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.roast(bot, player, state) : null;
                yield roast != null ? roast : sayTo(bot, player,
                        state == AffinityState.NEMESIS ? "NemesisTaunt" : "RivalTaunt");
            }
            case KS -> startSpree(bot, player);
            case DEFAME -> defame(bot, player);
            case SIDE_EYE -> sayTo(bot, player, "CrewSideEye");
        };
        if (QaLog.on(QaLog.Topic.AFFINITY)) { // !qa af-1, af-2
            QaLog.map(QaLog.Topic.AFFINITY, bot.getMapId(), "ACT " + action.name().toLowerCase() + " " + bot.getName()
                    + " (" + state.label() + ") -> " + player.getName() + ": " + detail);
        }
    }

    private static String greet(BotSM botSM, Character bot, Character player) {
        if (BotConversation.greet(botSM, player)) { // through the chat brain: the player's answer reaches the bot
            return "greeted (chat opened)";
        }
        return sayTo(bot, player, "FriendHello");
    }

    private static String invite(Character bot, Character player) {
        boolean hadParty = bot.getParty() != null;
        if (!BotPartyCommands.botInvitePlayer(bot, player)) {
            return "invite refused by the party system";
        }
        sayTo(bot, player, "FriendInvite");
        if (!hadParty) {
            // The bot made a party just to invite them: if they never join, it leaves it again.
            BotTiming.after(TimeUnit.SECONDS.toMillis(75), () -> {
                if (bot.getParty() != null && bot.getParty().getMembers().size() <= 1) {
                    BotPartyCommands.botLeaveParty(bot);
                }
            });
        }
        return "invited to a party";
    }

    /** A party buff the bot really holds, or 0. */
    static int helpBuff(Character bot) {
        try {
            for (int id : HELP_BUFFS) {
                if (bot.getSkillLevel(id) > 0) {
                    return id;
                }
            }
        } catch (RuntimeException e) {
            return 0;
        }
        return 0;
    }

    private static String help(Character bot, Character player) {
        int skill = helpBuff(bot);
        if (skill == 0) {
            return "no buff to give";
        }
        if (!BotBuffEffects.givePartyBuff(bot, skill, List.of(player))) {
            return "no MP for the buff";
        }
        if (ThreadLocalRandom.current().nextBoolean()) {
            sayTo(bot, player, "FriendHelp");
        }
        return "buffed with skill " + skill;
    }

    // ── KS sprees ──

    private static String startSpree(Character bot, Character player) {
        sprees.put(bot.getId(), new KsSpree(player.getId(), bot.getMapId(), BotAffinity.now() + KS_SPREE_MS,
                new int[]{KS_SPREE_MOBS}));
        soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.arm(bot, player); // 1.5.0: it may follow once for revenge
        if (ThreadLocalRandom.current().nextDouble() < 0.5) {
            sayTo(bot, player, "RivalTaunt");
        }
        return "ks spree for " + TimeUnit.MILLISECONDS.toSeconds(KS_SPREE_MS) + " s, up to " + KS_SPREE_MOBS + " mobs";
    }

    /**
     * The grind brain picked {@code picked}; a bot on a KS spree takes a mob its rival is fighting instead, when one
     * is within its own short reach on the same map (it never chases across the map, and never another map).
     * Combat thread; memory only. Returns the mob to fight.
     */
    public static server.life.Monster ksPick(Character bot, server.life.Monster picked) {
        if (sprees.isEmpty() || bot == null) {
            return picked;
        }
        KsSpree spree = sprees.get(bot.getId());
        if (spree == null) {
            return picked;
        }
        long now = BotAffinity.now();
        boolean still = spree.war() ? BotAffinity.hostilityEnabled() && !BotAffinity.optedOut(spree.playerId())
                : BotAffinity.hostile(bot.getId(), spree.playerId());
        if (now > spree.until() || spree.left()[0] <= 0 || bot.getMapId() != spree.mapId() || !still) {
            sprees.remove(bot.getId());
            return picked;
        }
        Character rival = bot.getMap() == null ? null : bot.getMap().getCharacterById(spree.playerId());
        java.awt.Point pos = bot.getPosition();
        if (rival == null || pos == null) {
            return picked;
        }
        server.life.Monster best = null;
        double bestSq = Double.MAX_VALUE;
        int saw = 0;
        int rivalHit = 0;
        int ours = 0;
        for (server.maps.MapObject mo : bot.getMap().getMapObjectsInRange(pos, (double) KS_REACH_X * KS_REACH_X,
                List.of(server.maps.MapObjectType.MONSTER))) {
            server.life.Monster m = (server.life.Monster) mo;
            java.awt.Point mp = m.getPosition();
            if (!m.isAlive() || mp == null || Math.abs(mp.y - pos.y) > KS_REACH_Y) {
                continue;
            }
            saw++;
            if (!m.isAttackedBy(rival)) {
                continue;
            }
            rivalHit++;
            if (m.isAttackedBy(bot)) {
                ours++;
                continue;
            }
            double d = pos.distanceSq(mp);
            if (d < bestSq) {
                bestSq = d;
                best = m;
            }
        }
        if (best == null) {
            noTake(bot, rival, saw, rivalHit, ours);
            // 1.5.1 rv-2: a helper that finds nothing to take at hand drifts to the mobs of its own ledge and never
            // comes back. A KSer came for the rival: head for the closest mob around the rival, so it fights where the
            // player fights and the easy takes are in reach. Bounded by the rival's own reach, so it never chases far.
            server.life.Monster toward = nearestToRival(bot, rival);
            return toward == null ? picked : toward;
        }
        // 1.5.1 rv-2: a helper standing next to the player usually has that very mob as its pick already - it is
        // taking the player's mob, so the spree is credited with it too, once per mob. Counting only the picks that
        // *change* the target made a whole KS war look like no bot ever took a mob.
        boolean alreadyPicked = picked != null && picked.getObjectId() == best.getObjectId();
        if (!spree.counted().add(best.getObjectId())) {
            return picked; // this mob was credited to the spree already
        }
        spree.left()[0]--;
        if (QaLog.on(QaLog.Topic.AFFINITY)) {
            QaLog.map(QaLog.Topic.AFFINITY, bot.getMapId(), "KS " + bot.getName() + " takes " + best.getName()
                    + " (oid " + best.getObjectId() + ") from " + rival.getName()
                    + (alreadyPicked ? " (its pick already)" : "") + ", " + spree.left()[0] + " left");
        }
        if (spree.left()[0] == 0 && ThreadLocalRandom.current().nextDouble() < 0.4) {
            Character target = rival;
            ExecutorServiceManager.runAsync(() -> sayTo(bot, target, "KsAnnounce"));
        }
        return best;
    }

    /** The closest mob around the rival the bot could take next (the spree bot walks back toward its rival), or null. */
    private static server.life.Monster nearestToRival(Character bot, Character rival) {
        java.awt.Point rp = rival.getPosition();
        if (rp == null || bot.getMap() == null) {
            return null;
        }
        // Twice the take reach both ways: a helper that drifted to its own ledge gets a target on the player's side
        // and walks back; the take itself still happens inside the normal reach.
        int x = KS_REACH_X * 2;
        int y = KS_REACH_Y * 2;
        server.life.Monster near = null;
        double nearSq = Double.MAX_VALUE;
        for (server.maps.MapObject mo : bot.getMap().getMapObjectsInRange(rp, (double) x * x,
                List.of(server.maps.MapObjectType.MONSTER))) {
            server.life.Monster m = (server.life.Monster) mo;
            java.awt.Point mp = m.getPosition();
            if (!m.isAlive() || mp == null || Math.abs(mp.y - rp.y) > y) {
                continue;
            }
            double d = rp.distanceSq(mp);
            if (d < nearSq) {
                nearSq = d;
                near = m;
            }
        }
        return near;
    }

    /** Why a spree bot found nothing to take, once per bot per 10 s: auto-QA rv-2 reads these to explain a miss. */
    private static void noTake(Character bot, Character rival, int saw, int rivalHit, int ours) {
        if (!QaLog.on(QaLog.Topic.AFFINITY)) {
            return;
        }
        long now = BotAffinity.now();
        Long last = lastNoTake.get(bot.getId());
        if (last != null && now - last < TimeUnit.SECONDS.toMillis(10)) {
            return;
        }
        lastNoTake.put(bot.getId(), now);
        java.awt.Point at = bot.getPosition();
        QaLog.map(QaLog.Topic.AFFINITY, bot.getMapId(), "KS " + bot.getName() + " no take at "
                + (at == null ? "?" : at.x + "," + at.y) + ": " + saw + " mob(s) in reach, " + rivalHit
                + " hit by " + rival.getName() + ", " + ours + " already ours");
    }

    // ── defame ──

    /** The lowest fame a defame takes a player to (v83's own floor; the game clamps there too). */
    static final int FAME_FLOOR = -30000;

    /** The server's zone for "a day" (the roster's, America/Los_Angeles by default). */
    static java.time.ZoneId zone() {
        try {
            String z = config.YamlConfig.config.server.BOT_ROSTER_TIMEZONE;
            return z == null || z.isBlank() ? java.time.ZoneId.of("America/Los_Angeles") : java.time.ZoneId.of(z);
        } catch (RuntimeException | LinkageError e) { // no config (tests, tools): the roster's default
            return java.time.ZoneId.of("America/Los_Angeles");
        }
    }

    /** When the server's local day holding {@code now} began (epoch ms). */
    static long dayStart(long now, java.time.ZoneId zone) {
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Cheap pre-check on the driver thread (memory only); {@link #defame} makes the durable claim. */
    static boolean mayDefame(int playerId, long now) {
        Long day = defamedDay.get(playerId);
        return day == null || day < dayStart(now, zone());
    }

    /**
     * A nemesis defames: at most once per player per server day across all bots (claimed durably and atomically in
     * the store, so a restart or two bots at once cannot double it). Fame may go negative, as in v83, down to the
     * game's own floor ({@link #FAME_FLOOR}).
     */
    static String defame(Character bot, Character player) {
        long now = BotAffinity.now();
        if (!mayDefame(player.getId(), now) || player.getMapId() != bot.getMapId()) {
            return "skipped";
        }
        if (player.getFame() <= FAME_FLOOR) {
            return "fame at its floor: no defame";
        }
        long start = dayStart(now, zone());
        if (!BotAffinity.claimDefame(player.getId(), start, now)) {
            defamedDay.put(player.getId(), start);
            return "a bot defamed them today already";
        }
        defamedDay.put(player.getId(), start);
        if (!player.gainFame(-1, bot, 0)) {
            return "fame already at its floor";
        }
        if (ThreadLocalRandom.current().nextBoolean()) {
            sayTo(bot, player, "Defame");
        }
        return "defamed (-1)";
    }

    // ── refusals ──

    /** A bot weighing a trade request: a rival or nemesis says no (a curt line, at most once per 10 min). */
    public static boolean refusesTrade(Character bot, Character partner) {
        if (bot == null || partner == null || !BotAffinity.hostile(bot.getId(), partner.getId())) {
            return false;
        }
        long now = BotAffinity.now();
        long k = ((long) bot.getId() << 32) ^ partner.getId();
        Long last = lastRefuseLine.get(k);
        if (last == null || now - last > TimeUnit.MINUTES.toMillis(10)) {
            lastRefuseLine.put(k, now);
            soloMapling.server.BoundedMaps.prune(lastRefuseLine, 1_000, 5_000,
                    at -> now - at > TimeUnit.MINUTES.toMillis(10));
            ExecutorServiceManager.runAsync(() -> say(bot, "Refuse", partner.getName()));
        }
        if (QaLog.on(QaLog.Topic.AFFINITY)) {
            QaLog.map(QaLog.Topic.AFFINITY, bot.getMapId(), "REFUSE trade " + bot.getName() + " -> " + partner.getName());
        }
        return true;
    }

    // ── chat ──

    /** A player's line to a bot: its tone moves the bot's feelings (off the game thread, never blocks the reply). */
    public static void judgeLine(Character bot, Character player, String line, ChatTriage.Result triage) {
        if (bot == null || player == null || !BotAffinity.loaded(player.getId())) {
            return;
        }
        ChatTone.of(line, triage, ChatDecide.get()).thenAccept(tone -> {
            switch (tone) {
                case KIND -> BotAffinity.record(bot, player, AffinityEvent.KIND_CHAT);
                case RUDE -> {
                    int delta = AffinityEvent.RUDE_CHAT.delta();
                    if (triage != null && triage.con() == ChatTriage.Con.HARASSMENT) {
                        delta *= 2;
                    }
                    BotAffinity.record(bot.getId(), player.getId(), AffinityEvent.RUDE_CHAT, delta, player.getName(),
                            bot.getName(), player.getMapId());
                }
                case PLAIN -> {
                }
            }
        });
    }

    /**
     * Ticket 59: the same, with the tone ticket 59's one Decide call already read (no second model call). A flirty
     * line counts as kind only from a player the bot may flirt with (romance on); otherwise it moves nothing.
     */
    public static void judgeTone(Character bot, Character player, ChatTone.Tone tone, boolean flirty,
                                 ChatTriage.Result triage) {
        if (bot == null || player == null || !BotAffinity.loaded(player.getId())) {
            return;
        }
        if (flirty) {
            // 1.6.0 S5: the player flirted first - this one bot may flirt back (unless @romance off)
            SocialGate.playerFlirted(bot.getId(), player.getId());
            if (soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.mayFlirtWith(bot.getId(), player.getId())) {
                BotAffinity.record(bot, player, AffinityEvent.KIND_CHAT);
            }
            return;
        }
        switch (tone) {
            case KIND -> BotAffinity.record(bot, player, AffinityEvent.KIND_CHAT);
            case RUDE -> {
                int delta = AffinityEvent.RUDE_CHAT.delta();
                if (triage != null && triage.con() == ChatTriage.Con.HARASSMENT) {
                    delta *= 2;
                }
                BotAffinity.record(bot.getId(), player.getId(), AffinityEvent.RUDE_CHAT, delta, player.getName(),
                        bot.getName(), player.getMapId());
            }
            case PLAIN -> {
            }
        }
    }

    /** What the chat prompt adds about the bot's feelings: a sentence, or "". Never a number. */
    public static String promptLine(int botCharId, Character player) {
        if (player == null) {
            return "";
        }
        return switch (BotAffinity.state(botCharId, player.getId())) {
            case FRIEND -> " You and " + player.getName() + " are friends: you have partied and helped each other, so"
                    + " be warm and glad to see them.";
            case RIVAL -> " You have some beef with " + player.getName() + " (they KS'd you or took your stuff): be"
                    + " curt and a bit salty, light teasing is fine, never insults about real life, no threats.";
            case NEMESIS -> " " + player.getName() + " is your nemesis in the game (they keep KSing you): be cold and"
                    + " petty, a short jab is fine, never insults about real life, no threats, no slurs.";
            case NEUTRAL -> "";
        };
    }

    // ── gossip ──

    /**
     * A bot just passed a note about a player to another bot. If it is an affinity note and a real player (not the
     * one it is about) is on the map to overhear, the teller sometimes says it out loud. At most once per
     * {@code solomapling.affinity.gossipLineMin} (10) minutes world-wide.
     */
    public static void maybeMentionGossip(Character teller, Character listener, int aboutCharId, String text, Random rng) {
        AffinityEvent kind = BotAffinity.hearsayOf(text);
        if (kind == null || teller == null || teller.getMap() == null || rng.nextDouble() > 0.5) {
            return;
        }
        long now = BotAffinity.now();
        if (now - lastGossipLine < TimeUnit.MINUTES.toMillis(Long.getLong("solomapling.affinity.gossipLineMin", 10))) {
            return;
        }
        boolean audience = false;
        for (Character c : teller.getMap().getAllPlayers()) {
            if (c.getId() == aboutCharId) {
                return; // talk behind their back, not in front of them
            }
            if (!BotHelpers.isBot(c) && BotHelpers.isVisibleRealPlayer(c)) {
                audience = true;
            }
        }
        if (!audience || (kind == AffinityEvent.HEARSAY_BAD && BotAffinity.optedOut(aboutCharId))) {
            return;
        }
        String name = BotBuddies.nameOf(aboutCharId);
        if (name == null) {
            return;
        }
        lastGossipLine = now;
        String line = pick(kind == AffinityEvent.HEARSAY_BAD ? "GossipBad" : "GossipGood", Map.of("OTHER", name));
        if (line != null) {
            ExecutorServiceManager.runAsync(() -> BotSpeak(teller, line));
            if (QaLog.on(QaLog.Topic.AFFINITY)) {
                QaLog.map(QaLog.Topic.AFFINITY, teller.getMapId(), "GOSSIP " + teller.getName() + " to "
                        + (listener == null ? "?" : listener.getName()) + " about " + name + ": " + line);
            }
        }
    }

    // ── lines ──

    static String say(Character bot, String node, String playerName) {
        String line = pick(node, Map.of("PLAYER", playerName == null ? "" : playerName));
        if (line == null || bot == null) {
            return "no line";
        }
        BotSpeak(bot, line);
        return "said \"" + line + "\"";
    }

    /** A line of the node aimed at the player: never one this bot already said to them lately (1.6.0 S5). */
    static String sayTo(Character bot, Character player, String node) {
        if (bot == null || player == null) {
            return "no line";
        }
        String line = pick(node, Map.of("PLAYER", player.getName() == null ? "" : player.getName()),
                l -> SocialGate.fresh(bot.getId(), player.getId(), l));
        if (line == null) {
            return "no fresh line";
        }
        BotSpeak(bot, line);
        return "said \"" + line + "\"";
    }

    static String pick(String node, Map<String, String> tokens) {
        return pick(node, tokens, l -> true);
    }

    /** A random line of the node with every token filled that {@code ok} accepts (tried in a shuffled order). */
    static String pick(String node, Map<String, String> tokens, java.util.function.Predicate<String> ok) {
        BotDialogueHandler.DialogueConstructor dialog = BotDialogueHandler.getDialogueCon(PACK, ROOT, node);
        if (dialog == null || dialog.getDialogue() == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialog.getDialogue());
        Collections.shuffle(lines);
        for (String line : lines) {
            String out = line;
            for (Map.Entry<String, String> t : tokens.entrySet()) {
                if (t.getValue() != null && !t.getValue().isBlank()) {
                    out = out.replace("{" + t.getKey() + "}", t.getValue());
                }
            }
            if (!out.matches(".*\\{[A-Z_]+}.*") && ok.test(out)) {
                return out;
            }
        }
        return null;
    }

    /**
     * 1.5.0 bot drama: a bot called into a KS war takes up to {@code mobs} of the player's mobs within its own reach for
     * {@code ms} (the same rules as a grudge spree, but it needs no grudge of its own; {@code @botdrama off} still ends
     * it).
     */
    public static void startWarSpree(Character bot, Character player, long ms, int mobs) {
        sprees.put(bot.getId(), new KsSpree(player.getId(), player.getMapId(), BotAffinity.now() + ms, new int[]{mobs},
                true));
    }

    /**
     * Whether this bot is on a live KS spree against this player (mobs left, time left). KS manners step aside for
     * it: rc6 triage of rv-2 found a KS war's called helpers - neutral bots - kept off the player's platform and mobs
     * by their manners, so none of them ever took a mob.
     */
    public static boolean onKsSpreeAgainst(int botCharId, int playerId) {
        KsSpree s = sprees.get(botCharId);
        return s != null && s.playerId() == playerId && BotAffinity.now() <= s.until() && s.left()[0] > 0;
    }

    /**
     * 1.6.1 rv-2: the player a KS war called this bot in on (a live war spree on the bot's current map, mobs and time
     * left), or -1. The grind brain camps that player's platform for the war instead of its own pick: a helper that
     * settled on another ledge never had the player's mobs in reach, and the rally walks were undone by its anchor.
     */
    public static int warTargetOf(int botCharId, int mapId) {
        KsSpree s = sprees.get(botCharId);
        if (s == null || !s.war() || s.mapId() != mapId || BotAffinity.now() > s.until() || s.left()[0] <= 0) {
            return -1;
        }
        return s.playerId();
    }

    /** 1.5.0 bot drama: the bot's KS spree ends now. */
    public static void stopSpree(int botCharId) {
        sprees.remove(botCharId);
    }

    /** GM: put a bot on a KS spree against a player now (no cooldown). */
    public static void forceSpree(Character bot, Character player) {
        startSpree(bot, player);
    }

    /** Whether a bot is on a KS spree right now (GM display). */
    public static String spreeOf(int botCharId) {
        KsSpree s = sprees.get(botCharId);
        if (s == null || BotAffinity.now() > s.until()) {
            return null;
        }
        return "on a KS spree against " + s.playerId() + " (" + s.left()[0] + " mobs left, "
                + TimeUnit.MILLISECONDS.toSeconds(s.until() - BotAffinity.now()) + " s)";
    }

    /** Tests: forget every cooldown, cap and spree. */
    static void resetForTest() {
        SocialGate.resetBudgetForTest();
        defamedDay.clear();
        sprees.clear();
        lastRefuseLine.clear();
        lastNoTake.clear();
        lastGossipLine = 0;
    }
}
