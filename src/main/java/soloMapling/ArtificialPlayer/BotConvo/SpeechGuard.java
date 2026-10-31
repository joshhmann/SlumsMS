package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import constants.game.GameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.qa.QaLog;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * b247 (1.6.2): the one net under every line a bot says to a player. A {@code {slot}} nobody filled must never reach
 * a chat window - it reached the 1.6.x TEST transcripts as {@code {player}} (a {@code SocialBotDialogue} greeting
 * spoken raw, then copied by every model reply that saw it in the map history). The guard fills the slots it can read
 * from live state (the player's name, the bot's, the map, level, job, guild) and drops the line when a slot is
 * unknown ({item}, {price}, a stray {TOKEN}), logging each case once to the QA CONVO topic and the server log.
 *
 * <p>Ordinary braces are text, not slots: a slot is a brace, a letter, an identifier and a closing brace. So
 * {@code ":&#123;"}, {@code "{}"} and {@code "{ }"} pass untouched - emoticons and ASCII faces survive - while
 * {@code {player}}, {@code {PLAYER_NAME}} and {@code {item}} are slots.
 *
 * <p>Call it at the last step before the text leaves the bot: {@link #check(Character, Character, String)} returns
 * null for "do not say this".
 */
public final class SpeechGuard {
    private static final Logger log = LoggerFactory.getLogger(SpeechGuard.class);

    /** A brace-wrapped identifier. Narrow on purpose: ":{", "{}" and "{ }" in ordinary chat must survive. */
    static final Pattern SLOT = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,24})}");

    /** Slots already reported, so a broken line says so once and not every time a bot repeats it. */
    private static final Set<String> noted = ConcurrentHashMap.newKeySet();
    private static final int NOTED_CAP = 4_000;

    private SpeechGuard() {
    }

    /** Whether {@code text} carries anything the guard treats as a slot (a pure predicate: no logging). */
    public static boolean hasSlot(String text) {
        return text != null && SLOT.matcher(text).find();
    }

    /** As {@link #check(Character, Character, String)} with no player in context (map chat, guild chat). */
    public static String check(Character bot, String text) {
        return check(bot, null, text);
    }

    /**
     * The line as it may go out: unchanged when it has no slot, the refilled line when every slot resolves, or
     * {@code null} - drop it - when any slot cannot be filled from live state. Null in, null out.
     */
    public static String check(Character bot, Character player, String text) {
        if (!hasSlot(text)) {
            return text;
        }
        Matcher m = SLOT.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int last = 0;
        String first = null;
        while (m.find()) {
            String slot = m.group(1);
            String value = value(bot, player, slot);
            if (value == null || value.isBlank()) {
                note(bot, slot, false, text);
                return null;
            }
            out.append(text, last, m.start()).append(value);
            last = m.end();
            if (first == null) {
                first = slot;
            }
        }
        out.append(text, last, text.length());
        note(bot, first, true, text);
        return out.toString();
    }

    /**
     * What a slot means live, in lower case (so {@code {PLAYER_NAME}} and {@code {player_name}} agree), or null when
     * this guard cannot know it. Deliberately small: the money and item slots ({price}, {item}, {counter_price}) are
     * only knowable by the shop code that builds those lines, so a leftover one drops the line instead of guessing.
     */
    private static String value(Character bot, Character player, String slot) {
        return switch (slot.toLowerCase(Locale.ROOT)) {
            case "player", "name", "you", "player_name" -> name(player);
            case "me", "bot", "self" -> name(bot);
            case "map" -> bot == null || bot.getMap() == null ? null : bot.getMap().getMapName();
            case "level" -> bot == null ? null : String.valueOf(bot.getLevel());
            case "player_level" -> player == null ? null : String.valueOf(player.getLevel());
            case "job" -> bot == null ? null : GameConstants.getJobName(bot.getJob().getId());
            case "player_job" -> player == null ? null : GameConstants.getJobName(player.getJob().getId());
            case "guild" -> bot == null || bot.getGuild() == null ? null : bot.getGuild().getName();
            case "player_guild" -> player == null || player.getGuild() == null ? null : player.getGuild().getName();
            default -> null;
        };
    }

    private static String name(Character c) {
        return c == null ? null : c.getName();
    }

    /** Reports a guard decision once per bot and slot (never per line: a loop must not flood the log). */
    private static void note(Character bot, String slot, boolean filled, String text) {
        int id = bot == null ? 0 : bot.getId();
        String key = id + ":" + slot + ":" + filled;
        if (noted.size() >= NOTED_CAP) {
            noted.clear();
        }
        if (!noted.add(key)) {
            return;
        }
        String who = bot == null ? "?" : bot.getName();
        String what = filled ? "SPEECH GUARD " + who + " refilled {" + slot + "}: "
                : "SPEECH GUARD " + who + " dropped a line, {" + slot + "} unknown: ";
        String line = what + shorten(text);
        if (QaLog.on(QaLog.Topic.CONVO)) {
            QaLog.map(QaLog.Topic.CONVO, bot == null ? 0 : bot.getMapId(), line);
        }
        if (filled) {
            log.info(line);
        } else {
            log.warn(line);
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ');
        return t.length() <= 120 ? t : t.substring(0, 117) + "...";
    }

    /** Tests only: forget what was already reported. */
    public static void forgetForTest() {
        noted.clear();
    }
}
