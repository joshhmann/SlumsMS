package soloMapling.ArtificialPlayer.BotMovementSystem;

import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;

/**
 * 1.5.0 rc5: where a bot standing at a point really stands - the foothold it belongs on.
 *
 * <p>Bots on the old (recorded-path) engine have no physics: the server keeps whatever y the last movement packet
 * said, and a viewer's client draws the bot there. Two things left town bots below their foothold:
 * <ul>
 *   <li>The spawn drop ({@code portalenterdrop}) is replayed relative to the bot, and the recording ends 1 px lower
 *       than it starts. Every boot restored a town bot to its stored point and dropped it 1 px further, and the
 *       store saved the new point: after ~30 test-server boots the Henesys social bots stood 27-30 px inside the
 *       ground.</li>
 *   <li>{@code BotStore.restorePlacement} put the stored point back as is, so nothing ever healed the drift.</li>
 * </ul>
 * {@link #settle} finds the foothold a (possibly sunk) bot belongs on; {@link #landing} where a drop from a point
 * lands.
 */
public final class BotGround {

    /** How far below its foothold a bot may have sunk and still be lifted back onto it. */
    public static final int SINK_RECOVERY_PX = 60;

    private BotGround() {
    }

    /**
     * The ground point a bot at {@code p} belongs on: the foothold at or just below {@code p}, or the one up to
     * {@link #SINK_RECOVERY_PX} above it when that is closer (a bot sunk into the ground). Null when there is no
     * foothold under {@code p.x} at all.
     */
    public static Point settle(FootholdTree footholds, Point p) {
        if (footholds == null || p == null) {
            return null;
        }
        Point exact = pointBelow(footholds, p);
        Point raised = pointBelow(footholds, new Point(p.x, p.y - SINK_RECOVERY_PX));
        if (raised == null || raised.y > p.y) {
            return exact; // nothing within the recovery band above p (raised then is exact or a lower floor)
        }
        if (exact == null) {
            return raised;
        }
        return Math.abs(raised.y - p.y) < Math.abs(exact.y - p.y) ? raised : exact;
    }

    /** A drop from up to this many px under a floor still lands on it (some portals sit a pixel or two low). */
    public static final int LANDING_SLACK_PX = 8;

    /**
     * Where a bot dropping from {@code p} lands: the foothold at or below {@code p} (a portal sits above its floor;
     * {@link #LANDING_SLACK_PX} under it counts as on it), or, for a point already inside the ground with no floor
     * below it, the foothold it sank through.
     */
    public static Point landing(FootholdTree footholds, Point p) {
        if (footholds == null || p == null) {
            return null;
        }
        Point below = pointBelow(footholds, new Point(p.x, p.y - LANDING_SLACK_PX));
        return below != null ? below : settle(footholds, p);
    }

    /**
     * How many px {@code p} lies below (positive) or above (negative) the foothold {@link #settle} puts it on; 0 when
     * it stands on it, {@link Integer#MIN_VALUE} when there is no foothold under it.
     */
    public static int offset(FootholdTree footholds, Point p) {
        Point ground = settle(footholds, p);
        return ground == null ? Integer.MIN_VALUE : p.y - ground.y;
    }

    public static Point settle(MapleMap map, Point p) {
        return map == null ? null : settle(map.getFootholds(), p);
    }

    public static Point landing(MapleMap map, Point p) {
        return map == null ? null : landing(map.getFootholds(), p);
    }

    public static int offset(MapleMap map, Point p) {
        return map == null ? Integer.MIN_VALUE : offset(map.getFootholds(), p);
    }

    /** MapleMap.calcPointBelow: the foothold tree's first foothold at or below p, and its y at p.x. */
    static Point pointBelow(FootholdTree footholds, Point p) {
        Foothold fh = footholds.findBelow(p);
        if (fh == null) {
            return null;
        }
        int dropY = fh.getY1();
        if (!fh.isWall() && fh.getY1() != fh.getY2()) {
            double s1 = Math.abs(fh.getY2() - fh.getY1());
            double s2 = Math.abs(fh.getX2() - fh.getX1());
            double s5 = Math.cos(Math.atan(s2 / s1)) * (Math.abs(p.x - fh.getX1()) / Math.cos(Math.atan(s1 / s2)));
            dropY = fh.getY2() < fh.getY1() ? fh.getY1() - (int) s5 : fh.getY1() + (int) s5;
        }
        return new Point(p.x, dropY);
    }
}
