package soloMapling.mechanics;

import client.Character;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which bots a mechanics run holds (ticket 130), read on every bot's macro tick through {@code BotCommitments}: a
 * held bot neither logs out nor drifts off on the slow wheel mid-run. Released when its run ends, whatever the
 * reason (cleared, timer, abandon, exception), so nothing stays held after.
 */
public final class MechanicsHold {

    private static final Set<Integer> held = ConcurrentHashMap.newKeySet();

    private MechanicsHold() {
    }

    static void hold(int botId) {
        held.add(botId);
    }

    static void release(int botId) {
        held.remove(botId);
    }

    /** True while a run is driving this bot. Cheap: an empty set when mechanics is off. */
    public static boolean committed(Character bot) {
        return bot != null && !held.isEmpty() && held.contains(bot.getId());
    }

    static int count() {
        return held.size();
    }
}
