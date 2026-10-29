package soloMapling.achievements;

/**
 * One verified thing that happened to a character. {@code value} depends on the trigger: the job tier, the level,
 * the mob id, the map id, the Daily Check-In day, or 1 for a plain count; {@code key} is the event script's name
 * for a cleared instance.
 */
public record Event(Trigger trigger, int value, String key, boolean fact) {

    public Event(Trigger trigger, int value, String key) {
        this(trigger, value, key, false);
    }

    public Event(Trigger trigger, int value) {
        this(trigger, value, null);
    }

    /**
     * A fact read off the character at login (in a guild, married, the rebirth count): a counting goal takes it as a
     * floor (progress = max(progress, value)) instead of adding it, so logging in again never counts it twice.
     */
    public static Event fact(Trigger trigger, int value) {
        return new Event(trigger, value, null, true);
    }

    public static Event of(Trigger trigger) {
        return new Event(trigger, 1, null);
    }
}
