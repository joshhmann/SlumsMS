package soloMapling.Economy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 1.6.0 ticket 86: turns the words of a buy request into one item id, or refuses. Fuzzy but safe: the rule is the one
 * nutnnut/Cosmic's megaphone-trade plan uses (an exact name, else exactly one hit, else reject; their
 * {@code BotMarketGrammar}, 722719702), tightened for free-form talk. A query matches an item when every query word
 * is a word of the item's name (plural endings ignored) and the query covers at least half of the name's words, so
 * "claw" never picks "Claw for ATT 60%" but "60% glove dex" picks "Scroll for Gloves for DEX 60%". Two different
 * items that fit equally are ambiguous: no match, no offer.
 */
public final class MegaBuyMatcher {

    /** An item a bot could sell, by id and the name the server shows for it. */
    public record Candidate(int itemId, String name) {
    }

    public enum Outcome { MATCH, AMBIGUOUS, NONE }

    public record Result(Outcome outcome, int itemId, String name) {
        static final Result NONE = new Result(Outcome.NONE, 0, "");
        static final Result AMBIGUOUS = new Result(Outcome.AMBIGUOUS, 0, "");

        public boolean matched() {
            return outcome == Outcome.MATCH;
        }
    }

    private static final Set<String> FILLER = Set.of("for", "of", "the", "a", "an", "and");

    private MegaBuyMatcher() {
    }

    /** The one item {@code words} names among {@code candidates}. */
    public static Result match(List<String> words, List<Candidate> candidates) {
        if (words == null || words.isEmpty() || candidates == null || candidates.isEmpty()) {
            return Result.NONE;
        }
        List<String> query = new ArrayList<>();
        for (String w : words) {
            String n = stem(w);
            if (!n.isEmpty() && !query.contains(n)) {
                query.add(n);
            }
        }
        if (query.isEmpty()) {
            return Result.NONE;
        }
        Map<Integer, String> exact = new LinkedHashMap<>();
        Map<Integer, String> fuzzy = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            List<String> name = tokens(c.name());
            if (name.isEmpty()) {
                continue;
            }
            if (name.size() == query.size() && name.containsAll(query)) {
                exact.put(c.itemId(), c.name());
                continue;
            }
            if (name.containsAll(query) && query.size() * 2 >= name.size()) {
                fuzzy.put(c.itemId(), c.name());
            }
        }
        Map<Integer, String> hits = !exact.isEmpty() ? exact : fuzzy;
        if (hits.isEmpty()) {
            return Result.NONE;
        }
        if (hits.size() > 1) {
            return Result.AMBIGUOUS;
        }
        Map.Entry<Integer, String> e = hits.entrySet().iterator().next();
        return new Result(Outcome.MATCH, e.getKey(), e.getValue());
    }

    /** The words of an item name that carry meaning, lower case, plural endings cut. */
    static List<String> tokens(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        for (String w : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9% ]", " ").split("\\s+")) {
            String n = stem(w);
            if (!n.isEmpty() && !FILLER.contains(n) && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    static String stem(String w) {
        String s = w.toLowerCase(Locale.ROOT).trim();
        if (s.length() > 4 && s.endsWith("ies")) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
