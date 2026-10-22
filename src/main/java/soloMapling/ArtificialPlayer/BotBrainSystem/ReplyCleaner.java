package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a model's raw text into one chat line, or nothing. Thinking models leak their reasoning when the server
 * is not told to switch it off (a llama.cpp Qwen3 run spent its whole token budget on a visible
 * "Thinking Process:" block), and that must never reach the chat box.
 */
final class ReplyCleaner {

    /** MapleStory's general chat input caps a line at 70 characters. */
    static final int MAX_CHAT_LENGTH = 70;

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern UNCLOSED_THINK = Pattern.compile("(?is)<think>.*");
    private static final Pattern THINKING_HEADER = Pattern.compile("(?i)^\\s*(thinking process|thinking|reasoning)\\s*:");
    private static final Pattern SPEAKER_PREFIX = Pattern.compile("^[A-Za-z0-9]{3,12}\\s*:\\s+");

    private ReplyCleaner() {
    }

    // ── the voice check (ticket 37): replies that read like an assistant fall back to a scripted line ──

    private static final Pattern ASSISTANT = Pattern.compile("(?i)(\\bas an ai\\b|\\bi'?m an ai\\b|\\bi am an ai\\b"
            + "|language model|\\bai assistant\\b|\\bassistant\\b|\\bi'?d love to\\b|\\bi would love to\\b"
            + "|great question|feel free|happy to help|glad to help|how can i (help|assist)|let me know if|i'?m here to help"
            + "|\\bcertainly\\b|\\babsolutely[!,]|i (cannot|can'?t) (help|assist) with|i'?m (just )?a (bot|program|chatbot)"
            + "|\\bas a (player|gamer|fellow)\\b|\\bhope this helps\\b|\\bin conclusion\\b|\\badditionally\\b"
            + "|\\bhowever,|if (u|you) need anything|for asking|any other questions)");
    private static final Pattern MARKDOWN = Pattern.compile("(\\*\\*|__|^#+\\s|`|^>\\s|^\\s*[-*]\\s|\\*[^*\\s][^*]*\\*|\\[[^]]+]\\()");
    /** Real-world places, venues and anything after the game's era: a 2008 player in game would not bring these up. */
    private static final Pattern REAL_WORLD = Pattern.compile("(?i)\\b(austin|texas|sxsw|6th street|sixth street"
            + "|zilker|barton springs|rainey|south congress|tiktok|instagram|snapchat|discord|twitch|iphone|android"
            + "|smartphone|covid|pandemic|netflix|uber|lyft|spotify|chatgpt|openai|fortnite|among us|zoom call|wifi"
            + "|reddit|twitter|tweet|elon|crypto|bitcoin|nft)\\b");
    static final int MAX_RAW_LENGTH = 160;

    /**
     * Why a raw chat reply does not sound like a 2008 player, or null when it does: assistant phrasing, markdown,
     * emojis, a paragraph or several lines, real-world references.
     */
    static String voiceProblem(String raw) {
        if (raw == null) {
            return null;
        }
        String text = UNCLOSED_THINK.matcher(THINK_BLOCK.matcher(raw).replaceAll("")).replaceAll("").trim();
        long lines = text.lines().filter(l -> !l.isBlank()).count();
        if (lines > 2) {
            return "several lines";
        }
        String first = firstNonBlankLine(text).trim();
        if (first.length() > MAX_RAW_LENGTH) {
            return "paragraph";
        }
        if (ASSISTANT.matcher(text).find()) {
            return "assistant phrasing";
        }
        if (MARKDOWN.matcher(first).find()) {
            return "markdown";
        }
        if (emojis(text) > 1) {
            return "emojis";
        }
        if (REAL_WORLD.matcher(text).find()) {
            return "real-world reference";
        }
        return ContentGuard.problem(text); // 1.5.0 bot drama: PG-13 romance, game-only trash talk
    }

    /** A chat reply: the voice check, then {@link #clean}. */
    static Optional<String> chat(String raw) {
        if (voiceProblem(raw) != null) {
            return Optional.empty();
        }
        return clean(raw).map(ReplyCleaner::stripEmojis).map(String::trim).filter(l -> !l.isEmpty())
                .map(ReplyCleaner::dropFinalPeriod);
    }

    static int emojis(String text) {
        return (int) text.codePoints().filter(ReplyCleaner::isEmoji).count();
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || cp == 0xFE0F;
    }

