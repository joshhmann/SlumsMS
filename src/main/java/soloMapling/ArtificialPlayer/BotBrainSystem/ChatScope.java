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
    public enum Frame { NONE, QUOTED, REPORTED, WARNING, NEGATED, NARRATIVE }

    private static final Pattern CLAUSE_BREAK = Pattern.compile("[,;.]|\\b(but|and|so)\\b");
    private static final Set<String> NEGATORS = Set.of("no", "not", "dont", "don't", "cant", "can't", "wont", "won't",
            "never", "nah", "isnt", "isn't");
    /** The word right after a negator that says the negator governs another verb ("dont forget to buff me"). */
    private static final Set<String> GUARDS = Set.of("forget", "forgot", "be", "worry", "worries", "mind", "believe");

    /** Someone else's words: "he said follow me", "she typed party pls", "the npc told me come back". */
    private static final Set<String> REPORTING = Set.of("said", "says", "typed", "wrote", "asked", "yelling",
            "spamming");
    /** The report is addressed to the bot ("he said u should buff me"): ambiguous, so it stays a request. */
    private static final Set<String> TO_THE_BOT = Set.of("u", "you", "ur", "ya");
    /** Someone else as the subject of the clause, with a verb that tells about them ("my friend wants a party"). */
    private static final Pattern THIRD_PARTY = Pattern.compile("^((lol|omg|so|and|but) )*(someone|some guy|ppl|people"
            + "|a gm|the npc|the sign|my friend) (?!pls\\b|plz\\b)(\\w+(ed|s)|is|was|were|has|had|kept|got|told|said"
            + "|wrote)\\b");
    /** A warning about something is a mention of it, never a request or a con. */
    private static final Pattern WARNING = Pattern.compile("\\b(watch out|be careful|don'?t trust|never share"
            + "|it'?s a scam)\\b");

    /** "i helped", "we partied", "i bargained down to 300k": the player tells what already happened. */
    private static final Pattern NARRATIVE = Pattern.compile("^((lol|omg|so|and|but|yeah|ya|well|ok) )*(i|we)"
            + "( (just|already|also|finally|even|once))? (\\w+ed|got|came|went|sold|bought|did|made|had|took|gave|paid"
            + "|ran|saw|was|were)\\b");
    /** A question or a please turns a story back into a request ("i was selling claws earlier u want one?"). */
    private static final Pattern ASKS = Pattern.compile("\\?|\\b(pls|plz|please)\\b");

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
        if (insideQuotes(text, at)) {
            return Frame.QUOTED;
        }
        String before = text.substring(clauseStart(text, at), at).trim();
        String[] words = before.isEmpty() ? new String[0] : before.split(" ");
        if (reported(words, text.substring(at)) || THIRD_PARTY.matcher(before).find()) {
            return Frame.REPORTED;
        }
        if (WARNING.matcher(before).find()) {
            return Frame.WARNING;
        }
        if (negatedBy(words, text.substring(at))) {
            return Frame.NEGATED;
        }
        if (NARRATIVE.matcher(before).find() && !ASKS.matcher(text).find()) {
            return Frame.NARRATIVE;
        }
        return Frame.NONE;
    }

    /**
     * Whether the line warns about something ("watch out", "be careful", "its a scam"): what it names is a mention, so
     * a scam or account word in it is no con.
     */
    public static boolean warns(String text) {
        return WARNING.matcher(text).find();
    }

    private static boolean insideQuotes(String text, int at) {
        int quotes = 0;
        for (int i = 0; i < at; i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\u201c' || c == '\u201d') {
                quotes++;
            }
        }
        return quotes % 2 == 1;
    }

    /** A reporting verb before the cue, unless what it reports is addressed to the bot. */
    private static boolean reported(String[] words, String cueOnward) {
        for (int i = 0; i < words.length; i++) {
            int after;
            if (REPORTING.contains(words[i])) {
                after = i + 1;
            } else if ("told".equals(words[i]) && i + 1 < words.length && "me".equals(words[i + 1])) {
                after = i + 2;
            } else {
                continue;
            }
            if (after < words.length && "that".equals(words[after])) {
                after++;
            }
            String next = after < words.length ? words[after] : firstWord(cueOnward);
            if (!TO_THE_BOT.contains(next)) {
                return true;
            }
        }
        return false;
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
