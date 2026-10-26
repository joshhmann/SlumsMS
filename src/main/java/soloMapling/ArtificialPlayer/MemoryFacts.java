package soloMapling.ArtificialPlayer;

import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.0 slice S3, "who you are": what a bot takes from a chat without the LLM. Every fact comes from words that were
 * actually said, matched against a small vocabulary of 2009 MapleStory things (PQs, bosses, wanted items, job
 * advances, towns), so a scripted bot never invents a memory:
 *
 * <ul>
 *   <li>the <b>last topic</b>: the newest thing the chat named ("KPQ", "zhelm", "henesys");</li>
 *   <li>an <b>inside joke</b>: a short line one of them said that the other laughed at ("lol", "haha");</li>
 *   <li>the player's <b>goals</b> and wanted items ("saving for a zhelm" -> "wants zhelm", tag {@code item:zhelm};
 *   "trying for 4th job" -> tag {@code goal:4th job}), which slice S6 follows up a day later.</li>
 * </ul>
 * Pure apart from {@link #remember}, which stores them through {@link BotMemory}.
 */
public final class MemoryFacts {

    public enum Cat { ITEM, GOAL, BOSS, PQ, PLACE, ACTIVITY }

    /** One thing a chat can be about: what matches it, its short name, and what kind of thing it is. */
    record Term(Pattern pattern, String label, Cat cat) {
    }

    private static Term t(String regex, String label, Cat cat) {
        return new Term(Pattern.compile("\\b(?:" + regex + ")\\b"), label, cat);
    }

    /** Items first, so "zak helm" is the helmet and not the boss. */
    static final List<Term> TERMS = List.of(
            t("z ?helm|zakum helm(?:et)?|zak helm(?:et)?", "zhelm", Cat.ITEM),
            t("(?:ht|horntail) (?:neck|necklace|pendant)", "ht necklace", Cat.ITEM),
            t("pink adv(?:enturer)? cape", "pink adventurer cape", Cat.ITEM),
            t("ilbis?|ilbi stars?", "ilbis", Cat.ITEM),
            t("subis?|subi stars?", "subis", Cat.ITEM),
            t("steel(?:y|ies)", "steelies", Cat.ITEM),
            t("chaos scrolls?", "chaos scrolls", Cat.ITEM),
            t("white scrolls?", "white scrolls", Cat.ITEM),
            t("maple (?:claw|bow|staff|sword|axe|wand|crossbow|xbow|katara|gun|knuckle|weapon)s?", "maple weapon", Cat.ITEM),
            t("bwg|brown work gloves?", "brown work gloves", Cat.ITEM),
            t("(?:a |my )?mount", "a mount", Cat.ITEM),
            t("4th job|fourth job|4th adv(?:ancement)?|4th job adv(?:ancement)?", "4th job", Cat.GOAL),
            t("3rd job|third job|3rd adv(?:ancement)?", "3rd job", Cat.GOAL),
            t("2nd job|second job|2nd adv(?:ancement)?", "2nd job", Cat.GOAL),
            t("job adv(?:ance|ancement)?", "a job advance", Cat.GOAL),
            t("zak|zakum", "zakum", Cat.BOSS),
            t("ht|horntail", "horntail", Cat.BOSS),
            t("pap|papulatus", "papulatus", Cat.BOSS),
            t("pink ?bean", "pink bean", Cat.BOSS),
            t("kpq|kerning pq", "KPQ", Cat.PQ),
            t("lpq|ludi pq", "LPQ", Cat.PQ),
            t("opq|orbis pq", "OPQ", Cat.PQ),
            t("hpq|henesys pq", "HPQ", Cat.PQ),
            t("gpq|guild pq|guild quest", "GPQ", Cat.PQ),
            t("apq|amoria pq", "APQ", Cat.PQ),
            t("mpq|magatia pq", "MPQ", Cat.PQ),
            t("ppq|pirate pq", "PPQ", Cat.PQ),
            t("epq|ellin pq", "EPQ", Cat.PQ),
            t("cwk(?:pq)?|crimsonwood", "CWKPQ", Cat.PQ),
            t("cpq|carnival pq|monster carnival", "CPQ", Cat.PQ),
            t("party quests?|pqs?", "PQs", Cat.PQ),
            t("henesys|henny", "henesys", Cat.PLACE),
            t("ellinia", "ellinia", Cat.PLACE),
            t("perion", "perion", Cat.PLACE),
            t("kerning(?: city)?", "kerning", Cat.PLACE),
            t("lith(?: harbor)?", "lith harbor", Cat.PLACE),
            t("sleepywood", "sleepywood", Cat.PLACE),
            t("orbis", "orbis", Cat.PLACE),
            t("el ?nath", "el nath", Cat.PLACE),
            t("ludi(?:brium)?", "ludi", Cat.PLACE),
            t("aqua(?: ?road)?", "aqua road", Cat.PLACE),
            t("leafre", "leafre", Cat.PLACE),
            t("mu ?lung", "mu lung", Cat.PLACE),
            t("nlc|new leaf city", "NLC", Cat.PLACE),
            t("showa", "showa", Cat.PLACE),
            t("ariant", "ariant", Cat.PLACE),
            t("magatia", "magatia", Cat.PLACE),
            t("amoria", "amoria", Cat.PLACE),
            t("fm|free market", "the FM", Cat.PLACE),
            t("ant tunnel", "ant tunnel", Cat.PLACE),
            t("grind(?:ing)?|training|leveling|lvling", "grinding", Cat.ACTIVITY),
            t("fishing|fish", "fishing", Cat.ACTIVITY),
            t("omok|match cards", "omok", Cat.ACTIVITY),
            t("scroll(?:ing|ed)", "scrolling", Cat.ACTIVITY),
            t("wedding|married|marry", "weddings", Cat.ACTIVITY),
            t("guilds?", "guilds", Cat.ACTIVITY));

    /** "lvl 70", "level 120": a level goal. */
    private static final Pattern LEVEL = Pattern.compile("\\b(?:lv|lvl|level)\\s?(\\d{2,3})\\b");
    /** The player says they are after something; the thing is in the words that follow. */
    private static final Pattern WANT = Pattern.compile("\\b(?:want(?:s|ing)?|wanna|need(?:s)?|saving(?: up)? for|"
            + "trying (?:to get|for|to reach|to hit)|going for|farming(?: for)?|grinding (?:for|to)|lf|looking for|"
            + "hoping (?:for|to get)|gonna get|working (?:on|towards?)|almost|close to)\\b(.{0,40})");
    private static final Pattern NEGATED = Pattern.compile("\\b(?:don'?t|do not|dont|never|not|no)\\s+(?:\\w+\\s+)?$");
    private static final Pattern LAUGH = Pattern.compile("^\\W*(?:l+o+l+|lmao+|lmfao+|ha(?:ha)+h?|he(?:he)+|rofl|xd|kek|ahaha+|lul)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE = Pattern.compile("@|https?:|www\\.|\\d{5,}");

    private MemoryFacts() {
    }

    static String norm(String line) {
        return line == null ? "" : line.toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    /** The first vocabulary term a line names, or null. */
    static Term termIn(String line) {
        String text = norm(line);
        Term best = null;
        int at = Integer.MAX_VALUE;
        for (Term term : TERMS) {
            Matcher m = term.pattern().matcher(text);
            if (m.find() && m.start() < at) {
                best = term;
                at = m.start();
            }
        }
        return best;
    }

    /** The last topic of a chat: what the newest line that names something names. */
    public static String topic(List<BotBrain.Turn> transcript) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            BotBrain.Turn turn = transcript.get(i);
            if (turn.role() == BotBrain.Role.SYSTEM) {
                continue;
            }
            Matcher level = LEVEL.matcher(norm(turn.content()));
            Term term = termIn(turn.content());
            if (term != null) {
                return term.label();
            }
            if (level.find()) {
                return "level " + level.group(1);
            }
        }
        return null;
    }

    /**
     * An inside joke: the newest short line (8-80 characters, two words or more) that the other side answered with a
     * laugh, safe to repeat (PG-13, no AI talk, nothing private). Null when nobody laughed.
     */
    public static String insideJoke(List<BotBrain.Turn> transcript) {
        List<BotBrain.Turn> chat = new ArrayList<>();
        for (BotBrain.Turn turn : transcript) {
            if (turn.role() != BotBrain.Role.SYSTEM) {
                chat.add(turn);
            }
        }
        for (int i = chat.size() - 2; i >= 0; i--) {
            BotBrain.Turn said = chat.get(i);
            BotBrain.Turn reply = chat.get(i + 1);
            if (said.role() == reply.role() || !LAUGH.matcher(reply.content().trim()).find()) {
                continue;
            }
            String line = said.content().trim();
            if (line.length() < 8 || line.length() > 80 || line.split("\\s+").length < 2
                    || LAUGH.matcher(line).find() || PRIVATE.matcher(line).find()
                    || !ContentGuard.ok(line) || ContentGuard.aiTalk(line)) {
                continue;
            }
            return line;
        }
        return null;
    }

    /** A goal or wanted item the player named: the note to keep and its follow-up tag. */
    public record Goal(String note, String tag) {
    }

    /** The goals and wanted items in the player's lines (never the bot's), at most one per tag. */
    public static List<Goal> goals(List<BotBrain.Turn> transcript) {
        List<Goal> out = new ArrayList<>();
        for (BotBrain.Turn turn : transcript) {
            if (turn.role() != BotBrain.Role.USER) {
                continue;
            }
            Goal g = goalIn(turn.content());
            if (g != null && out.stream().noneMatch(o -> o.tag().equals(g.tag()))) {
                out.add(g);
            }
        }
        return out;
    }

    /** The goal one line names ("im saving for a zhelm" -> wants zhelm / item:zhelm), or null. */
    static Goal goalIn(String line) {
        String text = norm(line);
        Matcher want = WANT.matcher(text);
        while (want.find()) {
            if (NEGATED.matcher(text.substring(0, want.start())).find()) {
                continue; // "i dont want zhelm"
            }
            String tail = want.group(1);
            Matcher level = LEVEL.matcher(tail);
            Term term = termIn(tail);
            if (term != null) {
                switch (term.cat()) {
                    case ITEM -> {
                        return new Goal("wants " + term.label(), "item:" + term.label());
                    }
                    case GOAL -> {
                        return new Goal("trying for " + term.label(), "goal:" + term.label());
                    }
                    case BOSS -> {
                        return new Goal("wants to do " + term.label(), "goal:" + term.label());
                    }
                    default -> {
                    }
                }
            }
            if (level.find()) {
                return new Goal("trying for level " + level.group(1), "goal:level " + level.group(1));
            }
        }
        return null;
    }

    /** The follow-up tag a note (an LLM chat note too) carries: its goal's tag, or "". */
    public static String tagOf(String note) {
        Goal g = goalIn(note);
        return g == null ? "" : g.tag();
    }

    /**
     * Stores what the chat gave: the topic slot, the joke slot (only when a new joke was found) and one GOAL note per
     * goal the pair does not already hold. How many things were written.
     */
    public static int remember(int botId, int charId, List<BotBrain.Turn> transcript, long now) {
        int written = 0;
        String topic = topic(transcript);
        if (topic != null && BotMemory.setSlot(botId, charId, BotMemory.Kind.TOPIC, topic, now) != null) {
            written++;
        }
        String joke = insideJoke(transcript);
        if (joke != null && BotMemory.setSlot(botId, charId, BotMemory.Kind.JOKE, joke, now) != null) {
            written++;
        }
        for (Goal g : goals(transcript)) {
            if (BotMemory.episode(botId, charId, BotMemory.Kind.GOAL, g.note(), 4, g.tag(), now) != null) {
                written++;
            }
        }
        return written;
    }
}
