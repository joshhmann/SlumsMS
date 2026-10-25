package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rate limit for the "J&gt; OPQ" lines the OPQ lobby bots speak in 200080101.
 * <p>
 * Before this class each lobby bot spoke every 12 s and nothing looked at the lobby as a whole: 10-15 bots meant roughly 50-75 lines a minute in one map, which buried a player's
 * own chat and was the "PQ lobby spam" in HANDOFF. The cap is per lobby (channel + map), not per bot, so adding bots
 * never adds spam:
 * <ul>
 *   <li>at most {@code -Dsolomapling.opq.lobbyRecruitPerMinute} lines per minute for the whole lobby (default
 *   {@value #DEFAULT_MAX_LINES_PER_MINUTE}; 0 or less turns recruiting chat off),</li>
 *   <li>each bot still waits a random {@value #DEFAULT_BOT_INTERVAL_MIN_MS}-{@value #DEFAULT_BOT_INTERVAL_MAX_MS} ms
 *   between its own lines, so one bot cannot eat the lobby's whole budget.</li>
 * </ul>
 */
public final class OPQLobbyChat {

    private OPQLobbyChat() {
    }

    public static final int DEFAULT_MAX_LINES_PER_MINUTE = 3;
    public static final long DEFAULT_BOT_INTERVAL_MIN_MS = 45_000;
    public static final long DEFAULT_BOT_INTERVAL_MAX_MS = 90_000;
    static final long WINDOW_MS = 60_000;

    private static final Map<Long, Deque<Long>> LINES = new ConcurrentHashMap<>();

    public static int maxLinesPerMinute() {
        return Integer.getInteger("solomapling.opq.lobbyRecruitPerMinute", DEFAULT_MAX_LINES_PER_MINUTE);
    }

    /**
     * Reserve one recruit line for this lobby right now.
     *
     * @return true when the caller may speak; false when the lobby's budget for the last minute is spent
     */
    public static boolean allowRecruit(long nowMs, int channel, int mapId) {
        int cap = maxLinesPerMinute();
        if (cap <= 0) {
            return false;
        }
        Deque<Long> lines = LINES.computeIfAbsent(key(channel, mapId), k -> new ArrayDeque<>());
        synchronized (lines) {
            while (!lines.isEmpty() && nowMs - lines.peekFirst() >= WINDOW_MS) {
                lines.pollFirst();
            }
            if (lines.size() >= cap) {
                return false;
            }
            lines.addLast(nowMs);
            return true;
        }
    }

    /**
     * How long this bot should wait after its next line. Random on purpose: identical timers make every lobby bot
     * speak in the same second.
     */
    public static long nextIntervalMs() {
        return ThreadLocalRandom.current().nextLong(DEFAULT_BOT_INTERVAL_MIN_MS, DEFAULT_BOT_INTERVAL_MAX_MS + 1);
    }

    public static void resetForTest() {
        LINES.clear();
    }

    private static long key(int channel, int mapId) {
        return ((long) channel << 32) | (mapId & 0xFFFFFFFFL);
    }
}
