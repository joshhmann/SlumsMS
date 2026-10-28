package soloMapling.achievements;

import java.util.Locale;

/** What a goal listens to (achievements.yaml {@code trigger:}) and how its progress moves. */
public enum Trigger {
    /** Progress = the highest job tier reached (1..4). */
    JOB(Mode.MAX),
    /** Progress = the highest level reached. */
    LEVEL(Mode.MAX),
    /** 1 once the character is at its class's real level cap. */
    LEVEL_CAP(Mode.MAX),
    MOB_KILL(Mode.ADD),
    CLEAR(Mode.ADD),
    FISH(Mode.ADD),
    GUILD(Mode.ADD),
    WEDDING(Mode.ADD),
    REBIRTH(Mode.ADD),
    QUEST(Mode.ADD),
    TRADE(Mode.ADD),
    MARKET(Mode.ADD),
    /** Progress = the highest Daily Check-In day claimed. */
    CHECKIN(Mode.MAX),
    /** Progress = a bitmask of the listed maps visited. */
    VISIT(Mode.MASK);

    public enum Mode { ADD, MAX, MASK }

    private final Mode mode;

    Trigger(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public static Trigger parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown trigger '" + s + "'");
        }
    }
}
