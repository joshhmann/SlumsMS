package soloMapling.ArtificialPlayer.BotConvo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 1.6.1 (ticket 114, owner 2026-10-27): a bot's line carries what it meant. Whatever the speaking bot's brain chose
 * (a {@link TalkIntent}, or a thread's topic) is noted here with the line, so a listener that hears it reads the intent
 * instead of running the chat classifier: bot lines never go to ConvoSense / Decide. The chat brain's door
 * ({@code BotConversation.ask}) refuses a bot speaker outright and counts it here ({@link #keptFromClassifier}); with
 * the switch off it counts {@link #classified} instead, so a check can prove the number is 0 with it on.
 *
 * <p>Bounded: one entry per speaking bot (its last line), dropped after {@link #TTL_MS}.
 */
public final class BotLineIntents {

    static final long TTL_MS = 60_000;

    /** A bot's last line and what it meant. */
    public record Said(String text, String intent, int toId, long at) {
    }

    private static final Map<Integer, Said> last = new ConcurrentHashMap<>();
    private static final LongAdder noted = new LongAdder();
    private static final LongAdder kept = new LongAdder();
    private static final LongAdder classified = new LongAdder();

    private BotLineIntents() {
    }

    /** The speaking bot said {@code text}, meaning {@code intent} (to {@code toId}, 0: everyone). */
    public static void note(int botId, String text, String intent, int toId, long now) {
        last.put(botId, new Said(text, intent, toId, now));
        noted.increment();
        if (last.size() > 8192) {
            last.values().removeIf(s -> now - s.at() > TTL_MS);
        }
    }

    /** What a bot meant by its last line, or null (none, or older than the TTL). */
    public static Said of(int botId, long now) {
        Said s = last.get(botId);
        return s == null || now - s.at() > TTL_MS ? null : s;
    }

    /** A bot's line reached the chat brain and was kept from the classifier (the listener reads the intent). */
    public static void keptFromClassifier() {
        kept.increment();
    }

    /** A bot's line went to the classifier (only with the talk switch off). */
    public static void classifiedBotLine() {
        classified.increment();
    }

    /** Counters for the checks: lines noted with an intent, bot lines kept from the classifier, bot lines classified. */
    public static Map<String, Long> counts() {
        return Map.of("noted", noted.sum(), "keptFromClassifier", kept.sum(), "classifiedBotLines", classified.sum());
    }

    static void resetForTest() {
        last.clear();
        noted.reset();
        kept.reset();
        classified.reset();
    }
}
