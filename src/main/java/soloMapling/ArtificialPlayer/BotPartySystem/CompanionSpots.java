package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.7 follow-plus: where each companion stands while its owner is waiting by a portal or talking to an NPC. All of
 * them on the owner's pixel hides the owner and the portal; spread along the ground they look like a party.
 *
 * <p>The owner counts as waiting when it has stood still for {@link #STILL_MS} within {@link #NEAR_PX} (150 px) of a
 * portal or an NPC on its own height. Each companion then gets an ordered slot on the owner's foothold: slot 1 one
 * {@link #SLOT_PX} to the left, slot 2 one to the right, slot 3 two left, slot 4 two right. The slot is stable - it
 * is the companion's index among that owner's loaded alts by character id ({@code State.slot} in the driver) - so
 * two companions never choose the same spot and a re-issue does not reshuffle them. A slot that would land within
 * {@link #CLEAR_PX} of the portal or NPC it is keeping clear is pushed one step further out.
 *
 * <p>When the owner walks off, {@link #tick} stops holding a spot and the ordinary follow resumes.
 */
public final class CompanionSpots {

    /** The owner is "beside" a portal or NPC this close (px, either axis). */
    public static final int NEAR_PX = 150;
    /** The owner has to stand this still before companions take their waiting spots. */
    public static final long STILL_MS = 2_000L;
    /** Distance between neighbouring slots (px). */
    public static final int SLOT_PX = 40;
    /** A slot stays this far off the portal or NPC it keeps clear (px). */
    public static final int CLEAR_PX = 40;
    /** Close enough to its slot to count as standing there (px). */
    public static final int THERE_PX = 12;

    static final class Still {
        int mapId = -1;
        int x;
        int y;
        long sinceMs;
    }

    private static final Map<Integer, Still> STILL = new ConcurrentHashMap<>();

    private CompanionSpots() {
    }

    /**
     * Hold this companion's waiting spot, if there is one.
     *
     * @return true when it stands (or walks) at its waiting spot this poll, so the caller must not arm the follow
     */
    public static boolean tick(Character owner, Character companion, int slot, long now) {
        Point spot = spotFor(owner, companion, slot, now);
        if (spot == null) {
            return false;
        }
        Point p = companion.getPosition();
        if (p != null && Math.abs(p.x - spot.x) <= THERE_PX && Math.abs(p.y - spot.y) <= THERE_PX) {
            if (GCMovement.isFollowing(companion)) {
                GCMovement.stop(companion); // arrived: stand here until the owner moves
            }
            return true;
        }
        GCMovement.move(companion, spot.x, spot.y);
        return true;
    }

    /**
     * This companion's waiting spot, or null when the owner is not waiting by a portal or an NPC. {@code slot} is
     * 1-based; 0 or less is treated as slot 1.
     */
    public static Point spotFor(Character owner, Character companion, int slot, long now) {
        if (owner == null || companion == null) {
            return null;
        }
        MapleMap map = owner.getMap();
        Point p = owner.getPosition();
        if (map == null || map != companion.getMap() || p == null) {
            return null;
        }
        if (!PartyCompanionPolicy.on(Feature.FOLLOW_PLUS, companion)) {
            return null;
        }
        Still st = STILL.computeIfAbsent(owner.getId(), k -> new Still());
        if (st.mapId != owner.getMapId() || st.x != p.x || st.y != p.y) {
            st.mapId = owner.getMapId();
            st.x = p.x;
            st.y = p.y;
            st.sinceMs = now;
            return null; // it just moved: not waiting yet
        }
        if (now - st.sinceMs < STILL_MS) {
            return null;
        }
        Point anchor = anchorWithin(map, p);
        if (anchor == null) {
            return null;
        }
        int n = Math.max(1, slot);
        int step = (n + 1) / 2;             // 1 -> 1, 2 -> 1, 3 -> 2, 4 -> 2
        int side = (n % 2 == 1) ? -1 : 1;   // slot 1 left, slot 2 right, ...
        int x = p.x + side * step * SLOT_PX;
        if (Math.abs(x - anchor.x) < CLEAR_PX) {
            x = anchor.x + side * CLEAR_PX; // that slot is on the portal (or the NPC): keep clear, on the same side
        }
        Point ground = GCMovement.groundPointBelow(map, x, p.y);
        return ground != null ? ground : new Point(x, p.y);
    }

    /** The nearest portal or NPC on the owner's height within {@link #NEAR_PX}, or null. */
    static Point anchorWithin(MapleMap map, Point at) {
        Point best = null;
        int bestGap = Integer.MAX_VALUE;
        try {
            for (Portal portal : map.getPortals()) {
                Point q = portal == null ? null : portal.getPosition();
                int gap = gap(at, q);
                if (gap < bestGap) {
                    best = q;
                    bestGap = gap;
                }
            }
            for (MapObject object : map.getMapObjects()) {
                if (object == null || object.getType() != MapObjectType.NPC) {
                    continue;
                }
                Point q = object.getPosition();
                int gap = gap(at, q);
                if (gap < bestGap) {
                    best = q;
                    bestGap = gap;
                }
            }
        } catch (RuntimeException ignored) {
            return best; // a map mid-load: whatever was readable
        }
        return bestGap <= NEAR_PX ? best : null;
    }

    /** The larger axis gap, or MAX_VALUE when the point is unknown; also rejects a portal on another ledge. */
    private static int gap(Point at, Point q) {
        if (q == null) {
            return Integer.MAX_VALUE;
        }
        int dx = Math.abs(at.x - q.x);
        int dy = Math.abs(at.y - q.y);
        return dy > NEAR_PX ? Integer.MAX_VALUE : Math.max(dx, dy);
    }

    /** Forget an owner's "standing still" memory (tests; the owner left). */
    public static void forget(int ownerId) {
        STILL.remove(ownerId);
    }
}
