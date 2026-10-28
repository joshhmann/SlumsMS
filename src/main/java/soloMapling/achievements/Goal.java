package soloMapling.achievements;

import java.util.List;
import java.util.Locale;

/**
 * One achievement: a one-time goal per character that awards one medal. Pure: {@link #next} says how an {@link Event}
 * moves the stored progress, {@link #done} when that is enough.
 *
 * @param target  the count/tier/level to reach (ignored for a visit goal, which needs every map)
 * @param mobs    mob ids a {@code mob_kill} goal accepts
 * @param scripts lower-case event script names a {@code clear} goal accepts
 * @param maps    map ids a {@code visit} goal needs
 */
public record Goal(String id, String title, String text, int medal, Trigger trigger, int target, List<Integer> mobs,
                   List<String> scripts, List<Integer> maps) {

    /** The stored progress after {@code e}; the same value when the event does not concern this goal. */
    public int next(int progress, Event e) {
        if (e == null || e.trigger() != trigger) {
            return progress;
        }
        switch (trigger) {
            case MOB_KILL:
                return mobs.contains(e.value()) ? progress + 1 : progress;
            case CLEAR:
                return e.key() != null && scripts.contains(scriptName(e.key())) ? progress + 1 : progress;
            case VISIT: {
                int i = maps.indexOf(e.value());
                return i < 0 ? progress : progress | (1 << i);
            }
            default:
                break;
        }
        return switch (trigger.mode()) {
            case ADD -> progress + Math.max(0, e.value());
            case MAX -> Math.max(progress, e.value());
            case MASK -> progress;
        };
    }

    /** Whether {@code e} could move this goal at all (a cheap filter for the hot paths: kills, map changes). */
    public boolean concerns(Event e) {
        if (e == null || e.trigger() != trigger) {
            return false;
        }
        return switch (trigger) {
            case MOB_KILL -> mobs.contains(e.value());
            case CLEAR -> e.key() != null && scripts.contains(scriptName(e.key()));
            case VISIT -> maps.contains(e.value());
            default -> true;
        };
    }

    /** The progress that completes the goal. */
    public int needed() {
        return trigger == Trigger.VISIT ? (1 << maps.size()) - 1 : target;
    }

    public boolean done(int progress) {
        return progress >= needed();
    }

    /** Whether the stored progress has to be written before the goal is done (counts and visits, not tiers). */
    public boolean keepsProgress() {
        return trigger.mode() != Trigger.Mode.MAX;
    }

    /** "3/10", "2/5 places": what the player sees next to an unfinished goal. */
    public String progressText(int progress) {
        if (trigger == Trigger.VISIT) {
            return Integer.bitCount(progress) + "/" + maps.size() + " places";
        }
        if (trigger.mode() == Trigger.Mode.MAX || target <= 1) {
            return done(progress) ? "done" : "not yet";
        }
        return Math.min(progress, target) + "/" + target;
    }

    /** The script name an instance reports ("KerningPQ_1" or "kerningpq" -> "kerningpq"). */
    static String scriptName(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        int u = k.indexOf('_');
        return u < 0 ? k : k.substring(0, u);
    }
}
