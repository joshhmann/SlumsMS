package soloMapling.ArtificialPlayer.BotPartySystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ticket 14: short orders to the bots in your party, typed in chat - "jason follow", "1 stop", "fire grind", or
 * just "grind" for all of them. Ported from cosmic_agents' {@code AgentCommandParser} (a target token - full name,
 * name prefix or slot number - then the command), with the command words of a party companion and a looser fit
 * for how players type: the target may also come last ("stop 1"), a name may be matched by a piece of it ("fire"
 * for xFirex), and a line only counts as an order when it is the order and nothing more ("jason follow me pls"
 * yes, "jason follow the money lol" no) - anything else stays natural chat.
 *
 * <p>Pure: it knows the targets it is given (the player's party bots, in slot order) and nothing else.
 */
public final class BotCommandParser {

    /** What the bots are told to do. */
    public enum Verb {
        /** Follow me / come here: a stationed bot becomes a follower again, a staying one walks again. */
        FOLLOW,
        /** Stop / stay / wait: a follower stops where it is until told to follow. */
        STAY,
        /** Grind / train here: a follower stations on this map and trains. */
        GRIND
    }

    /** One party bot that can be told: its id and name, at its slot (1-based position in the list). */
    public record Target(int id, String name) {
    }

    /**
     * A parsed order. {@code targets} is who it is for (every bot for an untargeted order); when the name matched
     * more than one bot, {@code targets} is empty and {@code ambiguous} lists the candidates.
     */
    public record Order(Verb verb, List<Target> targets, List<Target> ambiguous, boolean everyone) {
        public boolean isAmbiguous() {
            return targets.isEmpty() && !ambiguous.isEmpty();
        }
    }

    /** cosmic_agents' tuning: a prefix needs two letters, a slot goes up to five (a party has six seats). */
    static final int MIN_PREFIX = 2;
    static final int MIN_PIECE = 3;
    static final int MAX_SLOT = 5;

    private static final Map<String, Verb> VERBS = Map.ofEntries(
            Map.entry("follow", Verb.FOLLOW), Map.entry("fm", Verb.FOLLOW), Map.entry("come", Verb.FOLLOW),
            Map.entry("regroup", Verb.FOLLOW),
            Map.entry("stop", Verb.STAY), Map.entry("stay", Verb.STAY), Map.entry("wait", Verb.STAY),
            Map.entry("hold", Verb.STAY), Map.entry("halt", Verb.STAY),
            Map.entry("grind", Verb.GRIND), Map.entry("train", Verb.GRIND), Map.entry("farm", Verb.GRIND),
            Map.entry("hunt", Verb.GRIND), Map.entry("station", Verb.GRIND));

    /** Words that may ride along with the command word without making the line something else. */
    private static final Set<String> FILLER = Set.of("me", "here", "there", "with", "us", "back", "on", "up",
            "following", "a", "bit", "sec", "min", "now", "rn", "pls", "plz", "please", "ok", "okay", "k", "kk",
            "thx", "ty", "asap", "too", "again", "this", "map", "spot", "for", "moment");
    /** Words before an order that are only a greeting ("hey jason follow", "ok grind"). */
    private static final Set<String> LEAD_IN = Set.of("hey", "yo", "oi", "ok", "okay", "k", "kk", "alright", "pls",
            "plz", "please", "now", "lets");
    /** Everyday words never read as a piece of a bot's name ("we grind" is not an order to Wendy). */
    private static final Set<String> NOT_A_NAME = Set.of("we", "u", "you", "ya", "yo", "i", "im", "me", "lets",
            "to", "can", "should", "gonna", "imma", "pls", "plz", "so", "and", "then", "just", "dont", "do", "no",
            "not", "ok", "okay");
    /** Target words that mean every party bot. */
    private static final Set<String> EVERYONE = Set.of("all", "everyone", "everybody", "guys", "yall", "team",
            "party", "bots");

    private BotCommandParser() {
    }

    /**
     * Reads a chat line as an order to the given party bots.
     *
     * @param slots the player's party bots in slot order (slot 1 first)
     * @return the order, or empty when the line is not one (it stays natural chat)
     */
    public static Optional<Order> parse(String line, List<Target> slots) {
        if (line == null || slots == null || slots.isEmpty()) {
            return Optional.empty();
        }
        List<String> words = new ArrayList<>(Arrays.asList(
                line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")));
        words.removeIf(String::isEmpty);
        // A bot named like a lead-in word ("Hey") is still a name: only strip what is not one.
        while (words.size() > 1 && LEAD_IN.contains(words.get(0)) && exact(words.get(0), slots) == null) {
            words.remove(0);
        }
        if (words.isEmpty() || words.size() > 7) {
            return Optional.empty();
        }
        // "grind" / "follow me": every bot.
        Optional<Order> untargeted = command(words, 0, words.size())
                .map(verb -> new Order(verb, List.copyOf(slots), List.of(), true));
        if (untargeted.isPresent()) {
            return untargeted;
        }
        // "jason follow", "1 stop", "fire, grind here".
        Optional<Order> first = targeted(words.get(0), command(words, 1, words.size()), slots);
        if (first.isPresent()) {
            return first;
        }
        // "stop 1", "follow me jason".
        return targeted(words.get(words.size() - 1), command(words, 0, words.size() - 1), slots);
    }

    private static Optional<Order> targeted(String token, Optional<Verb> verb, List<Target> slots) {
        if (verb.isEmpty()) {
            return Optional.empty();
        }
        if (EVERYONE.contains(token) && exact(token, slots) == null) {
            return Optional.of(new Order(verb.get(), List.copyOf(slots), List.of(), true));
        }
        List<Target> matched = resolve(token, slots);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matched.size() == 1
                ? new Order(verb.get(), matched, List.of(), false)
                : new Order(verb.get(), List.of(), matched, false));
    }

    /** The command word at {@code from} followed by filler only, up to {@code to}. */
    private static Optional<Verb> command(List<String> words, int from, int to) {
        if (from >= to) {
            return Optional.empty();
        }
        Verb verb = VERBS.get(words.get(from));
        if (verb == null) {
            return Optional.empty();
        }
        for (int i = from + 1; i < to; i++) {
            if (!FILLER.contains(words.get(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(verb);
    }

    /**
     * Who a target token names, best match first: the full name, a slot number, a name prefix (two letters or
     * more), then a piece inside the name (three or more - "fire" for xFirex). Several bots at the first matching
     * tier is an ambiguity; none is no target.
     */
    static List<Target> resolve(String token, List<Target> slots) {
        Target named = exact(token, slots);
        if (named != null) {
            return List.of(named);
        }
        if (token.chars().allMatch(java.lang.Character::isDigit)) {
            if (token.length() > 1) {
                return List.of();
            }
            int slot = token.charAt(0) - '0';
            return slot >= 1 && slot <= Math.min(MAX_SLOT, slots.size()) ? List.of(slots.get(slot - 1)) : List.of();
        }
        if (token.length() < MIN_PREFIX || NOT_A_NAME.contains(token)) {
            return List.of();
        }
        List<Target> prefixed = new ArrayList<>();
        for (Target t : slots) {
            if (t.name().toLowerCase(Locale.ROOT).startsWith(token)) {
                prefixed.add(t);
            }
        }
        if (!prefixed.isEmpty() || token.length() < MIN_PIECE) {
            return prefixed;
        }
        List<Target> pieces = new ArrayList<>();
        for (Target t : slots) {
            if (t.name().toLowerCase(Locale.ROOT).contains(token)) {
                pieces.add(t);
            }
        }
        return pieces;
    }

    private static Target exact(String token, List<Target> slots) {
        for (Target t : slots) {
            if (t.name().equalsIgnoreCase(token)) {
                return t;
            }
        }
        return null;
    }

    /** The "which one?" a bot asks when a name matched several: "1 Jane / 2 Jason", by slot. */
    public static String whichOne(List<Target> ambiguous, List<Target> slots) {
        StringBuilder text = new StringBuilder("which one? ");
        for (int i = 0; i < ambiguous.size(); i++) {
            if (i > 0) {
                text.append(" / ");
            }
            text.append(slots.indexOf(ambiguous.get(i)) + 1).append(' ').append(ambiguous.get(i).name());
        }
        return text.toString();
    }
}
