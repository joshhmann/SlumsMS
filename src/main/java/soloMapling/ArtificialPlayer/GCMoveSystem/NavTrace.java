package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 157 (idea from nutnnut/Cosmic {@code BotPathLogger}, 77e0c5bc9; AGPL-3.0, credit NutNNut): the path trace on
 * stuck. Every bot on full simulation keeps its last {@link #TICKS} movement ticks - map, position, the edge it was
 * on, where it was steering, what the planner decided and its pose - in a small ring, and a stuck event
 * ({@link NavStuckLog}) takes a copy of the ring with it, so each hotspot carries the moves that led into it and not
 * only the stop. Nav learning and the stuck report read it back from the day file ({@link NavStuckLog#traced}).
 *
 * <p>Cost: off by default ({@code -Dslums.navTrace=true} turns it on; 1.6.2 ships it on for live, owner 2026-10-30,
 * docs/release/1.6.2.conf), and then one volatile read a tick and nothing else. On, a bot gets its ring the first time
 * it ticks with full physics on an observed map (FULL, HALO or the dwell
 * window, the same rule the driver uses); the ring is primitives and references written in place, so a tick
 * allocates nothing. A bot that drops to coarse (LOD) movement lets its ring go, since coarse moves have no nav ticks.
 * Copies are capped per hour ({@code -Dslums.navTrace.perHour}, default 20) across the whole server, so a runaway map
 * cannot fill the stuck log.
 *
 * <p>Threading: a ring is written only by its bot's movement tick (one thread at a time, see BotMovementState) and
 * copied from that same tick when the stuck event fires there; {@link #capture(Character)} (the grind brain's rope
 * check) reads from another thread and may see a tick half-written, which a debug trace can live with.
 */
public final class NavTrace {

    public static final String PROPERTY = "slums.navTrace";
    /** How many ticks a ring keeps (~6 s at the 50 ms full-physics tick). */
    static final int TICKS = 120;

    static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    static volatile int perHour = Math.max(0, Integer.getInteger(PROPERTY + ".perHour", 20));

    private static long hourStartMs;
    private static int usedThisHour;
    private static long droppedTotal;
    private static long capturedTotal;

    private NavTrace() {
    }

    /** One bot's last ticks, written in place (parallel arrays, no allocation per tick). */
    static final class Ring {
        final long[] at = new long[TICKS];
        final int[] map = new int[TICKS];
        final int[] x = new int[TICKS];
        final int[] y = new int[TICKS];
        final int[] tx = new int[TICKS];
        final int[] ty = new int[TICKS];
        final byte[] pose = new byte[TICKS];
        final byte[] dir = new byte[TICKS];
        final BotNavigationGraph.Edge[] edge = new BotNavigationGraph.Edge[TICKS];
        final String[] action = new String[TICKS];
        int next;
        int size;

        void add(long now, int mapId, Point pos, Point target, BotNavigationGraph.Edge e, String decision, int poseBits,
                 int moveDir) {
            int i = next;
            at[i] = now;
            map[i] = mapId;
            x[i] = pos.x;
            y[i] = pos.y;
            tx[i] = target != null ? target.x : Integer.MIN_VALUE;
            ty[i] = target != null ? target.y : Integer.MIN_VALUE;
            pose[i] = (byte) poseBits;
            dir[i] = (byte) moveDir;
            edge[i] = e;
            action[i] = decision;
            next = (i + 1) % TICKS;
            if (size < TICKS) {
                size++;
            }
        }

        /** Oldest first, one line a tick, times relative to the newest tick. */
        List<String> lines() {
            List<String> out = new ArrayList<>(size);
            if (size == 0) {
                return out;
            }
            int newest = (next - 1 + TICKS) % TICKS;
            long last = at[newest];
            int start = (next - size + TICKS) % TICKS;
            for (int k = 0; k < size; k++) {
                int i = (start + k) % TICKS;
                StringBuilder b = new StringBuilder(80);
                b.append(at[i] - last).append("ms m").append(map[i]).append(" (").append(x[i]).append(',').append(y[i])
                        .append(") ").append(action[i] == null ? "-" : action[i]);
                if (edge[i] != null) {
                    b.append(" [").append(NavStuckLog.describe(edge[i])).append(']');
                }
                if (tx[i] != Integer.MIN_VALUE) {
                    b.append(" ->(").append(tx[i]).append(',').append(ty[i]).append(')');
                }
                b.append(' ').append(poseName(pose[i])).append(dir[i] > 0 ? " R" : dir[i] < 0 ? " L" : "");
                out.add(b.toString());
            }
            return out;
        }
    }

    static final int AIR = 1;
    static final int ROPE = 2;
    static final int SWIM = 4;

    static String poseName(int bits) {
        if ((bits & ROPE) != 0) {
            return "rope";
        }
        if ((bits & SWIM) != 0) {
            return "swim";
        }
        return (bits & AIR) != 0 ? "air" : "ground";
    }

    /** Whether path traces are being kept (the switch). */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * After a movement tick. {@code fullSim}: the bot ran full physics on an observed map this tick. Off: one read.
     */
    static void tick(BotMovementState entry, boolean fullSim, Point target) {
        if (!enabled) {
            return;
        }
        if (!fullSim) {
            entry.navTrace = null; // coarse from here: nothing to trace, and the old ring would mislead
            return;
        }
        Character bot = entry.bot;
        Point pos = bot == null ? null : bot.getPosition();
        if (pos == null) {
            return;
        }
        Ring ring = entry.navTrace;
        if (ring == null) {
            ring = new Ring();
            entry.navTrace = ring;
        }
        int pose = (entry.inAir ? AIR : 0) | (entry.climbing ? ROPE : 0) | (entry.swimming ? SWIM : 0);
        ring.add(System.currentTimeMillis(), bot.getMapId(), pos, entry.navTargetPos != null ? entry.navTargetPos : target,
                entry.navEdge, entry.lastNavDecision, pose, entry.moveDir);
    }

    /** A copy of the bot's ring for a stuck event, or null (off, no ring yet, or this hour's copies used up). */
    static List<String> capture(BotMovementState entry) {
        if (!enabled || entry == null) {
            return null;
        }
        Ring ring = entry.navTrace;
        if (ring == null || ring.size == 0 || !take(System.currentTimeMillis())) {
            return null;
        }
        return ring.lines();
    }

    /** {@link #capture(BotMovementState)} for code that has only the character (the grind brain). */
    public static List<String> capture(Character bot) {
        if (!enabled || bot == null) {
            return null;
        }
        return capture(GCMovement.stateOf(bot));
    }

    /** One copy from this hour's budget. */
    static synchronized boolean take(long now) {
        if (now - hourStartMs >= 3_600_000L || now < hourStartMs) {
            hourStartMs = now;
            usedThisHour = 0;
        }
        if (usedThisHour >= perHour) {
            droppedTotal++;
            return false;
        }
        usedThisHour++;
        capturedTotal++;
        return true;
    }

    /** For {@code !gcmove stuck}: whether tracing is on and how many traces were kept. */
    public static synchronized String describe() {
        if (!enabled) {
            return "path trace: off (-D" + PROPERTY + "=true)";
        }
        return "path trace: on, " + usedThisHour + "/" + perHour + " this hour, " + capturedTotal + " kept, "
                + droppedTotal + " over the cap";
    }

    /** Test seam: the switch and the hourly cap, with a fresh budget. */
    static synchronized void useForTest(boolean on, int cap) {
        enabled = on;
        perHour = cap;
        hourStartMs = 0;
        usedThisHour = 0;
        droppedTotal = 0;
        capturedTotal = 0;
    }
}
