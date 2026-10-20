package config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How the {@code worlds:} entries of config.yaml map to world ids (ticket 41).
 *
 * <p>An entry may name its world with {@code id} (0 = Scania, 1 = Bera, ... as in {@code GameConstants.WORLD_NAMES}).
 * Without it ({@code id: -1}, the default) the entry's position in the list is its id, which is how every config
 * written before ticket 41 keeps working unchanged. Only the entries the server starts are worlds; there is no
 * placeholder world for an id nobody configured, so a config with a single {@code id: 1} entry runs Bera alone.
 */
public final class WorldConfigs {
    public static final int UNSET = -1;

    private WorldConfigs() {
    }

    /** The world id the entry at {@code index} of the list configures. */
    public static int idOf(int index, WorldConfig entry) {
        return entry != null && entry.id >= 0 ? entry.id : index;
    }

    /** The world ids of every entry, in list order. */
    public static List<Integer> ids(List<WorldConfig> entries) {
        List<Integer> ids = new ArrayList<>();
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                ids.add(idOf(i, entries.get(i)));
            }
        }
        return ids;
    }

    /** The entry configuring world {@code worldId}, or null if no entry does. */
    public static WorldConfig forWorld(List<WorldConfig> entries, int worldId) {
        if (entries == null) {
            return null;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (idOf(i, entries.get(i)) == worldId) {
                return entries.get(i);
            }
        }
        return null;
    }

    /**
     * Fails with a message naming the offending entry if an id is negative (other than unset), outside the
     * {@code nameCount} worlds the client knows, or configured twice.
     */
    public static void validate(List<WorldConfig> entries, int nameCount) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException("config.yaml has no 'worlds:' entries; at least one world is needed.");
        }
        Map<Integer, Integer> seen = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            WorldConfig entry = entries.get(i);
            if (entry == null) {
                throw new IllegalStateException("config.yaml worlds entry #" + (i + 1) + " is empty.");
            }
            if (entry.id < UNSET) {
                throw new IllegalStateException("config.yaml worlds entry #" + (i + 1) + " has id " + entry.id
                        + "; use 0.." + (nameCount - 1) + ", or leave id out to use the entry's position.");
            }
            int id = idOf(i, entry);
            if (id >= nameCount) {
                throw new IllegalStateException("config.yaml worlds entry #" + (i + 1) + " is world id " + id
                        + ", but the client only knows world ids 0.." + (nameCount - 1) + ".");
            }
            Integer earlier = seen.putIfAbsent(id, i);
            if (earlier != null) {
                throw new IllegalStateException("config.yaml worlds entries #" + (earlier + 1) + " and #" + (i + 1)
                        + " are both world id " + id + "; every world needs its own id"
                        + " (an entry without 'id' takes its position in the list).");
            }
        }
    }
}
