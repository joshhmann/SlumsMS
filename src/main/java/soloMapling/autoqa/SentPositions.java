package soloMapling.autoqa;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * rc6 inference checks: where the server last TOLD clients a character stands. The server-side foothold check looks at
 * the server's own copy of a position; a viewer's client draws whatever the last spawn ({@code SPAWN_PLAYER}) or move
 * ({@code MOVE_PLAYER}) packet said. This keeps, per character, the last position, stance and foothold those packets
 * carried, so the health sweep can measure grounding as the client sees it ({@code foothold (sent)}).
 * <p>
 * Off unless auto-QA is on ({@link AutoQa#boot}): the packet builders call {@link #on()} (one volatile read) first.
 * Spawn and move packets are only built for maps a real or synthetic player is on (bots' clients get none), so the
 * table only ever holds characters someone could see. A character's entry is dropped when it enters a map
 * ({@link #entered}), so a move recorded on the old map never counts for the new one.
 */
public final class SentPositions {

    /** One sent position. {@code fh} is the move fragment's foothold (0 = in the air), -1 when the packet has none. */
    public record Sent(int mapId, int x, int y, int stance, int fh, long atMs, String how) {
    }

    /** What a movement list ends on: the last absolute position, its foothold and the last stance of any fragment. */
    public record Decoded(int x, int y, int stance, int fh) {
    }

    private static volatile boolean on;
    private static final Map<Integer, Sent> last = new ConcurrentHashMap<>();
    private static final int MAX = 20_000;

    private SentPositions() {
    }

    public static boolean on() {
        return on;
    }

    static void enable(boolean enabled) {
        on = enabled;
        if (!enabled) {
            last.clear();
        }
    }

    /** A spawn packet for {@code chrId} on {@code mapId} was built with this position and stance. */
    public static void spawned(int chrId, int mapId, int x, int y, int stance) {
        put(chrId, new Sent(mapId, x, y, stance, -1, System.currentTimeMillis(), "spawn"));
    }

    /**
     * A move packet was built: {@code packet} is the whole MOVE_PLAYER packet (opcode, character id, an int, then the
     * movement list). The map is the one of the character's last spawn record; with none the move is not kept.
     */
    public static void moved(int chrId, byte[] packet) {
        Sent prev = last.get(chrId);
        if (prev == null || packet == null || packet.length < 11) {
            return;
        }
        Decoded d = decode(packet, 10);
        if (d == null) {
            return;
        }
        put(chrId, new Sent(prev.mapId(), d.x() == Integer.MIN_VALUE ? prev.x() : d.x(),
                d.y() == Integer.MIN_VALUE ? prev.y() : d.y(), d.stance(), d.x() == Integer.MIN_VALUE ? prev.fh() : d.fh(),
                System.currentTimeMillis(), "move"));
    }

    /** The character is entering a map: whatever was sent for its old map no longer says where it stands. */
    public static void entered(int chrId) {
        last.remove(chrId);
    }

    public static Sent get(int chrId) {
        return last.get(chrId);
    }

    static void clearForTest() {
        last.clear();
    }

    private static void put(int chrId, Sent s) {
        if (last.size() >= MAX && !last.containsKey(chrId)) {
            last.clear(); // a runaway table on a long run: start over rather than grow
        }
        last.put(chrId, s);
    }

    /**
     * Reads a v83 movement list ({@code [count][fragments...]}, AbstractMovementPacketHandler.parseMovement's layout)
     * from {@code offset}: the last absolute or teleport position and its foothold, and the stance of the last
     * fragment. Null when the bytes do not parse. x/y are {@link Integer#MIN_VALUE} when no fragment carried a position.
     */
    public static Decoded decode(byte[] b, int offset) {
        try {
            int i = offset;
            int n = b[i++];
            if (n < 1) {
                return null;
            }
            int x = Integer.MIN_VALUE, y = Integer.MIN_VALUE, fh = -1, stance = -1;
            for (int k = 0; k < n; k++) {
                int cmd = b[i++];
                switch (cmd) {
                    case 0, 5, 17 -> {
                        x = s16(b, i);
                        y = s16(b, i + 2);
                        fh = s16(b, i + 8);
                        stance = b[i + 10] & 0xFF;
                        i += 13;
                    }
                    case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> {
                        stance = b[i + 4] & 0xFF;
                        i += 7;
                    }
                    case 3, 4, 7, 8, 9, 11 -> {
                        x = s16(b, i);
                        y = s16(b, i + 2);
                        fh = -1;
                        stance = b[i + 8] & 0xFF;
                        i += 9;
                    }
                    case 14 -> i += 9;
                    case 10 -> i += 1;
                    case 15 -> {
                        x = s16(b, i);
                        y = s16(b, i + 2);
                        fh = s16(b, i + 8);
                        stance = b[i + 12] & 0xFF;
                        i += 15;
                    }
                    case 21 -> i += 3;
                    default -> {
                        return null;
                    }
                }
            }
            return stance < 0 && x == Integer.MIN_VALUE ? null : new Decoded(x, y, stance, fh);
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static int s16(byte[] b, int i) {
        return (short) ((b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8);
    }

    /**
     * The pose for the foothold rule from what was sent: SWIMMING on a swim map; COARSE when the movement driver says
     * the bot is mid analytic move (the client then interpolates); else the stance's pose, and AIRBORNE for a
     * "standing" stance sent with foothold 0 (the v83 client's "no foothold").
     */
    public static FootholdCheck.Pose pose(int stance, int fh, boolean swimMap, String driverPose) {
        if (swimMap) {
            return FootholdCheck.Pose.SWIMMING;
        }
        if ("COARSE".equals(driverPose)) {
            return FootholdCheck.Pose.COARSE;
        }
        if (stance < 0) {
            return FootholdCheck.Pose.UNKNOWN;
        }
        FootholdCheck.Pose p = GameHealthSource.poseFromStance(stance, false);
        return p == FootholdCheck.Pose.GROUNDED && fh == 0 ? FootholdCheck.Pose.AIRBORNE : p;
    }
}
