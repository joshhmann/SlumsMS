package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotBrainSystem.Priority;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Ticket 59, owner decision 5: now and then two bots on a map with a real player talk to each other for 2-4 lines -
 * guild beef, gossip, looking for a party, trade talk, training - mostly in towns and the FM, rarely on grind maps. A
 * player can jump in: a line that names no one, while a thread is live, goes to its last speaker as a normal chat.
 *
 * <p>One LLM call writes the whole exchange, at {@link Priority#THREAD} (only when the local model has every slot
 * free); otherwise the line bank (an opener of the topic and a reaction) or a cut of a scripted conversation.
 * Off-screen maps never get a thread, so they never call the LLM.
 *
 * <p>Pacing: a 30 s tick; per map a gap of {@code solomapling.convo.threadGapMin}-{@code threadGapMax} (3-6)
 * minutes; at most {@code solomapling.convo.threadsPer10Min} (6) world-wide; town and FM maps always roll, grind maps
 * one time in ten, boss and event maps never.
 */
public final class BotThreads {

    private static final Logger log = LoggerFactory.getLogger(BotThreads.class);

    public enum Topic {
        GUILD_BEEF("guild beef", "thread_guild_beef"), GOSSIP("gossip", "thread_gossip"),
        LFG("looking for a party", "thread_lfg"), TRADE("trade talk", "thread_trade"),
        TRAINING("training", "thread_training");

        public final String words;
        public final String bankKey;

        Topic(String words, String bankKey) {
            this.words = words;
            this.bankKey = bankKey;
        }

