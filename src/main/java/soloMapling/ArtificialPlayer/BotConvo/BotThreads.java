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
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;
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
import java.util.Set;
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
        /** 1.6.0 S4: the true story a GOSSIP or TRAINING thread is about (null for the other topics). */
        final soloMapling.ArtificialPlayer.BotGossip.Fact fact;
        volatile long endsAt;
        volatile int lastSpeakerId;
        /** For the routing log ("thread:<id>"). */
        final int id = SEQ.incrementAndGet();
        volatile long startedAt;
        /** When its last line went out (0 before the first). */
        volatile long lastLineAt;

        Live(int channel, int mapId, List<Integer> botIds, List<String> names, Topic topic, String about,
             soloMapling.ArtificialPlayer.BotGossip.Fact fact, long endsAt, int lastSpeakerId) {
            this.channel = channel;
            this.mapId = mapId;
            this.botIds = botIds;
            this.names = names;
            this.topic = topic;
            this.about = about;
            this.fact = fact;
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

    /** Up to {@link #MAX_LIVE_PER_MAP} live threads per channel:map, oldest first. */
    private static final Map<Long, List<Live>> live = new ConcurrentHashMap<>();
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
        for (List<Live> threads : live.values()) {
            for (Live l : threads) {
                if (l.endsAt > now && l.botIds.contains(botId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How many threads a map may hold at once; the oldest gives way (its gap keeps the next one minutes away). */
    static final int MAX_LIVE_PER_MAP = 3;

    /** Puts a thread on its map, dropping the oldest when the map is full. */
    static synchronized void hold(Live l) {
        long k = key(l.channel, l.mapId);
        List<Live> threads = new ArrayList<>(live.getOrDefault(k, List.of()));
        threads.removeIf(old -> old.id == l.id);
        threads.add(l);
        while (threads.size() > MAX_LIVE_PER_MAP) {
            threads.remove(0);
        }
        live.put(k, List.copyOf(threads));
    }

    /** Drops threads that ended longer ago than the context window keeps. */
    static synchronized void forget(long now) {
        for (Long k : new ArrayList<>(live.keySet())) {
            List<Live> threads = live.get(k);
            if (threads == null) {
                continue;
            }
            List<Live> kept = new ArrayList<>(threads.size());
            for (Live l : threads) {
                if (now - l.endsAt <= CONTEXT_MS) {
                    kept.add(l);
                }
            }
            if (kept.isEmpty()) {
                live.remove(k);
            } else if (kept.size() != threads.size()) {
                live.put(k, List.copyOf(kept));
            }
        }
    }

    // ── the tick ──

    static void tick(long now) {
        if (!ConvoSwitches.on("threads") || soloMapling.server.BotShutdown.isQuiesced()) {
            return;
        }
        forget(now);
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
                pair = gossipFirst(pair);
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
        return topicFor(guildA, guildB, levelA, levelB, mapId, 0.25, rng);
    }

    /**
     * As {@link #topicFor(int, int, int, int, int, Random)}, for a pair whose mean Socializer share is {@code social}
     * (1.6.0 S4, Bartle): Socializers gossip more - the gossip share is 25% at 0.25 and grows to about 40% at 0.6.
     */
    static Topic topicFor(int guildA, int guildB, int levelA, int levelB, int mapId, double social, Random rng) {
        double gossip = Math.max(0.15, Math.min(0.45, 0.25 + 0.45 * (social - 0.25)));
        if (guildA > 0 && guildB > 0 && guildA != guildB
                && "war".equals(soloMapling.ArtificialPlayer.BotDramaSystem.GuildWars.relation(guildA, guildB))) {
            return Topic.GUILD_BEEF;
        }
        if (mapId >= 910000000 && mapId <= 910000022) {
            return rng.nextDouble() < 1 - gossip * 1.2 ? Topic.TRADE : Topic.GOSSIP;
        }
        double r = rng.nextDouble();
        int level = Math.min(levelA, levelB);
        if (r < 0.35 && level >= 21 && level <= 70) {
            return Topic.LFG;
        }
        if (r < 0.35 + gossip) {
            return Topic.GOSSIP;
        }
        if (r < 0.5 + gossip) {
            return Topic.TRADE;
        }
        return Topic.TRAINING;
    }

    /**
     * Runs one thread now (also the {@code !qa cv-5} door). Returns the topic, or empty when it could not start.
     */
    public static List<String> run(MapleMap map, int channel, List<Character> pair, Topic forced, long now) {
        return run(map, channel, pair, forced, null, false, now);
    }

    /**
     * {@code !qa gs-1}: a thread about a given true story ({@code fact}; null: grounded as usual), from the line bank
     * only when {@code bankOnly} (a deterministic opener), else as the tick would (the model when it is free).
     */
    public static List<String> run(MapleMap map, int channel, List<Character> pair, Topic forced,
                                   soloMapling.ArtificialPlayer.BotGossip.Fact given, boolean bankOnly, long now) {
        Character a = pair.get(0);
        Character b = pair.get(1);
        Random rng = ThreadLocalRandom.current();
        Topic topic = forced != null ? forced : topicFor(a.getGuildId(), b.getGuildId(), a.getLevel(), b.getLevel(),
                map.getId(), (social(a) + social(b)) / 2, rng);
        soloMapling.ArtificialPlayer.BotGossip.Fact fact = given != null ? given
                : ground(topic, a, b, map.getAllPlayers(), now, rng);
        String about = fact != null ? fact.about() : about(topic, a, b);
        Live l = new Live(channel, map.getId(), List.of(a.getId(), b.getId()), List.of(a.getName(), b.getName()), topic,
                about, fact, now + TimeUnit.MINUTES.toMillis(2), a.getId());
        l.startedAt = now;
        hold(l);
        lastThreadOf.put(a.getId(), l);
        lastThreadOf.put(b.getId(), l);
        soloMapling.server.MapleRootSolo.runHook("bond thread", () -> // 1.6.0 S2 (S4 owns this file later)
                soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.onThread(a.getId(), b.getId(),
                        topic == Topic.GUILD_BEEF));
        qa(map.getId(), "THREAD " + a.getName() + " + " + b.getName() + " on " + map.getId() + ": " + topic.words
                + (about == null ? "" : " (" + about + ")"));
        if (fact != null) {
            qa(map.getId(), "THREAD FACT " + fact.kind() + ": " + fact.about() + (fact.person() ? " names "
                    + fact.names() : " (no people)"));
        }
        java.util.Set<String> allowed = soloMapling.ArtificialPlayer.BotGossip.allowedNames(fact, a.getName(), b.getName());
        if (!bankOnly && BotBrains.get().isLive() && !ConvoSwitches.forceBank()) {
            List<BotBrain.Turn> prompt = List.of(BotBrain.Turn.system(threadPrompt(a, b, topic, about, map.getMapName(),
                            fact)), BotBrain.Turn.user("Write the lines now."));
            BotBrains.get().reply(Priority.THREAD, 0, prompt).thenAccept(r -> {
                List<String[]> lines = guard(r.map(x -> parse(x.text(), a.getName(), b.getName())).orElse(List.of()),
                        allowed, l.mapId);
                if (lines.size() < 2) {
                    play(l, a, b, guard(fallback(topic, a, b, fact, rng), allowed, l.mapId), "bank");
                } else {
                    play(l, a, b, lines, "llm");
                }
            });
        } else {
            play(l, a, b, guard(fallback(topic, a, b, fact, rng), allowed, l.mapId), "bank");
        }
        return List.of(topic.words);
    }

    /** A bot's Socializer share (Bartle, S1), 0.25 when unknown. */
    static double social(Character c) {
        try {
            return soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf.of(c.getId()).socialPull();
        } catch (RuntimeException | LinkageError e) {
            return 0.25;
        }
    }

    /**
     * The more talkative of the two opens a gossip thread: with a 50/50 tick pick, the Socializer of a pair speaks
     * first a little more often (its share against the other's).
     */
    static List<Character> gossipFirst(List<Character> pair) {
        double sa = social(pair.get(0));
        double sb = social(pair.get(1));
        return ThreadLocalRandom.current().nextDouble() * (sa + sb) < sa || sa + sb <= 0 ? pair
                : List.of(pair.get(1), pair.get(0));
    }

    /**
     * 1.6.0 S4: the real story a GOSSIP or TRAINING thread is about ({@link soloMapling.ArtificialPlayer.BotGossip}:
     * reputation, bond drama, world news; else a topic without people), or null for the other topics. {@code a} tells.
     */
    static soloMapling.ArtificialPlayer.BotGossip.Fact ground(Topic topic, Character a, Character b,
                                                             java.util.Collection<Character> present, long now,
                                                             Random rng) {
        try {
            return switch (topic) {
                case GOSSIP -> soloMapling.ArtificialPlayer.BotGossip.factFor(a, b, present, now, rng);
                case TRAINING -> soloMapling.ArtificialPlayer.BotGossip.trainingFor(a, b, rng);
                default -> null;
            };
        } catch (RuntimeException | LinkageError e) {
            log.debug("No gossip fact for {} and {}", a.getName(), b.getName(), e);
            return null;
        }
    }

    /**
     * 1.6.0 S4 name guard: every line that names someone who is not a real character (the two speakers, the fact's
     * people, anyone online or in the name registry) is rewritten without the name or dropped ({@link NameGuard}).
     */
    static List<String[]> guard(List<String[]> lines, java.util.Collection<String> allowed, int mapId) {
        List<String[]> out = new ArrayList<>(lines.size());
        for (String[] line : lines) {
            NameGuard.Verdict v = NameGuard.check(line[1], allowed);
            if (!v.clean()) {
                qa(mapId, "NAMEGUARD " + (v.dropped() ? "DROP" : "REWRITE") + " " + line[0] + ": \"" + line[1]
                        + "\" names " + v.unknown() + (v.dropped() ? "" : " -> \"" + v.line() + "\""));
            }
            if (!v.dropped()) {
                out.add(new String[]{line[0], v.line()});
            }
        }
        return out;
    }

    /**
     * What a thread is about in a few words. GOSSIP and TRAINING (1.6.0 S4) are grounded in real facts
     * ({@link #ground}): a real KS, scam or gift, a bond's drama, a boss kill or drop in the news, a crew mate's spot -
     * or a topic without people; never null for them.
     */
    static String about(Topic topic, Character a, Character b) {
        try {
            return switch (topic) {
                case GOSSIP, TRAINING -> {
                    soloMapling.ArtificialPlayer.BotGossip.Fact fact = ground(topic, a, b,
                            a.getMap() == null ? List.of() : a.getMap().getAllPlayers(), System.currentTimeMillis(),
                            ThreadLocalRandom.current());
                    yield fact == null ? null : fact.about();
                }
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
        return threadPrompt(a, b, topic, about, mapName, null);
    }

    /**
     * As above, grounded (1.6.0 S4): a GOSSIP or TRAINING thread gets its true story and the only players it may
     * name; a thread without one is told to name no other player (the name guard still checks every line).
     */
    static String threadPrompt(Character a, Character b, Topic topic, String about, String mapName,
                               soloMapling.ArtificialPlayer.BotGossip.Fact fact) {
        ConvoPersona pa = ConvoPersona.of(a.getId() - BotStore.BOT_ID_BASE);
        ConvoPersona pb = ConvoPersona.of(b.getId() - BotStore.BOT_ID_BASE);
        String subject;
        if (fact != null && fact.person()) {
            subject = ". Topic: " + topic.words + ". What really happened: " + fact.about() + ". " + a.getName()
                    + " brings it up first. The only other players you may name: " + String.join(", ", fact.names())
                    + "; never make up any other player name";
        } else if (fact != null) {
            subject = ". Topic: " + topic.words + " (" + fact.about() + "), in general: name no other player";
        } else {
            subject = ". Topic: " + topic.words + (about == null ? "" : " (" + about + ")") + "; name no other player";
        }
        return "Write a short public chat exchange between two MapleStory players in 2008, " + a.getName() + " (level "
                + a.getLevel() + ") and " + b.getName() + " (level " + b.getLevel() + "), standing in "
                + (mapName == null || mapName.isBlank() ? "town" : mapName) + subject + ". 2 to 4 lines in total,"
                + " alternating, " + a.getName() + " first, each under 60 characters. " + a.getName() + " "
                + pa.voice().prompt + ". " + b.getName() + " " + pb.voice().prompt + ". Format each line as Name: text."
                + " Game talk only, PG-13, insults only about the game. Never mention AI, bots, real places, real people"
                + " or anything after 2008.";
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
        return fallback(topic, a, b, null, rng);
    }

    /**
     * As above, grounded (1.6.0 S4): when the thread has a true story with an opener ("did u hear Gm ksed Nayrb"),
     * {@code a} says it and {@code b} reacts with a bank line - or, now and then, one of its catchphrases as a whole
     * line ("Fair enough.").
     */
    static List<String[]> fallback(Topic topic, Character a, Character b, soloMapling.ArtificialPlayer.BotGossip.Fact fact,
                                   Random rng) {
        if (fact != null && fact.opener() != null) {
            int slotB = b.getId() - BotStore.BOT_ID_BASE;
            String react = VoiceStyler.catchphraseLine(ConvoPersona.of(slotB), slotB, fact.about().hashCode(), 0.15);
            if (react == null) {
                ConvoLines.Line bank = ConvoLines.line("talk", b, a, null, null, null, false);
                react = bank == null ? null : bank.text();
            }
            List<String[]> out = new ArrayList<>();
            out.add(new String[]{a.getName(), fact.opener()});
            if (react != null) {
                out.add(new String[]{b.getName(), react});
            }
            return out;
        }
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
        List<ConversationLines.Script> candidates = new ArrayList<>(ConversationLines.scripts());
        return scripted(a, b, candidates, rng);
    }

    /**
     * A cut of one script both bots can back, picked at random among those, or empty when no script of
     * {@code candidates} is one they can back (the caller then leaves the thread unscripted). A script whose
     * {@code S>} ad the speaker does not hold is skipped whole, never edited.
     */
    static List<String[]> scripted(Character a, Character b, List<ConversationLines.Script> candidates, Random rng) {
        List<String[]> out = new ArrayList<>();
        List<ConversationLines.Script> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, rng);
        List<ConversationLines.Refusal> refused = new ArrayList<>();
        ConversationLines.Script s = ConversationLines.firstPlayable(shuffled,
                role -> "A".equals(role) ? a : b, refused::add);
        ConversationLines.logRefusals(refused);
        if (s == null) {
            return out; // no script these two can back: the thread stays unscripted
        }
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
            List<String> protect = new ArrayList<>(List.of(a.getName().toLowerCase(Locale.ROOT),
                    b.getName().toLowerCase(Locale.ROOT)));
            if (l.fact != null) { // 1.6.0 S4: the people the story is about keep their names (no typo in a name)
                for (String n : l.fact.names()) {
                    protect.add(n.toLowerCase(Locale.ROOT));
                }
            }
            String styled = VoiceStyler.style(honest, ConvoPersona.of(slot), slot, protect);
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
     * A player's line on a map with a live thread that names no one joins the thread the line is about: the bot that
     * answers it (that thread's last speaker), or null. The router resolved that thread already
     * ({@link #liveView(int, int, long, Ask)}) and passes its id, so the JOIN credits the thread the bot answers for.
     */
    public static Integer jumpIn(int channel, int mapId, int threadId, String playerName, String line, long now) {
        if (line == null || line.isBlank() || !ConvoSwitches.on("threads")) {
            return null;
        }
        Live l = null;
        for (Live t : liveOn(channel, mapId, now)) {
            if (t.id == threadId) {
                l = t;
                break;
            }
        }
        if (l == null) {
            return null;
        }
        qa(mapId, "JOIN a player jumps into " + l.names + " (" + l.topic.words + "): " + ConvoQuality.anonymise(line, playerName));
        return l.lastSpeakerId;
    }

    /** What the router knows about the player's line: the words, and where the player stands. */
    public record Ask(String line, int x, int y) {
    }

    /** The threads live on a map, oldest first (empty when none, or when threads are switched off). */
    private static List<Live> liveOn(int channel, int mapId, long now) {
        List<Live> threads = live.get(key(channel, mapId));
        if (threads == null || threads.isEmpty()) {
            return List.of();
        }
        List<Live> out = new ArrayList<>(threads.size());
        for (Live l : threads) {
            if (now <= l.endsAt) {
                out.add(l);
            }
        }
        return out;
    }

    private static View view(Live l) {
        return new View(l.id, l.botIds, l.names, l.lastSpeakerId, l.startedAt, l.lastLineAt, subject(l.topic, l.about));
    }

    /** The newest live thread on a map, for chat routing, or null (none, over, or threads switched off). */
    public static View liveView(int channel, int mapId, long now) {
        if (!ConvoSwitches.on("threads")) {
            return null;
        }
        List<Live> threads = liveOn(channel, mapId, now);
        return threads.isEmpty() ? null : view(threads.getLast());
    }

    /**
     * The live thread on this map that the player's line is about - the one at their feet when two fit it equally
     * (TEST cv-5-20261024T222812Z: a thread 4964 px away held the map's only slot, so the Kerning PQ thread 129 px
     * away never got the line). A map used to hold one thread; the newest thread still wins when nothing fits.
     *
     * <p>{@code near} is the caller's hearing rule ({@link
     * soloMapling.ArtificialPlayer.BotMessagingSystem.ChatRoute#inBubble}): a thread none of whose speakers is within
     * earshot is no candidate at all, whatever its fit - otherwise a far thread that matches a word more would shadow
     * the one the player is standing next to, and the router would then drop it for being unheard.
     */
    public static View liveView(int channel, int mapId, long now, Ask ask,
                                java.util.function.IntPredicate near) {
        if (!ConvoSwitches.on("threads")) {
            return null;
        }
        Live best = null;
        int bestFit = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (Live l : liveOn(channel, mapId, now)) {
            if (near != null && !anyNear(l, near)) {
                continue;
            }
            int fit = ask == null ? 0 : fit(l, ask.line());
            int distance = ask == null ? 0 : nearest(l, ask.x(), ask.y());
            if (best == null || fit > bestFit
                    || (fit == bestFit && (distance < bestDistance
                    || (distance == bestDistance && l.lastLineAt > best.lastLineAt)))) {
                best = l;
                bestFit = fit;
                bestDistance = distance;
            }
        }
        return best == null ? null : view(best);
    }

    /** Whether either of the thread's two speakers stands where the player would be heard (the caller's rule). */
    private static boolean anyNear(Live l, java.util.function.IntPredicate near) {
        for (int id : l.botIds) {
            if (near.test(id)) {
                return true;
            }
        }
        return false;
    }

    /** How much of the player's line is about this thread: the words of its subject ("looking for a party (Kerning PQ)"). */
    static int fit(Live l, String line) {
        Set<String> said = MapChatLog.words(line);
        if (said.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String word : MapChatLog.words(subject(l.topic, l.about))) {
            if (said.contains(word)) {
                n++;
            }
        }
        return n;
    }

    /** How far the nearest of the thread's two bots stands from this spot (the map's far side when unknown). */
    static int nearest(Live l, int x, int y) {
        int best = Integer.MAX_VALUE;
        for (int id : l.botIds) {
            try {
                BotSM sm = CharacterStorage.getBotById(id);
                Character chr = sm == null ? null : sm.getChr();
                if (chr != null && chr.getPosition() != null) {
                    best = Math.min(best, (int) Math.round(Math.hypot(chr.getPosition().x - x, chr.getPosition().y - y)));
                }
            } catch (RuntimeException e) {
                // no position to read: the other thread may win on distance
            }
        }
        return best;
    }

    /**
     * What a thread is about, as routing reads it: the topic and its detail ("looking for a party (Ludi PQ)"), so a
     * player's "lpq? i can come too" is a clear reply to it even when the two bots' own lines never said "lpq".
     */
    static String subject(Topic topic, String about) {
        return topic.words + (about == null || about.isBlank() ? "" : " (" + about + ")");
    }

    /** The detail of the newest live thread on a map ("Ludi PQ", "Legendz vs ZakumSlayerz"), else its topic; null none. */
    public static String aboutOf(int channel, int mapId) {
        List<Live> threads = liveOn(channel, mapId, System.currentTimeMillis());
        Live l = threads.isEmpty() ? null : threads.getLast();
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
        putLiveForTest(channel, mapId, a, nameA, b, nameB, topic, null, endsAt);
    }

    /** Test seam: a live thread on a map, about one thing ("Kerning PQ"). */
    static void putLiveForTest(int channel, int mapId, int a, String nameA, int b, String nameB, Topic topic, String about,
                               long endsAt) {
        Live l = new Live(channel, mapId, List.of(a, b), List.of(nameA, nameB), topic, about, null, endsAt, b);
        l.lastLineAt = Math.max(0, endsAt - JOIN_MS);
        hold(l);
        lastThreadOf.put(a, l);
        lastThreadOf.put(b, l);
    }
}
