package soloMapling.qa;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The QA suite's event log (test server only, {@link QaSuite}). Game code that a {@code !qa} scenario wants to watch
 * calls it like this:
 * <pre>
 *   if (QaLog.on(QaLog.Topic.ROPE)) {
 *       QaLog.map(QaLog.Topic.ROPE, map.getId(), bot.getName() + " stalled on a rope at " + pos);
 *   }
 * </pre>
 * {@link #on} is one volatile read and a bit test, false whenever no running scenario listens to that topic, so the
 * hooks cost nothing on live (where the suite is never registered) and nothing on the test server between runs. The
 * message string is only built inside the {@code if}.
 * <p>
 * A line goes to every running scenario that listens to its topic and matches its scope: {@link #map} lines only to
 * runs that watch that map (or every map), {@link #owner} lines only to the run of that GM, {@link #any} lines to all.
 * Each run writes its own file, {@code logs/qa/<id>-<UTC time>.log} ({@link QaRun}).
 */
public final class QaLog {

    /** What a log line is about. A scenario lists the topics it wants; nothing else reaches its file. */
    public enum Topic {
        ATTACK,         // bot attack packets: skill, pose, weapon, targets (bk-1)
        GUILD,          // guild creation steps (gd-1)
        FM_BUY,         // bot purchases from player shops and the seller cap (fm-1)
        ROPE,           // rope stalls and the rope stall watchdog (ld-1)
        HIDE,           // bots skipping a hidden GM, hide toggles (gh-1)
        MOB_CONTROL,    // which character controls each monster (gh-2)
        BOT_CHAT,       // every line a bot says on a watched map (gd-1, gh-1)
        MERCHANT,       // hired merchant closes, Fredrick retrievals (np-2, np-3)
        STORAGE,        // storage opened, items stored and taken out (np-1)
        EQUIP,          // equips put on, pet equips included (cs-2)
        SCROLL,         // scroll results (gn-2)
        CHANNEL_LOAD,   // channel load bars sent to the login screen (wd-1)
        IDLE,           // idle bots: hits taken while idle, moves to a safe spot (id-1)
        SLIDE;          // the GM's own attacks: how far the character moved during each (mv-1)

        final int bit = 1 << ordinal();
    }

    private static volatile int mask;
    private static final List<QaRun> runs = new CopyOnWriteArrayList<>();
    private static final Map<String, Long> lastByKey = new ConcurrentHashMap<>();

    private QaLog() {
    }

    /** True while a running scenario listens to this topic. The only call a hook makes when nothing runs. */
    public static boolean on(Topic topic) {
        return (mask & topic.bit) != 0;
    }

    /** True while any scenario runs. */
    public static boolean active() {
        return mask != 0 || !runs.isEmpty();
    }

    /** A line about something on this map (0 = no map): reaches runs that watch the map, or every map. */
    public static void map(Topic topic, int mapId, String message) {
        dispatch(topic, mapId, 0, message);
    }

    /** A line about this character: reaches only the run this character (a GM) started. */
    public static void owner(Topic topic, int characterId, String message) {
        dispatch(topic, 0, characterId, message);
    }

    /** A line for every run listening to the topic. */
    public static void any(Topic topic, String message) {
        dispatch(topic, 0, 0, message);
    }

    /**
     * Rate limit for a hook that would fire every tick (a bot skipping a hidden GM): true at most once per
     * {@code intervalMs} for the key. Call it inside the {@link #on} check.
     */
    public static boolean every(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = lastByKey.get(key);
        if (last != null && now - last < intervalMs) {
            return false;
        }
        lastByKey.put(key, now);
        return true;
    }

    private static void dispatch(Topic topic, int mapId, int ownerId, String message) {
        if (!on(topic)) {
            return;
        }
        for (QaRun run : runs) {
            if (run.accepts(topic, mapId, ownerId)) {
                run.event(topic, message);
            }
        }
    }

    static synchronized void attach(QaRun run) {
        runs.add(run);
        recompute();
    }

    static synchronized void detach(QaRun run) {
        runs.remove(run);
        recompute();
        if (runs.isEmpty()) {
            lastByKey.clear();
        }
    }

    private static void recompute() {
        int m = 0;
        for (QaRun run : runs) {
            for (Topic t : run.topics()) {
                m |= t.bit;
            }
        }
        mask = m;
    }

    /* Tests: forget every run. */
    static synchronized void reset() {
        runs.clear();
        lastByKey.clear();
        mask = 0;
    }
}
