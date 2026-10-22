package soloMapling.ArtificialPlayer.BotConvo;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ticket 59: which bank lines a player saw and a bot said lately, so the bank does not repeat itself: the last
 * {@link #PER_PLAYER} lines per player and {@link #PER_BOT} per bot. Memory only, bounded to the most recent
 * {@link #MAX_KEYS} players and bots.
 */
public final class RecentLines {

    static final int PER_PLAYER = 200;
    static final int PER_BOT = 60;
    static final int MAX_KEYS = 5000;

    private static final Map<Long, ArrayDeque<Integer>> seen = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ArrayDeque<Integer>> eldest) {
            return size() > MAX_KEYS;
        }
    };

    /** The two windows one pick checks. */
    public record Window(int playerId, int botId) {
        public boolean contains(int lineId) {
            synchronized (seen) {
                return has(playerKey(playerId), lineId) || has(botKey(botId), lineId);
            }
        }
    }

    private RecentLines() {
    }

    static long playerKey(int id) {
        return id & 0xffffffffL;
    }

    static long botKey(int id) {
        return (1L << 40) | (id & 0xffffffffL);
    }

    private static boolean has(long key, int lineId) {
        ArrayDeque<Integer> q = seen.get(key);
        return q != null && q.contains(lineId);
    }

    /** A bank line went out from this bot to this player (playerId 0 for a thread nobody was addressed in). */
    public static void used(int playerId, int botId, int lineId) {
        synchronized (seen) {
            if (playerId > 0) {
                push(playerKey(playerId), lineId, PER_PLAYER);
            }
            push(botKey(botId), lineId, PER_BOT);
        }
    }

    private static void push(long key, int lineId, int cap) {
        ArrayDeque<Integer> q = seen.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.addLast(lineId);
        while (q.size() > cap) {
            q.removeFirst();
        }
    }

    static void resetForTest() {
        synchronized (seen) {
            seen.clear();
        }
    }
}
