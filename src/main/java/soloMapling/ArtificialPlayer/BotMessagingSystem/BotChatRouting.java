package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.Character;
import config.ServerConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The state around addressing a bot (bot-persistence ticket 19): which bot spoke last on each map, the bots that
 * asked "u talking to me?" and are waiting for a yes, and the decision model that picks the addressee of a line
 * with no usable name ("ill come" after a bot asked for a party, "can i get a bless" near a cleric).
 */
public final class BotChatRouting {

    private static final Logger log = LoggerFactory.getLogger(BotChatRouting.class);

    /** A bot's line stays "the last thing said" on its map for this long. */
    static final long LAST_SPEAKER_MS = TimeUnit.SECONDS.toMillis(45);
    /** A "u talking to me?" waits this long for its answer. */
    static final long CONFIRM_MS = TimeUnit.SECONDS.toMillis(20);

    private static final List<String> ASK_LINES = List.of("u talking to me?", "me?", "who me?", "hm? u talkin to me?",
            "wait me?");
    private static final Set<String> YES = Set.of("ya", "yah", "yes", "yeah", "yea", "yep", "yup", "ye", "mhm", "u",
            "you", "ofc", "sure", "ok", "okay", "yes u", "ya u");

    public record LastSpeaker(int botId, String name, String line, long at) {
    }

    /** A bot waiting to hear whether it was meant. */
    public record Pending(int botId, ChatMessage original, long until) {
    }

    private static final Map<Integer, LastSpeaker> lastSpeakerByMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Pending> pendingBySender = new ConcurrentHashMap<>();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();

    private BotChatRouting() {
    }

    /** Every bot line passes here (from {@code SocialCommands.BotSpeak}). */
    public static void noteBotSpoke(Character bot, String line) {
        if (bot != null && bot.getMap() != null) {
            lastSpeakerByMap.put(bot.getMapId(), new LastSpeaker(bot.getId(), bot.getName(), line,
                    System.currentTimeMillis()));
            noteMapLine(bot.getMapId(), bot.getName(), line, System.currentTimeMillis());
        }
    }

    // ── what was said on a map lately (ticket 37 grounding: the prompt carries recent chat) ──

    static final int RECENT_LINES = 4;
    static final long RECENT_MS = TimeUnit.MINUTES.toMillis(2);

    private record MapLine(String speaker, String line, long at) {
    }

    private static final Map<Integer, java.util.Deque<MapLine>> recentByMap = new ConcurrentHashMap<>();

    /** A line said in map chat, by a player or a bot. */
    public static void noteMapLine(int mapId, String speaker, String line, long now) {
        if (speaker == null || line == null || line.isBlank()) {
            return;
        }
        java.util.Deque<MapLine> lines = recentByMap.computeIfAbsent(mapId, k -> new java.util.ArrayDeque<>());
        synchronized (lines) {
            lines.addLast(new MapLine(speaker, line.length() > 80 ? line.substring(0, 80) : line, now));
            while (lines.size() > RECENT_LINES) {
                lines.removeFirst();
            }
        }
    }

