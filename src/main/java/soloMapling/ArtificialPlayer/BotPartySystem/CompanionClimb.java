package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.7 follow-plus: following an owner who is up a rope, a ladder, or on a platform only one reaches. Standing at
 * the bottom is not following. The nav layer already plans CLIMB edges and rope exits for population bots, so this
 * only has to stop the plain "tail the owner's pixel" follow and hand the owner's position to
 * {@link GCMovement#move} as an explicit move target: the driver plans the climb from there. While it climbs it is
 * exempt from the stuck check (a climb is not walking), and at the rope head it gets one step off the rope toward
 * the owner so it does not hang there with the driver waiting for a ground target it can never reach.
 *
 * <p>If the climb makes no progress for {@link #CLIMB_TIMEOUT_MS} (12 s) - the rope plan gave up, another bot is
 * sitting on the rope, the owner went somewhere the climb cannot reach - it falls back to the existing catch-up:
 * beside the owner, and the one "oops, coming!" line.
 */
public final class CompanionClimb {

    /** A vertical gap this large (or an owner on a rope) is a climb, not a walk (px). */
    public static final int CLIMB_DY = 60;
    /** At the rope head, step this far off it toward the owner (px). */
    public static final int ROPE_EXIT_PX = 24;
    /** No progress for this long while climbing falls back to the catch-up. */
    public static final long CLIMB_TIMEOUT_MS = 12_000L;
    /** Closing the gap by this much counts as progress (px). */
    public static final int PROGRESS_PX = 30;
    /** Re-issue the move target when the owner has moved this far from the last one (px). */
    public static final int RETARGET_PX = 40;
    /** Re-arm a target the driver has already cleared, but not more often than this (ms). */
    static final long REARM_MS = 1_500L;

    static final class Drive {
        int best;
        long bestAtMs;
        int targetX;
        int targetY;
        boolean hasTarget;
        long issuedMs;
    }

    private static final Map<Integer, Drive> DRIVES = new ConcurrentHashMap<>();

    private CompanionClimb() {
    }

    /**
     * Follow through a climb, if that is what this owner needs.
     *
     * @return true when this companion is being driven to its owner by a climb this poll (do not arm the follow)
     */
    public static boolean tick(Character companion, Character owner, long now) {
        if (companion == null || owner == null) {
            return false;
        }
        MapleMap map = companion.getMap();
        if (map == null || map != owner.getMap() || !PartyCompanionPolicy.on(Feature.FOLLOW_PLUS, companion)) {
            DRIVES.remove(companion.getId());
            return false;
        }
        Point p = companion.getPosition();
        Point o = owner.getPosition();
        if (p == null || o == null) {
            return false;
        }
        Rope ownRope = GCMovement.climbingRope(companion);
        boolean ownerRoped = GCMovement.climbingRope(owner) != null;
        int dy = Math.abs(o.y - p.y);
        if (!ownerRoped && dy <= CLIMB_DY && ownRope == null) {
            DRIVES.remove(companion.getId());
            return false; // a normal walk: the follow tail handles it better
        }
        Drive d = DRIVES.computeIfAbsent(companion.getId(), k -> new Drive());
        int gap = Math.max(Math.abs(o.x - p.x), dy);
        if (d.bestAtMs == 0L || gap < d.best - PROGRESS_PX / 2) {
            d.best = gap;
            d.bestAtMs = now;
        }
        if (d.bestAtMs != 0L && now - d.bestAtMs >= CLIMB_TIMEOUT_MS) {
            // The climb is not working. The catch-up is the same recovery the stuck check uses.
            boolean moved = CompanionStuck.putBeside(companion, owner, now);
            DRIVES.remove(companion.getId());
            return moved;
        }
        // Rope-top exit: on the rope, near its head, and the owner already stands on the ledge above it.
        if (ownRope != null && p.y <= ownRope.topY() + ROPE_EXIT_PX
                && o.y < ownRope.topY() + ROPE_EXIT_PX && Math.abs(o.x - p.x) > ROPE_EXIT_PX / 2) {
            GCMovement.dismountRope(companion, o.x >= ownRope.x() ? 1 : -1);
            d.best = gap;
            d.bestAtMs = now;
            return true;
        }
        // Drive: an explicit move target on the owner, so the driver plans the CLIMB edge.
        if (!d.hasTarget || Math.abs(o.x - d.targetX) > RETARGET_PX || Math.abs(o.y - d.targetY) > RETARGET_PX
                || (!GCMovement.isMoving(companion) && now - d.issuedMs >= REARM_MS)) {
            d.targetX = o.x;
            d.targetY = o.y;
            d.hasTarget = true;
            d.issuedMs = now;
            GCMovement.move(companion, o.x, o.y);
        }
        return true;
    }

    /** Forget a companion's climb attempt (tests; the follow session ended). */
    public static void forget(int companionId) {
        DRIVES.remove(companionId);
    }
}
