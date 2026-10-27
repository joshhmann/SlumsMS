package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotChannels;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.0 (owner, TEST): a bot megaphoned "ch8 is empty af" on a five-channel server. Bot text is written without knowing
 * how many channels the world has, so every line that goes out passes through here: a channel mention above the
 * world's real channel count is rewritten to a valid one. Pure and cheap: precompiled patterns, and a line without a
 * digit (nearly all of them) is returned as the same String with no allocation.
 *
 * <p>Recognised: "ch8", "ch 8", "CH8", "ch.8", "channel 8", "chan 8", and "c8" only when the line is clearly about
 * switching channels ("cc c8", "hop to c8"). Left alone: "lv 8", "8m", "each8", "ch8x", a mention that is valid.
 */
public final class ChannelMentions {
    private ChannelMentions() {
    }

    private static final Pattern MENTION = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:ch\\.?|chan(?:nel)?\\.?)\\s?(\\d{1,3})(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT = Pattern.compile("(?<![\\p{L}\\p{N}])c(\\d{1,3})(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_CONTEXT = Pattern.compile("\\b(?:cc|switch|hop|change|chan|channel)\\b", Pattern.CASE_INSENSITIVE);
    /** The line is about the speaker's own channel. */
    private static final Pattern HERE = Pattern.compile("\\b(?:here|this (?:ch|channel)|in (?:ch|channel) here|im in|i'm in|i am in)\\b", Pattern.CASE_INSENSITIVE);
    /** The line says the channel is quiet; channel 1 is the busy one by design, so never point there. */
    private static final Pattern QUIET = Pattern.compile("\\b(?:empty|dead|quiet|peace|peaceful|nobody|no one|ghost town|forgets? it exists|lonely|deserted)\\b", Pattern.CASE_INSENSITIVE);

    /** The line as it should be said on a world with {@code channels} channels, from a speaker standing in {@code speakerChannel}. */
    public static String fix(String line, int channels, int speakerChannel) {
        return fix(line, channels, speakerChannel, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    /** As {@link #fix(String, int, int)} with a chosen random source ({@code rnd.applyAsInt(n)} is in [0, n)). */
    public static String fix(String line, int channels, int speakerChannel, IntUnaryOperator rnd) {
        if (line == null || channels < 1 || !hasDigit(line)) {
            return line;
        }
        String out = replace(MENTION, line, channels, speakerChannel, rnd);
        if (SHORT_CONTEXT.matcher(out).find()) {
            out = replace(SHORT, out, channels, speakerChannel, rnd);
        }
        return out;
    }

    /** Uses the home world's real channel count and the bot's own channel; a line is untouched when the world is not up. */
    public static String forSpeaker(Character bot, String line) {
        if (line == null || !hasDigit(line)) {
            return line;
        }
        int n = BotChannels.channels().size();
        int mine = 0;
        try {
            if (bot != null && bot.getClient() != null) {
                mine = bot.getClient().getChannel();
            }
        } catch (RuntimeException ignored) {
            // a bot mid-logout: no own channel
        }
        return fix(line, n, mine);
    }

    private static boolean hasDigit(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    private static String replace(Pattern p, String line, int channels, int speakerChannel, IntUnaryOperator rnd) {
        Matcher m = p.matcher(line);
        StringBuilder sb = null;
        int last = 0;
        int mapFrom = -1;
        int mapTo = -1;
        while (m.find()) {
            int n;
            try {
                n = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                n = Integer.MAX_VALUE;
            }
            if (n >= 1 && n <= channels) {
                continue;
            }
            if (n != mapFrom) { // the same wrong number gets the same new one within a line
                mapFrom = n;
                mapTo = pick(line, channels, speakerChannel, rnd);
            }
            if (sb == null) {
                sb = new StringBuilder(line.length() + 2);
            }
            sb.append(line, last, m.start(1)).append(mapTo);
            last = m.end(1);
        }
        if (sb == null) {
            return line;
        }
        return sb.append(line, last, line.length()).toString();
    }

    static int pick(String line, int channels, int speakerChannel, IntUnaryOperator rnd) {
        if (channels == 1) {
            return 1;
        }
        if (speakerChannel >= 1 && speakerChannel <= channels && HERE.matcher(line).find()) {
            return speakerChannel;
        }
        if (QUIET.matcher(line).find()) {
            return 2 + rnd.applyAsInt(channels - 1); // 2..channels
        }
        return 1 + rnd.applyAsInt(channels);
    }
}
