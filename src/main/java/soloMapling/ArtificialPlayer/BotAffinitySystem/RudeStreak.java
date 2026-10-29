package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.qa.QaLog;
import soloMapling.server.BoundedMaps;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Owner 2026-10-29: trash talk at a bot ("ur trash noob") is not harassment on its own. One rude line gets a
 * brush-off and nothing else; the bot holds it against the player from the second rude line within 10 minutes; and a
 * player who is rude to 3 or more bots on one map within 10 minutes is heard by every bot on that map, which turns a
 * little cooler (the capped {@link AffinityEvent#HEARSAY_BAD} nudge) and may tease them about it for an hour.
 * <p>
 * A "rude line" is one the chat model calls harassment. Death wishes, slurs and threats are harassment by rule
 * ({@link ChatTriage}) and still count in full from the first line.
 */
public final class RudeStreak {

    static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    static final long HEARD_MS = TimeUnit.HOURS.toMillis(1);
    static final int REPEAT = 2;
    static final int SPREAD_BOTS = 3;

    static final Pattern APOLOGY = Pattern.compile("\\b(sorry|sry|soz|my bad|mb|apologi[sz]e)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Where a rude line leaves the bot: the first one only gets a brush-off, from the second the bot minds. */
    public enum Step { LONE, REPEATED }

    /** @param spread this line made the player rude to {@link #SPREAD_BOTS}+ bots on the map within the window */
    public record Rude(Step step, boolean spread) {
    }

    private record Hit(int botId, int mapId, long at) {
    }

    private static final Map<Integer, Deque<Hit>> hits = new ConcurrentHashMap<>();
    /** (bot, player) -> when the bot stops remembering it saw the player being rude around the map. */
    private static final Map<Long, Long> heardUntil = new ConcurrentHashMap<>();

    private RudeStreak() {
    }

    /** Whether the line counts toward a streak: harassment from the model, not a rule's death wish, slur or threat. */
    public static boolean counts(ChatTriage.Result triage) {
        return triage != null && triage.con() == ChatTriage.Con.HARASSMENT
                && triage.conSource() != ChatTriage.Source.RULE;
    }

    public static boolean apologises(String line) {
        return line != null && APOLOGY.matcher(line).find();
    }

    /** A player's line to a bot: a rude one is counted, an apology clears this bot's count. Null when not rude. */
    public static Rude onLine(int botId, int playerId, int mapId, ChatTriage.Result triage, String line, long now) {
        if (counts(triage)) {
            return rude(botId, playerId, mapId, now);
        }
        if (apologises(line)) {
            apology(playerId, botId);
        }
        return null;
    }

    /** A rude line from the player to the bot. */
    public static Rude rude(int botId, int playerId, int mapId, long now) {
        Deque<Hit> mine = hits.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        int toBot;
        int botsHere;
        synchronized (mine) {
            mine.removeIf(h -> now - h.at() > WINDOW_MS);
            mine.addLast(new Hit(botId, mapId, now));
            toBot = 0;
            Set<Integer> bots = new HashSet<>();
            for (Hit h : mine) {
                if (h.botId() == botId) {
                    toBot++;
                }
                if (h.mapId() == mapId) {
                    bots.add(h.botId());
                }
            }
            botsHere = bots.size();
        }
        BoundedMaps.prune(hits, 1_000, 5_000, d -> {
            synchronized (d) {
                return d.isEmpty() || now - d.peekLast().at() > WINDOW_MS;
            }
        });
        return new Rude(toBot >= REPEAT ? Step.REPEATED : Step.LONE, botsHere >= SPREAD_BOTS);
    }

    /** The player said sorry to this bot: its count starts over. */
    public static void apology(int playerId, int botId) {
        Deque<Hit> mine = hits.get(playerId);
        if (mine != null) {
            synchronized (mine) {
                mine.removeIf(h -> h.botId() == botId);
            }
        }
    }

    /** Every bot on the player's map hears it: a small, capped cooling and an hour of knowing. */
    public static void spread(Iterable<Character> onMap, Character player, long now) {
        if (player == null || BotAffinity.optedOut(player.getId())) {
            return; // @botdrama off: no gossip or grudges from bystanders
        }
        int n = 0;
        for (Character c : onMap) {
            if (c == null || !BotHelpers.isBot(c)) {
                continue;
            }
            BotAffinity.record(c.getId(), player.getId(), AffinityEvent.HEARSAY_BAD,
                    AffinityEvent.HEARSAY_BAD.delta(), player.getName(), c.getName(), player.getMapId());
            heard(c.getId(), player.getId(), now);
            n++;
        }
        if (QaLog.on(QaLog.Topic.AFFINITY)) {
            QaLog.map(QaLog.Topic.AFFINITY, player.getMapId(), "RUDE-SPREAD " + player.getName() + " heard by " + n
                    + " bots");
        }
    }

    static void heard(int botId, int playerId, long now) {
        heardUntil.put(key(botId, playerId), now + HEARD_MS);
        BoundedMaps.prune(heardUntil, 5_000, 20_000, until -> until < now);
    }

    public static boolean hasHeard(int botId, int playerId, long now) {
        Long until = heardUntil.get(key(botId, playerId));
        return until != null && until >= now;
    }

    /** What the chat prompt adds when the bot saw the player being rude around the map: a sentence, or "". */
    public static String promptLine(int botId, Character player, long now) {
        if (player == null || !hasHeard(botId, player.getId(), now) || BotAffinity.optedOut(player.getId())) {
            return "";
        }
        return " You saw " + player.getName() + " being rude to several people around here just now: you can tease"
                + " them about it once, lightly and about the game only. Never lecture.";
    }

    private static long key(int botId, int playerId) {
        return ((long) botId << 32) | (playerId & 0xffffffffL);
    }

    /** Tests only. */
    static void reset() {
        hits.clear();
        heardUntil.clear();
    }
}
