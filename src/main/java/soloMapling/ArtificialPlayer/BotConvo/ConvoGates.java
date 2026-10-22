package soloMapling.ArtificialPlayer.BotConvo;

/**
 * Ticket 59, owner decision 1: "the LLM proposes, server rules decide". One pure function from the facts the game
 * gathered ({@link Facts}) to yes or no for one {@link ConvoAction}. Nothing a model writes or a player types reaches
 * here except as the action's name; every input is the server's own state. Actions an existing system already gates
 * with its own caps (party, follow, trade, haggle, guild, date, gift) answer {@link Verdict#DELEGATE} after the common
 * checks, and that system decides.
 */
public final class ConvoGates {

    /** What kind of bot is asked: decides which actions it can carry out at all. */
    public enum BotKind { GRINDER, SOCIAL, WANDERER, FOLLOWER, SERVICE }

    public enum Verdict { YES, NO, DELEGATE }

    public record Decision(Verdict verdict, String reason) {
        public boolean yes() {
            return verdict == Verdict.YES;
        }

        static Decision yes(String why) {
            return new Decision(Verdict.YES, why);
        }

        static Decision no(String why) {
            return new Decision(Verdict.NO, why);
        }
    }

    /**
     * What the game knows when a bot is asked.
     *
     * @param busy            {@code PlayerBusy}: event, boss map, trade/shop/storage, NPC chat, AFK
     * @param flagged         the line was flagged as a con (never reaches here in practice; belt and braces)
     * @param hostile         the bot counts the player as rival or nemesis (ticket 58)
     * @param wary            a memory note makes the bot wary (scam, rude, ...)
     * @param dramaOptOut     the player said {@code @botdrama off}
     * @param mayFlirt        romance on and the romance rules allow a flirt (dating, or a crush)
     * @param kind            the bot's kind
     * @param freeForScene    not in an event, not in a drama scene, not partied with another real player
     * @param targetMap       where the action would go (-1 none)
     * @param targetIsHere    the target is the map the bot and player are on
     * @param targetIsPlayers the target is the player's own map
     * @param targetGrindable the target has mobs in the bot's level band
     * @param targetIsTown    the target is a town or the FM (no mobs, not a boss or event map)
     * @param buffProblem     why the bot cannot buff the player now, or null
     * @param recentlyWalked  this bot already walked or guided for this player in the last 10 minutes
     * @param playerSorry     the player's line is an apology
     * @param playerRude      the player's line was rude
     * @param targetWalkable  the bot can walk there: a route of walkable portals only (never a warp, review 2026-10-22)
     */
    public record Facts(boolean busy, boolean flagged, boolean hostile, boolean wary, boolean dramaOptOut,
                        boolean mayFlirt, BotKind kind, boolean freeForScene, int targetMap, boolean targetIsHere,
                        boolean targetIsPlayers, boolean targetGrindable, boolean targetIsTown, String buffProblem,
                        boolean recentlyWalked, boolean playerSorry, boolean playerRude, boolean targetWalkable) {
    }

    /** The reason when the place cannot be reached on foot: the bot says so ("too far lol"), it never warps. */
    public static final String TOO_FAR = "too far to walk";

    private ConvoGates() {
    }

