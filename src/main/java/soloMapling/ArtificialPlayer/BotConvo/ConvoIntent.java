package soloMapling.ArtificialPlayer.BotConvo;

import java.util.Locale;

/**
 * Ticket 59: what a player's line to a bot is about (owner decision 8). Read by rules first and by one flat Decide
 * question for what the rules leave open ({@link ConvoSense}). The label names are the Decide question's labels.
 */
public enum ConvoIntent {
    PARTY, FOLLOW, COME, TRADE, HAGGLE, BUFF, HELP_KILL, GUIDE, DATE, BUDDY, GUILD, APOLOGY, INSULT, FLIRT, TALK;

    /** The label name on the wire ({@code help_kill}). */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConvoIntent parse(String raw) {
        if (raw == null) {
            return TALK;
        }
        for (ConvoIntent i : values()) {
            if (i.label().equalsIgnoreCase(raw.trim())) {
                return i;
            }
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "question", "chatter", "none" -> TALK;
            default -> TALK;
        };
    }

    /**
     * Whether a Decide answer alone may set this intent. Economy and romance never come from the model (a wrong one
     * would quote a price or ask a bot out), and neither does an insult (a mood the tone question covers).
     */
    public boolean decideMay() {
        return switch (this) {
            case PARTY, FOLLOW, COME, BUFF, HELP_KILL, GUIDE, BUDDY, GUILD, APOLOGY -> true;
            default -> false;
        };
    }

    /** The action a granted request runs; NONE for talk and moods handled as words. */
    public ConvoAction action() {
        return switch (this) {
            case PARTY -> ConvoAction.PARTY;
            case FOLLOW -> ConvoAction.FOLLOW;
            case COME -> ConvoAction.COME;
            case TRADE -> ConvoAction.TRADE;
            case HAGGLE -> ConvoAction.HAGGLE;
            case BUFF -> ConvoAction.BUFF;
            case HELP_KILL -> ConvoAction.HELP;
            case GUIDE -> ConvoAction.GUIDE;
            case DATE -> ConvoAction.DATE;
            case BUDDY -> ConvoAction.BUDDY;
            case GUILD -> ConvoAction.GUILD;
            case APOLOGY -> ConvoAction.MAKEUP;
            case INSULT -> ConvoAction.INSULT;
            case FLIRT -> ConvoAction.FLIRT;
            case TALK -> ConvoAction.NONE;
        };
    }
}
