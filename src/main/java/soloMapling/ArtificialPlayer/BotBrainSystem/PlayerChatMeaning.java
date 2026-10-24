package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** Narrow clause guards for requests versus reports/refusals. Not a general language parser. */
public final class PlayerChatMeaning {
    public record View(String text, boolean filtered) { }

    private static final Pattern QUOTE = Pattern.compile("\"[^\"]*\"|(?<![a-z])'[^'\\n]+'(?![a-z])");
    private static final Pattern CLAUSES = Pattern.compile("[,;.!?]+|\\b(?:but|however|instead|then)\\b");
    private static final Pattern REFUSAL = Pattern.compile("^(?:hey |please )*(?:(?:i|im|i'm|we|we're) )?"
            + "(?:do not|don'?t|not|no longer) (?:want|need|looking for|asking(?: u| you)? for|wanna)\\b.*");
    // 'don't move/leave' are positive requests to stay, not refusals.
    private static final Pattern NEGATIVE_ACTION = Pattern.compile("^(?:hey |please )*(?:do not|don'?t|never|stop) "
            + "(?:follow|come|join|invite|trade|buff|heal|add|give|lend|send|kill|help|take|show|sell|buy)\\b.*");
    private static final Pattern REPORT = Pattern.compile("^(?:(?:the |a )?npc|someone|somebody|he|she|they|my friend|"
            + "[a-z0-9]+) (?:said|typed|told|wrote|asked)\\b.*");
    private static final Pattern PAST_START = Pattern.compile("^(?:i|we) (?:already |just |finally )?"
            + "(?:helped|killed|joined|traded|bought|sold|followed|went|got|had|cast|met|added)\\b.*");
    private static final Pattern PAST_TIME = Pattern.compile("\\b(yesterday|earlier|last (?:night|week|time)|ago|already)\\b");
    private static final Pattern META = Pattern.compile("^(?:the )?(?:word|phrase)\\b.*");
    private static final Pattern WARNING = Pattern.compile("^(?:watch out for|beware of)\\b.*");
    private static final Pattern PASSWORD_WARNING = Pattern.compile("^(?:please )?(?:do not|don'?t|never) share "
            + "(?:ur|your) (?:password|pw|login|pin) (?:with anyone|with anybody|ever)$");
    private static final Pattern BOT_COMPLAINT = Pattern.compile("^i hate when (?:people|players) ask if "
            + "(?:ur|you'?re|you are|u are) (?:a |an )?(?:bot|ai)$");

    private PlayerChatMeaning() { }

    public static View requests(String line) { return filter(line, false); }
    public static View safety(String line) { return filter(line, true); }

    private static View filter(String line, boolean safety) {
        String original = line == null ? "" : line.toLowerCase(Locale.ROOT).replace('\u2019', '\'')
                .replace('\u201c', '"').replace('\u201d', '"').replaceAll("\\s+", " ").trim();
        String unquoted = QUOTE.matcher(original).replaceAll(" ");
        boolean filtered = !unquoted.equals(original);
        var kept = new ArrayList<String>();
        for (String raw : CLAUSES.split(unquoted)) {
            String clause = raw.trim();
            if (clause.isEmpty()) continue;
            boolean remove = WARNING.matcher(clause).matches() || PASSWORD_WARNING.matcher(clause).matches()
                    || BOT_COMPLAINT.matcher(clause).matches();
            if (!safety) remove |= REFUSAL.matcher(clause).matches() || NEGATIVE_ACTION.matcher(clause).matches()
                    || REPORT.matcher(clause).matches() || META.matcher(clause).matches()
                    || PAST_START.matcher(clause).matches() && PAST_TIME.matcher(clause).find()
                    || clause.matches("(?:i|we) (?:did not|didn'?t)");
            if (remove) filtered = true;
            else kept.add(clause);
        }
        // Don't rewrite ordinary lines: question marks and contractions matter to the existing rules.
        return new View(filtered ? String.join(", ", kept) : original, filtered);
    }
}