    /** The last few lines said on a map in the last two minutes, oldest first, as "name: line". */
    public static List<String> recentOnMap(int mapId, long now) {
        java.util.Deque<MapLine> lines = recentByMap.get(mapId);
        if (lines == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        synchronized (lines) {
            for (MapLine l : lines) {
                if (now - l.at() <= RECENT_MS) {
                    out.add(l.speaker() + ": " + l.line());
                }
            }
        }
        return out;
    }

    public static LastSpeaker lastSpeaker(int mapId, long now) {
        LastSpeaker last = lastSpeakerByMap.get(mapId);
        return last != null && now - last.at() <= LAST_SPEAKER_MS ? last : null;
    }

    /** The bot asks whether it was meant, and waits for the answer. */
    public static String askFirst(int botId, ChatMessage original, long now) {
        pendingBySender.put(original.getSender().getId(), new Pending(botId, original, now + CONFIRM_MS));
        return ASK_LINES.get(ThreadLocalRandom.current().nextInt(ASK_LINES.size()));
    }

    /**
     * If this sender owes a bot an answer: the bot it confirms (a yes, or the bot's name), or empty when the line
     * declines or is about something else. A pending question is used once, and expires.
     */
    public static Optional<Pending> confirmation(ChatMessage message, String botName, long now) {
        Pending pending = pendingBySender.remove(message.getSender().getId());
        if (pending == null || now > pending.until()) {
            return Optional.empty();
        }
        String text = message.getContent().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
        boolean yes = YES.contains(text) || text.startsWith("yes") || text.startsWith("yea") || text.startsWith("ya ")
                || (botName != null && BotAddressee.score(text.split(" ")[0], botName) >= BotAddressee.ASK);
        return yes ? Optional.of(pending) : Optional.empty();
    }

    /** The bot a pending question is from, without answering it. */
    public static Pending pendingFor(int senderId) {
        return pendingBySender.get(senderId);
    }

    // ── attention (ticket 37): after a bot answers a player, it keeps listening to them for a while ──

    /** A bot listening to a player: follow-up lines without its name are for it. */
    public record Attention(int botId, long lastAt) {
    }

    /** Why a bot stopped listening, or LISTENING when it still is. */
    public enum Drift { LISTENING, QUIET, LEFT_MAP, WALKED_AWAY, GONE }

    private static final Map<Integer, Attention> attentionByPlayer = new ConcurrentHashMap<>();

    /** How long a bot keeps listening after the last line between them: {@code -Dsolomapling.chat.attentionMs}. */
    public static long attentionMs() {
        return Long.getLong("solomapling.chat.attentionMs", TimeUnit.SECONDS.toMillis(50));
    }

    /** How far (px) the player may walk from the bot before it stops listening: {@code .attentionRange}. */
    public static int attentionRange() {
        return Integer.getInteger("solomapling.chat.attentionRange", 700);
    }

    /** This bot is now listening to this player (a newer bot replaces an older one: one at a time). */
    public static void attend(int playerId, int botId, long now) {
        attentionByPlayer.put(playerId, new Attention(botId, now));
    }

    /** The bot answered: it keeps listening from now, if it still was. */
    public static void touchAttention(int playerId, int botId, long now) {
        attentionByPlayer.computeIfPresent(playerId, (k, a) -> a.botId() == botId ? new Attention(botId, now) : a);
    }

    public static void dropAttention(int playerId) {
        attentionByPlayer.remove(playerId);
    }

    /** The raw attention entry for a player (not checked for drift). */
    public static Attention attention(int playerId) {
        return attentionByPlayer.get(playerId);
    }

    /**
     * Whether a listening bot still listens: the player went quiet, changed map or walked off, or the bot left.
     * {@code distance} is between the two characters, ignored when they are on different maps.
     */
    public static Drift drift(Attention attention, long now, boolean botPresent, int playerMapId, int botMapId,
                              double distance) {
        if (attention == null || !botPresent) {
            return Drift.GONE;
        }
        if (now - attention.lastAt() > attentionMs()) {
            return Drift.QUIET;
        }
        if (playerMapId != botMapId) {
            return Drift.LEFT_MAP;
        }
        return distance > attentionRange() ? Drift.WALKED_AWAY : Drift.LISTENING;
    }

    /** Whether a line names one of these other characters (a player talking to someone else). */
    public static boolean namesSomeoneElse(String line, java.util.Collection<String> otherNames) {
        String lower = line == null ? "" : line.toLowerCase(Locale.ROOT);
        for (String name : otherNames) {
            if (name != null && name.length() >= 3) {
                Pattern word = Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(name.toLowerCase(Locale.ROOT))
                        + "($|[^a-z0-9])");
                if (word.matcher(lower).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a line is aimed away from the bot that is listening: it names (or nearly names) another bot, or names
     * another player on the map.
     */
    public static boolean addressesSomeoneElse(int listeningBotId, BotAddressee.Result named, String line,
                                               java.util.Collection<String> otherPlayerNames) {
        boolean otherBot = named != null && named.best() != null && named.best().id() != listeningBotId
                && (named.sure() || named.worthAsking() || named.ambiguous());
        return otherBot || namesSomeoneElse(line, otherPlayerNames);
    }

    /**
     * What a line naming a bot says to it, without the name: the full name and any word close enough to be a
     * nickname for it ("fire" for xFirex) are cut out. Empty when the line was only the name.
     */
    public static String withoutName(String line, String botName) {
        if (line == null) {
            return "";
        }
        String text = botName == null || botName.isEmpty() ? line
                : line.replaceAll("(?i)" + Pattern.quote(botName), " ");
        StringBuilder out = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String bare = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (word.isEmpty() || (botName != null && bare.length() >= 3
                    && BotAddressee.score(bare, botName) >= BotAddressee.ASK)) {
                continue;
            }
            out.append(out.length() == 0 ? "" : " ").append(word);
        }
        String said = out.toString().trim();
        return said.replaceAll("^[,.!?:~ ]+", "").trim();
    }


    // ── layer 3: who a nameless line is for ─────────────────────────────────────────────────────

    /*
     * Probed against GLiNER2.5-Decide (2026-10-18): asked to pick one of four nearby players plus "none", it chose
     * the same option for every line (0/8). Split into small questions it does better, but it still took "lol" and
     * "brb mom says dinner" for answers to a party call. So the reply case is a rule, and the model answers only
     * the question it is good at - which kind of player a request is for (bless -> cleric, "the thief").
     */

    private static final Pattern ACCEPT = Pattern.compile(
            "^(ill come|i'?ll come|me|me too|count me in|im in|i'?m in|sure|ok|okay|k|yes|ya|yea|yeah|yep|inv|inv me"
                    + "|invite me|inv pls|lets go|let'?s go|omw|on my way|i can|i wanna|i want in)\\b.*");
    private static final Pattern NOT_A_REPLY = Pattern.compile(".*\\b(brb|afk|gtg|lol|lmao|haha|selling|buying|wts|wtb)\\b.*");
    private static final Pattern ASKS_SOMETHING = Pattern.compile(".*(\\?|\\b(anyone|wanna|who|need|party|pt|lf|looking for|come)\\b).*");
    private static final Pattern TRADE_TALK = Pattern.compile(".*\\b(selling|buying|wts|wtb|s>|b>|price|pc)\\b.*");
    private static final Pattern REQUEST = Pattern.compile(".*(\\?|\\b(u|you|ur|can i|could i|pls|plz|please|anyone|which|who)\\b).*");

    /**
     * The bot that just spoke, when this line answers it: the bot asked something or was recruiting, and the line is
     * a short acceptance ("ill come", "sure", "inv me") rather than chatter.
     */
    public static Optional<Integer> replyToLastSpeaker(String line, LastSpeaker last) {
        if (last == null) {
            return Optional.empty();
        }
        String text = line.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ?]", " ").replaceAll("\\s+", " ").trim();
        String said = last.line() == null ? "" : last.line().toLowerCase(Locale.ROOT);
        if (text.split(" ").length > 6 || NOT_A_REPLY.matcher(text).matches() || !ACCEPT.matcher(text).matches()
                || !ASKS_SOMETHING.matcher(said).matches()) {
            return Optional.empty();
        }
        return Optional.of(last.botId());
    }

    /** The classes a request can point at, by job id ({@code jobId / 100}, with clerics split out of magicians). */
    public enum JobFamily {
        CLERIC("a cleric, priest or bishop - for heal, bless or holy symbol"),
        MAGICIAN("a magician or mage"),
        BOWMAN("a bowman, hunter or crossbowman - someone with a bow"),
        THIEF("a thief, assassin or bandit"),
        WARRIOR("a warrior, fighter, page or spearman"),
        PIRATE("a pirate");

        final String description;

        JobFamily(String description) {
            this.description = description;
        }

        public static JobFamily of(int jobId) {
            if (jobId < 100) {
                return null;
            }
            return switch (jobId / 100) {
                case 1 -> WARRIOR;
                case 2 -> jobId / 10 == 23 ? CLERIC : MAGICIAN;
                case 3 -> BOWMAN;
                case 4 -> THIEF;
                case 5 -> PIRATE;
                default -> null;
            };
        }
    }

    public record Choice(int botId, double confidence) {
    }

    public static boolean decideConfigured() {
        ServerConfig server = YamlConfig.config.server;
        return server != null && server.BOT_DECIDE_URL != null && !server.BOT_DECIDE_URL.isBlank();
    }

    /**
     * For a request with no usable name ("which one of u is the thief", "u with the bow"): asks the decision model
     * which kind of player it wants, and returns the one nearby bot of that kind. Buff requests ("bless pls") never
     * get here - {@code BotBuffRequestHandler} answers them. Blocking with a short
     * timeout - call it off the dispatcher. Empty when the line is not a request, the kind is unclear, no bot or
     * several bots of it are near, on any failure, and when no endpoint is configured.
     */
    public static Optional<Choice> decideByJob(String line, List<BotAddressee.Candidate> nearby) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (nearby.isEmpty() || TRADE_TALK.matcher(lower).matches() || !REQUEST.matcher(lower).matches()) {
            return Optional.empty();
        }
        if (!decideConfigured()) {
            return Optional.empty();
        }
        ServerConfig server = YamlConfig.config.server;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(server.BOT_DECIDE_URL) + "/v1/systemone"))
                    .timeout(Duration.ofMillis(server.BOT_DECIDE_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(jobRequest(line))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.debug("Decision model answered HTTP {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }
            return parseJob(response.body()).flatMap(pick -> onlyOne(nearby, pick.family(), pick.confidence()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Decision model unavailable: {}", e.toString());
            return Optional.empty();
        }
    }

    record JobPick(JobFamily family, double confidence) {
    }

    /** The bot of that kind, when exactly one is nearby (with several, nobody can tell which was meant). */
    private static Optional<Choice> onlyOne(List<BotAddressee.Candidate> nearby, JobFamily family, double confidence) {
        List<BotAddressee.Candidate> ofKind = nearby.stream().filter(c -> JobFamily.of(c.jobId()) == family).toList();
        return ofKind.size() == 1 ? Optional.of(new Choice(ofKind.get(0).id(), confidence)) : Optional.empty();
    }

    public static Map<String, Object> jobRequest(String line) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (JobFamily family : JobFamily.values()) {
            criteria.put(family.name().toLowerCase(Locale.ROOT), family.description);
        }
        criteria.put("any", "no particular kind of player");
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", "Which kind of player does this line ask for or point at?");
        question.put("criteria", criteria);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "decide");
        body.put("state", "The player typed: " + clean(line));
        body.put("questions", Map.of("need", question));
        return body;
    }

    static Optional<JobPick> parseJob(String responseBody) {
        if (!(Json.parse(responseBody) instanceof Map<?, ?> root)
                || !(root.get("answers") instanceof Map<?, ?> answers)
                || !(answers.get("need") instanceof Map<?, ?> need)
                || !(need.get("choice") instanceof String choice)
                || !(need.get("confidence") instanceof Number confidence)) {
            return Optional.empty();
        }
        for (JobFamily family : JobFamily.values()) {
            if (family.name().equalsIgnoreCase(choice)) {
                return Optional.of(new JobPick(family, confidence.doubleValue()));
            }
        }
        return Optional.empty();
    }

    /** The decision server rejects some characters (a ")" in a criterion is a 400); names and chat are user text. */
    static String clean(String text) {
        return text == null ? "" : text.replaceAll("[^A-Za-z0-9 ,.'!?%-]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
