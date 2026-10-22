package soloMapling.ArtificialPlayer.BotConvo;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Ticket 59, owner decision 3: a light post-processor that makes every bot line (LLM, bank or scripted) read in the
 * bot's own voice ({@link ConvoPersona.Voice}): casing, abbreviations, the voice's laugh, a catchphrase now and then,
 * and typos at the persona's rate. It never touches a protected word (a price, a map key word, a name), never adds
 * length past the chat limit, and is deterministic per bot and line, so the same line always comes out the same.
 */
public final class VoiceStyler {

    static final int MAX = 70;

    // ordered (review fix: Map.of iterates in a random order per JVM, so a line styled differently per run)
    private static final Map<String, String> SHORT = ordered(
            Map.entry("you", "u"), Map.entry("your", "ur"), Map.entry("you're", "ur"), Map.entry("youre", "ur"),
            Map.entry("are", "r"), Map.entry("please", "pls"), Map.entry("thanks", "thx"), Map.entry("okay", "ok"),
            Map.entry("because", "cuz"), Map.entry("people", "ppl"), Map.entry("i'm", "im"), Map.entry("don't", "dont"),
            Map.entry("can't", "cant"), Map.entry("won't", "wont"), Map.entry("it's", "its"), Map.entry("that's", "thats"),
            Map.entry("what's", "whats"), Map.entry("i'll", "ill"), Map.entry("didn't", "didnt"),
            Map.entry("doesn't", "doesnt"), Map.entry("isn't", "isnt"), Map.entry("really", "rly"),
            Map.entry("though", "tho"), Map.entry("right now", "rn"));
    // ordered (review fix: Map.of iterates in a random order per JVM, so a line styled differently per run)
    private static final Map<String, String> LONG = ordered(
            Map.entry("u", "you"), Map.entry("ur", "your"), Map.entry("r", "are"), Map.entry("pls", "please"),
            Map.entry("plz", "please"), Map.entry("im", "I'm"), Map.entry("i", "I"), Map.entry("dont", "don't"),
            Map.entry("cant", "can't"), Map.entry("wont", "won't"), Map.entry("ill", "I'll"), Map.entry("thx", "thanks"),
            Map.entry("cuz", "because"), Map.entry("ppl", "people"), Map.entry("rn", "right now"),
            Map.entry("idk", "I don't know"), Map.entry("ya", "yeah"), Map.entry("didnt", "didn't"),
            Map.entry("thats", "that's"), Map.entry("whats", "what's"), Map.entry("its", "it's"));

    private VoiceStyler() {
    }

