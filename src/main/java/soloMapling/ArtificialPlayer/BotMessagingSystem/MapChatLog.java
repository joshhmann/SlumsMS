package soloMapling.ArtificialPlayer.BotMessagingSystem;

import server.maps.MapleMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * What was said in public on each map lately (owner report 2026-10-22: a bot asked "anyone from the east coast?",
 * the player answered "im from the east", and the bot that replied had no idea what was asked). One shared log per
 * map and channel holds every public line - players, the bots' LLM replies, scripted ambient and pack lines, thread
 * lines, bank lines - with the speaker's name and id. Every reply prompt shows it as a transcript, and a line that
 * names nobody is routed by {@link ChatRoute} (1.5.0 rc6; {@link #answeredBy} was the earlier rule).
 */
public final class MapChatLog {

    /** Lines kept per map. */
    public static final int LINES = 8;
    /** A line stays in the transcript this long. */
    public static final long KEEP_MS = TimeUnit.MINUTES.toMillis(3);
    /** A bot's question can be answered this long after it was asked. */
    public static final long QUESTION_MS = TimeUnit.SECONDS.toMillis(60);
    /** Any other bot line can be answered this long after it was said. */
    public static final long LINE_MS = TimeUnit.SECONDS.toMillis(30);
    /** The same speaker repeating the same text this soon is one line (a typed bubble re-sent letter by letter). */
    static final long SAME_LINE_MS = TimeUnit.SECONDS.toMillis(15);

    /** One public line. {@code bot} is whether a bot said it. */
    public record Line(int speakerId, String speaker, String text, long at, boolean bot) {
    }

    private static final Map<Long, Deque<Line>> byMap = new ConcurrentHashMap<>();

    private MapChatLog() {
    }

    static long key(int channel, int mapId) {
        return ((long) channel << 32) | (mapId & 0xffffffffL);
    }

    /** The channel a map belongs to (1 when it cannot be read). */
    public static int channelOf(MapleMap map) {
        try {
            return map == null || map.getChannelServer() == null ? 1 : map.getChannelServer().getId();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** A public line on a map. Blank lines are skipped; long ones are cut at 100 characters. */
    public static void record(int channel, int mapId, int speakerId, String speaker, String text, boolean bot, long now) {
        if (speaker == null || text == null || text.isBlank()) {
            return;
        }
        String said = text.trim();
        said = said.length() > 100 ? said.substring(0, 100) : said;
        Deque<Line> lines = byMap.computeIfAbsent(key(channel, mapId), k -> new ArrayDeque<>());
        synchronized (lines) {
            for (Line l : lines) {
                if (l.speakerId() == speakerId && l.text().equals(said) && now - l.at() <= SAME_LINE_MS) {
                    return;
                }
            }
            lines.addLast(new Line(speakerId, speaker, said, now, bot));
            while (lines.size() > LINES) {
                lines.removeFirst();
            }
        }
    }

    /** The lines of the last three minutes on a map, oldest first. */
    public static List<Line> recent(int channel, int mapId, long now) {
        Deque<Line> lines = byMap.get(key(channel, mapId));
        if (lines == null) {
            return List.of();
        }
        List<Line> out = new ArrayList<>();
        synchronized (lines) {
            for (Line l : lines) {
                if (now - l.at() <= KEEP_MS) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /**
     * The transcript for one bot's prompt: "Name: text" lines, oldest first, the bot's own lines marked
     * "Name (you): text". Empty when nothing was said.
     */
    public static String transcript(List<Line> lines, int selfId) {
        StringBuilder s = new StringBuilder();
        for (Line l : lines) {
            s.append(l.speaker()).append(l.speakerId() == selfId ? " (you)" : "").append(": ").append(l.text())
                    .append('\n');
        }
        return s.toString().trim();
    }

    /** The bot's own last line before the player's latest line, when the player's line came right after it. */
    public static Line ownLineBefore(List<Line> lines, int selfId, int playerId, long withinMs, long now) {
        int playerAt = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).speakerId() == playerId) {
                playerAt = i;
                break;
            }
        }
        int end = playerAt < 0 ? lines.size() : playerAt;
        for (int i = end - 1; i >= 0; i--) {
            Line l = lines.get(i);
            if (l.speakerId() == selfId) {
                return now - l.at() <= withinMs ? l : null;
            }
            if (l.speakerId() == playerId) {
                return null; // the player already spoke after it
            }
        }
        return null;
    }

    private static final Pattern QUESTION = Pattern.compile(
            "(?i).*(\\?|^(anyone|any1|who|what|wat|where|when|why|how|which|wanna|want|u |you |do |does |did |is |are |can |"
                    + "could |should |would |will |have |has |r u|ru )).*");
    private static final Set<String> STOP = Set.of("the", "and", "you", "your", "for", "are", "was", "has", "have",
            "not", "but", "its", "what", "who", "how", "that", "this", "with", "from", "lol", "lmao", "haha", "just",
            "like", "yeah", "yea", "any", "anyone", "too", "can", "got", "get", "all", "out", "one", "dont", "im",
            "ill", "ive", "they", "them", "their", "there", "then", "than", "when", "where", "why", "u", "ur", "ya");

    /** Whether a line asks something ("anyone from the east coast?", "wanna pt"). */
    public static boolean isQuestion(String text) {
        return text != null && QUESTION.matcher(text.trim()).matches();
    }

    /**
     * The bot a nameless line from this player most plausibly answers: among the bot lines said on the map before
     * it (a question in the last 60 s, any line in the last 30 s, at most the last four), the newest that shares a
     * word with it, else the newest question, else the newest line. Null when no bot line qualifies.
     */
    public static Line answeredBy(List<Line> lines, int playerId, String line, long now) {
        int end = lines.size();
        for (int i = lines.size() - 1; i >= 0; i--) {
            Line l = lines.get(i);
            if (l.speakerId() == playerId && l.text().equals(line == null ? "" : line.trim())) {
                end = i; // the player's own line was logged before routing: look before it
                break;
            }
        }
        List<Line> candidates = new ArrayList<>();
        for (int i = end - 1; i >= 0 && candidates.size() < 4; i--) {
            Line l = lines.get(i);
            if (!l.bot()) {
                continue;
            }
            long age = now - l.at();
            if (age <= LINE_MS || (age <= QUESTION_MS && isQuestion(l.text()))) {
                candidates.add(l);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Set<String> words = words(line);
        for (Line c : candidates) {
            for (String w : words(c.text())) {
                if (words.contains(w)) {
                    return c;
                }
            }
        }
        for (Line c : candidates) {
            if (isQuestion(c.text())) {
                return c;
            }
        }
        return candidates.get(0);
    }

    /** The words that carry meaning in a line (lowercase, 3+ letters, stopwords dropped). */
    public static Set<String> words(String text) {
        Set<String> out = new java.util.HashSet<>();
        if (text == null) {
            return out;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Test seam. */
    public static void resetForTest() {
        byMap.clear();
    }
}
