package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import soloMapling.Economy.MegaBuyParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 1.6.0 (player-likeness gap 4): bots notice a real player's megaphone. Owner target: 40-70% of player smegas get at
 * least one bot reaction (a buy request that a bot answers with an offer counts as its reaction). A reaction is a short
 * all-chat line from a bot standing near the speaker ("lol", "gl w the whip"), sometimes a second bot a little later,
 * now and then a whisper. Never spammy: a server-wide cap per minute, a gap per player and a rest per bot, and lines
 * are picked so the last dozen do not repeat. Chance: {@code -Dsolomapling.megaReactChance} (default 0.55).
 * Pure logic over {@link Env}, tested without a server.
 */
public final class MegaReactions {

    /** The game side. */
    public interface Env {
        long now();

        double roll();

        int between(int lo, int hi);

        void later(long delayMs, Runnable task);

        /** Up to {@code n} different bots near the speaker, best first (same map as the player, else none). */
        List<Integer> botsNear(int playerId, int n);

        /** Some other bot, for a whisper when nobody stands near. */
        int anyBot(int playerId);

        void say(int botId, String line);

        boolean whisper(int botId, int playerId, String line);
    }

    enum Topic { BUY, SELL, PARTY, OTHER }

    private static final Pattern PARTY = Pattern.compile("(?i)\\b(lfp|lf\\s*(pt|party|members?)|party|recruit\\w*|pq|guild)\\b");

    static final List<String> BUY_LINES = List.of("gl finding it", "gl w the hunt", "ooh what for", "lol same", "hope u find one",
            "good luck w that", "fm prob has it", "check the fm rooms", "gl gl", "damn, rare one");
    static final List<String> SELL_LINES = List.of("gl w the sale", "how much", "ooh nice", "gl selling", "fm prices rn lol",
            "lol good luck", "nice one", "gl gl");
    static final List<String> PARTY_LINES = List.of("gl w the pt", "what lvl", "gl finding ppl", "lol the pt smega",
            "good luck", "what map");
    static final List<String> OTHER_LINES = List.of("lol", "haha", "who dat", "smega again lol", "gl", "lmao", "nice one",
            "fr", "ayy", "lol ok", "ha", "bruh", "wait what", "gl gl", "o/", "haha nice");

    private final Env env;
    private final Deque<String> recent = new ArrayDeque<>();
    private final Deque<Long> minute = new ArrayDeque<>();
    private final Map<Integer, Long> playerLast = new HashMap<>();
    private final Map<Integer, Long> botLast = new HashMap<>();

    public MegaReactions(Env env) {
        this.env = env;
    }

    static double chance() {
        return Double.parseDouble(System.getProperty("solomapling.megaReactChance", "0.55"));
    }

    static long playerGapMs() {
        return Long.getLong("solomapling.megaReactPlayerGapMs", 20_000);
    }

    static int perMinute() {
        return Integer.getInteger("solomapling.megaReactPerMinute", 4);
    }

    static long botRestMs() {
        return Long.getLong("solomapling.megaReactBotRestMs", 300_000);
    }

    static Topic topicOf(String text) {
        MegaBuyParser.Request r = MegaBuyParser.parse(text);
        if (PARTY.matcher(text == null ? "" : text).find()) {
            return Topic.PARTY;
        }
        return switch (r.kind()) {
            case BUY -> Topic.BUY;
            case SELL -> Topic.SELL;
            case NONE -> Topic.OTHER;
        };
    }

    /**
     * Rolls a reaction for a player's megaphone. {@code answered}: a bot is already going to whisper an offer, which
     * counts as the reaction. Returns how many bots will react (0 when the roll, a cap or a lack of bots says no).
     */
    public synchronized int react(int playerId, String playerName, String text, boolean answered) {
        long now = env.now();
        if (answered || env.roll() >= chance()) {
            return 0;
        }
        Long last = playerLast.get(playerId);
        if (last != null && now - last < playerGapMs()) {
            return 0;
        }
        while (!minute.isEmpty() && now - minute.peekFirst() > 60_000) {
            minute.pollFirst();
        }
        if (minute.size() >= perMinute()) {
            return 0;
        }
        int want = env.roll() < 0.05 ? 3 : env.roll() < 0.30 ? 2 : 1;
        List<Integer> near = new ArrayList<>();
        for (int id : env.botsNear(playerId, want + 3)) {
            Long rested = botLast.get(id);
            if ((rested == null || now - rested >= botRestMs()) && !near.contains(id)) {
                near.add(id);
            }
            if (near.size() >= want) {
                break;
            }
        }
        Topic topic = topicOf(text);
        int sent = 0;
        if (near.isEmpty()) {
            // nobody stands near: at most a whisper, from a bot that rested
            int bot = env.anyBot(playerId);
            Long rested = bot == 0 ? null : botLast.get(bot);
            if (bot == 0 || rested != null && now - rested < botRestMs() || env.roll() >= 0.5) {
                return 0;
            }
            String line = pick(topic);
            botLast.put(bot, now);
            env.later(env.between(8_000, 40_000), () -> env.whisper(bot, playerId, line));
            return count(now, playerId, 1);
        }
        long at = 0;
        for (int i = 0; i < near.size(); i++) {
            int bot = near.get(i);
            String line = pick(topic);
            botLast.put(bot, now);
            at += i == 0 ? env.between(4_000, 35_000) : env.between(5_000, 30_000);
            boolean whisper = i == 0 && env.roll() < 0.10;
            env.later(at, whisper ? () -> env.whisper(bot, playerId, line) : () -> env.say(bot, line));
            sent++;
        }
        if (botLast.size() > 3000) {
            botLast.values().removeIf(t -> now - t >= botRestMs());
        }
        return count(now, playerId, sent);
    }

    private int count(long now, int playerId, int sent) {
        playerLast.put(playerId, now);
        minute.addLast(now);
        return sent;
    }

    /** A line for the topic that is not among the last dozen said. */
    String pick(Topic topic) {
        List<String> pool = switch (topic) {
            case BUY -> BUY_LINES;
            case SELL -> SELL_LINES;
            case PARTY -> PARTY_LINES;
            case OTHER -> OTHER_LINES;
        };
        List<String> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>(recent);
        for (String l : pool) {
            if (!seen.contains(l)) {
                fresh.add(l);
            }
        }
        List<String> from = fresh.isEmpty() ? pool : fresh;
        String line = from.get(env.between(0, from.size() - 1));
        recent.addLast(line);
        while (recent.size() > 12) {
            recent.pollFirst();
        }
        return line;
    }
}
