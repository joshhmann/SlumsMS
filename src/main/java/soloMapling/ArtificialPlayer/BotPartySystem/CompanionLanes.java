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
 * <p>{@link Reach#AUTO} is the rule a player gets when the order names no reach (owner, 2026-11-01): everybody takes
 * a lane within a screen of the player first, and only with two or more companions the ones a screen cannot seat
 * (in party order) go to the whole map - one companion alone never goes wider than the screen.
 *
 * <p>Deterministic: the same map, player position, reach and roster give the same lanes - left to right, then by
 * height, in party order - so re-issuing the order does not reshuffle anyone.
 */
public final class CompanionLanes {

    private CompanionLanes() {
    }

    /** How far the lanes go: the automatic rule, a screen around the player, or the whole map. */
    public enum Reach {
        /**
         * A screen first for everybody; only with two or more companions do the ones the screen cannot seat go over
         * the whole map. Never a lane's own reach: {@link #spreads} resolves it to {@link #SCREEN} or {@link #MAP}.
         */
        AUTO,
        /** {@link #HALF_WINDOW_PX} px either side of the player. */
        SCREEN,
        /** Every spawn point on the map. */
        MAP;

        /** "map" / "screen" / "auto" (a whole order's argument or the switch), or null when the word is none. */
        public static Reach parse(String word) {
            if (word == null) {
                return null;
            }
            return switch (word.trim().toLowerCase(Locale.ROOT)) {
                case "map", "whole", "wholemap", "everywhere" -> MAP;
                case "screen", "local", "near", "nearby", "around" -> SCREEN;
                case "auto", "automatic" -> AUTO;
                default -> null;
            };
        }

        /**
         * The switch's default: {@code -Dslums.alts.lanes.reach=auto|screen|map}, the automatic rule when unset or
         * not a reach. An order's own word ("lanes map") wins over this.
         */
        public static Reach configured() {
            try {
                Reach r = parse(System.getProperty("slums.alts.lanes.reach"));
                return r == null ? AUTO : r;
            } catch (RuntimeException | LinkageError e) {
                return AUTO;
            }
        }
    }

    /** Half the window a SCREEN reach spreads the lanes over. */
    public static final int HALF_WINDOW_PX = 800;
    /** Two lanes on one platform are at least this far apart when the map has the room (2 x a sentry's reach). */
    public static final int MIN_SPACING_PX = 2 * CompanionStance.SENTRY_REACH_PX;

    /**
     * A companion's lane: the spot it holds and how far that lane reaches. Always {@link Reach#SCREEN} or
     * {@link Reach#MAP} - the {@link Reach#AUTO} rule is resolved into one or the other per companion, which is what
     * {@link CompanionStance#lanesWholeMap} reads.
     */
    public record Lane(Point spot, Reach reach) {
        public Lane {
            spot = new Point(spot);
            if (reach != Reach.SCREEN && reach != Reach.MAP) {
                throw new IllegalArgumentException("a lane reaches a screen or the whole map, not " + reach);
            }
        }
    }

    /** The lanes for {@code count} companions around {@code owner}, read from the map's mob spawn points. */
    public static List<Lane> lanes(MapleMap map, Point owner, Reach reach, int count) {
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
        // A different ledge is a different platform; the nav graph is only peeked, never built here.
        return spreads(ground, owner, reach, count,
                (a, b) -> GCMovement.onDifferentLedge(map, a.x, a.y, b.x, b.y));
    }

    /** {@link #spreads} without the reaches: the spots alone, left to right in party order. */
    public static List<Point> assign(List<Point> candidates, Point owner, Reach reach, int count,
                                     BiPredicate<Point, Point> differentPlatform) {
        List<Point> spots = new ArrayList<>();
        for (Lane lane : spreads(candidates, owner, reach, count, differentPlatform)) {
            spots.add(lane.spot());
        }
        return spots;
    }

    /**
     * The lanes themselves: up to {@code count} ground points, left to right, each with the reach it is held at.
     * {@code owner} is where the player stands when the order is given (null: use the candidates' own extent),
     * {@code differentPlatform} says whether two points rest on two platforms (so stacked spots may be close in x).
     *
     * <p>{@link Reach#AUTO}: the screen's lanes first, then - only with two or more companions, and only for the ones
     * the screen could not seat - the extras from the ground the screen lanes left, over the whole map.
     */
    public static List<Lane> spreads(List<Point> candidates, Point owner, Reach reach, int count,
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
        if (reach != Reach.AUTO) {
            List<Point> spots = spread(all, owner, reach, count, differentPlatform, new boolean[all.size()]);
            return lanes(spots, reach);
        }
        // the rule: a screen for everybody, and the whole map only for the extras of a party of two or more
        boolean[] taken = new boolean[all.size()];
        List<Point> screen = spread(all, owner, Reach.SCREEN, count, differentPlatform, taken);
        List<Lane> out = lanes(screen, Reach.SCREEN);
        if (count < 2 || screen.size() >= count) {
            return out;
        }
        out.addAll(lanes(spread(all, owner, Reach.MAP, count - screen.size(), differentPlatform, taken), Reach.MAP));
        return out;
    }

    private static List<Lane> lanes(List<Point> spots, Reach reach) {
        List<Lane> out = new ArrayList<>(spots.size());
        for (Point p : spots) {
            out.add(new Lane(p, reach));
        }
        return out;
    }

    /**
     * The lanes {@code reach} seats for {@code count} companions out of the ground nobody holds yet
     * ({@code taken[i]} true: the point is already a lane). Marks its own picks in {@code taken}.
     */
    private static List<Point> spread(List<Point> all, Point owner, Reach reach, int count,
                                      BiPredicate<Point, Point> differentPlatform, boolean[] taken) {
        boolean wholeMap = owner == null || reach == Reach.MAP;
        int left = wholeMap ? Integer.MIN_VALUE : owner.x - HALF_WINDOW_PX;
        int right = wholeMap ? Integer.MAX_VALUE : owner.x + HALF_WINDOW_PX;
        List<Integer> window = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            Point p = all.get(i);
            if (taken[i] || p.x < left || p.x > right) {
                continue;
            }
            window.add(i);
        }
        if (window.isEmpty()) {
            return List.of(); // nothing worth holding inside the window: they keep following
        }
        int x0;
        int x1;
        if (reach == Reach.MAP) {
            // the ground these lanes may use: the whole map, or what the screen lanes did not take (AUTO extras)
            x0 = all.get(window.get(0)).x;
            x1 = all.get(window.get(window.size() - 1)).x;
        } else {
            x0 = owner.x - HALF_WINDOW_PX;
            x1 = owner.x + HALF_WINDOW_PX;
        }
        BiPredicate<Point, Point> apart = differentPlatform == null ? (a, b) -> false : differentPlatform;
        int lanes = Math.min(count, window.size());
        int ownerY = owner == null ? all.get(window.get(0)).y : owner.y;
        // One spacing for the whole plan, as tight as the map allows: four hundred px when there is room, then two
        // hundred, then near-touching. A plan that seats more companions always wins, so a narrow map loses the extra
        // distance before it loses a lane.
        List<Point> best = List.of();
        for (int spacing : new int[] {MIN_SPACING_PX, CompanionStance.SENTRY_REACH_PX, CompanionStance.THERE_PX}) {
            List<Point> plan = plan(all, window, taken, lanes, x0, x1, ownerY, apart, spacing);
            if (plan.size() > best.size()) {
                best = plan;
            }
            if (best.size() == lanes) {
                break; // everybody has a lane
            }
        }
        for (Point p : best) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).equals(p)) {
                    taken[i] = true;
                    break;
                }
            }
        }
        return best; // fewer lanes than companions: the rest follow (or, in AUTO, go over the whole map)
    }

    /** {@code lanes} spots, each as near its own column's middle as the ground and {@code spacing} allow. */
    private static List<Point> plan(List<Point> all, List<Integer> window, boolean[] taken, int lanes,
                                    int x0, int x1, int ownerY,
                                    BiPredicate<Point, Point> differentPlatform, int spacing) {
        boolean[] used = taken.clone();
        List<Point> chosen = new ArrayList<>(lanes);
        for (int i = 0; i < lanes; i++) {
            int idealX = x0 + (int) Math.round((double) (x1 - x0) * (i + 0.5) / lanes);
            int idx = pick(all, window, used, chosen, idealX, ownerY, differentPlatform, spacing);
            if (idx < 0) {
                break;
            }
            used[idx] = true;
            chosen.add(all.get(idx));
        }
        chosen.sort(Comparator.<Point>comparingInt(p -> p.x).thenComparingInt(p -> p.y));
        return chosen;
    }

    /**
     * The unused candidate nearest {@code idealX} that keeps {@code spacing} from the lanes already handed out, on a
     * platform no lane uses yet when one is there. -1 when there is none.
     */
    private static int pick(List<Point> all, List<Integer> window, boolean[] used, List<Point> chosen, int idealX,
                            int ownerY, BiPredicate<Point, Point> differentPlatform, int spacing) {
        for (boolean freshPlatformOnly : new boolean[] {true, false}) {
            int best = -1;
            long bestScore = Long.MAX_VALUE;
            for (int slot : window) {
                if (used[slot]) {
                    continue;
                }
                Point p = all.get(slot);
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
                    best = slot;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }
}
