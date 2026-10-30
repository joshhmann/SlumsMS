package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import constants.id.MapId;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotGrindSystem.Approach;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A follower fights next to its leader on a Mu Lung Dojo floor (1.5.5 hotfix: "Mu Lung Dojo - bots don't register
 * enemies on party climb so they just watch").
 *
 * <p>A {@link FollowerBot} only follows; it fights once its leader says "train here", which turns it into a
 * TrainingBot. That way out is shut in the dojo: a floor (9250x0x00, Map.wz) has no {@code life} mobs, its monster
 * is spawned by the map script ({@code dojang_1st} -> {@code MapleMap.spawnDojoMonster}), so the static mob index
 * every grinding path reads ({@code MapMobIndex.level}) calls the floor mobless: "train here" answers "no mobs here"
 * and a partied TrainingBot does not count it as a training map. So on a dojo floor the follower swings at the live
 * monsters itself while its leader is on the same floor, and goes back to following when the floor is clear or the
 * leader moves on. Rest floors (every sixth) have no monster, so nothing changes there.
 *
 * <p>1.6.0: the same holds inside a boss expedition its leader took it into ({@code ExpeditionBots.inRun}): the
 * altar's monsters are spawned by the event, so a follower there fights the nearest one next to its leader.
 *
 * <p>1.6.0: and in a party quest: a follower in its leader's party and in the same event instance (KPQ, HPQ, LPQ,
 * ...) fights the stage's monsters with him. Friendly mobs (HPQ's Moon Bunny) are never targets
 * ({@code BotAttackDriver.botMayTarget}).
 *
 * <p>1.6.2 (followerfix, TEST 2026-10-30 Zakum altar): and anywhere else in its leader's party, it fights the party's
 * fight. Two population bots partied and told to follow stood beside the player through a whole Zakum fight (only a
 * forced {@code !bot attack} swung) because none of the three cases above held: the altar was reached by {@code !zakum}
 * and {@code !warp}, not through an expedition or an event instance. A follower in its leader's party now goes for the
 * mob the party is fighting (a member hit it lately, Zakum's arms and body included) by the owned alts' own rule
 * ({@link soloMapling.companions.OwnedAltCombat#target}: engaged mobs only, never a stranger's, by its leader); it walks
 * in with the shared approach step ({@link Approach}) and swings with its own rotation ({@link BotAttackDriver#botAttack}:
 * the human skill choice and rotation extras, its cooldowns setting the pace). It never starts on a quiet mob alone.
 */
final class FollowerFight {

    /** Tick of a follower while it fights: the attack driver's own cooldown sets the real swing rate. */
    static final long FIGHT_TICK_MS = 200;

    /** A party-fight target it walked to or swung at this long without landing a hit is left alone a while. */
    static final long STALL_MS = 10_000;
    static final long GIVE_UP_MS = 15_000;

    /** Per follower: what the fight remembers between ticks (the shared approach's last walk, a stalled target). */
    private static final class State {
        int lastMoveX = Integer.MIN_VALUE;
        int targetOid = -1;
        long targetSince;
        long lastLandedMs;
        final Map<Integer, Long> skip = new HashMap<>(); // mob oid -> until when it is left alone
    }

    private static final Map<Integer, State> states = new ConcurrentHashMap<>();

    private FollowerFight() {
    }

    /** Maps where a follower fights on its own: the Mu Lung Dojo (solo and party floors). */
    static boolean fightsOn(int mapId) {
        return MapId.isDojo(mapId);
    }

    /**
     * The monster the follower fights: the nearest live one on its map when it stands on a dojo floor, inside its
     * expedition or inside its leader's party quest, with its leader; else, in its leader's party, the party's fight
     * (see the class note); else null (it just follows).
     */
    static Monster target(Character bot, Character leader) {
        return target(bot, leader, System.currentTimeMillis());
    }

    static Monster target(Character bot, Character leader, long now) {
        if (bot == null || leader == null) {
            return null;
        }
        MapleMap map = bot.getMap();
        Point at = bot.getPosition();
        if (map == null || at == null || leader.getMap() != map) {
            return null;
        }
        if (instanceFight(bot, leader, map)) {
            return nearest(map, at);
        }
        if (!inLeadersParty(bot, leader)) {
            return null;
        }
        State s = states.get(bot.getId());
        Set<Integer> party = soloMapling.companions.OwnedAltCombat.partyIds(bot, leader);
        return soloMapling.companions.OwnedAltCombat.target(leader, bot, party, now,
                m -> s != null && skipped(s, m.getObjectId(), now));
    }

    /** The dojo, its expedition's run, its leader's party quest: everything there is the fight. */
    private static boolean instanceFight(Character bot, Character leader, MapleMap map) {
        return fightsOn(map.getId()) || soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionHold.inRun(bot)
                || inLeadersPq(bot, leader);
    }

    private static Monster nearest(MapleMap map, Point at) {
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
        for (Monster m : map.getAllMonsters()) {
            if (m == null || !m.isAlive() || m.getPosition() == null || !BotAttackDriver.botMayTarget(m)) {
                continue;
            }
            double d = at.distanceSq(m.getPosition());
            if (d < bestSq) {
                bestSq = d;
                best = m;
            }
        }
        return best;
    }

    private static boolean skipped(State s, int oid, long now) {
        Long until = s.skip.get(oid);
        return until != null && until > now;
    }

    /** In its leader's party quest: in his party and in the same event instance as him. */
    static boolean inLeadersPq(Character bot, Character leader) {
        scripting.event.EventInstanceManager eim = bot.getEventInstance();
        return eim != null && eim == leader.getEventInstance() && inLeadersParty(bot, leader);
    }

    /** In its leader's party (a party window invite, a recruit, {@code !bot acceptparty}). */
    static boolean inLeadersParty(Character bot, Character leader) {
        int party = bot.getPartyId();
        return party != -1 && party == leader.getPartyId();
    }

    /**
     * One fight beat: walks to the target and swings when it is in reach. True while the follower fights (the caller
     * leaves its follow session down), false when there is nothing to fight.
     */
    static boolean tick(Character bot, Character leader) {
        long now = System.currentTimeMillis();
        Monster t = target(bot, leader, now);
        if (t == null) {
            standDown(bot);
            return false;
        }
        State s = states.computeIfAbsent(bot.getId(), id -> new State());
        if (t.getObjectId() != s.targetOid) {
            s.targetOid = t.getObjectId();
            s.targetSince = now;
        }
        if (now - Math.max(s.targetSince, s.lastLandedMs) > STALL_MS) {
            // as an owned alt: a mob it walked at (or swung past) and never hit is left a while, the next beat
            // picks another or follows
            s.skip.put(t.getObjectId(), now + GIVE_UP_MS);
            s.skip.values().removeIf(until -> until <= now);
            s.targetOid = -1;
            log(bot, leader, "gives up on " + t.getName() + " (no hit in " + STALL_MS / 1000 + " s)");
            return true;
        }
        if (GCMovement.isFollowing(bot)) {
            GCMovement.stop(bot); // the follow session would drag it back to the leader's side every 50 ms
        }
        MapleMap map = bot.getMap();
        if (!instanceFight(bot, leader, map)) {
            // the party's fight: the driver faces and hits only the party's mobs, never a stranger's (as an alt)
            Set<Integer> party = soloMapling.companions.OwnedAltCombat.partyIds(bot, leader);
            BotAttackDriver.setTargetFilter(bot.getId(),
                    m -> soloMapling.companions.OwnedAltCombat.driverMayFace(m, map, leader, bot, party, now));
        } else {
            BotAttackDriver.setTargetFilter(bot.getId(), null);
        }
        Point at = bot.getPosition();
        Point mob = t.getPosition();
        int[] reach = Approach.reach(bot);
        if (!closeEnough(at, mob, reach, t.isBoss())) {
            int[] spot = Approach.fightSpot(bot, t, reach, BotCombatTuning.human());
            s.lastMoveX = Approach.walkTo(bot, spot[0], spot[1], s.lastMoveX);
            log(bot, leader, "walks to " + t.getName() + " (" + Math.abs(mob.x - at.x) + " px away)");
            return true;
        }
        s.lastMoveX = Integer.MIN_VALUE;
        BotAttackDriver.AttackResult r = BotAttackDriver.botAttack(bot);
        if (r != null && r.hit()) {
            s.lastLandedMs = now;
            log(bot, leader, "hits " + r.monsterName() + " for " + r.damage() + (r.killed() ? " (killed)" : "")
                    + " skill " + r.skillId());
        }
        return true;
    }

    /**
     * In reach to swing (the shared approach's box); a boss only needs to be within reach across - its body and parts
     * (Zakum's arms) stand high on their own footholds, and the attack driver's padded box decides what a swing hits.
     */
    static boolean closeEnough(Point at, Point mob, int[] reach, boolean boss) {
        if (Approach.inReach(at, mob, reach)) {
            return true;
        }
        return boss && at != null && mob != null && Math.abs(mob.x - at.x) <= reach[0];
    }

    /** Not fighting (or the ride ended): its attack filter and fight memory go. */
    static void standDown(Character bot) {
        if (bot == null) {
            return;
        }
        if (states.remove(bot.getId()) != null) {
            BotAttackDriver.setTargetFilter(bot.getId(), null);
        }
    }

    /*
     * One COMBAT line (!qa dj-1). A real player's follower logs to the run that player started, wherever it fights
     * (a run watches its own maps: dj-1's dojo run heard nothing from the Zakum altar); a bot's follower to the map.
     */
    static void log(Character bot, Character leader, String what) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.COMBAT)
                && soloMapling.qa.QaLog.every("follower-fight:" + bot.getId(), 3_000)) {
            String line = "FOLLOWER FIGHT " + bot.getName() + " " + what;
            if (leader != null && !soloMapling.ArtificialPlayer.BotHelpers.isBot(leader)) {
                soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.COMBAT, leader.getId(), line);
            } else {
                soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.COMBAT, bot.getMapId(), line);
            }
        }
    }
}
