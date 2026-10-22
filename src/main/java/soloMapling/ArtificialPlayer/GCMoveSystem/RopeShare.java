package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.qa.QaLog;

/*
 * 1.5.0 rc3 (owner decision 6, "avoid stacking on ropes and ladders"): a bot about to grab a rope or ladder that
 * another bot on the same map instance is already on waits at the bottom (or top) for a moment, the way players
 * queue for a rope, instead of piling on. The wait is capped (WAIT_CAP_MS) so a bot never stands forever behind a
 * rope hanger; after that it climbs anyway. Only on maps a real player can see; offscreen movement is untouched.
 */
final class RopeShare {

    private RopeShare() {
    }

    static final long WAIT_CAP_MS = 2_500;

    /* True when the bot should hold off grabbing this rope this tick. */
    static boolean shouldWait(BotMovementState entry, Character bot, Rope rope) {
        if (rope == null || bot == null || !BotCombatTuning.spread()) {
            return false;
        }
        MapleMap map = bot.getMap();
        if (map == null || !GCMovement.isMapObserved(bot.getMapId())) {
            entry.ropeWaitSinceMs = 0L;
            return false;
        }
        Character holder = otherBotOn(map, rope, bot.getId());
        long now = System.currentTimeMillis();
        if (holder == null) {
            entry.ropeWaitSinceMs = 0L;
            return false;
        }
        if (entry.ropeWaitSinceMs == 0L) {
            entry.ropeWaitSinceMs = now;
            if (QaLog.on(QaLog.Topic.SPREAD)) {
                QaLog.map(QaLog.Topic.SPREAD, bot.getMapId(), "ROPE " + bot.getName() + " waits: " + holder.getName()
                        + " is on the " + (rope.isLadder() ? "ladder" : "rope") + " at x " + rope.x());
            }
            return true;
        }
        if (now - entry.ropeWaitSinceMs < WAIT_CAP_MS) {
            return true;
        }
        entry.ropeWaitSinceMs = 0L; // waited long enough: go
        if (QaLog.on(QaLog.Topic.SPREAD)) {
            QaLog.map(QaLog.Topic.SPREAD, bot.getMapId(), "ROPE " + bot.getName() + " stops waiting for "
                    + holder.getName() + " after " + (WAIT_CAP_MS / 1000.0) + " s and climbs");
        }
        return false;
    }

    /* Another bot on this map instance on the same rope, or null. */
    static Character otherBotOn(MapleMap map, Rope rope, int selfId) {
        for (Character c : map.getCharacters()) {
            if (c == null || c.getId() == selfId || !BotHelpers.isBot(c)) {
                continue;
            }
            Rope on = GCMovement.climbingRope(c);
            if (on != null && on.equals(rope)) {
                return c;
            }
        }
        return null;
    }
}
