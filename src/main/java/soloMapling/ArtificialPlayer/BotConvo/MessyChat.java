package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.qa.QaLog;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 1.6.0 player likeness (audit #8/#7, owner-approved): the last pass over every line a bot types in map chat, a
 * whisper or buddy chat, so it reads like a 2009 player typed it - mostly lowercase, u/ur/ty/np/brb/k, a dropped full
 * stop, now and then a typo, rarely a word in caps for emphasis.
 *
 * <p>Each bot has one style for life, from its roster slot: {@link Style#TIDY} (every PROPER-voiced bot and a few
 * more: the line goes out untouched), {@link Style#MESSY} (most) or {@link Style#SLOPPY}. The same bot and line always
 * come out the same (the seed is the slot and the line), so a bot is recognisable.
 *
 * <p>It only ever lowercases, shortens and drops: it adds no words, so it cannot make a line ruder or make a bot say
 * it is an AI. It never touches a token that carries a number or a symbol (prices, levels, "S>", "50k", "1.2m"), a
 * capitalised word inside a sentence (item, map and player names: "Brown Work Gloves", "Henesys"), a word in all caps
 * ("KPQ", "HP"), anything inside quotes, or a word in the protect set (the names of the players on the map). A
 * capitalised word that starts a sentence is lowered only when it is a common English word ("Hey", "What", "Thanks").
 *
 * <p>Switch: {@code -Dsolomapling.messyChat=false}.
 */
public final class MessyChat {

    public static final String PROP = "solomapling.messyChat";

    public enum Style { TIDY, MESSY, SLOPPY }

    /** Phrases first, so "thank you" wins over "you". Lower-case keys; applied on word boundaries. */
    private static final List<Map.Entry<String, String>> PHRASES = List.of(
            Map.entry("be right back", "brb"), Map.entry("thank you", "ty"), Map.entry("thanks a lot", "ty"),
            Map.entry("no problem", "np"), Map.entry("i don't know", "idk"), Map.entry("i dont know", "idk"),
            Map.entry("oh my god", "omg"), Map.entry("right now", "rn"), Map.entry("got to go", "gtg"), Map.entry("to be honest", "tbh"),
            Map.entry("in my opinion", "imo"), Map.entry("by the way", "btw"), Map.entry("laughing out loud", "lol"));
    private static final Map<String, String> WORDS = ordered(
            "you", "u", "your", "ur", "you're", "ur", "youre", "ur", "are", "r", "okay", "k", "ok", "k",
            "please", "pls", "thanks", "thx", "because", "cuz", "people", "ppl", "really", "rly", "though", "tho",
            "i'm", "im", "don't", "dont", "can't", "cant", "won't", "wont", "it's", "its", "that's", "thats",
            "what's", "whats", "didn't", "didnt", "doesn't", "doesnt", "isn't", "isnt", "i'll", "ill", "i've", "ive",
            "wasn't", "wasnt", "haven't", "havent", "yeah", "ya", "yes", "ya", "what", "wat", "sorry", "sry",
            "probably", "prob", "something", "smth", "tonight", "tonite", "level", "lvl", "about", "abt");
    /** Words a capitalised sentence start may be lowered from (anything else might be a name or an item). */
    private static final Set<String> COMMON = Set.of(
            "a", "an", "the", "i", "i'm", "im", "i'll", "i've", "i'd", "hey", "hi", "hello", "yo", "sup", "what", "where",
            "when", "why", "how", "who", "which", "do", "does", "did", "can", "could", "would", "will", "should", "is",
            "are", "was", "were", "am", "have", "has", "had", "yes", "yeah", "ya", "no", "nope", "nah", "ok", "okay",
            "thanks", "thank", "thx", "ty", "np", "sure", "lol", "haha", "omg", "wow", "nice", "cool", "good", "great",
            "gg", "gz", "grats", "congrats", "sorry", "sry", "please", "pls", "anyone", "someone", "selling", "buying",
            "looking", "need", "want", "going", "gonna", "just", "so", "but", "and", "or", "if", "then", "that", "this",
            "it", "its", "it's", "my", "your", "you", "u", "we", "they", "he", "she", "me", "not", "dont", "don't", "cant",
            "can't", "oh", "well", "um", "uh", "hmm", "lets", "let's", "come", "go", "brb", "afk", "back", "be",
            "anybody", "everyone", "guys", "dude", "man", "bro", "same", "maybe", "probably", "really", "almost", "still",
            "there", "here", "too", "also", "all", "some", "any", "one", "first", "last", "time", "indeed", "actually",
            "anyway", "alright", "now", "later", "soon", "never", "always", "yep", "nvm", "idk", "tbh");
    private static final Set<String> EMPHASIS = Set.of("lol", "omg", "no", "yes", "wow", "nice", "gg", "ty", "wait", "help");
    private static final String VERBS = "(go|get|be|buy|sell|train|grind|do|make|try|see|party|quit|log|sleep|eat|kill"
            + "|hunt|farm|play|help|trade|join|leave|level|lvl|wait|check|come|start|stop)\\b";
    private static final Set<String> FILLERS = Set.of("well", "oh", "um", "uh", "so", "hmm");

    private MessyChat() {
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** On unless {@code -Dsolomapling.messyChat=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP, "true").trim());
    }

    /** This bot's typing style, stable for its whole life. PROPER typers stay tidy. */
    public static Style styleOf(int botSlot) {
        if (ConvoPersona.of(botSlot).voice() == ConvoPersona.Voice.PROPER) {
            return Style.TIDY;
        }
        int roll = new Random(botSlot * 0x9E3779B97F4A7C15L + 0x6D657373L).nextInt(100);
        return roll < 8 ? Style.TIDY : roll < 78 ? Style.MESSY : Style.SLOPPY;
    }

    /** The line as this bot types it in map chat: the names of the players on its map are left alone. */
    public static String forChat(Character bot, String line) {
        line = ChannelMentions.forSpeaker(bot, line); // a channel the world does not have becomes one it does
        if (bot == null || line == null || !enabled()) {
            return line;
        }
        Set<String> protect = new HashSet<>();
        protect.add(bot.getName());
        try {
            if (bot.getMap() != null) {
                for (Character c : bot.getMap().getAllPlayers()) {
                    if (c != null) {
                        protect.add(c.getName());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // a map mid-teardown: the capitalisation rule still keeps names safe
        }
        return logged(bot.getName(), line, apply(line, slotOf(bot.getId()), protect));
    }

    /** As {@link #forChat} for a whisper or buddy line from {@code senderName} (slot unknown: seeded by the name). */
    public static String forWhisper(Character sender, String senderName, Character to, String line) {
        line = ChannelMentions.forSpeaker(sender, line);
        if (line == null || !enabled()) {
            return line;
        }
        int slot = sender != null ? slotOf(sender.getId()) : (senderName == null ? 0 : senderName.hashCode());
        Set<String> protect = new HashSet<>();
        if (senderName != null) {
            protect.add(senderName);
        }
        if (to != null && to.getName() != null) {
            protect.add(to.getName());
        }
        return logged(senderName, line, apply(line, slot, protect));
    }

    private static int slotOf(int charId) {
        return charId - BotStore.BOT_ID_BASE;
    }

    private static String logged(String who, String line, String out) {
        if (!out.equals(line) && QaLog.on(QaLog.Topic.LIKENESS)
                && QaLog.every("messy:" + who, 20_000)) { // !qa lk-1, at most one sample per bot per 20 s
            QaLog.any(QaLog.Topic.LIKENESS, "MESSY " + who + ": \"" + line + "\" -> \"" + out + "\"");
        }
        return out;
    }

    /** The pure pass: {@code protect} words are left exactly as written (compared case-insensitively). */
    public static String apply(String line, int botSlot, Collection<String> protect) {
        if (line == null || line.isBlank() || !enabled()) {
            return line;
        }
        Style style = styleOf(botSlot);
        if (style == Style.TIDY) {
            return line;
        }
        boolean sloppy = style == Style.SLOPPY;
        Random rng = new Random(botSlot * 1_000_003L ^ line.hashCode() * 0x5DEECE66DL);
        Set<String> keep = new HashSet<>();
        if (protect != null) {
            for (String p : protect) {
                if (p != null && !p.isBlank()) {
                    keep.add(p.toLowerCase(Locale.ROOT));
                }
            }
        }

        String[] raw = line.trim().split(" +");
        boolean[] locked = new boolean[raw.length];
        boolean inQuote = false;
        boolean sentenceStart = true;
        for (int i = 0; i < raw.length; i++) {
            String t = raw[i];
            boolean opensQuote = t.startsWith("\"");
            if (opensQuote || inQuote) {
                locked[i] = true;
            }
            if (opensQuote && !(t.length() > 1 && t.endsWith("\""))) {
                inQuote = true;
            } else if (inQuote && t.endsWith("\"")) {
                inQuote = false;
            }
            if (!locked[i]) {
                locked[i] = isProtected(t, sentenceStart, keep);
            }
            if (!locked[i] && sentenceStart && java.lang.Character.isUpperCase(t.charAt(0))) {
                if (rng.nextDouble() < (sloppy ? 0.97 : 0.9)) {
                    raw[i] = t.toLowerCase(Locale.ROOT);
                }
            } else if (!locked[i] && (t.equals("I") || t.matches("I'(m|ll|ve|d)[,.!?]*"))) {
                if (rng.nextDouble() < (sloppy ? 0.97 : 0.85)) {
                    raw[i] = t.toLowerCase(Locale.ROOT);
                }
            }
            char last = t.charAt(t.length() - 1);
            sentenceStart = last == '.' || last == '!' || last == '?';
        }

        // Drop a leading filler ("Well, ..." / "oh ...") now and then: 2009 lines get to the point.
        int start = 0;
        if (raw.length > 2 && !locked[0] && FILLERS.contains(bare(raw[0]).toLowerCase(Locale.ROOT))
                && rng.nextDouble() < (sloppy ? 0.6 : 0.4)) {
            start = 1;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < raw.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(locked[i] ? "\u0000" + i + "\u0001" : raw[i]); // placeholders keep locked tokens out of the regexes
        }
        String text = sb.toString();
        double abbr = sloppy ? 0.95 : 0.75;
        for (Map.Entry<String, String> e : PHRASES) {
            if (rng.nextDouble() < abbr) {
                text = swap(text, e.getKey(), e.getValue(), keep);
            }
        }
        for (Map.Entry<String, String> e : WORDS.entrySet()) {
            if (rng.nextDouble() < abbr * (e.getValue().length() <= 2 ? 1.0 : 0.8)) {
                text = swap(text, e.getKey(), e.getValue(), keep);
            }
        }
        if (rng.nextDouble() < abbr) {
            // "going to"/"want to" + a verb only: "going to Henesys" and "going to the fm" stay as they are
            text = text.replaceAll("(?<![\\w'])going to (?=" + VERBS + ")", "gonna ")
                    .replaceAll("(?<![\\w'])want to (?=" + VERBS + ")", "wanna ");
        }
        if (rng.nextDouble() < (sloppy ? 0.9 : 0.6)) {
            text = text.replaceAll("(?<=[a-z])'(?=(s|t|m|re|ll|ve|d)\\b)", ""); // its, dont, im
        }
        // Punctuation: the full stop goes, commas often go, question marks sometimes.
        text = text.replaceAll("(?<=[a-z])\\.(?=\\s|$)(?!\\.)", "");
        if (rng.nextDouble() < (sloppy ? 0.8 : 0.5)) {
            text = text.replaceAll("(?<=[a-z]),(?=\\s)", "");
        }
        if (rng.nextDouble() < (sloppy ? 0.6 : 0.3)) {
            text = text.replaceAll("(?<=[a-z])\\?$", "");
        }
        text = typo(text, sloppy ? 0.10 : 0.04, keep, rng);
        if (rng.nextDouble() < (sloppy ? 0.05 : 0.025)) {
            text = emphasis(text, rng);
        }
        // Put the locked tokens back exactly as they were written.
        for (int i = 0; i < raw.length; i++) {
            if (locked[i]) {
                text = text.replace("\u0000" + i + "\u0001", raw[i]);
            }
        }
        text = text.replaceAll(" {2,}", " ").trim();
        if (text.isEmpty() || text.length() > line.length() + 2) {
            return line; // never longer than what the bot meant to say (a doubled letter at most)
        }
        return text;
    }

    /** A token that must reach the player exactly as written. */
    static boolean isProtected(String token, boolean sentenceStart, Set<String> keep) {
        String b = bare(token);
        if (b.isEmpty()) {
            return true;
        }
        if (keep.contains(b.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (java.lang.Character.isDigit(c) || c > 0x7f || "<>/@#$%&*+=_[](){}:;~^|\\\"".indexOf(c) >= 0) {
                return true; // prices, levels, stats, S>/B>, emoticons, links
            }
        }
        if (b.length() >= 2 && b.equals(b.toUpperCase(Locale.ROOT)) && !b.equals(b.toLowerCase(Locale.ROOT))) {
            return !b.equals("I'M") && !b.equals("I'LL"); // KPQ, HP, LF, GM, and shouting the bot meant
        }
        if (java.lang.Character.isUpperCase(b.charAt(0))) {
            if (b.equals("I") || b.matches("I'(m|ll|ve|d)")) {
                return false;
            }
            // a capital inside a sentence is a name (item, map, player); at the start only common words are lowered
            return !sentenceStart || !COMMON.contains(b.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static String bare(String token) {
        return token.replaceAll("^[\"'(]+|[\"'),.!?]+$", "");
    }

    private static String swap(String text, String from, String to, Set<String> keep) {
        if (keep.contains(from) || !text.toLowerCase(Locale.ROOT).contains(from)) {
            return text;
        }
        // whole words only, and only lower-case ones (a capital inside a sentence is a locked name anyway)
        return text.replaceAll("(?<![\\w'\\u0000])" + java.util.regex.Pattern.quote(from) + "(?![\\w'\\u0001])",
                java.util.regex.Matcher.quoteReplacement(to));
    }

    /** At most one typo: two inner letters swapped, a letter dropped or doubled, in a plain word of 4+ letters. */
    static String typo(String text, double rate, Set<String> keep, Random rng) {
        if (rng.nextDouble() >= rate) {
            return text;
        }
        String[] w = text.split(" ");
        int tries = w.length;
        for (int n = 0; n < tries; n++) {
            int i = rng.nextInt(w.length);
            String word = w[i];
            if (word.length() < 4 || !word.chars().allMatch(c -> c >= 'a' && c <= 'z')
                    || keep.contains(word) || NO_TYPO.contains(word)) {
                continue;
            }
            int at = 1 + rng.nextInt(word.length() - 2);
            w[i] = switch (rng.nextInt(3)) {
                case 0 -> word.substring(0, at) + word.charAt(at + 1) + word.charAt(at) + word.substring(at + 2);
                case 1 -> word.substring(0, at) + word.substring(at + 1);
                default -> word.substring(0, at) + word.charAt(at) + word.substring(at);
            };
            return String.join(" ", w);
        }
        return text;
    }

    /** Words whose misspelling would read as a different word to a player; never typo'd. */
    private static final Set<String> NO_TYPO = Set.of("mesos", "meso", "scroll", "scrolls", "potions");

    private static String emphasis(String text, Random rng) {
        String[] w = text.split(" ");
        for (int n = 0; n < w.length; n++) {
            int i = rng.nextInt(w.length);
            if (EMPHASIS.contains(w[i])) {
                w[i] = w[i].toUpperCase(Locale.ROOT);
                return String.join(" ", w);
            }
        }
        return text;
    }
}