    @SafeVarargs
    private static Map<String, String> ordered(Map.Entry<String, String>... entries) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries) {
            m.put(e.getKey(), e.getValue());
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The line in this persona's voice. {@code protect}: words to leave exactly as they are (lower case). */
    public static String style(String line, ConvoPersona persona, int botSlot, Collection<String> protect) {
        if (line == null || line.isBlank() || persona == null || !ConvoSwitches.on("styler")) {
            return line;
        }
        Random rng = new Random(botSlot * 1_000_003L + line.hashCode());
        Set<String> keep = new HashSet<>();
        if (protect != null) {
            for (String p : protect) {
                if (p != null && !p.isBlank()) {
                    keep.add(p.toLowerCase(Locale.ROOT));
                }
            }
        }
        String out = switch (persona.voice()) {
            case LOWER -> lower(line, keep, rng);
            case PROPER -> proper(line, keep, rng);
            case LEET -> leet(line, keep, rng);
            case KOREAN -> korean(line, keep, rng);
            case SPANISH -> spanish(line, keep, rng);
            case TERSE -> terse(line);
        };
        out = typo(out, persona.typoRate(), keep, rng);
        if (!persona.catchphrases().isEmpty() && rng.nextDouble() < 0.06 + persona.chattiness() * 0.08) {
            String phrase = persona.catchphrases().get(rng.nextInt(persona.catchphrases().size()));
            String joined = out + " " + (persona.voice() == ConvoPersona.Voice.PROPER ? phrase : phrase.toLowerCase(Locale.ROOT));
            if (joined.length() <= MAX && !out.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT))) {
                out = joined;
            }
        }
        return out.length() > MAX ? line : out;
    }

    private static String words(String line, Map<String, String> table, Set<String> keep, Random rng, double chance) {
        String text = " " + line + " ";
        for (Map.Entry<String, String> e : table.entrySet()) {
            String from = e.getKey();
            if (keep.contains(from) || rng.nextDouble() >= chance) {
                continue;
            }
            text = text.replaceAll("(?i)(?<=[\\s,!?.])" + java.util.regex.Pattern.quote(from) + "(?=[\\s,!?.])",
                    java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return text.trim();
    }

    static String lower(String line, Set<String> keep, Random rng) {
        String t = words(line.toLowerCase(Locale.ROOT), SHORT, keep, rng, 0.85);
        t = t.replace("'", "");
        return stripStop(t);
    }

    static String proper(String line, Set<String> keep, Random rng) {
        String t = words(line, LONG, keep, rng, 1.0); // a proper typer always spells it out
        if (!t.isEmpty() && Character.isLetter(t.charAt(0)) && !keep.contains(firstWord(t))) {
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        t = t.replaceAll("\\bi\\b", "I");
        char last = t.isEmpty() ? ' ' : t.charAt(t.length() - 1);
        if (Character.isLetterOrDigit(last) && rng.nextDouble() < 0.5 && t.length() < MAX) {
            t += t.contains("?") || t.toLowerCase(Locale.ROOT).matches("^(who|what|where|when|why|how|do|does|can|are|is)\\b.*")
                    ? "?" : ".";
        }
        return t;
    }

    static String leet(String line, Set<String> keep, Random rng) {
        String t = lower(line, keep, rng);
        t = t.replaceAll("\\blol\\b", "lolz").replaceAll("\\bhaha\\b", "xD");
        if (rng.nextDouble() < 0.25) {
            String[] w = t.split(" ");
            int i = rng.nextInt(w.length);
            if (w[i].length() >= 3 && !keep.contains(w[i])) {
                w[i] = w[i].toUpperCase(Locale.ROOT);
                t = String.join(" ", w);
            }
        }
        if (rng.nextDouble() < 0.15) {
            String[] w = t.split(" ");
            int i = rng.nextInt(w.length);
            if (w[i].length() >= 4 && !keep.contains(w[i].toLowerCase(Locale.ROOT))) {
                w[i] = w[i].replace('e', '3').replace('o', '0');
                t = String.join(" ", w);
            }
        }
        if (rng.nextDouble() < 0.3 && !t.endsWith("!") && t.length() < MAX - 3) {
            t += rng.nextBoolean() ? " xD" : "!!!";
        }
        return t;
    }

    static String korean(String line, Set<String> keep, Random rng) {
        String t = lower(line, keep, rng);
        t = t.replaceAll("\\b(lol|haha|lmao)\\b", "kekeke");
        if (!t.contains("^^") && !t.contains("kekeke") && rng.nextDouble() < 0.35 && t.length() < MAX - 3) {
            t += " ^^";
        }
        return t;
    }

    static String spanish(String line, Set<String> keep, Random rng) {
        String t = lower(line, keep, rng);
        t = t.replaceAll("\\b(lol|haha|lmao)\\b", "jaja");
        if (rng.nextDouble() < 0.5) {
            t = t.replaceAll("\\bpls\\b", "porfa");
        }
        if (rng.nextDouble() < 0.3) {
            t = t.replaceAll("^(wat|what)\\b", "q");
        }
        if (rng.nextDouble() < 0.12 && t.length() < MAX - 6 && !t.contains("amigo") && !t.contains("bro")) {
            t += rng.nextBoolean() ? " amigo" : " bro";
        }
        return t;
    }

    static String terse(String line) {
        String t = line.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[.!,]+", "").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^(oh |well |hey |lol |haha |um |uh )+", "").trim();
        return t.isEmpty() ? line.toLowerCase(Locale.ROOT) : t;
    }

    /** At most one typo: two inner letters swapped, or a letter dropped, in a word of 4+ letters not protected. */
    static String typo(String line, double rate, Set<String> keep, Random rng) {
        String[] w = line.split(" ");
        for (int i = 0; i < w.length; i++) {
            String word = w[i];
            if (word.length() < 4 || !word.chars().allMatch(Character::isLetter)
                    || keep.contains(word.toLowerCase(Locale.ROOT)) || rng.nextDouble() >= rate) {
                continue;
            }
            int at = 1 + rng.nextInt(word.length() - 2);
            w[i] = rng.nextBoolean()
                    ? word.substring(0, at) + word.charAt(at + 1) + word.charAt(at) + word.substring(at + 2)
                    : word.substring(0, at) + word.substring(at + 1);
            return String.join(" ", w);
        }
        return line;
    }

    private static String stripStop(String t) {
        return t.endsWith(".") && !t.endsWith("..") ? t.substring(0, t.length() - 1) : t;
    }

    private static String firstWord(String t) {
        int sp = t.indexOf(' ');
        return (sp < 0 ? t : t.substring(0, sp)).toLowerCase(Locale.ROOT);
    }
}
