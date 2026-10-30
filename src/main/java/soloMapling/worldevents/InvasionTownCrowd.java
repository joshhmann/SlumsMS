package soloMapling.worldevents;

import client.Character;
import constants.id.MapId;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTownSystem.AfkCampers;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.qa.QaLog;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * 1.6.0 madness (owner, TEST 2026-10-27: "none of the current playerbots in town are participating, there should be
 * madness"): when invaders pour into a town, the town bots already standing there react within seconds. About half
 * of them ({@link #FIGHT_SHARE}) fight the event's mobs with their own basic attack ({@link BotAttackDriver}, the same
 * validated swing grinders and followers use, at whatever level they are); the rest run for the map's edge, now and
 * then shouting about it. When the event ends they go back to what they were doing.
 * <p>
 * Who: town wanderers, social bots and Henesys bots on the event map (an AFK camper among them stops camping), running, free by the
 * population director's own rule (no trade, no party with a player, not talking to one, not following anyone), not
 * travelling, not staged by a GM, not keeping a shop or a minigame. Merchants, dealers, hosts, gacha and the other
 * service bots, companions and owned alts ({@code FollowerBot}) and training bots (the event calls those itself,
 * {@link WorldEventReactions#recruit}) are never taken.
 * <p>
 * Fighters can die like any bot at an invasion (the swarm rules in {@link WorldEvents#swarmDeath}: per-bot cooldown,
 * the event's pace and cap). A dead one lies down, respawns in the town, grumbles (WorldEvents#onBotDied), and then
 * either goes back in or has had enough and runs.
 * <p>
 * How: a taken bot's own macro tick runs its beat here instead of its usual state machine ({@link #handles}, first
 * thing in {@code BotSM}'s tick), at a fight cadence of {@link #FIGHT_TICK_MS}; nothing else changes for any other bot
 * (one map lookup when nobody is held). The end is applied on the bot's own tick too, so its movement is only ever
 * touched from one thread.
 */
public final class InvasionTownCrowd {

    /** The share of the town bots on the map that fight; the rest run. */
    static final double FIGHT_SHARE = 0.5;
    /** After a death, the share that has had enough and runs instead of going back in. */
    static final double FLEE_AFTER_DEATH = 0.5;
    static final long FIGHT_TICK_MS = 400;
    static final long FLEE_TICK_MS = 1_500;
    /** Shouts ("RUN") on one event: at most one per this gap, only where a real player is. */
    static final long SHOUT_GAP_MS = 6_000;
    static final double SHOUT_CHANCE = 0.5;
    /** How close (px, horizontally) a fighter walks to its target before it plants and swings. */
    private static final int MIN_REACH_PX = 60;
    private static final double REACH_FRAC = 0.8;
    /** A runner that finds an invader this close moves on to the other edge. */
    static final int FLEE_AGAIN_PX = 120;
    private static final int EDGE_INSET_PX = 30;

    enum Role { FIGHT, FLEE }

    static final class Held {
        final ActiveEvent event;
        final boolean wasMoving; // under GCMovement before: left so after (a social bot's old engine needs it off)
        volatile Role role;
        volatile boolean placed;
        volatile boolean over;

        Held(ActiveEvent event, Role role, boolean wasMoving) {
            this.event = event;
            this.role = role;
            this.wasMoving = wasMoving;
        }
    }

    private static final Map<Integer, Held> HELD = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastShoutByEvent = new ConcurrentHashMap<>();

    /** Test seam: free for something else, by the population director's rule. */
    static volatile Predicate<BotSM> free = BotPopulationDirector::mayLogOut;

    private InvasionTownCrowd() {
    }

    /** True while this bot fights or flees an invasion in its town. */
    public static boolean holds(int charId) {
        return HELD.containsKey(charId);
    }

    /** What this bot does at the invasion now, or null when it is not in one. */
    static Role roleOf(int charId) {
        Held h = HELD.get(charId);
        return h == null ? null : h.role;
    }

    /** Pure: a fighter or a runner. */
    static Role roleFor(double roll) {
        return roll < FIGHT_SHARE ? Role.FIGHT : Role.FLEE;
    }

    /** The town types that join in: town wanderers, social bots, Henesys bots (merchants, hosts, followers... never). */
    static boolean townType(BotSM bot) {
        return bot instanceof TownWandererBot || bot instanceof SocialBot
                || bot instanceof soloMapling.ArtificialPlayer.BotTypes.HenesysBot;
    }

    /** May this bot standing on the event map be taken now? */
    static boolean eligible(BotSM bot, ActiveEvent e) {
        Character chr = bot == null ? null : bot.getChr();
        if (chr == null || !townType(bot) || HELD.containsKey(chr.getId())) {
            return false;
        }
        int id = chr.getId();
        if (bot.getState() != BotSM.BotState.RUNNING || !bot.getRunning() || chr.getMapId() != e.mapId()
                || BotDeaths.isDead(id) || CharacterStorage.checkIfInvisibleBot(id)) {
            return false;
        }
        if (chr.isOwnedAlt()) {
            return false; // a player's alt: its owner decides
        }
        if (chr.getTrade() != null || chr.getPlayerShop() != null || chr.getMiniGame() != null) {
            return false; // a shop or a game open: it keeps it
        }
        if (soloMapling.ArtificialPlayer.StageSpots.pinned(id) || WorldEventSteer.isStagingHeld(id)) {
            return false; // GM staging
        }
        if (GCMovement.isTraveling(chr) || GCMovement.isInTransit(chr)
                || soloMapling.ArtificialPlayer.BotConversation.isHoldingChat(chr)) {
            return false; // on its way out, or talking with a player
        }
        return free.test(bot);
    }

    /**
     * The town bots on an invasion's map that are not in it yet join it: about half fight, the rest run. Called when
     * the invasion starts and on every event tick (bots that walk in later, campers). Returns how many were taken.
     */
    static int rally(ActiveEvent e, MapleMap map, long now, Random rng) {
        if (e == null || e.finished || map == null || e.template.kind() != EventTemplate.Kind.INVASION) {
            return 0;
        }
        int fight = 0;
        int flee = 0;
        for (Character chr : new ArrayList<>(map.getAllPlayers())) {
            if (chr == null || !BotHelpers.isBot(chr) || HELD.containsKey(chr.getId())) {
                continue;
            }
            BotSM bot = CharacterStorage.getBotById(chr.getId());
            if (bot == null) {
                continue;
            }
            if (!townType(bot)) {
                if (AfkCampers.isCamping(chr.getId())) {
                    AfkCampers.endCamp(chr.getId(), "invasion"); // a grinder AFK in town: back, and free to answer
                }
                continue;
            }
            if (!eligible(bot, e)) {
                continue;
            }
            Role role = roleFor(rng.nextDouble());
            take(bot, e, role, rng);
            if (role == Role.FIGHT) {
                fight++;
            } else {
                flee++;
            }
        }
        if (fight + flee > 0) {
            WorldEvents.qa("WEVENT TOWN #" + e.id + " " + fight + " town bots fight, " + flee + " run on " + e.mapId()
                    + " (" + heldOn(e.id) + " in it)");
            soloMapling.BotLogger.log("[WorldEvents] #" + e.id + " " + fight + " town bots fight, " + flee
                    + " run on " + e.mapName);
        }
        return fight + flee;
    }

    private static void take(BotSM bot, ActiveEvent e, Role role, Random rng) {
        Character chr = bot.getChr();
        int id = chr.getId();
        AfkCampers.endCamp(id, "invasion");
        HELD.put(id, new Held(e, role, GCMovement.isEnabled(chr)));
        BotWanderSystem.stop(chr); // the stroll loop would walk it off again
        if (role == Role.FIGHT) {
            BotDeaths.setEligible(id, true); // the swarm rules decide (cooldown, the event's pace and cap)
        }
        bot.updateScheduleDelay(role == Role.FIGHT ? FIGHT_TICK_MS : FLEE_TICK_MS); // the first beat within a second
    }

    static int heldOn(int eventId) {
        int n = 0;
        for (Held h : HELD.values()) {
            if (h.event.id == eventId && !h.over) {
                n++;
            }
        }
        return n;
    }

    /** The event ended: every bot it holds goes back to its day on its own next tick. */
    static void release(int eventId) {
        int n = 0;
        for (Map.Entry<Integer, Held> en : HELD.entrySet()) {
            Held h = en.getValue();
            if (h.event.id != eventId) {
                continue;
            }
            BotSM bot = CharacterStorage.getBotById(en.getKey());
            if (bot == null || bot.getChr() == null) {
                HELD.remove(en.getKey(), h); // logged out meanwhile
                BotDeaths.setEligible(en.getKey(), false);
                continue;
            }
            h.over = true;
            n++;
            bot.nudgeSoon(300 + ThreadLocalRandom.current().nextInt(2_000));
        }
        lastShoutByEvent.remove(eventId);
        if (n > 0) {
            WorldEvents.qa("WEVENT TOWN #" + eventId + " over: " + n + " town bots go back to their day");
        }
    }

    // ── the bot's own tick (BotSM) ──────────────────────────────────────────────────────────────────

    /**
     * Called first thing on a bot's macro tick: true = this tick was the invasion's (fight, run, lie dead, respawn);
     * false = not held, or just let go, and the bot's own state machine runs as usual.
     */
    public static boolean handles(BotSM bot) {
        if (HELD.isEmpty() || bot == null) {
            return false;
        }
        Character chr = bot.getChr();
        Held h = chr == null ? null : HELD.get(chr.getId());
        if (h == null) {
            return false;
        }
        int id = chr.getId();
        try {
            if (BotDeaths.isDead(id)) {
                if (BotDeaths.readyToRespawn(id)) {
                    respawn(bot, chr, h);
                }
                return true; // lying there, or just back on its feet
            }
            String why = endReason(bot, chr, h);
            if (why != null) {
                end(bot, chr, h, why);
                return false;
            }
            if (h.role == Role.FIGHT) {
                fight(chr, h);
            } else {
                flee(chr, h);
            }
        } catch (RuntimeException ex) {
            end(bot, chr, h, "error " + ex);
            return false;
        }
        return true;
    }

    static String endReason(BotSM bot, Character chr, Held h) {
        if (h.over || h.event.finished) {
            return "event over";
        }
        if (chr.getMap() == null || chr.getMapId() != h.event.mapId()) {
            return "left the map";
        }
        if (bot.getState() != BotSM.BotState.RUNNING || chr.getTrade() != null) {
            return "trade";
        }
        if (!free.test(bot)) {
            return "commitment"; // a party with a player, a conversation, an invite...: that comes first
        }
        return null;
    }

    private static void end(BotSM bot, Character chr, Held h, String why) {
        if (!HELD.remove(chr.getId(), h)) {
            return;
        }
        BotDeaths.setEligible(chr.getId(), false);
        try {
            GCMovement.stop(chr);
            if (!h.wasMoving) {
                GCMovement.disable(chr); // hand movement back to its own engine
            }
        } catch (RuntimeException ignored) {
            // movement clean-up only
        }
        bot.setPriorityNormal();
        if (QaLog.on(QaLog.Topic.WORLD_EVENT)) {
            QaLog.map(QaLog.Topic.WORLD_EVENT, h.event.mapId(), "WEVENT TOWN #" + h.event.id + " " + chr.getName()
                    + " (" + h.role.name().toLowerCase(java.util.Locale.ROOT) + ") back to its day: " + why);
        }
    }

    /** One fight beat: walk to the nearest invader and swing when it is in reach (the driver paces the swings). */
    private static void fight(Character chr, Held h) {
        Monster t = WorldEvents.nearestEventMob(chr, BotAttackDriver::botMayTarget);
        if (t == null || chr.getPosition() == null) {
            return; // between waves: it stands where it is
        }
        Point at = chr.getPosition();
        Point mob = t.getPosition();
        int reach = Math.max(MIN_REACH_PX, (int) (BotAttackDriver.attackReachX(chr) * REACH_FRAC));
        int dx = mob.x - at.x;
        if (Math.abs(dx) > reach || Math.abs(mob.y - at.y) > Math.max(40, BotAttackDriver.attackReachY(chr))) {
            GCMovement.move(chr, mob.x - Integer.signum(dx) * (reach / 2), mob.y);
            return;
        }
        BotAttackDriver.AttackResult r = BotAttackDriver.botAttack(chr);
        if (r != null && r.hit() && QaLog.on(QaLog.Topic.WORLD_EVENT)
                && QaLog.every("town-fight:" + chr.getId(), 5_000)) {
            QaLog.map(QaLog.Topic.WORLD_EVENT, chr.getMapId(), "WEVENT TOWN FIGHT " + chr.getName() + " (lv "
                    + chr.getLevel() + ") hits " + r.monsterName() + " for " + r.damage());
        }
        if (!h.placed) {
            h.placed = true;
            shout(chr, h.event, "TownFight");
        }
    }

    /** A runner heads for the far end of the ground, once, and again if an invader comes close. */
    private static void flee(Character chr, Held h) {
        Point at = chr.getPosition();
        if (at == null || chr.getMap() == null) {
            return;
        }
        Monster near = WorldEvents.nearestEventMob(chr, m -> true);
        boolean threatened = near != null && near.getPosition() != null
                && Math.abs(near.getPosition().x - at.x) <= FLEE_AGAIN_PX;
        if (h.placed && (!threatened || GCMovement.isMoving(chr))) {
            return;
        }
        List<GCMovement.Ledge> ledges;
        try {
            ledges = GCMovement.walkableLedges(chr.getMap());
        } catch (RuntimeException | LinkageError ex) {
            ledges = List.of();
        }
        Point to = edge(ledges, at, near == null ? null : near.getPosition());
        if (to == null) {
            h.placed = true;
            return;
        }
        GCMovement.move(chr, to.x, to.y);
        if (!h.placed) {
            h.placed = true;
            shout(chr, h.event, "TownFlee");
        }
    }

    /**
     * Pure: where a runner goes: the end of the map's ground (its leftmost or rightmost ledge end) away from the
     * invader nearest it, else the end nearer to itself. Null when there is no ground.
     */
    static Point edge(List<GCMovement.Ledge> ledges, Point at, Point threat) {
        GCMovement.Ledge left = null;
        GCMovement.Ledge right = null;
        for (GCMovement.Ledge l : ledges) {
            if (left == null || l.minX() < left.minX()) {
                left = l;
            }
            if (right == null || l.maxX() > right.maxX()) {
                right = l;
            }
        }
        if (left == null) {
            return null;
        }
        Point l = new Point(left.minX() + EDGE_INSET_PX, left.centerY());
        Point r = new Point(right.maxX() - EDGE_INSET_PX, right.centerY());
        if (threat != null) {
            return threat.x >= at.x ? l : r; // away from it
        }
        return Math.abs(at.x - l.x) <= Math.abs(at.x - r.x) ? l : r;
    }

    private static void respawn(BotSM bot, Character chr, Held h) {
        int diedOn = chr.getMapId();
        int town = chr.getMap() != null ? chr.getMap().getReturnMapId() : diedOn;
        if (town <= 0 || town == MapId.NONE) {
            town = diedOn;
        }
        GCMovement.releaseCommitmentHold(chr);
        chr.respawn(town);
        boolean runs = h.role == Role.FIGHT && ThreadLocalRandom.current().nextDouble() < FLEE_AFTER_DEATH;
        if (runs) {
            h.role = Role.FLEE; // had enough
            h.placed = false;
            BotDeaths.setEligible(chr.getId(), false);
            bot.updateScheduleDelay(FLEE_TICK_MS);
        }
        if (QaLog.on(QaLog.Topic.HABITS)) {
            QaLog.any(QaLog.Topic.HABITS, "RESPAWN " + chr.getName() + " died on " + diedOn + ", respawned in town "
                    + town + ", " + (runs ? "has had enough and runs" : "goes back in") + " (town bot)");
        }
    }

    private static void shout(Character chr, ActiveEvent e, String node) {
        long now = System.currentTimeMillis();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Long last = lastShoutByEvent.get(e.id);
        if (last != null && now - last < SHOUT_GAP_MS || rng.nextDouble() >= SHOUT_CHANCE
                || !GCMovement.isMapObserved(chr)) {
            return;
        }
        lastShoutByEvent.put(e.id, now);
        String line = WorldEventReactions.pickLine(node, WorldEventReactions.tokens(e), rng);
        if (line != null) {
            WorldEvents.qa("WEVENT CHATTER #" + e.id + " " + chr.getName() + " (" + node + "): " + line);
            SocialCommands.BotSpeak(chr, line);
        }
    }

    /** Test seam: forget everyone. */
    static void clearForTest() {
        HELD.clear();
        lastShoutByEvent.clear();
        free = BotPopulationDirector::mayLogOut;
    }
}
