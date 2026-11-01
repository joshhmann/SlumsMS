package soloMapling.ArtificialPlayer.BotPartySystem;

import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * 1.6.3 s-lanes: where each companion of a leader stands when it is told to "spread lanes" - its own spot on the
 * map's ground instead of all of them stacking on the sentry spot next to the player ({@link CompanionStance}).
 *
 * <p>Spots come from the map's own mob spawn points, snapped down to the foothold under them by
 * {@link GCMovement#groundPointBelow} - the same reading {@code SpotFinder} makes when it picks grind spots, so no
 * new pathing. They are then handed out in columns - one column per companion, left to right, evenly spaced across
 * the ordered window (a screen around the player, or the whole map, {@link Reach}) - and each column takes the
 * candidate nearest its middle that is on a platform no other lane uses yet (when the nav graph says so) and is at
 * least {@link #MIN_SPACING_PX} from the lanes already handed out. When the room is short the spacing gives way in
 * two steps; when there is no room left at all the companions left over get no lane and keep following.
 *
 * <p>Deterministic: the same map, player position, reach and roster give the same lanes - left to right, then by
 * height, in party order - so re-issuing the order does not reshuffle anyone.
 */
public final class CompanionLanes {

    private CompanionLanes() {
    }

    /** How far the lanes go: a screen around the player when ordered, or the whole map. */
    public enum Reach {
        /** {@link #HALF_WINDOW_PX} px either side of the player. */
        SCREEN,
        /** Every spawn point on the map. */
        MAP;

        /** "map" / "screen" (a whole order's argument), or null when the word is neither. */
        public static Reach parse(String word) {
            if (word == null) {
                return null;
            }
            return switch (word.trim().toLowerCase(Locale.ROOT)) {
                case "map", "whole", "wholemap", "everywhere" -> MAP;
                case "screen", "local", "near", "nearby", "around" -> SCREEN;
                default -> null;
            };
        }

        /**
         * The switch's default: {@code -Dslums.alts.lanes.reach=screen|map}, screen when unset or not a reach. An
         * order's own word ("lanes map") wins over this.
         */
        public static Reach configured() {
            try {
                Reach r = parse(System.getProperty("slums.alts.lanes.reach"));
                return r == null ? SCREEN : r;
            } catch (RuntimeException | LinkageError e) {
                return SCREEN;
            }
        }
    }

    /** Half the window a SCREEN reach spreads the lanes over. */
    public static final int HALF_WINDOW_PX = 800;
    /** Two lanes on one platform are at least this far apart when the map has the room (2 x a sentry's reach). */
    public static final int MIN_SPACING_PX = 2 * CompanionStance.SENTRY_REACH_PX;

    /** The lanes for {@code count} companions around {@code owner}, read from the map's mob spawn points. */
    public static List<Point> spots(MapleMap map, Point owner, Reach reach, int count) {
        if (map == null || count <= 0) {
            return List.of();
        }
        List<Point> spawns = map.getMonsterSpawnPositions();
        if (spawns == null || spawns.isEmpty()) {
            return List.of();
        }
        List<Point> ground = new ArrayList<>(spawns.size());
        for (Point p : spawns) {
            if (p == null) {
                continue;
            }
            Point g = GCMovement.groundPointBelow(map, p.x, p.y);
            ground.add(g != null ? g : new Point(p));
        }
        if (ground.isEmpty()) {
            return List.of();
        }
        // A different ledge is a different platform; the nav graph is only peeked, never built here.
        return assign(ground, owner, reach, count,
                (a, b) -> GCMovement.onDifferentLedge(map, a.x, a.y, b.x, b.y));
    }

    /**
     * The lanes themselves: up to {@code count} candidate ground points, left to right. {@code owner} is where the
     * player stands when the order is given (null: use the candidates' own extent), {@code differentPlatform} says
     * whether two points rest on two platforms (so stacked spots are allowed to be close in x).
     */
    public static List<Point> assign(List<Point> candidates, Point owner, Reach reach, int count,
                                     BiPredicate<Point, Point> differentPlatform) {
        if (count <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Point> all = new ArrayList<>(candidates.size());
        for (Point p : candidates) {
            if (p != null && !all.contains(p)) {
                all.add(new Point(p));
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        // left to right, then by height: the party's first companion always takes the leftmost lane
        all.sort(Comparator.<Point>comparingInt(p -> p.x).thenComparingInt(p -> p.y));
        int x0;
        int x1;
        if (reach == Reach.MAP || owner == null) {
            x0 = all.get(0).x;
            x1 = all.get(all.size() - 1).x;
        } else {
            x0 = owner.x - HALF_WINDOW_PX;
            x1 = owner.x + HALF_WINDOW_PX;
        }
        List<Point> window = new ArrayList<>(all.size());
        for (Point p : all) {
            if (p.x >= x0 && p.x <= x1) {
                window.add(p);
            }
        }
        if (window.isEmpty()) {
            return List.of(); // nothing worth holding inside the window: they keep following
        }
        BiPredicate<Point, Point> apart = differentPlatform == null ? (a, b) -> false : differentPlatform;
        int lanes = Math.min(count, window.size());
        int ownerY = owner == null ? window.get(0).y : owner.y;
        // One spacing for the whole plan, as tight as the map allows: four hundred px when there is room, then two
        // hundred, then near-touching. A plan that seats more companions always wins, so a narrow map loses the extra
        // distance before it loses a lane.
        List<Point> best = List.of();
        for (int spacing : new int[] {MIN_SPACING_PX, CompanionStance.SENTRY_REACH_PX, CompanionStance.THERE_PX}) {
            List<Point> plan = plan(window, lanes, x0, x1, ownerY, apart, spacing);
            if (plan.size() > best.size()) {
                best = plan;
            }
            if (best.size() == lanes) {
                break; // everybody has a lane
            }
        }
        return best; // fewer lanes than companions: the rest keep following
    }

    /** {@code lanes} spots, each as near its own column's middle as the ground and {@code spacing} allow. */
    private static List<Point> plan(List<Point> window, int lanes, int x0, int x1, int ownerY,
                                    BiPredicate<Point, Point> differentPlatform, int spacing) {
        boolean[] used = new boolean[window.size()];
        List<Point> chosen = new ArrayList<>(lanes);
        for (int i = 0; i < lanes; i++) {
            int idealX = x0 + (int) Math.round((double) (x1 - x0) * (i + 0.5) / lanes);
            int idx = pick(window, used, chosen, idealX, ownerY, differentPlatform, spacing);
            if (idx < 0) {
                break;
            }
            used[idx] = true;
            chosen.add(window.get(idx));
        }
        chosen.sort(Comparator.<Point>comparingInt(p -> p.x).thenComparingInt(p -> p.y));
        return chosen;
    }

    /**
     * The unused candidate nearest {@code idealX} that keeps {@code spacing} from the lanes already handed out, on a
     * platform no lane uses yet when one is there. -1 when there is none.
     */
    private static int pick(List<Point> window, boolean[] used, List<Point> chosen, int idealX, int ownerY,
                           BiPredicate<Point, Point> differentPlatform, int spacing) {
        for (boolean freshPlatformOnly : new boolean[] {true, false}) {
            int best = -1;
            long bestScore = Long.MAX_VALUE;
            for (int i = 0; i < window.size(); i++) {
                if (used[i]) {
                    continue;
                }
                Point p = window.get(i);
                boolean near = true; // on a platform no lane sits on yet
                boolean fits = true;
                for (Point q : chosen) {
                    if (differentPlatform.test(p, q)) {
                        continue; // stacked: not in the way of this lane
                    }
                    near = false;
                    if (Math.abs(p.x - q.x) <= spacing && Math.abs(p.y - q.y) <= spacing) {
                        fits = false;
                        break;
                    }
                }
                if (!fits || freshPlatformOnly && !near) {
                    continue;
                }
                long score = Math.abs(p.x - idealX) * 1000L + Math.abs(p.y - ownerY);
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }
}
