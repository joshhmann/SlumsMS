package soloMapling.autoqa;

import java.util.List;

/**
 * Auto-QA health sweep (a): does a bot that should be standing actually stand on a foothold?
 * <p>
 * Pure geometry, no game objects, so it is tested on its own ({@code FootholdCheckTest}). The server's own
 * {@code FootholdTree.findBelow} only looks for footholds at or <em>below</em> a point, so a bot that has sunk under
 * the ground finds the next platform further down (or none) and looks fine to it. This check instead takes every
 * non-wall foothold that spans the bot's x and measures the vertical distance to the nearest one, above or below:
 * a grounded bot is within {@link #TOLERANCE_PX} of its platform; a sunk bot is some pixels under the nearest one.
 */
public final class FootholdCheck {

    /** How far (px) a grounded bot may be from the foothold line before it is flagged. */
    public static final int TOLERANCE_PX = 2;

    /** One foothold line; a wall is vertical ({@code x1 == x2}) and never ground. */
    public record Seg(int x1, int y1, int x2, int y2) {
        boolean isWall() {
            return x1 == x2;
        }

        boolean spans(int x) {
            return Math.min(x1, x2) <= x && x <= Math.max(x1, x2);
        }

        /** The line's y at x, interpolated in doubles (Foothold.calculateFooting rounds the slope to an int). */
        double yAt(int x) {
            if (y1 == y2) {
                return y1;
            }
            return y1 + (double) (y2 - y1) * (x - x1) / (x2 - x1);
        }
    }

    /** What the bot is doing, as far as the movement code says. Only GROUNDED is checked. */
    public enum Pose {
        GROUNDED, AIRBORNE, CLIMBING, SWIMMING, UNKNOWN
    }

    /** The verdict for one bot. {@code dy} is bot y minus ground y: positive = under the ground (sunk). */
    public record Verdict(Kind kind, double groundY, double dy) {
        public boolean bad() {
            return kind == Kind.SUNK || kind == Kind.FLOATING || kind == Kind.NO_GROUND;
        }
    }

    public enum Kind {
        /** On the line, within the tolerance. */
        OK,
        /** Below the nearest foothold by more than the tolerance (the Henesys bug, 2026-10-22). */
        SUNK,
        /** Above the nearest foothold by more than the tolerance while it claims to stand. */
        FLOATING,
        /** No foothold spans its x at all. */
        NO_GROUND,
        /** Not checked: airborne, on a rope or ladder, swimming, or the pose is unknown. */
        SKIPPED
    }

    private FootholdCheck() {
    }

    /**
     * The y of the non-wall foothold spanning x that is nearest to y (above or below), or NaN when none spans x.
     */
    public static double nearestGroundY(List<Seg> footholds, int x, int y) {
        double best = Double.NaN;
        double bestDist = Double.MAX_VALUE;
        for (Seg s : footholds) {
            if (s.isWall() || !s.spans(x)) {
                continue;
            }
            double gy = s.yAt(x);
            double d = Math.abs(y - gy);
            if (d < bestDist) {
                bestDist = d;
                best = gy;
            }
        }
        return best;
    }

    /** Checks one bot at (x, y) in the given pose against the map's footholds. */
    public static Verdict check(List<Seg> footholds, int x, int y, Pose pose) {
        return check(footholds, x, y, pose, TOLERANCE_PX);
    }

    public static Verdict check(List<Seg> footholds, int x, int y, Pose pose, int tolerancePx) {
        if (pose != Pose.GROUNDED) {
            return new Verdict(Kind.SKIPPED, Double.NaN, Double.NaN);
        }
        double gy = nearestGroundY(footholds, x, y);
        if (Double.isNaN(gy)) {
            return new Verdict(Kind.NO_GROUND, Double.NaN, Double.NaN);
        }
        double dy = y - gy;
        if (Math.abs(dy) <= tolerancePx) {
            return new Verdict(Kind.OK, gy, dy);
        }
        return new Verdict(dy > 0 ? Kind.SUNK : Kind.FLOATING, gy, dy);
    }
}
