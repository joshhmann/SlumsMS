package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 1.5.0 bot drama (owner rules, 2026-10-22): what a bot may never say, checked on every model reply before it reaches
 * a player ({@link ReplyCleaner#voiceProblem}) and on every drama line. A reply that trips it is dropped and the
 * caller's scripted line goes out instead.
 *
 * <ul>
 *   <li><b>Explicit:</b> romance is flirty, cheesy and dramatic, PG-13 - never sexual.</li>
 *   <li><b>Off-limits insults:</b> trash talk stays about the game (gear, level, job, deaths, KS habits): never slurs,
 *   looks, family, real life, or wishing harm.</li>
 * </ul>
 * {@link #flirty} is softer: a flirt aimed at a player who never turned {@code @romance on} falls back to a plain line.
 */
public final class ContentGuard {

    private static final Pattern EXPLICIT = Pattern.compile("\\b(sex|sexy|sexual|sexting|sext|nudes?|naked|nsfw|horny"
            + "|porn\\w*|boobs?|tits?|titties|dick|cock|pussy|penis|vagina|blowjob|bj|handjob|cum|cumming|orgasm\\w*"
            + "|moan\\w*|kinky|kink|fetish|strip(ping)?|undress\\w*|lewd|hump\\w*|grope\\w*|spank\\w*|thrust\\w*"
            + "|in bed|into bed|to bed with|sleep with|sleep together|hook ?up|one night stand|bang (u|you|me)"
            + "|f+u+c+k+ (u|you|me)|touch (u|you|me) there|take (it|ur|your|my) (clothes )?off|get naked|make love)\\b");
    private static final Pattern SLUR = Pattern.compile("\\b(n+i+g+(g+)?(a|er|ers|az|uh)s?|f+a+g+(g+)?(ot|ots|s)?|dykes?"
            + "|trann(y|ies)|retard(ed|s)?|tard|spics?|chinks?|kikes?|gooks?|wetbacks?|beaners?|coons?|towelheads?"
            + "|raghead|sp[a@]stic|cripple|mongoloid|homo|queer)\\b");
    private static final Pattern REAL_LIFE = Pattern.compile("\\b(ugly|fat|fatty|fatass|fatso|obese|hideous|acne|zits?"
            + "|ur face|your face|ur looks|your looks|bald|short ?ass|ur mom|your mom|ur mother|your mother|yo mama"
            + "|ur dad|your dad|ur father|your father|ur parents|your parents|ur family|your family|ur sister"
            + "|your sister|ur brother|your brother|get a job|no job|unemployed|basement|virgin|no friends irl|irl"
            + "|in real life|real life|touch grass|go outside|kys|kill (ur|your)self|kill yourself|hope (u|you) die"
            + "|go die|drop dead|cancer|retire from life|neckbeard|incel|poor (kid|loser)|homeless|stupid ?(irl|human)"
            + "|ur ip|your ip|dox\\w*|swat\\w*|i know where (u|you) live|rape\\w*|suicide)\\b");
    private static final Pattern FLIRTY = Pattern.compile("\\b(babe|baby|cutie|cute|handsome|gorgeous|beautiful|pretty"
            + "|kiss(es|y)?|crush|date|dating|go out|marry|married|wifey|hubby|boyfriend|girlfriend|bf|gf|love (u|you|ya)"
            + "|ily|xoxo|darling|sweetheart|honey|hottie|flirt\\w*|my love|be mine|single\\?)\\b");

    private ContentGuard() {
    }

    /**
     * Why a line must not be said, or null when it is fine: {@code "explicit"}, {@code "slur"} or
     * {@code "off-limits insult"} (looks, family, real life, harm).
     */
    public static String problem(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String text = normalise(line);
        if (EXPLICIT.matcher(text).find()) {
            return "explicit";
        }
        if (SLUR.matcher(text).find()) {
            return "slur";
        }
        if (REAL_LIFE.matcher(text).find()) {
            return "off-limits insult";
        }
        return null;
    }

    /** Whether the line is safe to say. */
    public static boolean ok(String line) {
        return problem(line) == null;
    }

    /** Whether a line flirts (a reply to a player without {@code @romance on} that does falls back to a plain one). */
    public static boolean flirty(String line) {
        return line != null && FLIRTY.matcher(normalise(line)).find();
    }

    /** Lower case, common character swaps undone (s3x, n00d), runs of spaces folded. */
    static String normalise(String line) {
        String t = line.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            out.append(switch (c) {
                case '0' -> 'o';
                case '3' -> 'e';
                case '4' -> between(t, i) ? 'a' : c;
                case '1', '!' -> between(t, i) ? 'i' : c;
                case '$' -> 's';
                case '@' -> 'a';
                case '*' -> between(t, i) ? 'u' : ' ';
                default -> c;
            });
        }
        return out.toString().replaceAll("\\s+", " ");
    }

    private static boolean between(String t, int i) {
        return i > 0 && i + 1 < t.length() && Character.isLetter(t.charAt(i - 1)) && Character.isLetter(t.charAt(i + 1));
    }
}
