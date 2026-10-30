package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * 1.6.2 (owner 2026-10-30, "one implementation per behaviour"): the one drop-selection path. Which drops on the floor
 * a character may go for - still there, settled, inside its x bounds, on its ledge, and whatever the caller's own
 * eligibility says - and which of them is nearest. The population grinders (GrindLoot: the at-feet vacuum and the
 * sweep, the pets) and owned alts (OwnedAltLoot) both pick through here; each keeps its own pickup action (a bot's
 * holdings credit, an alt's {@code Character.pickupItem}) and its own rules about whose drop it is.
 * <p>
 * Ledges: a drop more than {@link Reach#ledgeDy} above or below the picker is on another ledge and is never picked
 * (1.6.2 b082: a drop that fell to the ledge below an alt was rightly ignored; the auto-QA staging, not the picker,
 * was wrong - it now asks {@link #onItsLedge} before judging).
 */
public final class LootPicker {

    private LootPicker() {
    }

    /** Let a fresh drop finish its fly-out arc first: an instant pickup looks like it teleports in mid-air. */
    public static final long SETTLE_MS = 1_500;
    /** A drop this far above or below the picker is on a stacked ledge (unreachable by walking along). */
    public static final int SAME_LEDGE_DY = 120;
    /** Close enough to grab from where it stands. */
    public static final int PICKUP_PX = 60;

    /**
     * Where a picker looks: within {@code rangePx} of it, x inside [{@code x0}, {@code x1}], at most {@code ledgeDy}
     * above or below it, settled for {@code settleMs}.
     */
    public record Reach(int rangePx, int x0, int x1, int ledgeDy, long settleMs) {

        /** At its feet: {@link #PICKUP_PX} around it, any x. */
        public static Reach atFeet(int px, long settleMs) {
            return new Reach(px, Integer.MIN_VALUE, Integer.MAX_VALUE, px, settleMs);
        }
    }

    /** The drop is on the floor: not taken, and it has a position. */
    public static boolean onTheFloor(MapItem mi) {
        return mi != null && !mi.isPickedUp() && mi.getPosition() != null;
    }

    /** The drop has been on the ground at least {@code settleMs}. */
    public static boolean settled(MapItem mi, long settleMs, long now) {
        return now - mi.getDropTime() >= settleMs;
    }

    /** A drop at {@code drop} is on the ledge of a picker at {@code at} (within the vertical reach). */
    public static boolean onItsLedge(Point at, Point drop, int ledgeDy) {
        return at != null && drop != null && Math.abs(drop.y - at.y) <= ledgeDy;
    }

    /** Close enough to pick up from where it stands. */
    public static boolean atFeet(Point at, Point drop) {
        return at != null && drop != null && at.distanceSq(drop) <= (double) PICKUP_PX * PICKUP_PX;
    }

    /**
     * Whose drop it is: one of {@code mine} owns it, or its ownership window ({@code freeAfterMs}) has run out. A
     * negative {@code freeAfterMs} means never (an owned alt never sweeps a stranger's loot).
     */
    public static boolean claimable(MapItem mi, IntPredicate mine, long freeAfterMs, long now) {
        return mine.test(mi.getOwnerId()) || freeAfterMs >= 0 && now - mi.getDropTime() >= freeAfterMs;
    }

    /** Every drop the picker may go for, in no order. */
    public static List<MapItem> all(Character chr, Reach r, long now, Predicate<MapItem> eligible) {
        Point pos = chr.getPosition();
        if (pos == null || chr.getMap() == null) {
            return List.of();
        }
        List<MapItem> out = new ArrayList<>();
        for (MapObject mo : chr.getMap().getMapObjectsInRange(pos, (double) r.rangePx() * r.rangePx(),
                List.of(MapObjectType.ITEM))) {
            if (mo instanceof MapItem mi && fits(pos, mi, r, now) && eligible.test(mi)) {
                out.add(mi);
            }
        }
        return out;
    }

    /** The nearest drop the picker may go for, or null. */
    public static MapItem nearest(Character chr, Reach r, long now, Predicate<MapItem> eligible) {
        Point pos = chr.getPosition();
        if (pos == null || chr.getMap() == null) {
            return null;
        }
        MapItem best = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : chr.getMap().getMapObjectsInRange(pos, (double) r.rangePx() * r.rangePx(),
                List.of(MapObjectType.ITEM))) {
            if (!(mo instanceof MapItem mi) || !fits(pos, mi, r, now) || !eligible.test(mi)) {
                continue; // eligible is asked about every drop in reach (an alt notes the bags it has no room in)
            }
            double dsq = pos.distanceSq(mi.getPosition());
            if (dsq < bestSq) {
                bestSq = dsq;
                best = mi;
            }
        }
        return best;
    }

    // still there, settled, in the x bounds and on the picker's ledge
    private static boolean fits(Point pos, MapItem mi, Reach r, long now) {
        if (!onTheFloor(mi) || !settled(mi, r.settleMs(), now)) {
            return false;
        }
        Point ip = mi.getPosition();
        return ip.x >= r.x0() && ip.x <= r.x1() && onItsLedge(pos, ip, r.ledgeDy());
    }
}
