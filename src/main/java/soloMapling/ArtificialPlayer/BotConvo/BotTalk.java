package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotBrainSystem.Priority;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Feasible;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Mind;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Relation;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Act;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Offer;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Plan;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Step;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeGoals;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * 1.6.1 (ticket 114, owner 2026-10-27): bots talk to bots as a goal-driven decision tree - "playerbot says hi to
 * another playerbot -> casual convo -> asks to party -> other bot decides to say yes or no and in a certain way -> all
 * dependent on bots' internal goals". {@link TalkPlanner} decides every move first ({@link TalkBrain}); this runs it:
 * <ul>
 *   <li><b>Greetings:</b> a bot arriving on a map greets a bot there it knows (a bond, or a note from an earlier chat),
 *   sometimes going on into a chat ({@link #tick}).</li>
 *   <li><b>Chats:</b> now and then two idle bots, or a group of 3-4 friends standing together, talk; towns and the FM
 *   most, grind maps rarely, boss and event maps never; a gap per map and a world cap per minute.</li>
 *   <li><b>Words:</b> the line bank and short lines by default. The model words a whole conversation only when a real
 *   player is within view, at {@link Priority#THREAD} (only when every model slot is free) and a cap of
 *   {@code slums.bottalk.llmPer10Min} (6); each line it writes must keep the decided meaning, pass the content guard
 *   and the name guard, else the bank line stays.</li>
 *   <li><b>Intent with the line:</b> every line is noted with its intent ({@link BotLineIntents}); no bot line goes to
 *   the chat classifier.</li>
 *   <li><b>Acts:</b> a yes is carried out by the existing actions: a PQ party and trip ({@link LfgParty}) or a plain duo
 *   party, a deal ({@code BotBotTrade}), help (the helper's HELP_FRIEND), a come-along (both bots' next hang-out goes
 *   there), a story kept ({@code BotGossip.keep}).</li>
 *   <li><b>Memory:</b> each chat leaves a short note in both bots' life memory, written in a batch on the next tick
 *   ({@link LifeMemory#noteMet}), and later greetings bring it up.</li>
 *   <li><b>Players:</b> a talk is a live thread for chat routing, so a player's line near it joins it and the bot that
 *   answers uses the normal player path (classifier + model); a player who names a bot always does.</li>
 * </ul>
 * Cheap at 3,000 bots: one pass over the online bots per {@value #TICK_S} s grouping them by map, a few talks a minute;
 * its CPU is counted ({@link #stats}) and written to {@code bot-life.json} with the brain's. Switch:
 * {@code -Dslums.bottalk=true}. The social trace records each talk ({@code TALK_*} topics).
 */
public final class BotTalk {

    private static final Logger log = LoggerFactory.getLogger(BotTalk.class);

    static final int TICK_S = 10;
    /** Two bots greet each other again only after this long. */
    static final long GREET_PAIR_GAP_MS = TimeUnit.MINUTES.toMillis(20);
    /** How close bots stand to talk: within earshot, so nobody walks over (personal space unchanged). */
    static final int EAR_X = 600, EAR_Y = 250;
    /** A real player this close to the opener sees the talk (the model may word it). */
    static final int VIEW_X = 700, VIEW_Y = 450;
    static final long PLAIN_PARTY_MS = TimeUnit.MINUTES.toMillis(10);
    static final long COME_ALONG_MS = TimeUnit.MINUTES.toMillis(8);
    static final long HELP_MS = TimeUnit.MINUTES.toMillis(15);
    /** Talk ids are kept apart from the threads' in the social trace. */
    private static final AtomicInteger SEQ = new AtomicInteger(5_000_000);

    private static volatile boolean running;
    private static final Map<Integer, Long> lastMapOf = new ConcurrentHashMap<>();
    private static final Map<Long, Long> nextTalkAt = new ConcurrentHashMap<>();
    private static final Map<Long, Long> greetedAt = new ConcurrentHashMap<>();
    /** Bots in a talk now, and since when (a talk whose end never ran frees its bots after {@link #STUCK_MS}). */
    private static final Map<Integer, Long> inTalk = new ConcurrentHashMap<>();
    static final long STUCK_MS = TimeUnit.MINUTES.toMillis(3);
    /** Bots staged by QA: the tick leaves them alone (QA runs their talks itself). */
    private static final Set<Integer> qaHeld = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Live> byId = new ConcurrentHashMap<>();
    private static final Deque<Long> talkStarts = new ArrayDeque<>();
    private static final Deque<Long> greetStarts = new ArrayDeque<>();
    private static final Deque<Long> llmStarts = new ArrayDeque<>();
    private static final Deque<Long> plainParties = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<Note> notes = new ConcurrentLinkedQueue<>();

    private static final LongAdder ticks = new LongAdder(), cpuNanos = new LongAdder(), talks = new LongAdder(),
            greets = new LongAdder(), groups = new LongAdder(), lines = new LongAdder(), bankLines = new LongAdder(),
            builtInLines = new LongAdder(), llmLines = new LongAdder(), llmCalls = new LongAdder(),
            llmRejected = new LongAdder(), actsOk = new LongAdder(), actsFailed = new LongAdder(),
            notesWritten = new LongAdder(), playerJoins = new LongAdder(), dropped = new LongAdder();
    private static final Map<String, LongAdder> tally = new ConcurrentHashMap<>();

    /** A note for a bot's life memory, written in the next tick's batch. */
    record Note(int botId, int otherId, String otherName, String what, long at) {
    }

    /** A talk being said: its plan, its router thread, and who is in it. */
    static final class Live {
        final int id;
        final Plan plan;
        final int channel;
        final int mapId;
        final BotThreads.Live thread;
        final String topic;
        volatile String playerJoined;

        Live(int id, Plan plan, int channel, int mapId, BotThreads.Live thread, String topic) {
            this.id = id;
            this.plan = plan;
            this.channel = channel;
            this.mapId = mapId;
            this.thread = thread;
            this.topic = topic;
        }
    }

    private BotTalk() {
    }

    /** The switch: {@code -Dslums.bottalk=true}. */
    public static boolean on() {
        return Boolean.getBoolean("slums.bottalk");
    }

    static double num(String key, double fallback) {
        String raw = System.getProperty("slums.bottalk." + key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Starts the tick (EnvironmentManager, with the threads). */
    public static synchronized void start() {
        if (running || !on()) {
            return;
        }
        running = true;
        ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                tick(System.currentTimeMillis());
            } catch (RuntimeException | LinkageError e) {
                log.warn("Bot talk tick failed", e);
            }
        }), 60, TICK_S, TimeUnit.SECONDS);
        log.info("Bot talk: on (greetings, chats, groups; tick {} s)", TICK_S);
    }

    // ── the tick ──

    static void tick(long now) {
        if (!on() || soloMapling.server.BotShutdown.isQuiesced()) {
            return;
        }
        long began = System.nanoTime();
        try {
            ticks.increment();
            writeNotes(now);
            Map<Long, List<Character>> byMap = new HashMap<>();
            Set<Integer> seen = new HashSet<>();
            for (BotSM sm : CharacterStorage.getAllBots().values()) {
                Character c = sm == null ? null : sm.getChr();
                if (c == null || c.getMap() == null || CharacterStorage.checkIfInvisibleBot(c.getId())) {
                    continue;
                }
                seen.add(c.getId());
                byMap.computeIfAbsent(key(c.getMap().getChannelId(), c.getMapId()), k -> new ArrayList<>(4)).add(c);
            }
            Random rng = ThreadLocalRandom.current();
            List<Map.Entry<Long, List<Character>>> maps = new ArrayList<>(byMap.entrySet());
            Collections.shuffle(maps, rng);
            for (Map.Entry<Long, List<Character>> e : maps) {
                List<Character> here = e.getValue();
                long k = e.getKey();
                List<Character> arrived = new ArrayList<>(2);
                for (Character c : here) {
                    Long was = lastMapOf.put(c.getId(), k);
                    if (was != null && was != k) {
                        arrived.add(c);
                    }
                }
                if (here.size() < 2) {
                    continue;
                }
                Long next = nextTalkAt.get(k);
                boolean due = next == null || now >= next;
                if (arrived.isEmpty() && !due) {
                    continue; // nothing to do here this tick: no map read at all
                }
                MapleMap map = here.get(0).getMap();
                double weight = BotThreads.mapWeight(map);
                if (weight <= 0) {
                    continue;
                }
                for (Character c : arrived) {
                    greetOnArrival(c, here, map, now, rng);
                }
                if (due) {
                    maybeChat(k, here, map, weight, now, rng);
                }
            }
            lastMapOf.keySet().retainAll(seen);
            inTalk.entrySet().removeIf(x -> now - x.getValue() > STUCK_MS);
            if (greetedAt.size() > 20_000) {
                greetedAt.values().removeIf(t -> now - t > GREET_PAIR_GAP_MS);
            }
        } finally {
            cpuNanos.add(System.nanoTime() - began);
        }
    }

    static long key(int channel, int mapId) {
        return ((long) channel << 32) | (mapId & 0xffffffffL);
    }

    static long pair(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    /** A bot that just arrived greets one bot here it knows (its strongest bond first), maybe into a chat. */
    static void greetOnArrival(Character c, List<Character> here, MapleMap map, long now, Random rng) {
        if (!free(c, false) || !budget(greetStarts, now, TimeUnit.MINUTES.toMillis(1), (int) num("greetsPerMin", 20))) {
            return;
        }
        Map<Integer, Character> present = new HashMap<>();
        for (Character o : here) {
            if (o != c && near(c, o, EAR_X, EAR_Y)) {
                present.put(o.getId(), o);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        Character known = null;
        for (BotBonds.View v : BotBonds.bondsOf(c.getId())) { // at most 14, strongest feeling first
            Character o = present.get(v.otherId());
            if (o != null) {
                known = o;
                break;
            }
        }
        if (known == null) {
            LifeMemory mem = memoryOf(c.getId());
            if (mem != null) {
                for (LifeMemory.Met m : mem.met()) {
                    Character o = present.get(m.otherId());
                    if (o != null) {
                        known = o;
                    }
                }
            }
        }
        if (known == null || !free(known, false)) {
            return;
        }
        Long last = greetedAt.get(pair(c.getId(), known.getId()));
        if (last != null && now - last < GREET_PAIR_GAP_MS) {
            return;
        }
        WorldSense sense = new WorldSense();
        Mind mc = sense.mind(c, now);
        Relation rel = sense.relation(c, known);
        if (!TalkBrain.greets(mc, rel, rng)) {
            return;
        }
        greetedAt.put(pair(c.getId(), known.getId()), now);
        took(greetStarts, now);
        boolean chat = BotThreads.talker(CharacterStorage.getBotById(c.getId()))
                && BotThreads.talker(CharacterStorage.getBotById(known.getId()))
                && TalkBrain.chats(mc, sense.mind(known, now), rel, rng);
        run(map, List.of(c, known), !chat, null, false, chat ? "met a friend, stopped to chat" : "met someone it knows",
                now, rng);
    }

    /** Now and then two idle bots here talk, or a group of 3-4 standing together. */
    static void maybeChat(long mapKey, List<Character> here, MapleMap map, double weight, long now, Random rng) {
        Long next = nextTalkAt.get(mapKey);
        if (next != null && now < next) {
            return;
        }
        double lo = num("gapMin", 2), hi = Math.max(lo, num("gapMax", 5));
        long gap = (long) ((lo + rng.nextDouble() * (hi - lo)) * 60_000);
        nextTalkAt.put(mapKey, now + gap);
        BotThreads.View thread = BotThreads.liveView(BotThreads.channelOf(map), map.getId(), now);
        if (thread != null && !byId.containsKey(thread.id())) {
            return; // a 1.6.0 thread is going on here (in front of a player): one conversation at a time
        }
        if (rng.nextDouble() >= weight || !budget(talkStarts, now, TimeUnit.MINUTES.toMillis(1),
                (int) num("talksPerMin", 12))) {
            return;
        }
        List<Character> idle = new ArrayList<>();
        for (Character c : here) {
            if (free(c, true)) {
                idle.add(c);
            }
        }
        if (idle.size() < 2) {
            return;
        }
        Collections.shuffle(idle, rng);
        Character a = gossipFirst(idle);
        List<Character> close = new ArrayList<>();
        for (Character o : idle) {
            if (o != a && near(a, o, EAR_X, EAR_Y)) {
                close.add(o);
            }
        }
        if (close.isEmpty()) {
            return;
        }
        // friends first: a crew standing together talks as a group (3-4), else the opener's best-known bot
        close.sort((x, y) -> Double.compare(BotBonds.score(a.getId(), y.getId()), BotBonds.score(a.getId(), x.getId())));
        List<Character> who = new ArrayList<>(List.of(a, close.get(0)));
        int friends = 0;
        for (Character o : close) {
            if (BotBonds.friend(a.getId(), o.getId())) {
                friends++;
            }
        }
        boolean group = close.size() >= 2 && (friends >= 2 || rng.nextDouble() < num("groupChance", 0.2));
        if (group) {
            int size = Math.min(close.size() + 1, 3 + (rng.nextDouble() < 0.4 ? 1 : 0));
            for (int i = 1; who.size() < size && i < close.size(); i++) {
                who.add(close.get(i));
            }
        }
        took(talkStarts, now);
        run(map, who, false, null, false, who.size() >= 3 ? "a group standing together (" + friends + " friends)"
                : "two idle bots near each other", now, rng);
    }

    /** The Socializer of the idle ones opens a little more often. */
    static Character gossipFirst(List<Character> idle) {
        Character best = idle.get(0);
        double top = -1;
        for (int i = 0; i < Math.min(4, idle.size()); i++) {
            double s = BotThreads.social(idle.get(i)) + ThreadLocalRandom.current().nextDouble() * 0.5;
            if (s > top) {
                top = s;
                best = idle.get(i);
            }
        }
        return best;
    }

    static synchronized void took(Deque<Long> starts, long now) {
        starts.addLast(now);
    }

    static synchronized boolean budget(Deque<Long> starts, long now, long windowMs, int max) {
        while (!starts.isEmpty() && now - starts.peekFirst() > windowMs) {
            starts.pollFirst();
        }
        return starts.size() < max;
    }

    static boolean near(Character a, Character b, int dx, int dy) {
        java.awt.Point pa = a.getPosition();
        java.awt.Point pb = b.getPosition();
        return pa != null && pb != null && Math.abs(pa.x - pb.x) <= dx && Math.abs(pa.y - pb.y) <= dy;
    }

    /**
     * Whether a bot may take part: not already talking (a talk, a thread, a player chat, a drama scene, a scripted
     * town conversation); {@code idle}: also free for a chat (a town bot loitering, a training bot resting in town).
     */
    static boolean free(Character c, boolean idle) {
        int id = c.getId();
        Long since = inTalk.get(id);
        if (since != null && System.currentTimeMillis() - since > STUCK_MS) {
            inTalk.remove(id, since); // its talk never finished (a lost timer): free again
            since = null;
        }
        if (since != null || qaHeld.contains(id) || BotThreads.busy(id)) {
            return false;
        }
        try {
            if (soloMapling.ArtificialPlayer.ConversationManager.getInstance().isInConversation(id)
                    || soloMapling.ArtificialPlayer.BotConversation.hasAnyChat(id)
                    || soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.inScene(id)) {
                return false;
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        return !idle || BotThreads.talker(CharacterStorage.getBotById(id));
    }

    // ── one talk ──

    /**
     * Plans and says one talk among {@code who} (opener first) on {@code map}. {@code forced}: the ask (QA);
     * {@code bankOnly}: no model (QA). Returns the plan, or null when it could not start.
     */
    public static Plan run(MapleMap map, List<Character> who, boolean greetOnly, Ask forced, boolean bankOnly,
                           String why, long now, Random rng) {
        for (Character c : who) {
            if (inTalk.putIfAbsent(c.getId(), now) != null) {
                for (Character d : who) {
                    if (d == c) {
                        break;
                    }
                    inTalk.remove(d.getId());
                }
                return null;
            }
        }
        try {
            WorldSense sense = new WorldSense();
            Plan plan = TalkPlanner.plan(who, greetOnly, forced, sense, true, now, rng);
            int id = SEQ.incrementAndGet();
            int channel = BotThreads.channelOf(map);
            String topic = topicOf(plan);
            BotThreads.Live thread = register(id, map, channel, plan, now);
            Live live = new Live(id, plan, channel, map.getId(), thread, topic);
            byId.put(id, live);
            count(greetOnly ? "kind:greet" : who.size() >= 3 ? "kind:group" : "kind:chat");
            if (greetOnly) {
                greets.increment();
            } else {
                talks.increment();
                if (who.size() >= 3) {
                    groups.increment();
                }
            }
            if (plan.ask != null) {
                count("ask:" + plan.ask.name());
                plan.decisions.values().forEach(d -> {
                    count("answer:" + d.answer().name());
                    count("manner:" + d.manner().name());
                });
            }
            trace(live, map, why, now);
            String names = names(who);
            qa(map.getId(), "TALK " + id + " " + names + " on " + map.getId() + " (" + why + "): " + plan.summary());
            boolean viewed = !bankOnly && playerInView(map, who.get(0));
            if (viewed && BotBrains.get().isLive() && !ConvoSwitches.forceBank()
                    && budget(llmStarts, now, TimeUnit.MINUTES.toMillis(10), (int) num("llmPer10Min", 6))) {
                took(llmStarts, now);
                llmCalls.increment();
                List<BotBrain.Turn> prompt = List.of(BotBrain.Turn.system(prompt(plan, map.getMapName())),
                        BotBrain.Turn.user("Write the lines now."));
                BotBrains.get().reply(Priority.THREAD, 0, prompt).thenAccept(r -> {
                    int kept = reword(plan, r.map(x -> x.text()).orElse(null));
                    traceComposed(live, kept > 0 ? "llm" : "bank", r.isEmpty() ? "the model gave no reply" : kept > 0
                            ? null : "the model's lines were unusable", kept);
                    play(live);
                });
            } else {
                traceComposed(live, "bank", bankOnly ? "bank only (!qa)" : !viewed ? "no real player in view"
                        : !BotBrains.get().isLive() ? "the model is not live" : "line bank forced or model cap used", -1);
                play(live);
            }
            return plan;
        } catch (RuntimeException | LinkageError e) {
            for (Character c : who) {
                inTalk.remove(c.getId());
            }
            log.debug("Bot talk failed for {}", names(who), e);
            return null;
        }
    }

    /** The trace topic: TALK_PARTY, TALK_COME_ALONG...; TALK for small talk with no ask, GREET for a hello. */
    static String topicOf(Plan plan) {
        if (plan.greetOnly) {
            return "GREET";
        }
        return plan.ask == null ? "TALK" : "TALK_" + plan.ask.name();
    }

    /** The talk is a live thread for chat routing (a player's line near it joins it). */
    private static BotThreads.Live register(int id, MapleMap map, int channel, Plan plan, long now) {
        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Character c : plan.who) {
            ids.add(c.getId());
            names.add(c.getName());
        }
        BotThreads.Topic topic = plan.ask == null ? BotThreads.Topic.GOSSIP : switch (plan.ask) {
            case PARTY -> BotThreads.Topic.LFG;
            case TRADE -> BotThreads.Topic.TRADE;
            case HELP -> BotThreads.Topic.TRAINING;
            case COME_ALONG, GOSSIP -> BotThreads.Topic.GOSSIP;
        };
        BotThreads.Live l = new BotThreads.Live(channel, map.getId(), List.copyOf(ids), List.copyOf(names), topic,
                plan.offer != null ? plan.offer.about() : plan.topic, null, now + TimeUnit.MINUTES.toMillis(2),
                ids.get(0));
        l.startedAt = now;
        BotThreads.hold(l);
        return l;
    }

    /** Says the plan's lines with typing time, each with its intent, and carries out the acts after their lines. */
    static void play(Live live) {
        Plan plan = live.plan;
        soloMapling.server.BotTiming.Chain chain = soloMapling.server.BotTiming.chain();
        Random rng = ThreadLocalRandom.current();
        long total = 0;
        if (soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.threadPlanned(live.id, plan.steps.size());
        }
        Set<String> allowed = new HashSet<>();
        for (Character c : plan.who) {
            allowed.add(c.getName().toLowerCase(Locale.ROOT));
        }
        for (Step s : plan.steps) {
            int slot = s.speaker.getId() - BotStore.BOT_ID_BASE;
            String voiced = VoiceStyler.style(s.text, ConvoPersona.of(slot), slot, allowed);
            String styled = voiced.length() > BotThreads.LINE_MAX && s.text.length() <= BotThreads.LINE_MAX
                    ? VoiceStyler.oneAccentWord(s.text) : voiced;
            long wait = HumanTiming.typingMs(styled, 0, rng) + 400 + rng.nextInt(1200);
            total += wait;
            chain = chain.pause(wait).run(() -> say(live, s, styled));
            if (s.act != null) {
                Act act = s.act;
                chain = chain.pause(800 + rng.nextInt(1500)).run(() -> act(live, act));
            }
        }
        live.thread.endsAt = System.currentTimeMillis() + total + BotThreads.JOIN_MS;
        chain.run(() -> finished(live)).start();
    }

    private static void say(Live live, Step s, String styled) {
        Character who = s.speaker;
        if (who.getMap() == null || who.getMapId() != live.mapId) {
            dropped.increment();
            if (soloMapling.autoqa.SocialTrace.on()) {
                soloMapling.autoqa.SocialTrace.threadDropped(live.id, who.getName(), styled, "the speaker left the map");
            }
            return;
        }
        long now = System.currentTimeMillis();
        BotLineIntents.note(who.getId(), styled, s.intent.label(), s.to == null ? 0 : s.to.getId(), now);
        SayDo.withSource("talk-" + s.source, () -> BotSpeak(who, styled));
        lines.increment();
        (switch (s.source) {
            case "llm" -> llmLines;
            case "bank" -> bankLines;
            default -> builtInLines;
        }).increment();
        live.thread.lastSpeakerId = who.getId();
        live.thread.lastLineAt = now;
        live.thread.endsAt = Math.max(live.thread.endsAt, now + BotThreads.JOIN_MS);
        if (soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.threadLine(live.id, who, styled);
        }
        qa(live.mapId, "TALK LINE " + live.id + " [" + s.source + "] " + who.getName() + " (" + s.intent.label() + "): "
                + styled);
    }

    // ── acts ──

    /** Carries out a yes through the existing action; the trace and QA log say what happened. */
    static void act(Live live, Act act) {
        String kind;
        String text;
        boolean ok;
        try {
            Result r = carryOut(live, act);
            ok = r.ok;
            kind = r.kind;
            text = r.text;
        } catch (RuntimeException | LinkageError e) {
            ok = false;
            kind = "act-drop";
            text = "error: " + e.getClass().getSimpleName();
            log.debug("Bot talk act failed", e);
        }
        (ok ? actsOk : actsFailed).increment();
        count("act:" + kind);
        qa(live.mapId, "TALK ACT " + live.id + " " + act.ask() + " " + (ok ? "done: " : "not done: ") + text);
        if (soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.threadActed(live.id, act.asker().getId(), kind, text);
        }
        String what = switch (act.ask()) {
            case PARTY -> "partied up";
            case TRADE -> "traded";
            case HELP -> "said they'd help train";
            case COME_ALONG -> "went to " + (act.counterTown() != null ? act.counterTown() : act.offer().about()) + " together";
            case GOSSIP -> "talked about " + act.offer().about();
        };
        if (ok) {
            for (Character c : act.yes()) {
                note(act.asker(), c, what);
                note(c, act.asker(), what);
            }
        }
    }

    record Result(boolean ok, String kind, String text) {
    }

    /** Test seam: how acts are carried out (the real world by default). */
    static volatile java.util.function.BiFunction<Live, Act, Result> doer = BotTalk::realAct;

    static Result carryOut(Live live, Act act) {
        return doer.apply(live, act);
    }

    private static Result realAct(Live live, Act act) {
        Character a = act.asker();
        Offer o = act.offer();
        long now = System.currentTimeMillis();
        switch (act.ask()) {
            case PARTY -> {
                if (o.payload() instanceof soloMapling.ArtificialPlayer.BotLifeSystem.LifePq.Lobby) {
                    LfgParty.Plan lfg = LfgParty.plan(a, act.yes().get(0), now); // takes a slot of the LFG cap
                    if (!lfg.yes()) {
                        return new Result(false, "lfg-drop", "LFG rules now say no: " + lfg.decline());
                    }
                    LfgParty.form(lfg, live.mapId, live.id);
                    return new Result(true, "lfg", "PQ party for " + o.about() + " (formed and the trip handed over,"
                            + " unless the LFG rules' last check refuses)");
                }
                return plainParty(a, act.yes(), live.mapId);
            }
            case TRADE -> {
                if (!(o.payload() instanceof soloMapling.Economy.BotBotTrade.Deal deal)) {
                    return new Result(false, "trade_failed", "no deal");
                }
                soloMapling.Economy.BotBotTrade.Result r = soloMapling.Economy.BotBotTrade.execute(deal);
                return new Result(r.ok(), r.ok() ? "trade" : "trade_failed", r.why());
            }
            case HELP -> {
                Character helper = act.yes().get(0);
                LifeRuntime life = lifeOf(helper.getId());
                if (life == null) {
                    return new Result(false, "help-drop", helper.getName() + " has no daily life to go help with");
                }
                life.botHelp(a.getId(), now + HELP_MS);
                nudge(helper.getId(), 1_000 + ThreadLocalRandom.current().nextInt(3_000));
                return new Result(true, "help", helper.getName() + " will go help " + a.getName() + " at " + o.about()
                        + " when its brain is free");
            }
            case COME_ALONG -> {
                int town = act.counterTown() != null && o.payload() instanceof Integer t ? t
                        : o.payload() instanceof Integer t2 ? t2 : -1;
                if (town <= 0) {
                    return new Result(false, "trip-drop", "no town");
                }
                List<String> going = new ArrayList<>();
                long delay = 1_000;
                for (Character c : with(a, act.yes())) {
                    LifeRuntime life = lifeOf(c.getId());
                    if (life != null) {
                        life.invite(town, now + COME_ALONG_MS);
                        nudge(c.getId(), delay);
                        delay += 2_000 + ThreadLocalRandom.current().nextInt(4_000);
                        going.add(c.getName());
                    }
                }
                return going.isEmpty() ? new Result(false, "trip-drop", "nobody here has a daily life to travel with")
                        : new Result(true, "trip", String.join(", ", going) + " head to " + TalkPlanner.townName(town)
                        + " (map caps checked on the way)");
            }
            case GOSSIP -> {
                if (!(o.payload() instanceof soloMapling.ArtificialPlayer.BotGossip.Fact fact)) {
                    return new Result(false, "kept-drop", "no story");
                }
                List<String> kept = new ArrayList<>();
                for (Character c : act.yes()) {
                    if (soloMapling.ArtificialPlayer.BotGossip.keep(c.getId(), a.getId(), a.getName(), fact, now) != null) {
                        kept.add(c.getName());
                    }
                }
                return kept.isEmpty() ? new Result(false, "kept-drop", "already known or too many hops")
                        : new Result(true, "kept", String.join(", ", kept) + " kept: " + fact.about());
            }
        }
        return new Result(false, "act-drop", "unknown ask");
    }

    private static List<Character> with(Character a, List<Character> others) {
        List<Character> out = new ArrayList<>(others.size() + 1);
        out.add(a);
        out.addAll(others);
        return out;
    }

    /** A plain party: the asker leads, the yes-sayers join; it breaks up after {@link #PLAIN_PARTY_MS}. */
    static Result plainParty(Character a, List<Character> yes, int mapId) {
        if (a.getMapId() != mapId) {
            return new Result(false, "party-drop", a.getName() + " left the map");
        }
        if (a.getParty() == null && !net.server.world.Party.createParty(a, true) || a.getParty() == null) {
            return new Result(false, "party-drop", "the party system refused " + a.getName() + "'s party");
        }
        int partyId = a.getParty().getId();
        List<Character> joined = new ArrayList<>(List.of(a));
        for (Character c : yes) {
            if (c.getParty() == null && c.getMapId() == mapId && net.server.world.Party.joinParty(c, partyId, true)) {
                joined.add(c);
            }
        }
        if (joined.size() < 2) {
            soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(a);
            return new Result(false, "party-drop", "nobody could join");
        }
        took(plainParties, System.currentTimeMillis());
        soloMapling.server.BotTiming.after(PLAIN_PARTY_MS, () -> breakUp(joined, partyId));
        return new Result(true, "party", "party " + partyId + ": " + names(joined));
    }

    /** A plain party breaks up again, unless a real player joined it. */
    static void breakUp(List<Character> members, int partyId) {
        for (Character c : members) {
            if (c.getParty() != null && c.getParty().getId() == partyId) {
                for (net.server.world.PartyCharacter m : c.getParty().getPartyMembersOnline()) {
                    if (m.getId() < BotStore.BOT_ID_BASE) {
                        return; // a real player joined: the party is theirs now
                    }
                }
                break;
            }
        }
        for (int i = members.size() - 1; i >= 0; i--) {
            Character c = members.get(i);
            if (c.getParty() != null && c.getParty().getId() == partyId) {
                soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(c);
            }
        }
    }

    static LifeRuntime lifeOf(int id) {
        BotSM sm = CharacterStorage.getBotById(id);
        return sm instanceof TrainingBot tb && LifeRuntime.enabled() ? tb.lifeRuntime() : null;
    }

    private static void nudge(int id, long delayMs) {
        BotSM sm = CharacterStorage.getBotById(id);
        if (sm instanceof TrainingBot tb && tb.lifeRuntime() != null) {
            tb.lifeRuntime().changed();
            tb.nudgeSoon(delayMs);
        }
    }

    /** After the last line: bonds, a memory note for each pair, and the bots are free again. */
    static void finished(Live live) {
        Plan plan = live.plan;
        try {
            Character a = plan.who.get(0);
            String where = mapName(a);
            for (Character c : plan.who.subList(1, plan.who.size())) {
                if (!plan.greetOnly) {
                    boolean spat = BotBonds.state(a.getId(), c.getId()).hostile();
                    soloMapling.server.MapleRootSolo.runHook("bond talk", () -> BotBonds.onThread(a.getId(), c.getId(), spat));
                }
                String what = plan.greetOnly ? "said hi in " + where : plan.ask == null ? "chatted in " + where
                        : askWords(plan.ask, plan.offer) + " (" + answerOf(plan, c) + ")";
                note(a, c, what);
                note(c, a, what);
            }
            if (soloMapling.autoqa.SocialTrace.on()) {
                soloMapling.autoqa.SocialTrace.threadBonded(live.id);
                soloMapling.autoqa.SocialTrace.threadRemembered(live.id, "notes for " + plan.who.size()
                        + " bots (life memory, written in the next batch)");
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("After talk {} failed", live.id, e);
        } finally {
            for (Character c : plan.who) {
                inTalk.remove(c.getId());
            }
            byId.remove(live.id);
        }
    }

    static String askWords(Ask ask, Offer o) {
        String about = o == null || o.about() == null ? "" : " " + o.about();
        return switch (ask) {
            case PARTY -> "asked to party" + about;
            case TRADE -> "talked trade:" + about;
            case HELP -> "asked for help at" + about;
            case COME_ALONG -> "asked to come to" + about;
            case GOSSIP -> "gossip:" + about;
        };
    }

    static String answerOf(Plan plan, Character c) {
        TalkBrain.Decision d = plan.decisions.get(c.getId());
        return d == null ? "no answer" : d.answer().name().toLowerCase(Locale.ROOT);
    }

    static void note(Character self, Character other, String what) {
        notes.add(new Note(self.getId(), other.getId(), other.getName(), what, System.currentTimeMillis()));
    }

    /** The batch: queued notes go to the bots' life memory (and its SOCIAL need), at most 500 a tick. */
    static int writeNotes(long now) {
        int n = 0;
        Note note;
        while (n < 500 && (note = notes.poll()) != null) {
            LifeMemory mem = memoryOf(note.botId());
            if (mem != null) {
                mem.noteMet(note.otherId(), note.otherName(), note.what(), note.at());
                mem.talked(note.at());
                n++;
            }
        }
        if (n > 0) {
            notesWritten.add(n);
        }
        return n;
    }

    /** Test seam: a bot's life memory (null without one). */
    static volatile java.util.function.IntFunction<LifeMemory> memories = id -> LifeRuntime.enabled()
            ? LifeMemoryStore.of(id) : null;

    static LifeMemory memoryOf(int id) {
        try {
            return memories.apply(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ── players ──

    /** A real player (not a bot, not a pretend auto-QA player, not hiding bot chat) within view of {@code at}. */
    static boolean playerInView(MapleMap map, Character at) {
        try {
            for (Character p : map.getAllPlayers()) {
                if (p == null || BotHelpers.isBot(p) || p.getClient() instanceof client.SyntheticClient
                        || !BotHelpers.isVisibleRealPlayer(p) || soloMapling.ArtificialPlayer.SocialGate.quietBots(p.getId())) {
                    continue;
                }
                if (near(p, at, VIEW_X, VIEW_Y)) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        return false;
    }

    /** A player's line joined a talk (BotThreads.jumpIn): the trace notes it; the bot answers on the player path. */
    public static void joined(int threadId, String playerName) {
        Live live = byId.get(threadId);
        if (live == null) {
            return;
        }
        live.playerJoined = playerName;
        playerJoins.increment();
        if (soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.threadActed(live.id, live.plan.who.get(0).getId(), "player-joined",
                    "a player joined the talk (answered on the player path)");
        }
    }

    // ── the model ──

    /** The prompt: the decided moves, in order; the model only words them. */
    static String prompt(Plan plan, String mapName) {
        StringBuilder b = new StringBuilder();
        b.append("You write short MapleStory (2009, v83) public chat lines between players standing in ")
                .append(mapName == null ? "town" : mapName).append(". Each line below is already decided: keep its ")
                .append("meaning exactly (a yes stays a yes, a no stays a no, the same place, item and price), keep the ")
                .append("speaker, and say it the way that player would type: lowercase, short (under 60 characters), ")
                .append("casual MMO chat, PG-13 (mild swearing at most, no slurs, nothing sexual, no real-life ")
                .append("insults). Never mention AI, bots, scripts or being an assistant. Name no player who is not ")
                .append("in this list: ").append(names(plan.who)).append(".\n");
        int i = 1;
        for (Step s : plan.steps) {
            b.append(i++).append(". ").append(s.speaker.getName()).append(" (")
                    .append(ConvoPersona.of(s.speaker.getId() - BotStore.BOT_ID_BASE).voice().prompt).append(") ")
                    .append(s.intent.label().toLowerCase(Locale.ROOT)).append(s.to == null ? " to everyone" : " to "
                            + s.to.getName()).append(": \"").append(s.text).append("\"\n");
        }
        b.append("Answer with exactly ").append(plan.steps.size()).append(" lines, one per line above, in order, each as ")
                .append("Name: text");
        return b.toString();
    }

    /**
     * Rewords the plan from the model's reply: line k replaces step k's words only when its speaker matches and it
     * keeps the meaning (a yes is no refusal, a no is no agreement), passes the content guard and the name guard and
     * is short. Returns how many lines the model wrote that were kept.
     */
    static int reword(Plan plan, String reply) {
        if (reply == null || reply.isBlank()) {
            return 0;
        }
        String[] rows = reply.strip().split("\\R+");
        Set<String> allowed = new HashSet<>();
        for (Character c : plan.who) {
            allowed.add(c.getName());
        }
        int kept = 0;
        for (int i = 0; i < plan.steps.size() && i < rows.length; i++) {
            Step s = plan.steps.get(i);
            String row = rows[i].strip().replaceFirst("^\\d+[.)]\\s*", "");
            int colon = row.indexOf(':');
            if (colon <= 0 || !row.substring(0, colon).strip().equalsIgnoreCase(s.speaker.getName())) {
                llmRejected.increment();
                continue;
            }
            String text = row.substring(colon + 1).strip().replaceAll("^\"|\"$", "");
            if (!keeps(s.intent, text) || text.length() > BotThreads.LINE_MAX || ContentGuard.chatProblem(text) != null
                    || !NameGuard.check(text, allowed).clean()) {
                llmRejected.increment();
                continue;
            }
            s.text = text;
            s.source = "llm";
            kept++;
        }
        return kept;
    }

    /** Whether a worded line keeps its intent's answer (a yes that refuses, or a no that agrees, is not kept). */
    static boolean keeps(TalkIntent intent, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (intent.answer() == Answer.YES) {
            return !LfgParty.DECLINE.matcher(text).find();
        }
        if (intent.answer() == Answer.NO) {
            return !LfgParty.agrees(text) || LfgParty.DECLINE.matcher(text).find();
        }
        return true;
    }

    // ── the trace ──

    private static void trace(Live live, MapleMap map, String why, long now) {
        if (!soloMapling.autoqa.SocialTrace.on()) {
            return;
        }
        Plan plan = live.plan;
        soloMapling.autoqa.SocialTrace.talkStart(live.id, map, live.channel, plan.who, live.topic, why,
                plan.offer != null ? plan.offer.about() : plan.topic, now);
        if (plan.ask != null) {
            boolean anyYes = plan.decisions.values().stream().anyMatch(TalkBrain.Decision::yes)
                    || plan.counterTaken != null && plan.counterTaken.yes();
            StringBuilder why2 = new StringBuilder();
            plan.decisions.forEach((id, d) -> why2.append(nameOf(plan, id)).append(": ").append(d.answer())
                    .append(' ').append(d.manner()).append(" (").append(d.why()).append("); "));
            soloMapling.autoqa.SocialTrace.threadDecided(live.id, anyYes ? plan.summary() : null,
                    anyYes ? null : "every answer was no: " + why2);
            soloMapling.autoqa.SocialTrace.talkWhy(live.id, why2.toString());
        } else {
            soloMapling.autoqa.SocialTrace.threadDecided(live.id, null, plan.noAskWhy);
        }
    }

    private static void traceComposed(Live live, String source, String fallback, int kept) {
        if (soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.threadComposed(live.id, source, fallback, "llm".equals(source) ? -1 : kept);
        }
    }

    private static String nameOf(Plan plan, int id) {
        for (Character c : plan.who) {
            if (c.getId() == id) {
                return c.getName();
            }
        }
        return String.valueOf(id);
    }

    static String names(List<Character> who) {
        StringBuilder b = new StringBuilder();
        for (Character c : who) {
            if (b.length() > 0) {
                b.append(" + ");
            }
            b.append(c.getName());
        }
        return b.toString();
    }

    private static String mapName(Character c) {
        try {
            String n = c.getMap() == null ? null : c.getMap().getMapName();
            return n == null || n.isBlank() ? "town" : n;
        } catch (RuntimeException e) {
            return "town";
        }
    }

    static void qa(int mapId, String message) {
        BotThreads.qa(mapId, message);
    }

    private static void count(String key) {
        tally.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /** QA: the tick leaves these staged bots alone ({@code held} false: back to normal). */
    public static void qaHold(int botId, boolean held) {
        if (held) {
            qaHeld.add(botId);
        } else {
            qaHeld.remove(botId);
        }
    }

    /** Counters for bot-life.json, {@code !bottalk} and the checks (CPU in nanoseconds of the tick). */
    public static Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("on", on());
        out.put("ticks", ticks.sum());
        out.put("cpuNanos", cpuNanos.sum());
        out.put("talks", talks.sum());
        out.put("greets", greets.sum());
        out.put("groups", groups.sum());
        out.put("lines", lines.sum());
        out.put("bankLines", bankLines.sum());
        out.put("builtInLines", builtInLines.sum());
        out.put("llmLines", llmLines.sum());
        out.put("llmCalls", llmCalls.sum());
        out.put("llmRejected", llmRejected.sum());
        out.put("actsOk", actsOk.sum());
        out.put("actsFailed", actsFailed.sum());
        out.put("notesWritten", notesWritten.sum());
        out.put("playerJoins", playerJoins.sum());
        out.put("droppedLines", dropped.sum());
        out.put("inTalk", inTalk.size());
        Map<String, Long> t = new java.util.TreeMap<>();
        tally.forEach((k, v) -> t.put(k, v.sum()));
        out.put("tally", t);
        out.put("intents", BotLineIntents.counts());
        out.put("crews", soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews.stats());
        return out;
    }

    static void resetForTest() {
        lastMapOf.clear();
        nextTalkAt.clear();
        greetedAt.clear();
        inTalk.clear();
        qaHeld.clear();
        byId.clear();
        talkStarts.clear();
        greetStarts.clear();
        llmStarts.clear();
        plainParties.clear();
        notes.clear();
        tally.clear();
        doer = BotTalk::realAct;
        memories = id -> LifeRuntime.enabled() ? LifeMemoryStore.of(id) : null;
    }

    // ── the world ──

    /** The planner's view of the real game (memory reads only; never blocks). */
    static final class WorldSense implements TalkPlanner.Sense {

        @Override
        public Mind mind(Character c, long now) {
            int id = c.getId();
            double social = 0.25, killer = 0.15;
            try {
                SocialSelf self = SocialSelf.of(id);
                social = self.socialPull();
                killer = self.rivalPull();
            } catch (RuntimeException | LinkageError ignored) {
                // seeded defaults
            }
            double mood = 0.2;
            TalkBrain.Goal goal = TalkBrain.Goal.NONE;
            double pressure = 0;
            boolean hang = false, grind = false, pq = false, busy = false;
            int town = -1;
            LifeRuntime life = lifeOf(id);
            try {
                LifeMemory mem = memoryOf(id);
                if (mem != null && !mem.goals().isEmpty()) {
                    LifeGoals.Goal g = mem.goals().get(0);
                    pressure = g.pressure();
                    goal = g.kind().progression() ? TalkBrain.Goal.LEVEL
                            : g.kind() == LifeGoals.Kind.WEALTH ? TalkBrain.Goal.MESOS
                            : g.kind() == LifeGoals.Kind.MARRIAGE || g.kind() == LifeGoals.Kind.GUILD
                            ? TalkBrain.Goal.COMPANY : TalkBrain.Goal.NONE;
                }
                if (life != null) {
                    var snap = life.lastDecision();
                    if (snap != null && snap.report() != null && snap.report().context() != null) {
                        Map<LifeDecision.Need, Double> needs = snap.report().context().needs().values();
                        double fatigue = needs.getOrDefault(LifeDecision.Need.FATIGUE, 0.0);
                        double lonely = needs.getOrDefault(LifeDecision.Need.SOCIAL, 0.0);
                        double supplies = needs.getOrDefault(LifeDecision.Need.SUPPLIES, 0.0);
                        mood = 0.5 - 0.9 * fatigue - 0.4 * supplies + 0.2 * lonely;
                        if (goal == TalkBrain.Goal.NONE && lonely > 0.6) {
                            goal = TalkBrain.Goal.COMPANY;
                            pressure = lonely;
                        }
                    }
                    var next = life.planNext();
                    if (next != null) {
                        hang = next.step().activity == LifeDecision.Activity.HANG_OUT;
                        grind = next.step().activity == LifeDecision.Activity.GRIND;
                        pq = next.step().activity == LifeDecision.Activity.PARTY_QUEST;
                    }
                    int t = life.townDestination();
                    town = t > 0 && t != c.getMapId() && LifeTowns.isTownMap(t) ? t : -1;
                } else {
                    // no daily life: a mood that drifts by the hour, seeded per bot
                    mood = new Random(id * 31L + now / 3_600_000L).nextDouble() * 0.8 - 0.3;
                }
                LfgParty.State st = LfgParty.stateOf(c, now);
                busy = st.busy() || st.afk() || st.shop();
            } catch (RuntimeException | LinkageError ignored) {
                // a read that fails leaves the quiet default
            }
            return new Mind(id, c.getName(), c.getLevel(), social, killer, mood, goal, pressure, hang, grind, pq, town,
                    busy);
        }

        @Override
        public Relation relation(Character from, Character to) {
            if (to == null) {
                return Relation.STRANGER;
            }
            try {
                AffinityState s = BotBonds.state(from.getId(), to.getId());
                double score = Math.max(-1, Math.min(1, BotBonds.score(from.getId(), to.getId()) / 100.0));
                LifeMemory mem = memoryOf(from.getId());
                boolean knows = s != AffinityState.NEUTRAL || Math.abs(score) > 0.001
                        || mem != null && mem.metWith(to.getId()) != null;
                return new Relation(s.label(), score, knows);
            } catch (RuntimeException | LinkageError e) {
                return Relation.STRANGER;
            }
        }

        @Override
        public Map<Ask, Offer> offers(Character a, Character b, boolean group, long now, Random rng) {
            Map<Ask, Offer> out = new EnumMap<>(Ask.class);
            LfgParty.State sa = safeState(a, now);
            // PARTY: a PQ party when both fit a lobby (the LFG rules decide the other's answer), else a plain party
            if (!sa.partied() && !sa.busy() && !sa.afk() && !sa.shop() && !sa.owned()) {
                soloMapling.ArtificialPlayer.BotLifeSystem.LifePq.Lobby lobby = LfgParty.lobbyFor(a.getLevel());
                if (!group && sa.canTrip() && lobby != null) {
                    // the LFG rules decide whether the other can come (no cap slot is taken until a yes is acted on)
                    LfgParty.Why why = LfgParty.fit(lobby, safeState(b, now));
                    out.put(Ask.PARTY, new Offer(Ask.PARTY, lobby.label(), Map.of("pq", lobby.name()), lobby,
                            why == LfgParty.Why.YES ? Feasible.YES : Feasible.no(b.getName() + " " + why.words)));
                } else if (budget(plainParties, now, TimeUnit.MINUTES.toMillis(10), (int) num("partiesPer10Min", 6))) {
                    out.put(Ask.PARTY, new Offer(Ask.PARTY, "a duo", Map.of(), null, null));
                }
            }
            // COME_ALONG: the opener means to hang out in another town, and both have a daily life to travel with
            LifeRuntime la = lifeOf(a.getId());
            if (la != null && lifeOf(b.getId()) != null) {
                int town = la.townDestination();
                if (town > 0 && town != a.getMapId() && LifeTowns.isTownMap(town)
                        && soloMapling.ArtificialPlayer.BotMapCap.hasRoomOn(a.getMap().getChannelId(), town)) {
                    String name = TalkPlanner.townName(town);
                    out.put(Ask.COME_ALONG, new Offer(Ask.COME_ALONG, name, Map.of("town", name), town, null));
                }
            }
            if (group) {
                return out; // a group is asked to party or to come along; the rest are one-to-one
            }
            // HELP: a grinder asks a stronger bot (5-20 levels above) to help on its map
            if (la != null && lifeOf(b.getId()) != null && b.getLevel() >= a.getLevel() + 5
                    && b.getLevel() <= a.getLevel() + 20 && a.getMap() != null && !a.getMap().isTown()
                    && !a.getMap().getAllMonsters().isEmpty()) {
                String spot = mapName(a);
                out.put(Ask.HELP, new Offer(Ask.HELP, spot, Map.of("spot", spot), a.getMapId(), null));
            }
            // TRADE: a real deal the trade rules planned, with the opener selling
            try {
                if (soloMapling.Economy.BotBotTrade.enabled()) {
                    soloMapling.Economy.BotBotTrade.Deal d = soloMapling.Economy.BotBotTrade.plan(a, b, rng);
                    if (d != null && d.seller().getId() == a.getId()) {
                        out.put(Ask.TRADE, new Offer(Ask.TRADE, d.about(), Map.of("item",
                                d.itemName().toLowerCase(Locale.ROOT), "price", d.priceWords()), d,
                                d.agreed() ? Feasible.YES : Feasible.no(d.reason())));
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // no deal
            }
            // GOSSIP: a true story the opener knows about someone
            try {
                soloMapling.ArtificialPlayer.BotGossip.Fact fact = soloMapling.ArtificialPlayer.BotGossip.factFor(a, b,
                        a.getMap() == null ? List.of() : a.getMap().getAllPlayers(), now, rng);
                if (fact != null && fact.person()) {
                    out.put(Ask.GOSSIP, new Offer(Ask.GOSSIP, fact.about(), Map.of("about", fact.about()), fact,
                            Feasible.YES));
                }
            } catch (RuntimeException | LinkageError ignored) {
                // no story
            }
            return out;
        }

        @Override
        public Feasible canAccept(Character listener, Character asker, Offer offer, long now) {
            LfgParty.State s = safeState(listener, now);
            return switch (offer.ask()) {
                case PARTY -> s.owned() ? Feasible.no(LfgParty.Why.OWNED.words) : s.partied()
                        ? Feasible.no(LfgParty.Why.PARTIED.words) : s.afk() ? Feasible.no(LfgParty.Why.AFK.words)
                        : s.shop() ? Feasible.no(LfgParty.Why.SHOP.words) : s.busy()
                        ? Feasible.no(LfgParty.Why.BUSY.words) : Feasible.YES;
                case COME_ALONG -> {
                    int town = offer.payload() instanceof Integer t ? t : -1;
                    LifeTowns.Town t = LifeTowns.townOf(town);
                    yield lifeOf(listener.getId()) == null ? Feasible.no("has no daily life to travel with")
                            : s.busy() ? Feasible.no(LfgParty.Why.BUSY.words)
                            : t != null && !t.fits(listener.getLevel()) ? Feasible.no("the town is not for its level")
                            : Feasible.YES;
                }
                case HELP -> lifeOf(listener.getId()) == null ? Feasible.no("has no daily life to go help with")
                        : s.busy() ? Feasible.no(LfgParty.Why.BUSY.words) : Feasible.YES;
                case TRADE, GOSSIP -> offer.decided() != null ? offer.decided() : Feasible.YES;
            };
        }

        @Override
        public String met(Character a, Character b) {
            LifeMemory mem = memoryOf(a.getId());
            LifeMemory.Met m = mem == null ? null : mem.metWith(b.getId());
            return m == null ? null : m.what();
        }

        @Override
        public Map<String, String> slots(Character speaker, Character to) {
            Map<String, String> s = new HashMap<>();
            s.put("map", mapName(speaker));
            s.put("lv", String.valueOf(to == null ? speaker.getLevel() : to.getLevel()));
            if (to != null) {
                s.put("name", to.getName());
            }
            return s;
        }

        private static LfgParty.State safeState(Character c, long now) {
            try {
                return LfgParty.stateOf(c, now);
            } catch (RuntimeException | LinkageError e) {
                return new LfgParty.State(c.getLevel(), c.getParty() != null, true, false, false, false, false);
            }
        }
    }
}
