package soloMapling.ArtificialPlayer.BotConvo;

import java.util.Locale;

/**
 * Ticket 59, owner decision 1: the fixed whitelist of things a bot may do because of a chat. The chat model may
 * propose one of these per reply ({@link ConvoContract}); {@link ConvoGates} decides; {@link ConvoActions} does it
 * with the existing systems. Nothing outside this list can ever come out of a chat.
 */
public enum ConvoAction {
    // social
    PARTY(Family.SOCIAL, true),
    FOLLOW(Family.SOCIAL, false),
    BUDDY(Family.SOCIAL, true),
    GUILD(Family.SOCIAL, false),
    LEAVE_GUILD(Family.SOCIAL, false),
    DATE(Family.SOCIAL, false),
    COME(Family.SOCIAL, true),
    // helpful
    BUFF(Family.HELPFUL, true),
    HEAL(Family.HELPFUL, true),
    HELP(Family.HELPFUL, true),
    GUIDE(Family.HELPFUL, false),
    // economy: never from a model tag, only from the player's own words
    TRADE(Family.ECONOMY, false),
    HAGGLE(Family.ECONOMY, false),
    GIFT(Family.ECONOMY, false),
    // mood: words that shift affinity
    APOLOGIZE(Family.MOOD, true),
    MAKEUP(Family.MOOD, true),
    INSULT(Family.MOOD, true),
    FLIRT(Family.MOOD, true),
    NONE(Family.NONE, false);

    public enum Family { SOCIAL, HELPFUL, ECONOMY, MOOD, NONE }

    public final Family family;
    /** Whether a model tag may start it when the player asked for nothing (still gated). */
    public final boolean postHoc;

    ConvoAction(Family family, boolean postHoc) {
        this.family = family;
        this.postHoc = postHoc;
    }

    /** The tag name the model writes ({@code [do:help]}). */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A tag name (and a few spellings small models use) to an action; NONE for anything else. */
    public static ConvoAction parse(String raw) {
        if (raw == null) {
            return NONE;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (t) {
            case "party", "party_invite", "invite", "pt" -> PARTY;
            case "follow", "follow_me" -> FOLLOW;
            case "buddy", "add_buddy", "friend", "add_friend" -> BUDDY;
            case "guild", "join_guild" -> GUILD;
            case "leave_guild", "quit_guild" -> LEAVE_GUILD;
            case "date", "go_on_a_date" -> DATE;
            case "come", "come_to_map", "omw", "go", "walk" -> COME;
            case "buff" -> BUFF;
            case "heal" -> HEAL;
            case "help", "help_kill", "kill", "carry" -> HELP;
            case "guide", "show", "show_way" -> GUIDE;
            case "trade", "open_trade", "quote", "sell", "buy" -> TRADE;
            case "haggle", "counter" -> HAGGLE;
            case "gift", "give" -> GIFT;
            case "apologize", "apologise", "apology", "sorry" -> APOLOGIZE;
            case "makeup", "make_up", "forgive" -> MAKEUP;
            case "insult", "roast", "trash_talk" -> INSULT;
            case "flirt" -> FLIRT;
            default -> NONE;
        };
    }
}
