package soloMapling.worldevents;

import java.util.List;
import java.util.Locale;

/**
 * One scripted world event as the owner wrote it in {@code WorldEvents.yaml} (ticket 21). A template never changes
 * rates, drops or shops: it spawns its own mobs or NPC on one map for a fixed time, and the only value it hands out
 * is its own fixed reward table.
 *
 * @param minLevel   lowest bot level that reacts (0 = derive from the map or boss when the event starts)
 * @param maxLevel   highest bot level that reacts (0 = derive)
 * @param botCap     most bots that head to this event
 * @param spots      where it may happen: a map, plus the boss (boss sighting) or the wanted item (wandering NPC)
 * @param waves      invasion waves, in order; the boss comes after the last one
 * @param expBonus   hot spot only: the EXP multiplier on that map (1.5 = +50%)
 */
public record EventTemplate(String id, Kind kind, String name, double weight, int minutes, int cooldownMinutes,
                            int minLevel, int maxLevel, int botCap, List<Spot> spots, List<Wave> waves,
                            int bossMobId, int npcId, double expBonus, List<Reward> rewards, boolean enabled) {

    /** The five kinds of the first set (spec "Event templates"). */
    public enum Kind {
        INVASION, BOSS_SIGHTING, TREASURE_HUNT, HOT_SPOT, WANDERING_NPC;

        public static Kind parse(String raw) {
            if (raw == null) {
                return null;
            }
            String k = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Kind kind : values()) {
                if (kind.name().equals(k)) {
                    return kind;
                }
            }
            return null;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        /** Whether bots travel to this event (a town quest NPC only gets talked about). */
        public boolean botsGather() {
            return this != WANDERING_NPC;
        }
    }

    /** A place the event can happen; mobId (boss sighting) or itemId/count (wandering NPC quest) when they apply. */
    public record Spot(int mapId, int mobId, int itemId, int count) {
        public static Spot map(int mapId) {
            return new Spot(mapId, 0, 0, 0);
        }
    }

    public record Wave(int mobId, int count) {
    }

    /** One line of the reward table: the item, how many, and the chance it is given (1.0 = always). */
    public record Reward(int itemId, int qty, double chance) {
    }

    public EventTemplate {
        spots = spots == null ? List.of() : List.copyOf(spots);
        waves = waves == null ? List.of() : List.copyOf(waves);
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
    }

    public long durationMs() {
        return minutes * 60_000L;
    }

    public long cooldownMs() {
        return cooldownMinutes * 60_000L;
    }
}
