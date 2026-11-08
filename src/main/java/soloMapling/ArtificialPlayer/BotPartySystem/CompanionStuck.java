package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.7 follow-plus: a companion that should be walking to its owner but is not. The owner's rule: if it has a
 * follow target farther than {@link #FOLLOW_FAR_PX}, is not fighting, not holding a stance, not in the air and not on
 * a rope - and has not moved more than {@link #DRIFT_PX} in {@link #STUCK_MS} (8 s) - then put it beside the owner
 * and say so once ("oops, coming!"). It is the same recovery the existing catch-up uses, on the map the owner is on;
 * the cross-map half stays the travel in {@code GCFollow}.
 *
 * <p>Nothing is snapped when the instance gate refuses ({@code GCFollow.followBlockReason}: the owner is in an event
 * instance this companion is not part of, or reached a map it may not enter) - the caller passes that verdict in
 * {@code blocked}. After one recovery the companion is quiet for {@link #COOLDOWN_MS} and the drift clock restarts
 * from where it was put down, so a genuinely wedged bot is snapped about once per cooldown, not every 8 s.
 *
 * <p>Gated by {@link PartyCompanionPolicy.Feature#FOLLOW_PLUS}: owned alts on, party regulars off by default.
 */
public final class CompanionStuck {

    /** Movement smaller than this many px on both axes is standing still. */
    public static final int DRIFT_PX = 8;
    /** Standing this still for this long, while it should be moving, is stuck. */
    public static final long STUCK_MS = 8_000L;
    /** After one recovery, no second one for this long. */
    public static final long COOLDOWN_MS = 30_000L;
    /** A follow target farther than this should be walked to (px, either axis). */
    public static final int FOLLOW_FAR_PX = 60;
    /** How far beside the owner the stuck companion is put down (px). */
    public static final int SIDE_PX = 30;
    /** The line the owner hears when it catches up. */
    public static final String LINE = "oops, coming!";

    static final class Watch {
        int x;
        int y;
        long movedAtMs;
        long quietUntilMs;
    }

    private static final Map<Integer, Watch> WATCHES = new ConcurrentHashMap<>();

    private CompanionStuck() {
    }

    /**
     * One poll for one follower. {@code busy} - fighting, holding a stance, climbing, in the air - restarts the
     * clock: a companion doing something is not stuck. Being on another map, or a different map object with the same
     * id (another channel, another event instance), is never a snap either: the recovery is same-map only, so the
     * instance gate and {@code BotMapGates} are enforced structurally by the map identity check below - the cross-map
     * half stays the travel in {@code GCFollow} and {@code OwnedAltService.catchUp}, which do gate.
     *
     * @return true when the companion was put beside its owner this poll
     */
    public static boolean tick(Character companion, Character owner, long now, boolean busy) {
        if (companion == null || owner == null) {
            return false;
        }
        int id = companion.getId();
        MapleMap map = companion.getMap();
        if (map == null || map != owner.getMap() || !PartyCompanionPolicy.on(Feature.FOLLOW_PLUS, companion)) {
            WATCHES.remove(id);
            return false;
        }
        Point p = companion.getPosition();
        Point o = owner.getPosition();
        if (p == null || o == null || busy) {
            WATCHES.remove(id); // not in the middle of anything: whatever it does next starts a fresh window
            return false;
        }
        Watch w = WATCHES.computeIfAbsent(id, k -> {
            Watch fresh = new Watch();
            fresh.x = p.x;
            fresh.y = p.y;
            fresh.movedAtMs = now;
            return fresh;
        });
        if (Math.abs(p.x - w.x) > DRIFT_PX || Math.abs(p.y - w.y) > DRIFT_PX) {
            w.x = p.x;
            w.y = p.y;
            w.movedAtMs = now;
        }
        int gap = Math.max(Math.abs(p.x - o.x), Math.abs(p.y - o.y));
        if (gap <= FOLLOW_FAR_PX) {
            w.x = p.x;
            w.y = p.y;
            w.movedAtMs = now; // close enough: not "should be moving"
            return false;
        }
        if (now < w.quietUntilMs || now - w.movedAtMs < STUCK_MS) {
            return false;
        }
        return putBeside(companion, owner, now);
    }

    /**
     * The recovery itself: the companion is snapped beside its owner (on the owner's ground) and says one line.
     * Used by {@link #tick} and by the rope-follow timeout ({@link CompanionClimb}). Respects {@link #COOLDOWN_MS}.
     *
     * @return true when the companion moved
     */
    public static boolean putBeside(Character companion, Character owner, long now) {
        if (companion == null || owner == null || companion.getMap() == null
                || companion.getMap() != owner.getMap()) {
            return false;
        }
        Point p = companion.getPosition();
        Point o = owner.getPosition();
        if (o == null) {
            return false;
        }
        Watch w = WATCHES.computeIfAbsent(companion.getId(), k -> new Watch());
        if (now < w.quietUntilMs) {
            return false;
        }
        int side = (p != null && p.x <= o.x) ? -SIDE_PX : SIDE_PX;
        Point ground = GCMovement.groundPointBelow(companion.getMap(), o.x + side, o.y);
        int x = ground != null ? ground.x : o.x + side;
        int y = ground != null ? ground.y : o.y;
        if (!GCMovement.setDownBeside(companion, x, y, "followplus")) {
            return false;
        }
        w.x = x;
        w.y = y;
        w.movedAtMs = now;
        w.quietUntilMs = now + COOLDOWN_MS;
        CompanionLines.say(owner, companion, LINE);
        return true;
    }

    /** Forget a companion (tests; and a follow session that ended). */
    public static void forget(int companionId) {
        WATCHES.remove(companionId);
    }
}