    /** The v83 client cannot draw emojis: one that slipped through is dropped rather than shown as "?". */
    static String stripEmojis(String line) {
        StringBuilder out = new StringBuilder();
        line.codePoints().filter(cp -> !isEmoji(cp)).forEach(out::appendCodePoint);
        return out.toString().replaceAll("\\s{2,}", " ");
    }

    /** Chat lines end without a full stop; an ellipsis stays. */
    static String dropFinalPeriod(String line) {
        return line.endsWith(".") && !line.endsWith("..") ? line.substring(0, line.length() - 1) : line;
    }

    static Optional<String> clean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = THINK_BLOCK.matcher(raw).replaceAll("");
        text = UNCLOSED_THINK.matcher(text).replaceAll("");
        if (THINKING_HEADER.matcher(text).find()) {
            // Reasoning with no closing tag: nothing in it is a safe chat line.
            return Optional.empty();
        }
        String line = firstNonBlankLine(text).trim();
        line = SPEAKER_PREFIX.matcher(line).replaceFirst("");
        line = stripQuotes(line).trim();
        if (line.isEmpty()) {
            return Optional.empty();
        }
        if (line.length() > MAX_CHAT_LENGTH) {
            line = clampAtWord(line);
        }
        return Optional.of(line);
    }

    /**
     * Ticket 59's bot threads: several "Name: text" lines in one reply. Only the reasoning is stripped here; each line is
     * checked on its own by the thread code (voice, ContentGuard, length).
     */
    static Optional<String> lines(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = UNCLOSED_THINK.matcher(THINK_BLOCK.matcher(raw).replaceAll("")).replaceAll("").trim();
        return THINKING_HEADER.matcher(text).find() || text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** A memory note's limit: longer than a chat line, still one thought. */
    static final int MAX_NOTE_LENGTH = 140;
    static final int MAX_NOTES = 2;
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*\u2022]|\\d+[.)])\\s*");

    /**
     * Turns a note-writing reply into at most {@link #MAX_NOTES} lines of at most {@link #MAX_NOTE_LENGTH}
     * characters, joined by newlines; empty when the model says there is nothing to keep ("NONE") or leaked only
     * reasoning.
     */
    static Optional<String> notes(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = UNCLOSED_THINK.matcher(THINK_BLOCK.matcher(raw).replaceAll("")).replaceAll("");
        if (THINKING_HEADER.matcher(text).find()) {
            return Optional.empty();
        }
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String line : text.split("\\R")) {
            String note = stripQuotes(BULLET.matcher(line).replaceFirst("").trim()).trim();
            if (note.isEmpty() || note.toUpperCase().startsWith("NONE")) {
                continue;
            }
            if (note.length() > MAX_NOTE_LENGTH) {
                int cut = note.lastIndexOf(' ', MAX_NOTE_LENGTH);
                note = note.substring(0, cut > MAX_NOTE_LENGTH / 2 ? cut : MAX_NOTE_LENGTH).trim();
            }
            kept.add(note);
            if (kept.size() == MAX_NOTES) {
                break;
            }
        }
        return kept.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", kept));
    }

    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private static String stripQuotes(String line) {
        if (line.length() >= 2) {
            char first = line.charAt(0);
            char last = line.charAt(line.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”')) {
                return line.substring(1, line.length() - 1);
            }
        }
        return line;
    }

    private static final Pattern DANGLING = Pattern.compile("(?i)(\\s+(the|a|an|to|for|of|and|or|if|u|you|ur|your|my|at|in|on"
            + "|with|from|but|so|is|its|it's|some|any))+$");

    /** Cuts an over-long line at the last sentence break that keeps most of it, else at the last word. */
    private static String clampAtWord(String line) {
        for (int i = MAX_CHAT_LENGTH - 1; i > MAX_CHAT_LENGTH / 2; i--) {
            char c = line.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ',') {
                return line.substring(0, c == ',' ? i : i + 1).trim();
            }
        }
        int cut = line.lastIndexOf(' ', MAX_CHAT_LENGTH);
        String clamped = (cut > MAX_CHAT_LENGTH / 2 ? line.substring(0, cut) : line.substring(0, MAX_CHAT_LENGTH)).trim();
        // A cut mid-thought should not end on a dangling "the" or "to".
        return DANGLING.matcher(clamped).replaceAll("").replaceAll("[,;:]$", "").trim();
    }
}
