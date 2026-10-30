package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 161 item 3 (1.6.2, behind {@code slums.bots.famerequest}): "fame me pls". A bot a player is talking to may
 * fame them back when the player asks and the bot likes them. Nothing here bends the fame rules: the bot must be a
 * friend of the player (ticket 58's affinity), level 15 or more like any famer, on the player's map, and the server's
 * own {@link Character#canGiveFame} (one fame a day per famer, one per target a month) says yes; on top of that a bot
 * famed a given player at most once a day however the fame log reads. Anything else is not answered here, so the
 * normal chat reply stands.
 *
 * <p>Idea from nutnnut/Cosmic's bot chat (fame on request, README_SERVER_BOTS.md); the gates and wording are ours.
 */
public final class BotFameRequest {

    static final long PAIR_GAP_MS = TimeUnit.HOURS.toMillis(24);
    static final int MIN_FAMER_LEVEL = 15;

    private static final Set<String> ASKS = Set.of("pls", "plz", "please", "me", "can", "could",
            "give", "gimme", "need", "want", "wanna");
    private static final Set<String> NOT = Set.of("defame", "defamed", "dont", "don't", "no", "not", "never", "stop",
            "lose", "negative", "unfame", "minus");

    private static final Map<Long, Long> famedAt = new ConcurrentHashMap<>();

    /** What the server does for real; behind a seam for the tests. */
    public interface Effects {
        boolean friend(int botId, int playerId);

        /** The server's own once-a-day / once-a-month rule for this bot famed this player. */
        Character.FameStatus status(Character bot, Character player);

        /** Gives the +1 and records it in the fame log; false when the fame could not be given. */
        boolean give(Character bot, Character player);

        void say(Character bot, String line);
    }

    private static volatile Effects effects = new GameEffects();

    private BotFameRequest() {
    }

    public static void useForTest(Effects test) {
        effects = test == null ? new GameEffects() : test;
        famedAt.clear();
    }

    /** Whether a line asks to be famed: "fame me pls", "can u fame me", "gimme a fame" - never "defame" or "dont fame". */
    public static boolean isFameRequest(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String[] tokens = line.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").trim().split("\\s+");
        boolean fame = false;
        boolean ask = false;
        for (String t : tokens) {
            if (NOT.contains(t)) {
                return false;
            }
            if (t.equals("fame") || t.equals("famed") || t.equals("fames")) {
                fame = true;
            } else if (ASKS.contains(t)) {
                ask = true;
            }
        }
        return fame && ask && tokens.length <= 12;
    }

    /** What the bot does with a request. */
    public enum Outcome { NOT_A_REQUEST, IGNORED, FAMED, ALREADY_TODAY, CANNOT_NOW }

    /**
     * Handles one line said to this bot. True when it was a fame request the bot answered (the caller then says no
     * more); false leaves the line to the normal chat.
     */
    public static boolean tryHandle(Character bot, Character player, String line) {
        Outcome out = handle(bot, player, line, System.currentTimeMillis());
        return out != Outcome.NOT_A_REQUEST && out != Outcome.IGNORED;
    }

    static Outcome handle(Character bot, Character player, String line, long now) {
        if (!BotSupportSwitches.fameRequest() || bot == null || player == null || bot == player
                || BotHelpers.isBot(player) || !isFameRequest(line)) {
            return Outcome.NOT_A_REQUEST;
        }
        Outcome out = decide(bot, player, now);
        switch (out) {
            case FAMED -> effects.say(bot, pick(FAMED_LINES));
            case ALREADY_TODAY -> effects.say(bot, pick(ALREADY_LINES));
            case CANNOT_NOW -> effects.say(bot, pick(CANNOT_LINES));
            default -> { }
        }
        return out;
    }

    private static Outcome decide(Character bot, Character player, long now) {
        if (bot.getMapId() != player.getMapId() || bot.getLevel() < MIN_FAMER_LEVEL
                || !effects.friend(bot.getId(), player.getId())) {
            return Outcome.IGNORED;   // not a friend (or not here): the normal chat answers
        }
        long key = ((long) bot.getId() << 32) ^ (player.getId() & 0xffffffffL);
        Long last = famedAt.get(key);
        if (last != null && now - last < PAIR_GAP_MS) {
            return Outcome.ALREADY_TODAY;
        }
        Character.FameStatus status = effects.status(bot, player);
        if (status == Character.FameStatus.NOT_THIS_MONTH) {
            return Outcome.ALREADY_TODAY;
        }
        if (status != Character.FameStatus.OK) {
            return Outcome.CANNOT_NOW;   // this bot already famed someone today
        }
        if (!effects.give(bot, player)) {
            return Outcome.CANNOT_NOW;   // the player is at the fame ceiling
        }
        famedAt.put(key, now);
        soloMapling.server.BoundedMaps.prune(famedAt, 2_000, 10_000, at -> now - at >= PAIR_GAP_MS);
        return Outcome.FAMED;
    }

    private static final String[] FAMED_LINES = {"there u go ^^", "famed u~", "sure, fame for u!", "done, enjoy the fame lol"};
    private static final String[] ALREADY_LINES = {"i already famed u lol", "u got my fame already, ask again later",
            "already famed u, no more for now"};
    private static final String[] CANNOT_LINES = {"i already used my fame today, sorry", "cant fame rn, maybe later"};

    private static String pick(String[] lines) {
        return lines[ThreadLocalRandom.current().nextInt(lines.length)];
    }

    private static final class GameEffects implements Effects {
        @Override
        public boolean friend(int botId, int playerId) {
            return BotAffinity.friend(botId, playerId);
        }

        @Override
        public Character.FameStatus status(Character bot, Character player) {
            try {
                return bot.canGiveFame(player);
            } catch (RuntimeException e) {
                return Character.FameStatus.NOT_TODAY;   // no fame history loaded: do not guess
            }
        }

        @Override
        public boolean give(Character bot, Character player) {
            if (!player.gainFame(1, bot, 1)) {
                return false;
            }
            bot.hasGivenFame(player);
            return true;
        }

        @Override
        public void say(Character bot, String line) {
            SocialCommands.BotChatbubble(bot, line);
        }
    }
}
