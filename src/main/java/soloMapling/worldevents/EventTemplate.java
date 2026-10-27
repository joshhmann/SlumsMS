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
 * @param slot       1.6.0: when the director runs it ({@link Slot})
 * @param bossHp     1.6.0: the boss's HP for this event (0 = its WZ HP); a giant cake is a crowd's punching bag
 * @param participation        1.6.0: what every real player who took part gets when it is won
 * @param participationExpPct  1.6.0: participation EXP, in percent of the player's current level (0-10)
 */
public record EventTemplate(String id, Kind kind, String name, double weight, int minutes, int cooldownMinutes,
                            int minLevel, int maxLevel, int botCap, List<Spot> spots, List<Wave> waves,
                            int bossMobId, int npcId, double expBonus, List<Reward> rewards, boolean enabled,
                            Slot slot, long bossHp, List<Reward> participation, double participationExpPct) {

    /**
     * 1.6.0: where the {@link EventDirector} may use a template. HEADLINE: the daily evening event (~8 PM PT, a weighted
     * rotation); SMALL: the 1-3 random ones at peak hours; MANUAL: only a GM or another system starts it (weddings).
     */
    public enum Slot {
        HEADLINE, SMALL, MANUAL;

        public static Slot parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (Slot s : values()) {
                if (s.name().equalsIgnoreCase(raw.trim())) {
                    return s;
                }
            }
            return null;
        }
    }

    /** The pre-1.6.0 shape (tests, older callers): a small event without a boss HP or participation rewards. */
    public EventTemplate(String id, Kind kind, String name, double weight, int minutes, int cooldownMinutes,
                         int minLevel, int maxLevel, int botCap, List<Spot> spots, List<Wave> waves,
                         int bossMobId, int npcId, double expBonus, List<Reward> rewards, boolean enabled) {
        this(id, kind, name, weight, minutes, cooldownMinutes, minLevel, maxLevel, botCap, spots, waves, bossMobId,
                npcId, expBonus, rewards, enabled, weight > 0 ? Slot.SMALL : Slot.MANUAL, 0, List.of(), 0);
    }

    /**
     * The five kinds of the first set (spec "Event templates"), a bot couple's wedding (ticket 58, 1.5.0) and the
     * 1.6.0 giant cake (everyone in town whacks one huge cake, as at the old anniversary events).
     */
    public enum Kind {
        INVASION, BOSS_SIGHTING, TREASURE_HUNT, HOT_SPOT, WANDERING_NPC, WEDDING, GIANT_CAKE;

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
            return this != WANDERING_NPC && this != WEDDING; // a wedding brings its own guests
        }
    }

    /** A place the event can happen; mobId (boss sighting) or itemId/count (wandering NPC quest) when they apply. */
    public record Spot(int mapId, int mobId, int itemId, int count) {
        public static Spot map(int mapId) {
            return new Spot(mapId, 0, 0, 0);
        }
    }

    /**
     * One invasion wave: {@code count} of {@code mobId}, and (1.6.0 tuning) an optional mini-boss that comes with it
     * at its own WZ stats ({@code miniBossId} 0 = none). The wave is cleared when all of them, mini-boss too, are down.
     */
    public record Wave(int mobId, int count, int miniBossId) {
        public Wave(int mobId, int count) {
            this(mobId, count, 0);
        }
    }

    /** One line of the reward table: the item, how many, and the chance it is given (1.0 = always). */
    public record Reward(int itemId, int qty, double chance) {
    }

    public EventTemplate {
        spots = spots == null ? List.of() : List.copyOf(spots);
        waves = waves == null ? List.of() : List.copyOf(waves);
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        participation = participation == null ? List.of() : List.copyOf(participation);
        slot = slot == null ? (weight > 0 ? Slot.SMALL : Slot.MANUAL) : slot;
    }

    /** Whether the boss fight decides it (invasion, sighting, cake): the boss's death ends it. */
    public boolean bossFight() {
        return kind == Kind.INVASION || kind == Kind.BOSS_SIGHTING || kind == Kind.GIANT_CAKE;
    }

    public long durationMs() {
        return minutes * 60_000L;
    }

    public long cooldownMs() {
        return cooldownMinutes * 60_000L;
    }
}
