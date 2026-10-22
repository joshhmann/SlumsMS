package soloMapling.ArtificialPlayer.BotDramaSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The arithmetic and word rules of player-bot romance (1.5.0 bot drama), pure and tested.
 *
 * <ul>
 *   <li>Nothing romantic happens to a player without {@code @romance on}: no flirting, no asking out, no proposal.</li>
 *   <li>A bot flirts with a friend it likes a lot ({@code flirtAt}, 45) and asks them out when it likes them even more
 *   ({@code askAt}, 55); asked out, it says yes to a friend at {@code yesAt} (35) or more. A bot dates one player at a
 *   time, and never while it is in a bot couple.</li>
 *   <li>Neglect: a dating or engaged pair that has not stood on one map for {@code neglectDays} (4) breaks up. A married
 *   pair never breaks up on its own: divorce is the player's call.</li>
 *   <li>An ex comes back as an old flame after {@code flameDays} (60).</li>
 * </ul>
 */
public final class RomanceRules {

    private RomanceRules() {
    }

    static double flirtAt() {
        return DramaKit.prop("romance.flirtAt", 45);
    }

    static double askAt() {
        return DramaKit.prop("romance.askAt", 55);
    }

    static double yesAt() {
        return DramaKit.prop("romance.yesAt", 35);
    }

    static long neglectMs() {
        return DramaKit.days("romance.neglectDays", 4);
    }

    static long flameMs() {
        return DramaKit.days("romance.flameDays", 60);
    }

    static long proposeAfterMs() {
        return DramaKit.days("romance.proposeAfterDays", 2);
    }

    /**
     * Whether a bot may flirt with a player now: the player turned romance on, the bot counts them as a friend it
     * likes at least {@code flirtAt}, and the bot is single (no bot couple, not dating someone else).
     */
    static boolean mayFlirt(boolean romanceOn, AffinityState state, double score, boolean botTaken) {
        return romanceOn && !botTaken && state == AffinityState.FRIEND && score >= flirtAt();
    }

    /** Whether a bot asks a player out (on top of {@link #mayFlirt}). */
    static boolean mayAskOut(boolean romanceOn, AffinityState state, double score, boolean botTaken,
                             boolean playerDatingThisBot) {
        return !playerDatingThisBot && mayFlirt(romanceOn, state, score, botTaken) && score >= askAt();
    }

    /** Whether a bot asked out by a player says yes. */
    static boolean saysYes(boolean romanceOn, AffinityState state, double score, boolean botTaken) {
        return romanceOn && !botTaken && state == AffinityState.FRIEND && score >= yesAt();
    }

    /** Whether a bot says yes to a ring: it dates the player and still counts them as a friend. */
    static boolean acceptsRing(boolean romanceOn, Romance r, AffinityState state) {
        return romanceOn && r != null && r.stage() == Romance.Stage.DATING && state != null && !state.hostile();
    }

    static boolean neglected(Romance r, long now) {
        return r != null && (r.stage() == Romance.Stage.DATING || r.stage() == Romance.Stage.ENGAGED)
                && now - Math.max(r.togetherAt(), r.startedAt()) >= neglectMs();
    }

    static boolean oldFlame(Romance r, long now) {
        return r != null && r.stage() == Romance.Stage.EX && r.endedAt() > 0 && now - r.endedAt() >= flameMs();
    }

    static boolean readyToPropose(Romance r, long now) {
        return r != null && r.stage() == Romance.Stage.DATING && now - r.startedAt() >= proposeAfterMs();
    }

    // ── words ──

    private static final Pattern ASK_OUT = Pattern.compile("\\b(go out with me|wanna go out|want to go out|go on a date"
            + "|date me|wanna date|want to date|will (u|you) date|be my (gf|bf|girlfriend|boyfriend|partner|valentine"
            + "|wife|husband)|be mine|r u single|are you single|ur single\\?|go steady)\\b");
    private static final Pattern MARRY = Pattern.compile("\\b(marry me|will (u|you) marry|wanna (get )?marr(y|ied)"
            + "|lets get married|let's get married)\\b");
    private static final Pattern YES = Pattern.compile("^(y+e*s+|ye+|ya+|yea+h*|yep|yup|sure|ok+|okay|okie|k|of course"
            + "|ofc|absolutely|definitely|i do|i'd love to|id love to|yes please|why not|<3|omg yes|totally|deal)\\b.*");
    private static final Pattern NO = Pattern.compile("^(n+o+|nah+|nope|no thanks|no ty|not really|pass|im good|i'm good"
            + "|sorry no|ew|eww|gross|never|lol no|haha no)\\b.*");
    private static final Pattern SORRY = Pattern.compile("\\b(sorry|sry|soz|my bad|mb|apolog\\w*|forgive me|didnt mean"
            + "|didn't mean|wont happen again|won't happen again|my fault|i was wrong)\\b");
    private static final Pattern MOCK = Pattern.compile("\\b(lol no|cry|crying|cope|seethe|mad\\??|stay mad|ez|get good|git gud"
            + "|skill issue|deal with it|whatever|who cares|no u|make me|nope|never|l ?bozo|bozo|ratio|cry more"
            + "|go away|stfu|shut up|lmao|kekw|noob|trash)\\b");

    static String norm(String line) {
        return line == null ? "" : line.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /** "wanna go out with me?", "be my gf", "r u single" ... */
    static boolean asksOut(String line) {
        return ASK_OUT.matcher(norm(line)).find();
    }

    static boolean asksToMarry(String line) {
        return MARRY.matcher(norm(line)).find();
    }

    /** Yes, no, or neither (null) to a question just asked. */
    static Boolean yesNo(String line) {
        String t = norm(line).replaceAll("^[^a-z<]+", "");
        if (NO.matcher(t).matches()) {
            return false;
        }
        if (YES.matcher(t).matches()) {
            return true;
        }
        return null;
    }

    /** An apology ("sorry", "my bad"); mocking wins when both show up ("sorry not sorry lol"). */
    static boolean apology(String line) {
        String t = norm(line);
        return SORRY.matcher(t).find() && !t.contains("not sorry") && !MOCK.matcher(t).find();
    }

    /** Mocking a bot that asked for an apology. */
    static boolean mocking(String line) {
        String t = norm(line);
        return t.contains("not sorry") || MOCK.matcher(t).find();
    }

    /** The wedding ring of an engagement box: Moonstone, Star Gem, Golden Heart or Silver Swan (Moonstone otherwise). */
    static int weddingRingOf(int boxItemId) {
        return switch (boxItemId) {
            case 2240001, 4031359, 4031360 -> 1112806;
            case 2240002, 4031361, 4031362 -> 1112807;
            case 2240003, 4031363, 4031364 -> 1112809;
            default -> 1112803;
        };
    }

    /**
     * The engagement token the proposer keeps for a box (the stock mapping of {@code RingActionHandler}); the one who
     * said yes keeps this + 1.
     */
    static int engagementTokenOf(int boxItemId) {
        return switch (boxItemId) {
            case 2240000 -> 4031357;
            case 2240001 -> 4031359;
            case 2240002 -> 4031361;
            case 2240003 -> 4031363;
            default -> boxItemId >= 2240004 && boxItemId <= 2240015 ? 1112300 + (boxItemId - 2240004) : 4031357;
        };
    }

    /** Whether the item is one of the eight engagement tokens. */
    static boolean engagementToken(int itemId) {
        return itemId >= 4031357 && itemId <= 4031364;
    }
}
