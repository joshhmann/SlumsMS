package soloMapling.ArtificialPlayer;

import client.Character;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 26: a bot greets a player it remembers like someone it knows. "Remembers" means it holds a note of its own
 * about them ({@link BotMemory}, not hearsay - recognising someone you only heard about is creepy) and none of its
 * notes makes it wary of them.
 *
 * <p>Two places use it. In a chat, the bot's first reply to such a player is told to greet them as an acquaintance
 * (the model may bring up one light remembered thing) and the scripted greeting says "oh hey again". And now and then
 * a roaming bot that notices such a player nearby says hi first ({@link #maybeGreetOnSight}) - rarely: once per bot
 * and player per few hours, never right after they last talked, never mid-chat.
 *
 * <p>System properties: {@code solomapling.chat.recognizeChance} (0.5), the chance a bot that may greet on sight
 * does; {@code solomapling.chat.recognizeAfterMin} (30), how long after the bot's newest note about the player it
 * may greet them on sight; {@code solomapling.chat.recognizeEveryHours} (6), how often at most per bot and player.
 */
public final class BotRecognition {

    static final List<String> HELLO_AGAIN = List.of("oh hey %s", "yo %s", "%s! hey", "heyy %s", "o hey its %s",
            "sup %s", "hey %s :)");
    static final List<String> HELLO_AGAIN_NO_NAME = List.of("oh hey again", "hey again", "yo long time", "o hey",
            "heyy wb");

    private static final Map<Long, Long> lastGreetOnSight = new ConcurrentHashMap<>();

    private BotRecognition() {
    }

    /** Whether the bot holds a note of its own about this player and is not wary of them. Reads memory. */
    public static boolean knows(int botSlot, int playerId) {
        if (soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.friend(BotStore.BOT_ID_BASE + botSlot, playerId)) {
            return true; // ticket 58: a friend is someone it knows, notes or not
        }
        try {
            return hasOwnNote(BotMemory.about(botSlot, playerId)) && !BotChatActions.wary(botSlot, playerId);
        } catch (RuntimeException e) {
            return false; // memory unreadable: a stranger
        }
    }

    /** A note the bot wrote itself that is not a grudge. */
    static boolean hasOwnNote(List<BotMemory.Note> notes) {
        return newestOwnNote(notes) > 0;
    }

    /** When the bot's newest own, non-grudge note about the player was written, or 0 when it has none. */
    static long newestOwnNote(List<BotMemory.Note> notes) {
        long newest = 0;
        for (BotMemory.Note note : notes) {
            if (note.source() == BotMemory.Source.DIRECT && !grudge(note.text())) {
                newest = Math.max(newest, Math.max(1, note.createdAt()));
            }
        }
        return newest;
    }

    private static boolean grudge(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return BotChatActions.WARY_MARKERS.stream().anyMatch(lower::contains);
    }

    /** A scripted "hey again" for a known player, sometimes with their name. */
    static String scriptedHello(String playerName, Random rng) {
        if (playerName == null || playerName.isBlank() || rng.nextDouble() < 0.4) {
            return HELLO_AGAIN_NO_NAME.get(rng.nextInt(HELLO_AGAIN_NO_NAME.size()));
        }
        String name = rng.nextDouble() < 0.5 ? playerName.toLowerCase(Locale.ROOT) : playerName;
        return String.format(HELLO_AGAIN.get(rng.nextInt(HELLO_AGAIN.size())), name);
    }

    /**
     * Whether a bot that just noticed a player greets them first: it knows them (an own note, no grudge), they are
     * not already talking, its newest note is not fresh (they did not just part), it has not greeted them on sight
     * lately, and the roll says so.
     */
    static boolean shouldGreetOnSight(List<BotMemory.Note> notes, boolean wary, boolean talking, long lastGreet,
                                      long now, double roll) {
        long newest = newestOwnNote(notes);
        if (newest == 0 || wary || talking) {
            return false;
        }
        if (now - newest < TimeUnit.MINUTES.toMillis(Long.getLong("solomapling.chat.recognizeAfterMin", 30))) {
            return false;
        }
        if (lastGreet > 0 && now - lastGreet < TimeUnit.HOURS.toMillis(Long.getLong("solomapling.chat.recognizeEveryHours", 6))) {
            return false;
        }
        return roll < chance();
    }

    /**
     * A roaming bot noticed a real player nearby: if it knows them, it may say hi first (through
     * {@link BotConversation#greet}, so the player's answer reaches it). Reads memory; call off the game thread.
     *
     * @return whether the bot greeted them (the caller then skips its usual reaction line)
     */
    public static boolean maybeGreetOnSight(BotSM bot, Character player) {
        if (bot == null || bot.getChr() == null || player == null || bot.getChr().getId() < BotStore.BOT_ID_BASE) {
            return false;
        }
        int slot = bot.getChr().getId() - BotStore.BOT_ID_BASE;
        long now = System.currentTimeMillis();
        long pair = ((long) slot << 32) | (player.getId() & 0xffffffffL);
        List<BotMemory.Note> notes;
        boolean wary;
        try {
            notes = BotMemory.about(slot, player.getId());
            wary = BotChatActions.wary(slot, player.getId());
        } catch (RuntimeException e) {
            return false;
        }
        boolean talking = BotConversation.isTalking(bot.getChr().getId(), player.getId());
        if (!shouldGreetOnSight(notes, wary, talking, lastGreetOnSight.getOrDefault(pair, 0L), now,
                ThreadLocalRandom.current().nextDouble())) {
            return false;
        }
        lastGreetOnSight.put(pair, now);
        return BotConversation.greet(bot, player);
    }

    private static double chance() {
        try {
            return Double.parseDouble(System.getProperty("solomapling.chat.recognizeChance", "0.5"));
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    /** Test seam: forgets who was greeted on sight. */
    static void resetForTest() {
        lastGreetOnSight.clear();
    }
}
