package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-QA health sweeps (b) to (f): the pure parts, tested in {@code SweepMathTest}. {@link HealthSweep} feeds them
 * samples taken from the game.
 */
public final class SweepMath {

    private SweepMath() {
    }

    /* ---------------------------------------------------------------- (b) stuck bots */

    /**
     * Remembers where each bot was and since when, between sweeps. A bot that claims to be moving (walking to a
     * target, grinding, travelling) but has not left a small box for longer than the limit is stuck. Idle or seated
     * bots are not stuck, they are resting; their clock restarts when they start moving again.
     */
    public static final class StuckTracker {
        /** A bot counts as still while it stays within this many px of where it was first seen still. */
        public static final int BOX_PX = 8;

        private record Seen(int mapId, int x, int y, long sinceMs) {
        }

        private final Map<Integer, Seen> seen = new HashMap<>();

        /**
         * Records a sample; returns how long (ms) the bot has been in the same spot while moving, or 0 when it moved
         * (or is not in a moving state).
         */
        public long observe(int botId, int mapId, int x, int y, boolean moving, long nowMs) {
            Seen s = seen.get(botId);
            if (!moving) {
                seen.remove(botId);
                return 0;
            }
            if (s == null || s.mapId != mapId || Math.abs(s.x - x) > BOX_PX || Math.abs(s.y - y) > BOX_PX) {
                seen.put(botId, new Seen(mapId, x, y, nowMs));
                return 0;
            }
            return nowMs - s.sinceMs;
        }

        /** Forgets bots not seen in this sweep (logged out, or not sampled). */
        public void retain(Set<Integer> present) {
            seen.keySet().retainAll(present);
        }

        public int size() {
            return seen.size();
        }
    }

    /* ---------------------------------------------------------------- (c) clumping */

    /** A clump: the bot at its centre and the bots within the radius (the centre included). */
    public record Clump(int centreId, int x, int y, List<Integer> members) {
    }

    /** One bot's spot for the clump check. */
    public record Spot(int id, int x, int y) {
    }

    /**
     * Clumps on one map: greedily takes the bot with the most neighbours within {@code radius}, reports it when that
     * count is at least {@code minBots}, removes those bots and repeats. O(n^2) per map; the sweep caps n.
     */
    public static List<Clump> clumps(List<Spot> spots, int radius, int minBots) {
        List<Clump> out = new ArrayList<>();
        List<Spot> left = new ArrayList<>(spots);
        long r2 = (long) radius * radius;
        while (left.size() >= minBots) {
            Spot bestCentre = null;
            List<Spot> best = List.of();
            for (Spot c : left) {
                List<Spot> near = new ArrayList<>();
                for (Spot o : left) {
                    long dx = o.x - c.x, dy = o.y - c.y;
                    if (dx * dx + dy * dy <= r2) {
                        near.add(o);
                    }
                }
                if (near.size() > best.size()) {
                    best = near;
                    bestCentre = c;
                }
            }
            if (bestCentre == null || best.size() < minBots) {
                break;
            }
            List<Integer> ids = new ArrayList<>();
            for (Spot s : best) {
                ids.add(s.id);
            }
            out.add(new Clump(bestCentre.id, bestCentre.x, bestCentre.y, ids));
            Set<Spot> taken = new HashSet<>(best);
            left.removeIf(taken::contains);
        }
        return out;
    }

    /* ---------------------------------------------------------------- (d) channel split */

    /** One channel's line in the split check. */
    public record ChannelShare(int channel, int bots, double share, double expected, boolean off) {
    }

    /**
     * Compares the bots per channel with the expected shares ({@code BotChannels.shares} of BOT_CHANNEL_WEIGHTS). A channel is off when its share differs from the
     * expected one by more than {@code tolerance} (0.15 = 15 percentage points), or when a channel that should have
     * no bots has some (or one that should have bots has none). Too few bots in total ({@code < minTotal}) to judge:
     * nothing is off.
     */
    public static List<ChannelShare> channelSplit(int[] botsPerChannel, double[] expected, double tolerance, int minTotal) {
        int total = 0;
        for (int b : botsPerChannel) {
            total += b;
        }
        List<ChannelShare> out = new ArrayList<>();
        for (int i = 0; i < botsPerChannel.length; i++) {
            double share = total == 0 ? 0 : (double) botsPerChannel[i] / total;
            double exp = i < expected.length ? expected[i] : 0;
            boolean off = false;
            if (total >= minTotal) {
                off = Math.abs(share - exp) > tolerance
                        || (exp == 0 && botsPerChannel[i] > 0)
                        || (exp >= 0.05 && botsPerChannel[i] == 0);
            }
            out.add(new ChannelShare(i + 1, botsPerChannel[i], share, exp, off));
        }
        return out;
    }

    /* ---------------------------------------------------------------- (f) error rate */

    /**
     * Error lines per sweep window: a spike is a window with at least {@code minErrors} errors and more than
     * {@code factor} times the median of the last windows (at least 1). Keeps the last {@code history} windows.
     */
    public static final class ErrorRate {
        private final int history;
        private final int minErrors;
        private final double factor;
        private final List<Integer> windows = new ArrayList<>();

        public ErrorRate(int history, int minErrors, double factor) {
            this.history = Math.max(1, history);
            this.minErrors = minErrors;
            this.factor = factor;
        }

        /** Adds a window's count; true when it is a spike against the windows before it. */
        public boolean add(int errors) {
            double base = Math.max(1, median());
            boolean spike = errors >= minErrors && errors > factor * base;
            windows.add(errors);
            while (windows.size() > history) {
                windows.remove(0);
            }
            return spike;
        }

        public double median() {
            if (windows.isEmpty()) {
                return 0;
            }
            List<Integer> s = new ArrayList<>(windows);
            s.sort(Integer::compare);
            int n = s.size();
            return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        }
    }

    /** True for a log line that counts as an error: log4j's ERROR level, or an uncaught exception's first line. */
    public static boolean isErrorLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        if (line.contains(" ERROR ") || line.startsWith("ERROR") || line.contains("[ERROR]")) {
            return true;
        }
        return line.startsWith("Exception in thread");
    }

    /** Takes at most {@code max} items, evenly spread over the list (the sweep samples big maps). */
    public static <T> List<T> sample(List<T> all, int max) {
        if (all.size() <= max || max <= 0) {
            return all;
        }
        List<T> out = new ArrayList<>(max);
        double step = (double) all.size() / max;
        for (int i = 0; i < max; i++) {
            out.add(all.get((int) (i * step)));
        }
        return out;
    }

    /** Drops map entries whose value list is empty (helper for the JSON). */
    static <K, V> void dropEmpty(Map<K, List<V>> m) {
        for (Iterator<Map.Entry<K, List<V>>> it = m.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isEmpty()) {
                it.remove();
            }
        }
    }
}
