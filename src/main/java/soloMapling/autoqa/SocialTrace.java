package soloMapling.autoqa;

import client.Character;
import net.server.Server;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.autoqa.SocialTraceEntry.Bond;
import soloMapling.autoqa.SocialTraceEntry.Event;
import soloMapling.autoqa.SocialTraceEntry.Line;
import soloMapling.autoqa.SocialTraceEntry.Stage;
import soloMapling.autoqa.SocialTraceEntry.Verdict;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1.6.0 (owner 2026-10-26): "observe social life between bots, record their convo and see if decisions were made and
 * what decision". The social trace follows every bot<->bot social episode - a bot thread ({@code BotThreads}), or a
 * drama event between two bots (read from the drama systems' own QA lines, {@link SocialTraceLines}) - through the
 * pipeline stages of {@link SocialTraceEntry.Stage}, closes it after its follow-up window and writes one JSON line per
 * entry to {@code data/autoqa/social-trace/<UTC date>.jsonl}, plus a running summary
 * {@code data/autoqa/social-trace-latest.json}.
 *
 * <p>Observe only: the hooks read the game and never change it. Each hook is one volatile read when no trace runs. A
 * trace is started by {@code !socialtrace start [hours]}, by {@code -Dslums.socialtrace=<hours>} at boot, or by the
 * auto-QA check t-st-1; while it runs, {@link SocialObservers} keeps pretend players standing in town so threads have
 * someone to happen in front of. Bounded: at most {@link #MAX_OPEN} open entries (the oldest closes early), 200 events
 * per entry, and a closed entry leaves only its compact JSON in a {@link #KEEP_CLOSED}-entry ring for the summary.
 */
public final class SocialTrace {

    public static final String PROPERTY = "slums.socialtrace";
    public static final double DEFAULT_HOURS = 9;
    static final int MAX_OPEN = 2000;
    static final int KEEP_CLOSED = 5000;
    static final int MAX_EVENTS = 200;
    static final int RECENT_LINES = 3000;
    /** The drama kinds a third bot can later bring up (a FRIEND_DRAMA or GOSSIP thread about the same people). */
    static final Set<String> DRAMA_SPREADS = Set.of("KS_SPAT", "CALLOUT", "GUILD_FEUD", "JEALOUS", "BREAKUP",
            "COUPLE", "REBOUND");

    private static volatile boolean on;
    private static volatile long startedAt;
    private static volatile long endsAt;
    private static volatile String startedBy;
    private static volatile int observerCount;

    private static final Map<String, SocialTraceEntry> open = new ConcurrentHashMap<>();
    private static final Map<Integer, SocialTraceEntry> byThread = new ConcurrentHashMap<>();
    private static final Map<Integer, List<SocialTraceEntry>> byBot = new ConcurrentHashMap<>();
    private static final Deque<Map<String, Object>> closed = new ArrayDeque<>();
    private static final LinkedHashMap<String, String> recentLines = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > RECENT_LINES;
        }
    };
    private static final AtomicInteger dramaSeq = new AtomicInteger();
    private static final AtomicInteger cutForRoom = new AtomicInteger();
    private static volatile boolean summaryDirty;

    private static ScheduledExecutorService exec;
    private static ScheduledFuture<?> tickTask;

    static volatile SocialTraceJudge.Rules rules = new SocialTraceJudge.Rules(
            line -> soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard.chatProblem(line), ChatQuality::stiffFlags);
    /** Reads where the two bots are at close; tests replace it (no world in a unit test). */
    static volatile java.util.function.Function<SocialTraceEntry, Map<String, Object>> endReader = SocialTrace::endState;
    /** Where closed entries go; tests replace it. */
    static volatile java.util.function.Consumer<Map<String, Object>> sink = SocialTrace::writeEntry;

    private SocialTrace() {
    }

    /** True while a trace runs: the only call a hook makes when none does. */
    public static boolean on() {
        return on;
    }

    // ── control ──

    /** {@code -Dslums.socialtrace=<hours>}: starts a trace three minutes after boot (bots and maps up first). */
    public static void boot() {
        double hours = hoursProperty(System.getProperty(PROPERTY));
        if (hours <= 0) {
            return;
        }
        executor().schedule(() -> {
            try {
                start(hours, "boot (-D" + PROPERTY + ")", SocialObservers.MAX);
            } catch (RuntimeException e) {
                AutoQa.log.warn("Social trace: boot start failed", e);
            }
        }, 3, TimeUnit.MINUTES);
        AutoQa.log.info("Social trace: starts in 3 min for {} h (-D{})", hours, PROPERTY);
    }

    /** The property's hours ("9", "1.5"); 0 when absent or not a positive number. */
    static double hoursProperty(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            double h = Double.parseDouble(raw.trim());
            return h > 0 && h <= 72 ? h : 0;
        } catch (NumberFormatException e) {
            return "true".equalsIgnoreCase(raw.trim()) ? DEFAULT_HOURS : 0;
        }
    }

    /**
     * Starts a trace for {@code hours} with {@code observers} pretend players (0-4) logging in and rotating through the
     * social maps. A running trace is extended instead. The lines say what happened.
     */
    public static synchronized List<String> start(double hours, String by, int observers) {
        long now = System.currentTimeMillis();
        long until = now + (long) (hours * 3_600_000L);
        List<String> out = new ArrayList<>();
        if (on) {
            endsAt = Math.max(endsAt, until);
            out.add("Social trace already running (since " + Instant.ofEpochMilli(startedAt) + "); now ends "
                    + Instant.ofEpochMilli(endsAt) + ".");
        } else {
            startedAt = now;
            endsAt = until;
            startedBy = by;
            on = true;
            out.add("Social trace on until " + Instant.ofEpochMilli(endsAt) + " UTC (" + hours + " h).");
        }
        observerCount = Math.max(observerCount, Math.max(0, Math.min(SocialObservers.MAX, observers)));
        int n = observerCount;
        if (tickTask == null) {
            tickTask = executor().scheduleAtFixedRate(SocialTrace::safeTick, 30, 30, TimeUnit.SECONDS);
        }
        executor().execute(() -> SocialObservers.ensure(n, System.currentTimeMillis()));
        out.add(n + " observer(s) log in and stand in town (rotating every " + SocialObservers.ROTATE_MIN + " min).");
        out.add("Entries: " + dir().toAbsolutePath() + "/<date>.jsonl; summary: " + latestFile().toAbsolutePath());
        AutoQa.log.info("Social trace started by {} until {} with {} observer(s)", by, Instant.ofEpochMilli(endsAt), n);
        return out;
    }

    /** Stops the trace: every open entry closes now (marked cut short), the observers log out. */
    public static synchronized List<String> stop(String why) {
        if (!on) {
            return List.of("No social trace is running.");
        }
        on = false;
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
        observerCount = 0;
        int n = closeAll(System.currentTimeMillis(), why);
        executor().execute(() -> {
            SocialObservers.stopAll();
            writeSummary();
        });
        AutoQa.log.info("Social trace stopped ({}): {} open entries closed early", why, n);
        return List.of("Social trace stopped (" + why + "): " + n + " open entr" + (n == 1 ? "y" : "ies")
                + " closed early; observers log out. Summary: " + latestFile().toAbsolutePath());
    }

    public static boolean running() {
        return on;
    }

    /** {@code !socialtrace status}. */
    public static List<String> status() {
        List<String> out = new ArrayList<>();
        if (!on) {
            out.add("No social trace running." + (closedCount() > 0 ? " Last one's summary: " + latestFile() : ""));
            return out;
        }
        out.add("Social trace since " + Instant.ofEpochMilli(startedAt) + " until " + Instant.ofEpochMilli(endsAt)
                + " UTC, started by " + startedBy + ".");
        out.add(open.size() + " open entr" + (open.size() == 1 ? "y" : "ies") + ", " + closedCount() + " closed.");
        Map<String, Object> s = summary();
        Object stages = s.get("stages");
        if (stages instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("ok rates:");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> st) {
                    b.append(' ').append(e.getKey()).append(' ').append(st.get("okRate"));
                }
            }
            out.add(b.toString());
        }
        out.add("Observers: " + SocialObservers.describe());
        return out;
    }

    // ── thread hooks (BotThreads) ──

    /** A thread started: its pair, topic, why, grounding story, map and who watched; the bond before it. */
    public static void threadStart(int threadId, MapleMap map, int channel, Character a, Character b, String topic,
                                   boolean forced, boolean beefOrDrama, String about,
                                   soloMapling.ArtificialPlayer.BotGossip.Fact fact, long now) {
        if (!on || a == null || b == null || map == null) {
            return;
        }
        try {
            SocialTraceEntry e = new SocialTraceEntry("t" + threadId + "-" + now, "thread", topic, now, map.getId(),
                    channel, a.getId(), a.getName(), b.getId(), b.getName(), watchers(map));
            e.trigger = threadWhy(topic, forced, beefOrDrama, a.getLevel(), b.getLevel(), map.getId())
                    + (fact == null ? "" : "; grounded in " + fact.kind() + ": " + fact.about());
            e.about = about;
            e.minLevel = Math.min(a.getLevel(), b.getLevel());
            if (fact != null) {
                e.factKind = fact.kind();
                e.factAbout = fact.about();
                e.factNames = fact.names() == null ? List.of() : List.copyOf(fact.names());
                e.factPerson = fact.person();
                if (Set.of("GOSSIP", "FRIEND_DRAMA", "RIVALRY").contains(topic)) {
                    e.spreadKey = fact.about();
                    e.spreadNames = "RIVALRY".equals(topic) ? List.of(a.getName(), b.getName())
                            : fact.person() ? e.factNames : List.of();
                }
            }
            e.before = bond(a.getId(), b.getId());
            e.closesAt = SocialTraceJudge.closesAt(now, e.spreadKey != null);
            noteSpread(a.getId(), b.getName(), "thread " + e.id, fact == null ? null : fact.about(),
                    fact == null ? null : fact.names(), topic);
            add(e);
            byThread.put(threadId, e);
        } catch (RuntimeException | LinkageError ex) {
            AutoQa.log.debug("Social trace: thread start not recorded", ex);
        }
    }

    static String threadWhy(String topic, boolean forced, boolean beefOrDrama, int levelA, int levelB, int mapId) {
        if (forced) {
            return "forced by !qa";
        }
        boolean fm = mapId >= 910000000 && mapId <= 910000022;
        return switch (topic) {
            case "RIVALRY" -> beefOrDrama ? "rival bond: the grudge-holder's beef roll" : "rival pair";
            case "FRIEND_DRAMA" -> "a friend's drama news (50% roll)";
            case "GUILD_BEEF" -> "their guilds are at war";
            case "TRADE" -> fm ? "FM map: trade roll" : "topic roll: trade";
            case "LFG" -> "topic roll: both in PQ levels (lv " + levelA + "/" + levelB + ")";
            case "GOSSIP" -> (fm ? "FM map: " : "") + "topic roll: gossip (share by Socializer pull)";
            case "TRAINING" -> "topic roll: training";
            default -> "topic roll";
        };
    }

    /** The bond after {@code BotBonds.onThread}. */
    public static void threadBonded(int threadId) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e == null) {
            return;
        }
        Bond after = bond(e.aId, e.bId);
        synchronized (e) {
            e.after = after;
        }
    }

    /**
     * How the lines were written: {@code source} llm / bank / script; {@code fallback} why not the model (null for
     * llm); {@code modelLines} the usable lines a model reply had when it was too few (-1: no reply to judge).
     */
    public static void threadComposed(int threadId, String source, String fallback, int modelLines) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e == null) {
            return;
        }
        synchronized (e) {
            e.source = source;
            e.fallback = fallback;
            e.modelLines = modelLines;
            e.composeMs = System.currentTimeMillis() - e.startedAt;
        }
    }

    /** The name guard rewrote or dropped a line of this thread. */
    public static void nameGuard(int threadId, String what) {
        SocialTraceEntry e = on && threadId > 0 ? byThread.get(threadId) : null;
        if (e != null) {
            synchronized (e) {
                if (e.nameGuard.size() < 10) {
                    e.nameGuard.add(what);
                }
            }
        }
    }

    /** How many lines the thread will try to say (0: none to say). */
    public static void threadPlanned(int threadId, int lines) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e != null) {
            synchronized (e) {
                e.planned = lines;
            }
        }
    }

    /** A thread line went out. */
    public static void threadLine(int threadId, Character who, String text) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e == null || who == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = SocialTraceJudge.norm(text == null ? "" : text);
        String earlier;
        synchronized (recentLines) {
            earlier = recentLines.get(key);
            recentLines.put(key, e.id);
        }
        synchronized (e) {
            e.lines.add(new Line(now, who.getId(), who.getName(), text, voice(who.getId())));
            if (earlier != null && !earlier.equals(e.id) && key.length() > 3 && e.repeats.size() < 4) {
                e.repeats.add(text);
            }
        }
    }

    /** A planned thread line was not said, and why. */
    public static void threadDropped(int threadId, String who, String text, String why) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e != null) {
            synchronized (e) {
                e.dropped.add(why + " (" + who + ": \"" + text + "\")");
            }
        }
    }

    /**
     * 1.6.0: what the code decided after a thread ({@code decision}: "listener keeps the story", "escalate ..."), or
     * why it decided nothing ({@code decision} null, {@code why} the reason).
     */
    public static void threadDecided(int threadId, String decision, String why) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e != null) {
            synchronized (e) {
                e.decided = decision;
                e.decideWhy = why;
            }
        }
    }

    /** 1.6.0: a thread's decision was carried out (or failed): {@code kind} "kept", "escalate", "escalate-drop". */
    public static void threadActed(int threadId, int botId, String kind, String text) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e != null) {
            synchronized (e) {
                addEvent(e, new Event(System.currentTimeMillis(), botId, kind, text == null ? "" : text));
            }
        }
    }

    /** 1.6.0: a thread wrote memory ("talked" for both bots, a kept story). */
    public static void threadRemembered(int threadId, String what) {
        SocialTraceEntry e = on ? byThread.get(threadId) : null;
        if (e != null) {
            synchronized (e) {
                if (e.remembered.size() < 10) {
                    e.remembered.add(what);
                }
            }
        }
    }

    /**
     * 1.6.0: {@code tellerId} retold a story it heard ({@code BotGossip.keep}) to {@code listener}: every open GOSSIP
     * entry with that story, other than the teller's own telling, spread for real.
     */
    public static void storyRetold(int tellerId, String teller, String listener, String about, String heardFrom) {
        if (!on || about == null) {
            return;
        }
        for (SocialTraceEntry e : open.values()) {
            if (!"GOSSIP".equals(e.topic) || e.spreadKey == null || e.aId == tellerId
                    || !SocialTraceJudge.norm(about).equals(SocialTraceJudge.norm(e.spreadKey))) {
                continue;
            }
            synchronized (e) {
                if (e.retold.size() < 10) {
                    e.retold.add(teller + " told " + listener + " (heard from " + heardFrom + "): " + about);
                }
            }
        }
    }

    // ── drama hooks (the drama systems' QA sinks) ──

    /** A drama system's QA line ({@code DramaKit.qa}, {@code BeefKit.qaAny}, {@code BotCouples.qa}, {@code BotGuilds.qa}). */
    public static void dramaLine(int mapId, String line) {
        if (!on) {
            return;
        }
        try {
            SocialTraceLines.Parsed p = SocialTraceLines.read(line);
            if (p != null) {
                drama(p, mapId, System.currentTimeMillis());
            }
        } catch (RuntimeException | LinkageError e) {
            AutoQa.log.debug("Social trace: drama line not read: {}", line, e);
        }
    }

    static void drama(SocialTraceLines.Parsed p, int mapId, long now) {
        switch (p.role()) {
            case START -> dramaStart(p, mapId, now);
            case ANSWER -> {
                Character actor = bot(p.names().get(1));
                for (SocialTraceEntry e : entriesOf(actor == null ? 0 : actor.getId())) {
                    if (p.kind().equals(e.topic) && e.involves(idOf(p.names().get(0)))) {
                        synchronized (e) {
                            addEvent(e, new Event(now, actor.getId(), p.why(), p.text() == null ? "" : p.text()));
                            if (p.text() != null && "answer".equals(p.why())) {
                                e.lines.add(new Line(now, actor.getId(), actor.getName(), p.text(), voice(actor.getId())));
                            }
                        }
                    }
                }
            }
            case EVENT, NEWS -> {
                for (String name : p.names()) {
                    Character c = bot(name);
                    if (c != null) {
                        event(c.getId(), p.kind(), p.text());
                    }
                }
            }
        }
    }

    private static void dramaStart(SocialTraceLines.Parsed p, int mapId, long now) {
        Character a;
        Character b = null;
        String bName;
        if (!p.ids().isEmpty()) {
            a = online(p.ids().get(0));
            b = online(p.ids().get(1));
            bName = b == null ? String.valueOf(p.ids().get(1)) : b.getName();
        } else {
            a = bot(p.names().get(0));
            bName = p.names().get(1);
            if (!bName.startsWith("[")) {
                b = bot(bName);
                if (b == null) {
                    return; // a bot and a real player (or someone offline): not a bot<->bot episode
                }
            }
        }
        if (a == null || !BotHelpers.isBot(a)) {
            return;
        }
        int map = mapId > 0 ? mapId : a.getMapId();
        MapleMap m = a.getMap();
        SocialTraceEntry e = new SocialTraceEntry("d" + dramaSeq.incrementAndGet() + "-" + now, "drama", p.kind(), now,
                map, channelOf(a), a.getId(), a.getName(), b == null ? 0 : b.getId(), bName,
                m == null ? List.of() : watchers(m));
        e.trigger = p.why();
        e.source = p.text() == null ? null : "pack line";
        if (p.text() == null) {
            e.planned = 0;
        } else {
            e.planned = 1;
            if ("null".equals(p.text()) || p.text().isBlank()) {
                e.dropped.add("the pack had no line");
            } else {
                e.lines.add(new Line(now, a.getId(), a.getName(), p.text(), voice(a.getId())));
            }
        }
        if (b != null) {
            e.before = bond(a.getId(), b.getId());
        }
        if (DRAMA_SPREADS.contains(p.kind()) && b != null) {
            e.spreadKey = p.kind().toLowerCase() + ": " + a.getName() + " / " + bName;
            e.spreadNames = "BREAKUP".equals(p.kind()) ? List.of(bName) : List.of(a.getName(), bName);
        }
        if ("POACH".equals(p.kind())) {
            e.events.add(new Event(now, a.getId(), "switch", "joined " + bName));
        }
        e.closesAt = SocialTraceJudge.closesAt(now, e.spreadKey != null);
        add(e);
    }

    // ── what the two bots do next ──

    /** A life decision or start of one bot ({@code LifeRuntime}, {@code TrainingBot.lifeNote}, {@code LifeTownLife}). */
    public static void life(int botId, String text) {
        if (on && byBot.containsKey(botId)) {
            event(botId, "life", text);
        }
    }

    /** Two bots in one party ({@code BotBonds.onPartyJoin}). */
    public static void partyJoin(Character joiner, Character other) {
        if (!on || joiner == null || other == null) {
            return;
        }
        if (byBot.containsKey(joiner.getId())) {
            event(joiner.getId(), "party", "joined a party with " + other.getName());
        }
        if (byBot.containsKey(other.getId())) {
            event(other.getId(), "party", joiner.getName() + " joined its party");
        }
    }

    static void event(int botId, String kind, String text) {
        long now = System.currentTimeMillis();
        for (SocialTraceEntry e : entriesOf(botId)) {
            synchronized (e) {
                addEvent(e, new Event(now, botId, kind, text));
            }
        }
    }

    private static void addEvent(SocialTraceEntry e, Event ev) {
        if (e.events.size() < MAX_EVENTS) {
            e.events.add(ev);
        }
    }

    /** A later thread by {@code tellerId}: does it repeat an open entry's story? */
    static void noteSpread(int tellerId, String listener, String where, String about, List<String> names, String topic) {
        if (about == null && (names == null || names.isEmpty())) {
            return;
        }
        if (!Set.of("GOSSIP", "FRIEND_DRAMA", "RIVALRY").contains(topic)) {
            return;
        }
        for (SocialTraceEntry e : open.values()) {
            if (SocialTraceJudge.repeats(e, tellerId, about, names)) {
                synchronized (e) {
                    if (e.spread.size() < 10) {
                        e.spread.add(nameOrId(tellerId) + " told " + listener + " (" + where + "): " + about);
                    }
                }
            }
        }
    }

    // ── the index ──

    static void add(SocialTraceEntry e) {
        synchronized (open) {
            if (open.size() >= MAX_OPEN) {
                SocialTraceEntry oldest = null;
                for (SocialTraceEntry x : open.values()) {
                    if (oldest == null || x.startedAt < oldest.startedAt) {
                        oldest = x;
                    }
                }
                if (oldest != null) {
                    cutForRoom.incrementAndGet();
                    close(oldest, System.currentTimeMillis(), "cut short: too many open entries");
                }
            }
            open.put(e.id, e);
            index(e.aId, e);
            if (e.bId > 0) {
                index(e.bId, e);
            }
        }
    }

    private static void index(int botId, SocialTraceEntry e) {
        byBot.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).add(e);
    }

    private static void unindex(SocialTraceEntry e) {
        for (int id : new int[]{e.aId, e.bId}) {
            List<SocialTraceEntry> l = byBot.get(id);
            if (l != null) {
                l.remove(e);
                if (l.isEmpty()) {
                    byBot.remove(id, l);
                }
            }
        }
        byThread.values().remove(e);
    }

    static List<SocialTraceEntry> entriesOf(int botId) {
        List<SocialTraceEntry> l = byBot.get(botId);
        return l == null ? List.of() : l;
    }

    // ── closing ──

    private static void safeTick() {
        try {
            tick(System.currentTimeMillis());
        } catch (RuntimeException | LinkageError e) {
            AutoQa.log.warn("Social trace tick failed", e);
        }
    }

    static void tick(long now) {
        if (on && now >= endsAt) {
            stop("time up");
            return;
        }
        int n = closeDue(now);
        SocialObservers.tick(now);
        if (n > 0 || summaryDirty || now / 60_000 % 5 == 0) {
            writeSummary();
        }
    }

    /** Closes every entry whose window has passed; how many closed. */
    static int closeDue(long now) {
        int n = 0;
        synchronized (open) {
            for (SocialTraceEntry e : List.copyOf(open.values())) {
                if (SocialTraceJudge.due(e, now)) {
                    close(e, now, null);
                    n++;
                }
            }
        }
        return n;
    }

    static int closeAll(long now, String why) {
        int n = 0;
        synchronized (open) {
            for (SocialTraceEntry e : List.copyOf(open.values())) {
                close(e, now, "cut short: " + why);
                n++;
            }
        }
        return n;
    }

    private static void close(SocialTraceEntry e, long now, String cutShort) {
        open.remove(e.id);
        unindex(e);
        Map<String, Object> end = endReader.apply(e);
        Map<String, Object> json;
        synchronized (e) {
            e.endState = end;
            if (e.after == null && e.before != null && e.bId > 0 && "drama".equals(e.kind)) {
                e.after = bond(e.aId, e.bId);
            }
            e.closesAt = Math.min(e.closesAt, now);
            e.verdicts = SocialTraceJudge.judge(e, rules);
            json = e.toJson();
        }
        if (cutShort != null) {
            json.put("cutShort", cutShort);
        }
        synchronized (closed) {
            closed.addLast(json);
            while (closed.size() > KEEP_CLOSED) {
                closed.pollFirst();
            }
        }
        summaryDirty = true;
        try {
            sink.accept(json);
        } catch (RuntimeException ex) {
            AutoQa.log.warn("Social trace: entry {} not written", e.id, ex);
        }
    }

    /** Where the two bots are at close, and whether they share a party. */
    private static Map<String, Object> endState(SocialTraceEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Character a = online(e.aId);
            Character b = e.bId > 0 ? online(e.bId) : null;
            m.put("aMap", a == null ? null : a.getMapId());
            m.put("bMap", b == null ? null : b.getMapId());
            int pa = a == null ? -1 : a.getPartyId();
            int pb = b == null ? -2 : b.getPartyId();
            m.put("sameParty", pa > 0 && pa == pb);
        } catch (RuntimeException | LinkageError ex) {
            // the world is not up (tests)
        }
        return m;
    }

    // ── output ──

    static Path dir() {
        return AutoQa.dataDir().resolve("social-trace");
    }

    static Path latestFile() {
        return AutoQa.dataDir().resolve("social-trace-latest.json");
    }

    private static void writeEntry(Map<String, Object> json) {
        String day = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString();
        try {
            AutoQa.appendLine(dir().resolve(day + ".jsonl"), Json.write(json));
        } catch (IOException ex) {
            AutoQa.log.warn("Social trace: could not append to {}", dir(), ex);
        }
    }

    static int closedCount() {
        synchronized (closed) {
            return closed.size();
        }
    }

    /** The running summary of every closed entry still in memory. */
    public static Map<String, Object> summary() {
        List<Map<String, Object>> list;
        synchronized (closed) {
            list = new ArrayList<>(closed);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("running", on);
        meta.put("startedAt", SocialTraceJudge.iso(startedAt));
        meta.put("endsAt", SocialTraceJudge.iso(endsAt));
        meta.put("startedBy", startedBy);
        meta.put("open", open.size());
        meta.put("cutForRoom", cutForRoom.get());
        meta.put("observers", SocialObservers.describe());
        return SocialTraceSummary.build(list, meta, System.currentTimeMillis());
    }

    static void writeSummary() {
        summaryDirty = false;
        try {
            AutoQa.writeAtomic(latestFile(), Json.write(summary()));
        } catch (IOException | RuntimeException ex) {
            AutoQa.log.warn("Social trace: could not write {}", latestFile(), ex);
        }
    }

    // ── t-st-1 ──

    /**
     * The threads started since {@code since} whose every planned line is out, with their first three stages judged:
     * "id topic TRIGGER ok COMPOSE ok DELIVER ok lines=N (source)".
     */
    public static List<String> deliveredThreads(long since) {
        List<String> out = new ArrayList<>();
        for (SocialTraceEntry e : open.values()) {
            if (!"thread".equals(e.kind) || e.startedAt < since) {
                continue;
            }
            synchronized (e) {
                if (e.planned < 0 || e.lines.size() + e.dropped.size() < e.planned) {
                    continue;
                }
                EnumMap<Stage, Verdict> v = SocialTraceJudge.judge(e, rules);
                out.add(e.id + " " + e.topic + " TRIGGER " + v.get(Stage.TRIGGER).status().json + " COMPOSE "
                        + v.get(Stage.COMPOSE).status().json + " DELIVER " + v.get(Stage.DELIVER).status().json
                        + " lines=" + e.lines.size() + " (" + e.source + ")");
            }
        }
        return out;
    }

    // ── reading the game ──

    static Bond bond(int a, int b) {
        try {
            return new Bond(soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.score(a, b),
                    soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.score(b, a),
                    soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.state(a, b).label(),
                    soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.state(b, a).label());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The real players on a map: observers marked, hidden GMs left out. */
    static List<String> watchers(MapleMap map) {
        List<String> out = new ArrayList<>();
        try {
            for (Character c : map.getAllPlayers()) {
                if (c != null && !BotHelpers.isBot(c) && BotHelpers.isVisibleRealPlayer(c)) {
                    out.add(c.getName() + (SyntheticPlayers.isObserver(c) ? " (observer)" : ""));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // no players to read
        }
        return out;
    }

    static String voice(int botId) {
        try {
            return soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.of(botId
                    - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE).voice().label();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static int channelOf(Character c) {
        try {
            return c.getClient() == null ? 1 : c.getClient().getChannel();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    static Character online(int id) {
        try {
            return Server.getInstance().getWorld(Server.getInstance().getHomeWorldId()).getPlayerStorage()
                    .getCharacterById(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** An online bot by name, or null. */
    static Character bot(String name) {
        if (name == null || name.startsWith("[")) {
            return null;
        }
        try {
            Character c = Server.getInstance().getWorld(Server.getInstance().getHomeWorldId()).getPlayerStorage()
                    .getCharacterByName(name);
            return c != null && BotHelpers.isBot(c) ? c : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static int idOf(String name) {
        Character c = bot(name);
        return c == null ? -1 : c.getId();
    }

    private static String nameOrId(int id) {
        Character c = online(id);
        return c == null ? String.valueOf(id) : c.getName();
    }

    private static synchronized ScheduledExecutorService executor() {
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "social-trace");
                t.setDaemon(true);
                return t;
            });
        }
        return exec;
    }

    // ── tests ──

    static synchronized void resetForTest() {
        on = false;
        open.clear();
        byThread.clear();
        byBot.clear();
        synchronized (closed) {
            closed.clear();
        }
        synchronized (recentLines) {
            recentLines.clear();
        }
        cutForRoom.set(0);
    }

    static void onForTest(boolean value) {
        on = value;
    }

    static Map<String, SocialTraceEntry> openForTest() {
        return open;
    }
}
