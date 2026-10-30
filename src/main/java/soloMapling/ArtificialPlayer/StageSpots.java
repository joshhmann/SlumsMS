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
     * rc6 (QA cv-1: the owner "had nobody to talk to"): a staged social bot stays where it was put, next to the GM,
     * instead of drifting off to another spot of the town (SocialBot's idle relocation). It still turns, chats,
     * sits, and walks when something asks it to (a follow, a couple walking together).
     */
    private static final java.util.Set<Integer> pinned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void pin(int botId) {
        pinned.add(botId);
    }

    public static void unpin(int botId) {
        pinned.remove(botId);
    }

    public static boolean pinned(int botId) {
        return !pinned.isEmpty() && pinned.contains(botId);
    }

    /**
     * A spot near {@code center}: random side, 70-290 px out (growing with each try), on the ground {@code ground}
     * finds below a point (null = none), at most {@link #MAX_DY} from the centre's height, and at least
     * {@link #MIN_GAP} from every {@code taken} point. Falls back to the nearest grounded try, then the centre.
     */
    static Point pick(Point center, List<Point> taken, Random rng, BiFunction<Integer, Integer, Point> ground) {
        Point p = pickWithin(center, taken, rng, ground, MAX_DY);
        return p != null ? p : new Point(center);
    }

    /** As {@link #pick} with the ground at most {@code maxDy} from the centre's height; null when no try found any. */
    static Point pickWithin(Point center, List<Point> taken, Random rng, BiFunction<Integer, Integer, Point> ground,
                            int maxDy) {
        Point fallback = null;
        for (int i = 0; i < TRIES; i++) {
            int side = rng.nextBoolean() ? 1 : -1;
            int dx = side * (70 + 12 * i + rng.nextInt(60)); // a short walk: 70-290 px
            Point g = ground.apply(center.x + dx, center.y - 40);
            if (g == null || Math.abs(g.y - center.y) > maxDy) {
                continue;
            }
            if (fallback == null) {
                fallback = g;
            }
            if (clear(g, taken)) {
                return g;
            }
        }
        return fallback;
    }

    /** How far a "level" spot may sit above or below its centre: BotCouples' side-by-side height (a step, not a ledge). */
    static final int LEVEL_DY = 40;

    /**
     * Where the second of a pair goes ({@code !qa cp-1}): near the first bot and at its height when any such ground is
     * in reach, else as {@link #pick}. TEST t-cu-1 (2026-10-25 and 10-26, GM at 104040001 (395, -1045)): the second
     * landed on the ledge 60 px below the first, could not climb to the spot beside it and "walked over" all run, so
     * the couple never sat. Null-safe like {@link #pick}.
     */
    static Point pickLevel(Point center, List<Point> taken, Random rng, BiFunction<Integer, Integer, Point> ground) {
        Point p = pickWithin(center, taken, rng, ground, LEVEL_DY);
        if (p != null) {
            return p;
        }
        int side = rng.nextBoolean() ? 1 : -1; // the random tries missed it: sweep both sides out from the centre
        for (int d = 60; d <= 290; d += 10) {
            for (int s : new int[]{side, -side}) {
                Point g = ground.apply(center.x + s * d, center.y - 40);
                if (g != null && Math.abs(g.y - center.y) <= LEVEL_DY && clear(g, taken)) {
                    return g;
                }
            }
        }
        return pick(center, taken, rng, ground);
    }

    /** How far out a staged chat partner may stand: inside the 150 px a conversation hold counts as "with you". */
    public static final int CLOSE_PX = 120;

    /**
     * 162 runfix (t-cv-8 on TEST 10-30): a staged social bot is someone to talk to, so it stands within
     * {@code maxPx} of the centre (and within {@link #LEVEL_DY} of its height): the first clear grounded spot sweeping
     * out from 60 px on a random side, else the first grounded one even if crowded. Null when no ground is that close.
     * {@link #pick} grows its tries to 290 px; in a busy Henesys the first clear one was 151 px out, past the hold
     * radius, so cv-9 logged a bot that never moved as "WANDERED OFF".
     */
    static Point pickClose(Point center, List<Point> taken, Random rng, BiFunction<Integer, Integer, Point> ground,
                           int maxPx) {
        Point fallback = null;
        int side = rng.nextBoolean() ? 1 : -1;
        for (int d = 60; d <= maxPx; d += 10) {
            for (int s : new int[]{side, -side}) {
                Point g = ground.apply(center.x + s * d, center.y - 40);
                if (g == null || Math.abs(g.y - center.y) > LEVEL_DY || g.distance(center) > maxPx) {
                    continue;
                }
                if (clear(g, taken)) {
                    return g;
                }
                if (fallback == null) {
                    fallback = g;
                }
            }
        }
        return fallback;
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

    /** A spot on {@code map} near {@code center} and at its height when possible ({@link #pickLevel}). */
    public static Point nearLevel(MapleMap map, Point center) {
        if (map == null || center == null) {
            return near(map, center);
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
        return pickLevel(center, taken, ThreadLocalRandom.current(), (x, y) -> groundBelow(map, x, y));
    }

    /** A spot within {@link #CLOSE_PX} of the GM when there is ground that close ({@link #pickClose}), else {@link #nearGm}. */
    public static Point closeToGm(Character gm) {
        MapleMap map = gm.getMap();
        Point center = gm.getPosition();
        if (map == null || center == null) {
            return nearGm(gm);
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
        Point p = pickClose(center, taken, ThreadLocalRandom.current(), (x, y) -> groundBelow(map, x, y), CLOSE_PX);
        return p != null ? p : nearGm(gm);
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