        public static Topic parse(String raw) {
            if (raw == null) {
                return null;
            }
            String t = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Topic topic : values()) {
                if (topic.name().equalsIgnoreCase(t)) {
                    return topic;
                }
            }
            return switch (t) {
                case "beef", "war", "guild" -> GUILD_BEEF;
                case "lfg", "party", "pq" -> LFG;
                case "trade", "fm" -> TRADE;
                case "gossip", "drama" -> GOSSIP;
                case "train" -> TRAINING;
                default -> null;
            };
        }
    }

    /** A live thread on one map instance. */
    static final class Live {
        final int channel;
        final int mapId;
        final List<Integer> botIds;
        final List<String> names;
        final Topic topic;
        final String about;
        volatile long endsAt;
        volatile int lastSpeakerId;
        /** For the routing log ("thread:<id>"). */
        final int id = SEQ.incrementAndGet();
        volatile long startedAt;
        /** When its last line went out (0 before the first). */
        volatile long lastLineAt;

        Live(int channel, int mapId, List<Integer> botIds, List<String> names, Topic topic, String about, long endsAt,
             int lastSpeakerId) {
            this.channel = channel;
            this.mapId = mapId;
            this.botIds = botIds;
            this.names = names;
            this.topic = topic;
            this.about = about;
            this.endsAt = endsAt;
            this.lastSpeakerId = lastSpeakerId;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * What chat routing needs to know about a live thread (1.5.0 rc6): who is in it, who spoke last and when, and
     * when it started (its lines in {@code MapChatLog} are the participants' lines since then).
     */
    public record View(int id, List<Integer> botIds, List<String> names, int lastSpeakerId, long startedAt,
                       long lastLineAt, String topic) {
    }

    static final long JOIN_MS = TimeUnit.SECONDS.toMillis(45);
    static final long CONTEXT_MS = TimeUnit.MINUTES.toMillis(3);

    private static final Map<Long, Live> live = new ConcurrentHashMap<>();
    private static final Map<Long, Long> nextAt = new ConcurrentHashMap<>();
    private static final Deque<Long> started = new ArrayDeque<>();
    /** botId -> the thread it was last in, for the memory card after a player joins. */
    private static final Map<Integer, Live> lastThreadOf = new ConcurrentHashMap<>();
    private static volatile boolean running;

    private BotThreads() {
    }

    static long key(int channel, int mapId) {
        return ((long) channel << 32) | (mapId & 0xffffffffL);
    }

    /** Starts the tick (EnvironmentManager, with the town conversations). */
    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                tick(System.currentTimeMillis());
            } catch (RuntimeException | LinkageError e) {
                log.warn("Bot thread tick failed", e);
            }
        }), 45, 30, TimeUnit.SECONDS);
    }

    /** Whether this bot is in a live thread (the scripted town conversations leave it alone). */
    public static boolean busy(int botId) {
        long now = System.currentTimeMillis();
        for (Live l : live.values()) {
            if (l.endsAt > now && l.botIds.contains(botId)) {
                return true;
            }
        }
        return false;
    }

    // ── the tick ──

    static void tick(long now) {
        if (!ConvoSwitches.on("threads") || soloMapling.server.BotShutdown.isQuiesced()) {
            return;
        }
        live.values().removeIf(l -> now - l.endsAt > CONTEXT_MS);
        List<MapleMap> maps = mapsWithRealPlayers();
        Collections.shuffle(maps);
        Random rng = ThreadLocalRandom.current();
        for (MapleMap map : maps) {
            if (!budgetLeft(now)) {
                return;
            }
            int channel = channelOf(map);
            long k = key(channel, map.getId());
            long gap = gapMs(rng);
            if (!due(k, now)) {
                continue;
            }
            if (rng.nextDouble() >= mapWeight(map)) {
                defer(k, now + gap); // this map rolled no this time: its next roll is a gap away
                continue;
            }
            List<Character> pair = pickPair(map, rng);
            if (pair != null && claim(k, now, gap)) {
                run(map, channel, pair, null, now);
            }
        }
    }

    static int channelOf(MapleMap map) {
        try {
            return map.getChannelServer() == null ? 1 : map.getChannelServer().getId();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    static long gapMs(Random rng) {
        double lo = ConvoSwitches.num("threadGapMin", 3);
        double hi = Math.max(lo, ConvoSwitches.num("threadGapMax", 6));
        return (long) ((lo + rng.nextDouble() * (hi - lo)) * 60_000);
    }

    static synchronized boolean budgetLeft(long now) {
        while (!started.isEmpty() && now - started.peekFirst() > TimeUnit.MINUTES.toMillis(10)) {
            started.pollFirst();
        }
        return started.size() < (int) ConvoSwitches.num("threadsPer10Min", 6);
    }

    static synchronized boolean due(long mapKey, long now) {
        Long next = nextAt.get(mapKey);
        return next == null || now >= next;
    }

    static synchronized void defer(long mapKey, long until) {
        nextAt.put(mapKey, until);
    }

    /**
     * One atomic check-and-record for both limits (review 2026-10-22: overlapping ticks could pass both checks): the
     * world's threads per 10 minutes and the map's gap. True when this thread may start; both are then recorded.
     */
    static synchronized boolean claim(long mapKey, long now, long gapMs) {
        if (!budgetLeft(now) || !due(mapKey, now)) {
            return false;
        }
        started.addLast(now);
        nextAt.put(mapKey, now + gapMs);
        return true;
    }

    /** Towns and the FM 1, grind maps 0.1, boss and event maps 0. */
    static double mapWeight(MapleMap map) {
        return mapWeight(map.getId(), map.getEventInstance() != null || map.isEventMap(), map.isTown(),
                map.getAllMonsters().isEmpty());
    }

    /**
     * Never on a boss map, an event map (with or without an instance attached: the Jewel event's empty maps) or a
     * mini-dungeon (review 2026-10-22).
     */
    static boolean excluded(int mapId, boolean event) {
        return event || soloMapling.ArtificialPlayer.BotChatTuning.isBossMap(mapId)
                || server.maps.MiniDungeonInfo.isDungeonMap(mapId);
    }

    static double mapWeight(int mapId, boolean event, boolean town, boolean noMobs) {
        if (excluded(mapId, event)) {
            return 0;
        }
        if (mapId >= 910000000 && mapId <= 910000022 || town || noMobs) {
            return 1;
        }
        return 0.1;
    }

    static List<MapleMap> mapsWithRealPlayers() {
        java.util.Set<MapleMap> maps = new java.util.LinkedHashSet<>();
        try {
            for (Character chr : soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getAllCharacters()) {
                if (!BotHelpers.isBot(chr) && BotHelpers.isVisibleRealPlayer(chr) && chr.getMap() != null) {
                    maps.add(chr.getMap());
                }
            }
        } catch (RuntimeException e) {
            // the world is not up
        }
        return new ArrayList<>(maps);
    }

    /** Two idle talkers on the map, or null. */
    static List<Character> pickPair(MapleMap map, Random rng) {
        List<Character> idle = new ArrayList<>();
        for (Character chr : map.getAllPlayers()) {
            if (chr == null || !BotHelpers.isBot(chr)) {
                continue;
            }
            BotSM sm = CharacterStorage.getBotById(chr.getId());
            if (sm == null || !(sm instanceof SocialBot || sm instanceof TownWandererBot || sm instanceof TrainingBot)) {
                continue;
            }
            if (!sm.isAvailableForAmbientActions() || busy(chr.getId())
                    || soloMapling.ArtificialPlayer.ConversationManager.getInstance().isInConversation(chr.getId())
                    || soloMapling.ArtificialPlayer.BotConversation.hasAnyChat(chr.getId())
                    || soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.inScene(chr.getId())) {
                continue;
            }
            idle.add(chr);
        }
        if (idle.size() < 2) {
            return null;
        }
        Collections.shuffle(idle, rng);
        return List.of(idle.get(0), idle.get(1));
    }

    /** The topic that fits two bots and the map: their guilds' war first, trade in the FM, LFG for PQ levels. */
    static Topic topicFor(int guildA, int guildB, int levelA, int levelB, int mapId, Random rng) {
        if (guildA > 0 && guildB > 0 && guildA != guildB
                && "war".equals(soloMapling.ArtificialPlayer.BotDramaSystem.GuildWars.relation(guildA, guildB))) {
            return Topic.GUILD_BEEF;
        }
        if (mapId >= 910000000 && mapId <= 910000022) {
            return rng.nextDouble() < 0.7 ? Topic.TRADE : Topic.GOSSIP;
        }
        double r = rng.nextDouble();
        int level = Math.min(levelA, levelB);
        if (r < 0.35 && level >= 21 && level <= 70) {
            return Topic.LFG;
        }
        if (r < 0.6) {
            return Topic.GOSSIP;
        }
        if (r < 0.75) {
            return Topic.TRADE;
        }
        return Topic.TRAINING;
    }

    /**
     * Runs one thread now (also the {@code !qa cv-5} door). Returns the topic, or empty when it could not start.
     */
    public static List<String> run(MapleMap map, int channel, List<Character> pair, Topic forced, long now) {
        Character a = pair.get(0);
        Character b = pair.get(1);
        Random rng = ThreadLocalRandom.current();
        Topic topic = forced != null ? forced : topicFor(a.getGuildId(), b.getGuildId(), a.getLevel(), b.getLevel(),
                map.getId(), rng);
        String about = about(topic, a, b);
        Live l = new Live(channel, map.getId(), List.of(a.getId(), b.getId()), List.of(a.getName(), b.getName()), topic,
                about, now + TimeUnit.MINUTES.toMillis(2), a.getId());
        l.startedAt = now;
        live.put(key(channel, map.getId()), l);
        lastThreadOf.put(a.getId(), l);
        lastThreadOf.put(b.getId(), l);
        qa(map.getId(), "THREAD " + a.getName() + " + " + b.getName() + " on " + map.getId() + ": " + topic.words
                + (about == null ? "" : " (" + about + ")"));
        if (BotBrains.get().isLive() && !ConvoSwitches.forceBank()) {
            List<BotBrain.Turn> prompt = List.of(BotBrain.Turn.system(threadPrompt(a, b, topic, about, map.getMapName())),
                    BotBrain.Turn.user("Write the lines now."));
            BotBrains.get().reply(Priority.THREAD, 0, prompt).thenAccept(r -> {
                List<String[]> lines = r.map(x -> parse(x.text(), a.getName(), b.getName())).orElse(List.of());
                if (lines.size() < 2) {
                    play(l, a, b, fallback(topic, a, b, rng), "bank");
                } else {
                    play(l, a, b, lines, "llm");
                }
            });
        } else {
            play(l, a, b, fallback(topic, a, b, rng), "bank");
        }
        return List.of(topic.words);
    }

    static String about(Topic topic, Character a, Character b) {
        try {
            return switch (topic) {
                case GUILD_BEEF -> {
                    String ga = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guildName(a.getGuildId());
                    String gb = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guildName(b.getGuildId());
                    yield ga != null && gb != null ? ga + " vs " + gb : null;
                }
                case LFG -> {
                    int level = Math.min(a.getLevel(), b.getLevel());
                    yield level <= 30 ? "Kerning PQ" : level <= 50 ? "Ludi PQ" : "Orbis PQ";
                }
                default -> null;
            };
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** One call writes the whole exchange: both voices, the topic, the format. */
    static String threadPrompt(Character a, Character b, Topic topic, String about, String mapName) {
        ConvoPersona pa = ConvoPersona.of(a.getId() - BotStore.BOT_ID_BASE);
        ConvoPersona pb = ConvoPersona.of(b.getId() - BotStore.BOT_ID_BASE);
        return "Write a short public chat exchange between two MapleStory players in 2008, " + a.getName() + " (level "
                + a.getLevel() + ") and " + b.getName() + " (level " + b.getLevel() + "), standing in "
                + (mapName == null || mapName.isBlank() ? "town" : mapName) + ". Topic: " + topic.words
                + (about == null ? "" : " (" + about + ")") + ". 2 to 4 lines in total, alternating, each under 60"
                + " characters. " + a.getName() + " " + pa.voice().prompt + ". " + b.getName() + " " + pb.voice().prompt
                + ". Format each line as Name: text. Game talk only, PG-13, insults only about the game. Never mention AI,"
                + " bots, real places, real people or anything after 2008.";
    }

    /** "Name: text" lines of these two speakers, cleaned and checked; at most 4. */
    static List<String[]> parse(String text, String nameA, String nameB) {
        List<String[]> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.trim().replaceAll("^[-*\\d.)\\s]+", "");
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String who = line.substring(0, colon).trim().replace("*", "");
            String said = line.substring(colon + 1).trim().replaceAll("^\"|\"$", "").trim();
            String speaker = who.equalsIgnoreCase(nameA) || who.equalsIgnoreCase("A") ? nameA
                    : who.equalsIgnoreCase(nameB) || who.equalsIgnoreCase("B") ? nameB : null;
            if (speaker == null || said.isBlank() || said.length() > VoiceStyler.MAX || gibberish(said)
                    || ContentGuard.chatProblem(said) != null) {
                continue;
            }
            out.add(new String[]{speaker, said});
            if (out.size() == 4) {
                break;
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern REPEATS = java.util.regex.Pattern.compile("(?i)(.{2,3})\\1{4,}");

    /** A small model's leet overdrive ("XxXxXxXxXx !!!"): a two- or three-letter chunk five times or more in a row. */
    static boolean gibberish(String line) {
        return REPEATS.matcher(line.replace(" ", "")).find();
    }

    /** No LLM: the bank's opener for the topic and a reaction, else a cut of a scripted conversation. */
    static List<String[]> fallback(Topic topic, Character a, Character b, Random rng) {
        List<String[]> out = new ArrayList<>();
        Map<String, String> extra = new HashMap<>();
        if (topic == Topic.TRADE) {
            extra.put("item", "work gloves");
            extra.put("price", "500k");
        }
        ConvoLines.Line open = ConvoLines.line(topic.bankKey, a, null, null, extra, null, false);
        ConvoLines.Line react = open == null ? null : ConvoLines.line("talk", b, a, null, null, null, false);
        if (open != null && react != null) {
            out.add(new String[]{a.getName(), open.text()});
            out.add(new String[]{b.getName(), react.text()});
            return out;
        }
        List<ConversationLines.Script> scripts = ConversationLines.scripts();
        if (scripts.isEmpty()) {
            return out;
        }
        ConversationLines.Script s = scripts.get(rng.nextInt(scripts.size()));
        int n = Math.min(s.lines().size(), 2 + rng.nextInt(3));
        for (int i = 0; i < n; i++) {
            String[] l = s.lines().get(i);
            out.add(new String[]{"A".equals(l[0]) ? a.getName() : b.getName(), l[1]});
        }
        return out;
    }

    private static void play(Live l, Character a, Character b, List<String[]> lines, String source) {
        if (lines.isEmpty()) {
            return;
        }
        soloMapling.server.BotTiming.Chain chain = soloMapling.server.BotTiming.chain();
        long total = 0;
        Random rng = ThreadLocalRandom.current();
        for (String[] line : lines) {
            Character who = line[0].equals(a.getName()) ? a : b;
            int slot = who.getId() - BotStore.BOT_ID_BASE;
            // rc6 say-do: a thread line never promises what nobody will do ("brb", "omw to fm"); an LFG ask may stay
            String honest = ClaimActions.guardReply(line[1], java.util.EnumSet.of(ClaimRules.Kind.PARTY_ASK));
            if (honest == null) {
                continue;
            }
            String styled = VoiceStyler.style(honest, ConvoPersona.of(slot), slot,
                    List.of(a.getName().toLowerCase(Locale.ROOT), b.getName().toLowerCase(Locale.ROOT)));
            long wait = HumanTiming.typingMs(styled, 0, rng) + 400 + rng.nextInt(1200);
            total += wait;
            chain = chain.pause(wait).run(() -> {
                if (who.getMap() == null || who.getMapId() != l.mapId) {
                    return;
                }
                SayDo.withSource("thread-" + source, () -> BotSpeak(who, styled));
                l.lastSpeakerId = who.getId();
                l.lastLineAt = System.currentTimeMillis();
                l.endsAt = l.lastLineAt + JOIN_MS;
                ConvoQuality.threadLine(who, l.mapId, styled, source, l.topic.words);
                qa(l.mapId, "THREAD LINE [" + source + "] " + who.getName() + ": " + styled);
            });
        }
        l.endsAt = System.currentTimeMillis() + total + JOIN_MS;
        chain.start();
    }

    // ── a player jumps in ──

    /**
     * A player's line on a map with a live thread that names no one: the bot that answers it (the thread's last
     * speaker), or null. The bot then talks to the player like any chat, and its card says what the thread was about.
     */
    public static Integer jumpIn(int channel, int mapId, String playerName, String line, long now) {
        if (line == null || line.isBlank() || !ConvoSwitches.on("threads")) {
            return null;
        }
        Live l = live.get(key(channel, mapId));
        if (l == null || now > l.endsAt) {
            return null;
        }
        qa(mapId, "JOIN a player jumps into " + l.names + " (" + l.topic.words + "): " + ConvoQuality.anonymise(line, playerName));
        return l.lastSpeakerId;
    }

    /** The live thread on a map, for chat routing, or null (none, over, or threads switched off). */
    public static View liveView(int channel, int mapId, long now) {
        if (!ConvoSwitches.on("threads")) {
            return null;
        }
        Live l = live.get(key(channel, mapId));
        if (l == null || now > l.endsAt) {
            return null;
        }
        return new View(l.id, l.botIds, l.names, l.lastSpeakerId, l.startedAt, l.lastLineAt, subject(l.topic, l.about));
    }

    /**
     * What a thread is about, as routing reads it: the topic and its detail ("looking for a party (Ludi PQ)"), so a
     * player's "lpq? i can come too" is a clear reply to it even when the two bots' own lines never said "lpq".
     */
    static String subject(Topic topic, String about) {
        return topic.words + (about == null || about.isBlank() ? "" : " (" + about + ")");
    }

    /** The detail of the live thread on a map ("Ludi PQ", "Legendz vs ZakumSlayerz"), else its topic; null none. */
    public static String aboutOf(int channel, int mapId) {
        Live l = live.get(key(channel, mapId));
        return l == null ? null : l.about != null && !l.about.isBlank() ? l.about : l.topic.words;
    }

    /** The memory card's line for a player who joined a thread with this bot. */
    public static String contextFor(int botCharId, int playerId) {
        Live l = lastThreadOf.get(botCharId);
        if (l == null || System.currentTimeMillis() - l.endsAt > CONTEXT_MS) {
            return null;
        }
        String other = l.botIds.get(0) == botCharId ? l.names.get(1) : l.names.get(0);
        return "You were just talking with " + other + " about " + l.topic.words
                + (l.about == null ? "" : " (" + l.about + ")") + " when they joined in";
    }

    static void qa(int mapId, String message) {
        if (QaLog.on(QaLog.Topic.CONVO)) { // !qa cv-5
            QaLog.map(QaLog.Topic.CONVO, mapId, message);
        }
    }

    static void resetForTest() {
        live.clear();
        nextAt.clear();
        lastThreadOf.clear();
        synchronized (BotThreads.class) {
            started.clear();
        }
    }

    /** Test seam: a live thread on a map. */
    static void putLiveForTest(int channel, int mapId, int a, String nameA, int b, String nameB, Topic topic, long endsAt) {
        Live l = new Live(channel, mapId, List.of(a, b), List.of(nameA, nameB), topic, null, endsAt, b);
        l.lastLineAt = Math.max(0, endsAt - JOIN_MS);
        live.put(key(channel, mapId), l);
        lastThreadOf.put(a, l);
        lastThreadOf.put(b, l);
    }
}
