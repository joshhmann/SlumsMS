package soloMapling.Environment;

import java.awt.Point;
import java.util.List;

import server.maps.FootholdTree;
import soloMapling.ArtificialPlayer.BotMovementSystem.BotGround;

/**
 * A stretch of ground bots may be placed on. The extent and a first guess at the height come from recorded movement
 * data, or from a hand-drawn band; the height at an x comes from the map's footholds.
 *
 * <p>1.5.1: a FLAT stretch used to put every bot at one Y. Most stretches classified flat are not flat - a recording
 * whose Y varies by less than {@code PlatformParser.SLOPE_THRESHOLD} still climbs, and a hand-drawn band can run
 * across a slope or across two levels - so bots stood in the air or inside the ground (the health sweep's FLOATING
 * and SUNK). The recorded height is now only the guess {@link #getYAtX} settles onto the real ground.
 *
 * <p>FLAT: any x inside the bounds may be used. SLOPED: only the recorded x values may be used.
 */
public class Platform {

    /**
     * Platform type determining how coordinates are handled.
     */
    public enum Type {
        /** Flat platform - X can be any value within bounds */
        FLAT,
        /** Sloped platform - spawns must use the recorded reference points */
        SLOPED
    }

    private final int minX;
    private final int maxX;
    private final List<Point> sortedPoints;
    private final Type type;
    /** The map's footholds; null when the caller had no map (the recorded height is then all there is). */
    private final FootholdTree ground;

    public Platform(int minX, int maxX, List<Point> sortedPoints, Type type, FootholdTree ground) {
        this.minX = minX;
        this.maxX = maxX;
        this.sortedPoints = sortedPoints;
        this.type = type;
        this.ground = ground;
    }

    public int getMinX() {
        return minX;
    }

    public int getMaxX() {
        return maxX;
    }

    /**
     * Returns the horizontal width of the platform.
     */
    public int getWidth() {
        return maxX - minX;
    }

    /**
     * Returns the sorted list of recorded points (sorted by X coordinate).
     * For SLOPED platforms, these are the valid spawn positions.
     * For FLAT platforms, these are reference points only.
     */
    public List<Point> getSortedPoints() {
        return sortedPoints;
    }

    public Type getType() {
        return type;
    }

    public boolean isFlat() {
        return type == Type.FLAT;
    }

    public boolean isSloped() {
        return type == Type.SLOPED;
    }

    /**
     * The ground height at {@code x}: the map's foothold under x nearest the recorded height, or the recorded height
     * itself when there is no map or no foothold under x. Clamps x to the platform's bounds.
     */
    public int getYAtX(int x) {
        int recorded = getRecordedYAtX(x);
        if (ground == null) {
            return recorded;
        }
        Point onGround = BotGround.settle(ground, new Point(x, recorded));
        return onGround == null ? recorded : onGround.y;
    }

    /**
     * The height the recording (or the drawn band) suggests at {@code x}, interpolated between the two nearest
     * recorded points. Unclamped callers should clamp first; this clamps x to the platform's bounds.
     */
    public int getRecordedYAtX(int x) {
        if (sortedPoints.isEmpty()) {
            return 0;
        }

        // Clamp x to bounds
        x = Math.max(minX, Math.min(maxX, x));

        // Find the two points surrounding x
        Point left = null;
        Point right = null;

        for (Point p : sortedPoints) {
            if (p.x <= x) {
                left = p;
            }
            if (p.x >= x && right == null) {
                right = p;
            }
        }

        // Edge cases
        if (left == null) return sortedPoints.get(0).y;
        if (right == null) return sortedPoints.get(sortedPoints.size() - 1).y;
        if (left.x == right.x) return left.y;

        // Linear interpolation
        double ratio = (double) (x - left.x) / (right.x - left.x);
        return (int) (left.y + ratio * (right.y - left.y));
    }

    @Override
    public String toString() {
        return String.format("Platform[type: %s, x: %d to %d, width: %d, points: %d, ground: %s]",
                type, minX, maxX, getWidth(), sortedPoints.size(), ground == null ? "none" : "map");
    }
}
