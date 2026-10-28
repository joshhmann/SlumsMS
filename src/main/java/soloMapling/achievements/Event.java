package soloMapling.achievements;

/**
 * One verified thing that happened to a character. {@code value} depends on the trigger: the job tier, the level,
 * the mob id, the map id, the Daily Check-In day, or 1 for a plain count; {@code key} is the event script's name
 * for a cleared instance.
 */
public record Event(Trigger trigger, int value, String key) {

    public Event(Trigger trigger, int value) {
        this(trigger, value, null);
    }

    public static Event of(Trigger trigger) {
        return new Event(trigger, 1, null);
    }
}