    public static Decision decide(ConvoAction action, Facts f) {
        if (action == null || action == ConvoAction.NONE) {
            return Decision.no("no action");
        }
        if (f.flagged()) {
            return Decision.no("flagged line");
        }
        boolean errand = switch (action) {
            case COME, HELP, GUIDE, BUDDY, PARTY, FOLLOW, TRADE, HAGGLE, GIFT, GUILD, DATE -> true;
            default -> false;
        };
        if (errand && f.busy()) {
            return Decision.no("player busy");
        }
        boolean cold = f.hostile() || f.wary();
        return switch (action) {
            case COME -> come(f, cold);
            case HELP -> help(f, cold);
            case GUIDE -> guide(f, cold);
            case BUFF, HEAL -> f.hostile() ? Decision.no("hostile") : f.buffProblem() != null
                    ? Decision.no(f.buffProblem()) : Decision.yes("buff");
            case BUDDY -> cold ? Decision.no(f.hostile() ? "hostile" : "wary") : new Decision(Verdict.DELEGATE,
                    "buddy caps");
            case PARTY, FOLLOW, TRADE, HAGGLE, GUILD, DATE, GIFT -> new Decision(Verdict.DELEGATE, "own gates");
            case LEAVE_GUILD -> Decision.no("guild quits stay the guild rules'");
            case APOLOGIZE -> Decision.yes("words");
            case MAKEUP -> !f.hostile() ? Decision.no("no grudge to drop") : f.dramaOptOut() ? Decision.no("drama off")
                    : !f.playerSorry() ? Decision.no("they did not say sorry") : Decision.yes("grudge eases");
            case INSULT -> f.dramaOptOut() ? Decision.no("drama off")
                    : f.hostile() || f.playerRude() ? Decision.yes("trash talk back") : Decision.no("no reason");
            case FLIRT -> f.mayFlirt() ? Decision.yes("romance allows") : Decision.no("romance off or not close");
            case NONE -> Decision.no("no action");
        };
    }

    private static Decision come(Facts f, boolean cold) {
        if (cold) {
            return Decision.no(f.hostile() ? "hostile" : "wary");
        }
        if (f.targetMap() < 0) {
            return Decision.no("no place");
        }
        if (f.targetIsHere()) {
            return Decision.yes("already here");
        }
        if (!f.freeForScene()) {
            return Decision.no("busy with something");
        }
        if (f.recentlyWalked()) {
            return Decision.no("walked for them recently");
        }
        if (!f.targetWalkable()) {
            return Decision.no(TOO_FAR);
        }
        return switch (f.kind()) {
            case GRINDER -> f.targetGrindable() ? Decision.yes("grinds there") : Decision.no("grinding, not a map for it");
            case SOCIAL, WANDERER -> f.targetIsTown() ? Decision.yes("walks over") : Decision.no("stays in town");
            case FOLLOWER, SERVICE -> Decision.no("cannot leave its job");
        };
    }

    private static Decision help(Facts f, boolean cold) {
        if (cold) {
            return Decision.no(f.hostile() ? "hostile" : "wary");
        }
        if (f.kind() != BotKind.GRINDER) {
            return Decision.no("not a grinder");
        }
        if (!f.targetIsPlayers() || f.targetMap() < 0) {
            return Decision.no("no place");
        }
        if (!f.targetGrindable()) {
            return Decision.no("mobs not in its level band");
        }
        if (!f.freeForScene()) {
            return Decision.no("busy with something");
        }
        if (!f.targetIsHere() && f.recentlyWalked()) {
            return Decision.no("walked for them recently");
        }
        if (!f.targetIsHere() && !f.targetWalkable()) {
            return Decision.no(TOO_FAR);
        }
        return Decision.yes(f.targetIsHere() ? "grinds here" : "walks over to grind");
    }

    private static Decision guide(Facts f, boolean cold) {
        if (cold) {
            return Decision.no(f.hostile() ? "hostile" : "wary");
        }
        if (f.kind() != BotKind.SOCIAL && f.kind() != BotKind.WANDERER) {
            return Decision.no("busy, not a guide");
        }
        if (f.targetMap() < 0) {
            return Decision.no("no place");
        }
        if (f.targetIsPlayers()) {
            return Decision.no("already there");
        }
        if (!f.targetIsTown()) {
            return Decision.no("only guides to towns");
        }
        if (!f.freeForScene() || f.recentlyWalked()) {
            return Decision.no(f.recentlyWalked() ? "guided recently" : "busy with something");
        }
        if (!f.targetWalkable()) {
            return Decision.no(TOO_FAR);
        }
        return Decision.yes("leads the way");
    }
}
