package soloMapling.Economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.0 ticket 86: reads a player's megaphone as a trade request. Pure text, no game data, so it is unit tested
 * without WZ files. The shape of the grammar ({@code B>} / {@code S>} prefixes, a trailing price token with k/m
 * units, an {@code xN} quantity) follows nutnnut/Cosmic's {@code BotMarketGrammar} (722719702, price units
 * b00401fda); this version also takes the free wording players really use ("buying clean claw", "lf ilbis",
 * "wtb 60% glove dex"). AGPL-3.0, credited in the commit.
 * <p>
 * What it does not do is guess: a line that names two items, names none, or is not clearly a buy or a sell comes back
 * as {@link Kind#NONE}. Matching the words to an item is {@link MegaBuyMatcher}'s job.
 */
public final class MegaBuyParser {

    public enum Kind { BUY, SELL, NONE }

    /**
     * @param query the item words left after the verb, price and quantity are removed ("red whip", "60% glove dex")
     * @param qty   how many were asked for; 0 when the line did not say
     * @param price what the player offers (BUY) or asks (SELL) in mesos, or -1
     */
    public record Request(Kind kind, String query, List<String> words, int qty, long price, String raw) {
        public static final Request NONE = new Request(Kind.NONE, "", List.of(), 0, -1, "");
    }

    private static final Pattern BUY = Pattern.compile(
            "^\\W*(?:b\\s*>|wtb\\b|w2b\\b|buying\\b|buy\\b|lf\\b|lfs\\b|looking\\s+for\\b|lookin\\s+for\\b|lookin\\b|iso\\b|"
                    + "need\\b|wanted\\b|want\\b|anyone\\s+selling\\b)\\s*[:>.-]*\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELL = Pattern.compile(
            "^\\W*(?:s\\s*>|wts\\b|w2s\\b|selling\\b|sell\\b)\\s*[:>.-]*\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    // a party recruit is not a shopping list: "lf pt", "lf party", "lfp"
    private static final Pattern NOT_ITEM = Pattern.compile(
            "^(?:pt|party|p|pq|members?|guild|gf|bf|friends?|buddy|buddies|duo|trio|exp|eq|someone|anyone|ppl|people|"
                    + "healer|priest|cleric|bishop|mage|buffer|carry|run|rush|boss|help)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_TOKEN = Pattern.compile(
            "^(?:\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)(?:k|m|mil|mill|b|bil)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_X = Pattern.compile("^(?:x\\s*(\\d{1,4})|(\\d{1,4})\\s*x)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATOR = Pattern.compile("\\s(?:\\||//|--|pm\\b|pls pm\\b|whisper\\b|wisp\\b|dm\\b|msg\\b|"
            + "wtt\\b)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP = Set.of("for", "a", "an", "the", "my", "any", "some", "pls", "plz", "please",
            "clean", "cheap", "good", "nice", "only", "me", "offer", "offers", "budget", "ea", "each", "price", "at",
            "to", "of", "with", "one", "ur", "your", "pc", "now", "asap", "thx", "ty", "mesos", "meso", "coin", "per",
            "is", "this", "that", "item", "items", "can", "u", "you", "i", "im", "gimme", "give", "want", "need",
            "paying", "pay", "will", "willing", "up", "max", "negotiable", "nego", "neg", "fast", "quick", "tradable");

    private MegaBuyParser() {
    }

    /** A request in the player's words, or {@link Request#NONE}. */
    public static Request parse(String line) {
        if (line == null) {
            return Request.NONE;
        }
        String text = line.strip();
        if (text.isEmpty() || text.length() > 120) {
            return Request.NONE;
        }
        Kind kind;
        String rest;
        Matcher b = BUY.matcher(text);
        Matcher s = SELL.matcher(text);
        if (b.matches()) {
            kind = Kind.BUY;
            rest = b.group(1);
        } else if (s.matches()) {
            kind = Kind.SELL;
            rest = s.group(1);
        } else {
            return Request.NONE;
        }
        Matcher cut = SEPARATOR.matcher(" " + rest);
        if (cut.find()) {
            rest = (" " + rest).substring(0, cut.start()).strip();
        }
        // two items in one line are not guessed at
        if (rest.contains(",") && !rest.matches(".*\\d,\\d{3}.*") || rest.contains("/") || rest.contains("&")
                || rest.toLowerCase(Locale.ROOT).matches(".*\\b(and|or)\\b.*")) {
            return Request.NONE;
        }
        if (NOT_ITEM.matcher(rest.strip()).matches()) {
            return Request.NONE;
        }
        long price = -1;
        int qty = 0;
        List<String> words = new ArrayList<>();
        for (String raw : rest.toLowerCase(Locale.ROOT).replaceAll("[!?()\\[\\]\"'`~*]", " ").split("\\s+")) {
            String tok = raw.replaceAll("^[@$:;.-]+|[;:.]+$", "");
            if (tok.isEmpty()) {
                continue;
            }
            Matcher q = QTY_X.matcher(tok);
            if (q.matches()) {
                qty = Integer.parseInt(q.group(1) != null ? q.group(1) : q.group(2));
                continue;
            }
            if (isPrice(tok)) {
                price = priceOf(tok);
                continue;
            }
            if (STOP.contains(tok)) {
                continue;
            }
            words.add(tok);
        }
        // "2 red whips": a small bare number before words is a count
        if (qty == 0 && words.size() > 1 && words.get(0).matches("\\d{1,3}")) {
            qty = Integer.parseInt(words.remove(0));
        }
        if (words.isEmpty() || words.size() > 6) {
            return Request.NONE;
        }
        return new Request(kind, String.join(" ", words), List.copyOf(words), Math.max(0, qty), price, text);
    }

    /** A price token: has a unit (5m, 900k, 1.2b) or is 1,000 or more ("1,200,000", "500000"). */
    static boolean isPrice(String tok) {
        if (!PRICE_TOKEN.matcher(tok).matches()) {
            return false;
        }
        boolean unit = Character.isLetter(tok.charAt(tok.length() - 1));
        if (unit) {
            return true;
        }
        return priceOf(tok) >= 1000;
    }

    /** Mesos for a price token; -1 when it is not one or overflows the wallet. */
    static long priceOf(String tok) {
        String t = tok.toLowerCase(Locale.ROOT).replace(",", "");
        double mult = 1;
        if (t.endsWith("mil") || t.endsWith("bil")) {
            mult = t.endsWith("mil") ? 1_000_000 : 1_000_000_000;
            t = t.substring(0, t.length() - 3);
        } else if (t.endsWith("mill")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 4);
        } else if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("b")) {
            mult = 1_000_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double v = Double.parseDouble(t) * mult;
            return v > 0 && v <= 2_100_000_000d ? (long) v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
