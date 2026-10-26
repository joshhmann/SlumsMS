package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import constants.id.MapId;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;

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
 */
final class FollowerFight {

    /** Tick of a follower while it fights: the attack driver's own cooldown sets the real swing rate. */
    static final long FIGHT_TICK_MS = 200;

    /** How close (px, horizontally) a follower walks to its target before it plants and swings. */
    private static final int MIN_REACH_PX = 60;
    private static final double REACH_FRAC = 0.8;

    private FollowerFight() {
    }

    /** Maps where a follower fights on its own: the Mu Lung Dojo (solo and party floors). */
    static boolean fightsOn(int mapId) {
        return MapId.isDojo(mapId);
    }

    /**
     * The monster the follower fights: the nearest live one on its map when it stands on a dojo floor with its
     * leader, else null (it just follows).
     */
    static Monster target(Character bot, Character leader) {
        if (bot == null || leader == null) {
            return null;
        }
        MapleMap map = bot.getMap();
        Point at = bot.getPosition();
        if (map == null || at == null || leader.getMap() != map || !fightsOn(map.getId())) {
            return null;
        }
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
        for (Monster m : map.getAllMonsters()) {
            if (m == null || !m.isAlive() || m.getPosition() == null) {
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

    /**
     * One fight beat: walks to the target and swings when it is in reach. True while the follower fights (the caller
     * leaves its follow session down), false when there is nothing to fight.
     */
    static boolean tick(Character bot, Character leader) {
        Monster t = target(bot, leader);
        if (t == null) {
            return false;
        }
        if (GCMovement.isFollowing(bot)) {
            GCMovement.stop(bot); // the follow session would drag it back to the leader's side every 50 ms
        }
        Point at = bot.getPosition();
        Point mob = t.getPosition();
        int reach = Math.max(MIN_REACH_PX, (int) (BotAttackDriver.attackReachX(bot) * REACH_FRAC));
        int dx = mob.x - at.x;
        if (Math.abs(dx) > reach) {
            GCMovement.move(bot, mob.x - Integer.signum(dx) * (reach / 2), mob.y);
            log(bot, "walks to " + t.getName() + " (" + Math.abs(dx) + " px away)");
            return true;
        }
        BotAttackDriver.AttackResult r = BotAttackDriver.botAttack(bot);
        if (r != null && r.hit()) {
            log(bot, "hits " + r.monsterName() + " for " + r.damage() + (r.killed() ? " (killed)" : ""));
        }
        return true;
    }

    private static void log(Character bot, String what) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.COMBAT)
                && soloMapling.qa.QaLog.every("follower-fight:" + bot.getId(), 3_000)) { // !qa dj-1
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.COMBAT, bot.getMapId(),
                    "FOLLOWER FIGHT " + bot.getName() + " " + what);
        }
    }
}
