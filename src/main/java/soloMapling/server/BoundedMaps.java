package soloMapling.server;

import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 1.5.0 rc6 bug hunt: keeps per-player / per-pair cooldown maps from growing for the whole uptime. Call
 * {@link #prune} after a put: nothing happens until the map passes {@code soft}; then every stale entry goes, and if
 * the map is still over {@code hard} (everything still fresh) arbitrary entries go until it is back at {@code hard}.
 * Use it only for maps whose entries are cooldowns or caches, where losing one costs at most a repeat (a line said
 * again, a store read), never correctness.
 */
public final class BoundedMaps {

    private BoundedMaps() {
    }

    public static <K, V> void prune(Map<K, V> map, int soft, int hard, Predicate<V> stale) {
        if (map.size() <= soft) {
            return;
        }
        map.values().removeIf(stale);
        if (map.size() <= hard) {
            return;
        }
        Iterator<K> it = map.keySet().iterator();
        while (map.size() > hard && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
