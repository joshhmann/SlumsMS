package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.qa.QaLog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * b248 (1.6.2): the two repeats that make a bot map sound canned.
 *
 * <ol>
 *   <li><b>Greeting, per bot and player.</b> A bot greets the same player at most once per {@link #GREETING_GAP_MS}.
 *       Before, every "hi" back was a greeting again: TEST transcripts show one bot greeting one player 6-31 times a
 *       day, many within minutes. 10 minutes: shorter than the social gate's 60-minute unsolicited-greet cooldown
 *       ({@code SocialGate.pairMinutes(GREET)}), so this never blocks a hello the gate would allow, and longer than
 *       the "hi / hey / sup" exchange that made the repeat visible.</li>
 *   <li><b>Same line, two bots, one map.</b> No two bots on one map say the same text (case, whitespace and
 *       punctuation ignored) within {@link #MAP_SAME_LINE_MS}. Before, nothing compared map chat across bots, so two
 *       bots reading the same bank row or the same pack line said it back to back. 15 minutes: a map's small talk
 *       cycles in minutes, so the same sentence twice in a quarter hour is visibly scripted, while a longer window
 *       would starve popular maps of shared lines that players do repeat.</li>
 * </ol>
 *
 * <p>A repeat is dropped, not replaced: picking again is the pickers' job (the bank re-rolls on its own), and dropping
 * keeps this class free of any dependency on the packs.
 */
public final class RepeatGuard {
    private static final Logger log = LoggerFactory.getLogger(RepeatGuard.class);

    /** A bot greets the same player at most this often. */
    public static final long GREETING_GAP_MS = TimeUnit.MINUTES.toMillis(10);

    /** Two bots on one map do not say the same line within this. */
    public static final long MAP_SAME_LINE_MS = TimeUnit.MINUTES.toMillis(15);

    /**
     * Lines shorter than this (case, spaces and punctuation removed) are interjections - "lol", "gz", "ya", "brb",
     * "o/" - where a repeat is how people talk. Comparing those would take the life out of a busy map, so the
     * same-line rule starts at five characters ("gz man", "nice one").
     */
    static final int MIN_COMPARED_CHARS = 5;

    private static final int MAX_PAIRS = 20_000;
    private static final int MAX_LINES_PER_MAP = 400;

    /** (botId, playerId) -> when that bot last greeted that player. */
    private static final Map<Long, Long> greets = new ConcurrentHashMap<>();

    /** "map:line" pairs already reported as dropped, so a repeat says so once and not on every rerun. */
    private static final Set<String> noted = ConcurrentHashMap.newKeySet();
    private static final int NOTED_CAP = 4_000;

    /** mapId -> the lines said on it lately, oldest first. */
    private static final Map<Integer, Deque<MapLine>> lines = new ConcurrentHashMap<>();

    private record MapLine(String key, int botId, long at) {
    }

    private RepeatGuard() {
    }

    // ── 1. greetings ──

    /** Whether {@code bot} greeted {@code player} less than {@link #GREETING_GAP_MS} ago (never records). */
    public static boolean greetedRecently(Character bot, Character player) {
        return greetedRecently(bot == null ? 0 : bot.getId(), player == null ? 0 : player.getId(),
                System.currentTimeMillis());
    }

    static boolean greetedRecently(int botId, int playerId, long now) {
        Long at = greets.get(pair(botId, playerId));
        return at != null && now - at < GREETING_GAP_MS;
    }

    /** The greeting went out: hold the next one for {@link #GREETING_GAP_MS}. */
    public static void noteGreeting(Character bot, Character player) {
        if (bot == null || player == null) {
            return;
        }
        noteGreeting(bot.getId(), player.getId(), System.currentTimeMillis());
    }

    static void noteGreeting(int botId, int playerId, long at) {
        prunedGreets(at);
        greets.put(pair(botId, playerId), at);
    }

    private static void prunedGreets(long now) {
        if (greets.size() < MAX_PAIRS) {
            return;
        }
        greets.entrySet().removeIf(e -> now - e.getValue() >= GREETING_GAP_MS);
        if (greets.size() >= MAX_PAIRS) {
            greets.clear();
        }
    }

    private static long pair(int botId, int playerId) {
        return ((long) botId << 32) | (playerId & 0xffffffffL);
    }

    // ── 2. the same line twice on a map ──

    /**
     * Whether {@code bot} may say {@code text} on its map now: false when another bot already said the same text
     * there within {@link #MAP_SAME_LINE_MS}. Records the line when it may be said.
     */
    public static boolean claimMapLine(Character bot, String text) {
        if (bot == null || text == null) {
            return true;
        }
        String key = normalise(text);
        if (key.length() < MIN_COMPARED_CHARS) {
            return true; // an interjection or punctuation only: nothing worth comparing
        }
        int mapId = bot.getMapId();
        boolean ok = claimMapLine(mapId, bot.getId(), key, System.currentTimeMillis());
        if (!ok) {
            noteDrop(mapId, key, text);
        }
        return ok;
    }

    static boolean claimMapLine(int mapId, int botId, String normalised, long now) {
        if (normalised.length() < MIN_COMPARED_CHARS) {
            return true; // an interjection or punctuation only: nothing worth comparing
        }
        Deque<MapLine> q = lines.computeIfAbsent(mapId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst().at() >= MAP_SAME_LINE_MS) {
                q.pollFirst();
            }
            for (MapLine l : q) {
                if (l.botId() != botId && l.key().equals(normalised)) {
                    return false;
                }
            }
            q.addLast(new MapLine(normalised, botId, now));
            while (q.size() > MAX_LINES_PER_MAP) {
                q.pollFirst();
            }
            return true;
        }
    }

    /** Case, whitespace and punctuation ignored: "gz man!" and "GZ MAN" are the same line. */
    static String normalise(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Reports a dropped repeat once per map and line (never per send: a loop must not flood the log). */
    private static void noteDrop(int mapId, String key, String text) {
        String id = mapId + ":" + key;
        if (noted.size() >= NOTED_CAP && !noted.contains(id)) {
            return;
        }
        if (!noted.add(id)) {
            return;
        }
        String shown = text.length() <= 120 ? text : text.substring(0, 117) + "...";
        String line = "REPEAT GUARD map " + mapId + " dropped a line another bot said: " + shown;
        if (QaLog.on(QaLog.Topic.CONVO)) {
            QaLog.map(QaLog.Topic.CONVO, mapId, line);
        }
        log.info(line);
    }

    /** Tests only: forget every recorded greeting and line. */
    public static void forgetForTest() {
        greets.clear();
        lines.clear();
        noted.clear();
    }
}
