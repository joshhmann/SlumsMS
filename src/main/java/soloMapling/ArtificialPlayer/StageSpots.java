package soloMapling.ArtificialPlayer;

import client.Character;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * 1.5.0 rc6 (QA t-pb-1, owner: "when we spawn players they should be a bit spread out so they're all not lined up"):
 * where a staged bot appears near a GM (or a player, an altar): either side, at a random distance, on a foothold, and
 * not on top of anyone already there. Staging used to place bots at fixed offsets on the GM's own y (a row, or three
 * bots on one pixel), floating when the GM stood on a slope or a platform edge.
 */
public final class StageSpots {

    /** Closest two staged characters may stand (px), and how far the ground may be above or below the centre. */
    static final int MIN_GAP = 45;
    static final int MAX_DY = 90;
    static final int TRIES = 14;

    private StageSpots() {
    }

    /**
     * A spot near {@code center}: random side, 70-330 px out (growing with each try), on the ground {@code ground}
     * finds below a point (null = none), at most {@link #MAX_DY} from the centre's height, and at least
     * {@link #MIN_GAP} from every {@code taken} point. Falls back to the nearest grounded try, then the centre.
     */
    static Point pick(Point center, List<Point> taken, Random rng, BiFunction<Integer, Integer, Point> ground) {
        Point fallback = null;
        for (int i = 0; i < TRIES; i++) {
            int side = rng.nextBoolean() ? 1 : -1;
            int dx = side * (70 + 20 * i + rng.nextInt(60));
            Point g = ground.apply(center.x + dx, center.y - 40);
            if (g == null || Math.abs(g.y - center.y) > MAX_DY) {
                continue;
            }
            if (fallback == null) {
                fallback = g;
            }
            if (clear(g, taken)) {
                return g;
            }
        }
        return fallback != null ? fallback : new Point(center);
    }

    static boolean clear(Point p, List<Point> taken) {
        for (Point t : taken) {
            if (t != null && Math.abs(t.x - p.x) < MIN_GAP && Math.abs(t.y - p.y) < 40) {
                return false;
            }
        }
        return true;
    }

    /** A spot on {@code map} near {@code center}, clear of everyone on the map now. */
    public static Point near(MapleMap map, Point center) {
        if (map == null || center == null) {
            return center == null ? new Point(0, 0) : new Point(center);
        }
        List<Point> taken = new ArrayList<>();
        try {
            for (Character c : map.getAllPlayers()) {
                if (c != null && c.getPosition() != null) {
                    taken.add(c.getPosition());
                }
            }
        } catch (RuntimeException e) {
            // nobody known: any grounded spot will do
        }
        return pick(center, taken, ThreadLocalRandom.current(), (x, y) -> groundBelow(map, x, y));
    }

    /** A spot near the GM on the GM's map. */
    public static Point nearGm(Character gm) {
        return near(gm.getMap(), gm.getPosition() == null ? new Point(0, 0) : gm.getPosition());
    }

    /** The ground right below a point, or null (off the map, over a void). */
    public static Point groundBelow(MapleMap map, int x, int y) {
        try {
            return soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.groundPointBelow(map, x, y);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
