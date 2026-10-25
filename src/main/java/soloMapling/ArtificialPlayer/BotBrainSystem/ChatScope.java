package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.0 chat rules fix (tools/convo/benchmark/eval/fresh-v1/CODEX-HANDOFF.md, section 4): whether a request cue a rule
 * matched is really a request. The cue already matches; what was missing is scope. "dont follow me" has the follow
 * cue inside a negation, so it is not a request to follow.
 *
 * <p>Scope is read inside the cue's clause only: the line is split at {@code , ; .} and {@code but / and / so}, and
 * only words of the same clause before the cue can govern it. Works on the rules' normalised line (lower case).
 */
public final class ChatScope {

    /** Why a cue is not a request; {@link #NONE} when it is one. */
    public enum Frame { NONE, NEGATED }

    private static final Pattern CLAUSE_BREAK = Pattern.compile("[,;.]|\\b(but|and|so)\\b");
    private static final Set<String> NEGATORS = Set.of("no", "not", "dont", "don't", "cant", "can't", "wont", "won't",
            "never", "nah", "isnt", "isn't");
    /** The word right after a negator that says the negator governs another verb ("dont forget to buff me"). */
    private static final Set<String> GUARDS = Set.of("forget", "forgot", "be", "worry", "worries", "mind", "believe");

    private ChatScope() {
    }

    /**
     * How the rule's matches sit in the line: null when the pattern does not match at all, {@link Frame#NONE} when at
     * least one match is a request, else the frame of the first match.
     */
    public static Frame scan(Pattern cue, String text) {
        Matcher m = cue.matcher(text);
        Frame first = null;
        while (m.find()) {
            Frame f = frame(text, m.start());
            if (f == Frame.NONE) {
                return Frame.NONE;
            }
            if (first == null) {
                first = f;
            }
        }
        return first;
    }

    /** Whether some match of the cue is negated: the line declines that thing ("not looking to trade"). */
    public static boolean negated(Pattern cue, String text) {
        Matcher m = cue.matcher(text);
        while (m.find()) {
            if (frame(text, m.start()) == Frame.NEGATED) {
                return true;
            }
        }
        return false;
    }

    /** The frame of a cue that starts at {@code at}. */
    public static Frame frame(String text, int at) {
        String before = text.substring(clauseStart(text, at), at).trim();
        String[] words = before.isEmpty() ? new String[0] : before.split(" ");
        if (negatedBy(words, text.substring(at))) {
            return Frame.NEGATED;
        }
        return Frame.NONE;
    }

    static int clauseStart(String text, int at) {
        Matcher m = CLAUSE_BREAK.matcher(text);
        int start = 0;
        while (m.find() && m.end() <= at) {
            start = m.end();
        }
        return start;
    }

    /**
     * The nearest negator before the cue decides: it governs the cue unless the word after it is a guard. "stop" counts
     * as a negator before a verb in -ing ("stop asking me to party").
     */
    private static boolean negatedBy(String[] words, String cueOnward) {
        for (int i = words.length - 1; i >= 0; i--) {
            String w = words[i];
            String next = i + 1 < words.length ? words[i + 1] : firstWord(cueOnward);
            boolean negator = NEGATORS.contains(w) || ("stop".equals(w) && next.endsWith("ing"));
            if (negator) {
                return !GUARDS.contains(next);
            }
        }
        return false;
    }

    private static String firstWord(String s) {
        String t = s.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }
}
