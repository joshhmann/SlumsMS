/*
 * This file is part of SlumsMS, which is licensed under the GNU Affero General Public License v3.0.
 *
 * Ported from BotStory (AGPL-3.0), commit fd953285:
 *   src/main/java/server/agents/capabilities/combat/AgentCombatHitboxIntersection.java (intersectsMonsterBounds)
 *   src/main/java/server/agents/capabilities/combat/AgentCombatTargetPositionPolicy.java (isHorizontallyDetached)
 *   src/main/java/server/agents/capabilities/combat/AgentCombatAimPointPolicy.java (nearestPoint)
 *   src/main/java/server/agents/capabilities/combat/data/AgentMobHitboxProvider.java (the fixed-stance mirror rule)
 * See docs/PROVENANCE.md.
 */
package soloMapling.ArtificialPlayer.BotAttackSystem;

import server.life.Monster;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * 1.6.7 (QA's Zakum run on TEST, zakum-sweep-c-20261109T041606Z, 9 Nov 2026): a boss part is reached through its body
 * box, not its anchor. Zakum's eight arms and its body all report the anchor (-9,-216), while their Mob.wz bodies lie up
 * to 400 px from it, so melee measured from the anchor "gave up" on arms it stood beside and claimed arms high above.
 * Ported from BotStory (a swing reaches a mob when its box meets the mob's body; the walk goes to the body's nearest
 * point) and ADAPTED for 1.6.7: for boss parts only, the body is reach ADDED to today's anchor rule, not in place of
 * it, and the walk goes to the body only when a swing from there on the bot's ground meets it. BotStory's strict rule
 * (the anchor counts only on the body) would leave melee with no way to Horntail's heads or Zakum's upper arms, which
 * it reaches from ledges and with ranged members (boss-positioning-by-job, 1.6.8). Every other mob is unchanged.
 */
public final class BossBodyReach {

    private BossBodyReach() {
    }

    /** The body box of a boss part in world coordinates, or null for a non-boss or a part with no Mob.wz box. */
    public static Rectangle bodyOf(Monster m) {
        if (m == null || !m.isBoss() || m.getPosition() == null) {
            return null;
        }
        // BotStory's mirror rule: a mob with a fixed stance never flips its box (Zakum's parts stand fixed)
        boolean mirror = m.getStats() == null ? m.isFacingLeft()
                : m.getStats().getFixedStance() == 0 && m.isFacingLeft();
        return GCMovement.mobBounds(m.getId(), m.getPosition(), mirror);
    }

    /** A swing box reaches the mob: it meets the body, or (no body, or the anchor on the body) holds the anchor. */
    public static boolean intersectsMonsterBounds(Rectangle hitBox, Rectangle mobBounds, Point monsterPosition) {
        if (hitBox == null) {
            return false;
        }
        if (mobBounds != null) {
            if (hitBox.intersects(mobBounds)) {
                return true;
            }
            return !isHorizontallyDetached(monsterPosition, mobBounds) && hitBox.contains(monsterPosition);
        }
        return monsterPosition != null && hitBox.contains(monsterPosition);
    }

    /** The anchor lies left or right of the body: empty space between a fixed multipart mob's parts. */
    static boolean isHorizontallyDetached(Point origin, Rectangle visibleBody) {
        if (origin == null || visibleBody == null || visibleBody.isEmpty()) {
            return false;
        }
        int right = visibleBody.x + visibleBody.width;
        return origin.x < visibleBody.x || origin.x >= right;
    }

    /** The body's point nearest {@code source}; the fallback when there is no body or no source. */
    public static Point nearestPoint(Point source, Rectangle bounds, Point fallback) {
        if (source == null || bounds == null || bounds.isEmpty()) {
            return fallback == null ? null : new Point(fallback);
        }
        int right = bounds.x + Math.max(0, bounds.width - 1);
        int bottom = bounds.y + Math.max(0, bounds.height - 1);
        return new Point(Math.max(bounds.x, Math.min(source.x, right)),
                Math.max(bounds.y, Math.min(source.y, bottom)));
    }

    /** A swing box meets this boss part's Mob.wz body (false for a non-boss or a part with no body box). */
    public static boolean bodyReaches(Rectangle swing, Monster m) {
        Rectangle body = bodyOf(m);
        return body != null && swing != null && swing.intersects(body);
    }

    /**
     * The point a bot at {@code from} walks to and measures to for this mob. For a boss part: its body's nearest point
     * when a swing from there, on the bot's own ground, meets the body (Zakum's low arms beside the altar); else the
     * anchor, as before (Horntail's heads and Zakum's upper arms, whose bodies hang beyond a swing from the ground).
     */
    public static Point aimPoint(Point from, Monster m, int[] reach) {
        Point origin = m == null ? null : m.getPosition();
        Rectangle body = bodyOf(m);
        if (body == null || from == null || reach == null) {
            return origin;
        }
        Point near = nearestPoint(from, body, origin);
        Point stand = new Point(near.x, from.y);
        return reachBox(stand, reach[0], reach[1]).intersects(body) ? stand : origin;
    }

    /** The executor's reach box around a bot: {@code reachX} either side and {@code reachY} up and down. */
    public static Rectangle reachBox(Point at, int reachX, int reachY) {
        return new Rectangle(at.x - reachX, at.y - reachY, 2 * reachX + 1, 2 * reachY + 1);
    }
}
