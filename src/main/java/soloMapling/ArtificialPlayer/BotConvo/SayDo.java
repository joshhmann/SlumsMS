package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.qa.QaLog;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * rc6 say-do (owner goal: "a bot should do what it says"): the ledger of action claims bots make in chat and whether
 * the matching action followed. A bot line ({@link #said}) is classified by {@link ClaimRules}; each claim stays open
 * for its window ({@link #windowMs}) while cheap hooks report what the bot does ({@link #event}: a map change, a
 * party invite, a buff, a trade, a logout ...). The resolver then judges it ({@link #judge}): BACKED or UNBACKED.
 * <p>
 * Cheap and in memory everywhere (live too): a line costs one regex pass; an event costs one map lookup and returns at
 * once unless that bot has an open claim; at most {@link #MAX_OPEN} claims are open. Only with auto-QA on does it also
 * append each verdict to {@code data/autoqa/saydo/<UTC day>.jsonl} for the daily chat-quality report. Each verdict is
 * a {@code SAYDO} line on the CONVO topic for {@code !qa cv-*} runs. Player names never reach a file (hash /
 * {player}). Off with {@code -Dslums.saydo=false}.
 */
public final class SayDo {

    private static final Logger log = LoggerFactory.getLogger(SayDo.class);

    static final int MAX_OPEN = 2_000;
    static final int MAX_OPEN_PER_BOT = 4;
    static final int RECENT = 400;
    static final long DEDUPE_MS = 30_000;

    /** What a bot did that can back a claim. */
    public enum Ev { MAP, TRAVEL, WALK, LOGOUT, PARTY, BUFF, TRADE, SHOP, GIFT, LEAVE_GROUP, KILL, RESUPPLY, IDLE, LINE }

    /** One event: the kind, the map it concerns (a destination for TRAVEL/WALK), when. */
    public record Event(Ev kind, int mapId, long at) {
    }

    /** The bot and its addressee when the claim was made. {@code holds}: the S>/B> item is in its bag / wants. */
    public record Start(int mapId, int x, int y, boolean partied, int targetMap, int targetX, int targetY,
                        boolean holds, boolean shop) {
    }

    /** The bot (and addressee) now. {@code laterLines}: lines the bot said after the claim. */
    public record Now(boolean online, int mapId, int x, int y, int targetMap, int targetX, int targetY, int laterLines) {
    }

    /** A verdict: null backed = not decided yet (wait for more of the window). */
    public record Verdict(Boolean backed, String why) {
        static Verdict yes(String why) {
            return new Verdict(true, why);
        }

        static Verdict no(String why) {
            return new Verdict(false, why);
        }

        static final Verdict WAIT = new Verdict(null, "waiting");
    }

    /** An open claim. */
    static final class Open {
        final long id;
        final long at;
        final int botId;
        final String botName;
        final String source;
        final ClaimRules.Claim claim;
        final String line;
        final String norm;
        final int targetId;
        final String targetHash;
        final Start start;
        final List<Event> events = new ArrayList<>();
        int laterLines;

        Open(long id, long at, int botId, String botName, String source, ClaimRules.Claim claim, String line,
             int targetId, Start start) {
            this.id = id;
            this.at = at;
            this.botId = botId;
            this.botName = botName;
            this.source = source;
            this.claim = claim;
            this.line = line;
            this.norm = normLine(line);
            this.targetId = targetId;
            this.targetHash = targetId > 0 ? ConvoQuality.hash(targetId) : "-";
            this.start = start;
        }
    }

    /** A judged claim (also one JSONL row). */
    public record Resolved(long at, long resolvedAt, String bot, String source, ClaimRules.Kind kind, String claim,
                           boolean weak, String line, boolean backed, String why, String target) {
        Map<String, Object> row() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at);
            m.put("resolvedAt", resolvedAt);
            m.put("bot", bot);
            m.put("source", source);
            m.put("kind", kind.tag());
            m.put("claim", claim);
            m.put("weak", weak);
            m.put("line", line);
            m.put("backed", backed);
            m.put("why", why);
            m.put("target", target);
            return m;
        }
    }

    private static final ConcurrentHashMap<Integer, List<Open>> open = new ConcurrentHashMap<>();
    private static final AtomicLong ids = new AtomicLong();
    private static final Deque<Resolved> recent = new ArrayDeque<>();
    private static final Map<String, long[]> bySource = new TreeMap<>();
    private static final Map<ClaimRules.Kind, long[]> byKind = new EnumMap<>(ClaimRules.Kind.class);
    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();
    private static volatile boolean scheduled;
    private static volatile StateSource state = new GameState();
    private static volatile Sink sink = SayDo::defaultSink;
    private static volatile long clockOffset;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "say-do");
        t.setDaemon(true);
        return t;
    });
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private SayDo() {
    }

    // ── switches and seams ──

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("slums.saydo"));
    }

    /** What the ledger reads from the game: the bot and addressee now (tests pass a fake). */
    public interface StateSource {
        Start start(Character bot, Character target, ClaimRules.Claim claim);

        /** The bot now, or null when it is no longer online. */
        Now now(int botId, int targetId);
    }

    /** Where verdicts go besides memory (the JSONL file on auto-QA servers). */
    public interface Sink {
        void resolved(Resolved r);
    }

    public static void useForTest(StateSource s, Sink k) {
        scheduled = s != null; // tests resolve by hand: no background resolver
        state = s == null ? new GameState() : s;
        sink = k == null ? SayDo::defaultSink : k;
        resetForTest();
    }

    static void resetForTest() {
        open.clear();
        lastRare.clear();
        synchronized (recent) {
            recent.clear();
            bySource.clear();
            byKind.clear();
        }
        clockOffset = 0;
    }

    static void advanceForTest(long ms) {
        clockOffset += ms;
    }

    static long now() {
        return System.currentTimeMillis() + clockOffset;
    }

    // ── the source of the line being said ──

    /**
     * Runs {@code body} with {@code source} as the source label of any bot line said on this thread ("llm", "bank",
     * "pack:SocialHotPotato/RealLife", "thread-bank", "ad", "megaphone" ...). Lines said with no label are "scripted".
     */
    public static void withSource(String source, Runnable body) {
        String prev = SOURCE.get();
        SOURCE.set(source);
        try {
            body.run();
        } finally {
            if (prev == null) {
                SOURCE.remove();
            } else {
                SOURCE.set(prev);
            }
        }
    }

    public static String currentSource() {
        String s = SOURCE.get();
        return s == null ? "scripted" : s;
    }

    // ── lines ──

    /** A bot said a line in public (map chat, a bubble), with the thread's source label. */
    public static void said(Character bot, String line) {
        said(bot, null, line, null);
    }

    /**
     * A bot said a line: to {@code target} (a reply, a whisper; null for public chatter) from {@code source} (null =
     * the thread's label). A line already recorded for this bot in the last 30 s (the same reply arriving through
     * both the chat send and the transcript) only gains its source and addressee.
     */
    public static void said(Character bot, Character target, String line, String source) {
        if (bot == null || line == null || !enabled()) {
            return;
        }
        try {
            record(bot, target, line, source == null ? currentSource() : source);
        } catch (RuntimeException e) {
            log.debug("say-do: line not recorded", e);
        }
    }

    private static void record(Character bot, Character target, String line, String source) {
        long t = now();
        String n = normLine(line);
        List<Open> mine = open.get(bot.getId());
        if (mine != null) {
            synchronized (mine) {
                boolean dup = false;
                for (Open o : mine) {
                    if (t - o.at <= DEDUPE_MS && (o.norm.equals(n) || o.norm.contains(n) || n.contains(o.norm))) {
                        dup = true;
                    } else if (o.at < t) {
                        o.laterLines++;
                        o.events.add(new Event(Ev.LINE, bot.getMapId(), t));
                    }
                }
                if (dup) {
                    return;
                }
            }
        }
        List<ClaimRules.Claim> claims = ClaimRules.classify(line);
        if (claims.isEmpty()) {
            return;
        }
        String shown = target == null ? anonymiseOnMap(bot, line) : ConvoQuality.anonymise(line, target.getName());
        String src = labelFor(source, claims);
        int total = 0;
        for (List<Open> l : open.values()) {
            total += l.size();
        }
        if (total >= MAX_OPEN) {
            return;
        }
        List<Open> list = open.computeIfAbsent(bot.getId(), k -> new ArrayList<>());
        synchronized (list) {
            for (ClaimRules.Claim c : claims) {
                if (list.size() >= MAX_OPEN_PER_BOT) {
                    break;
                }
                Start s = state.start(bot, target, c);
                Open o = new Open(ids.incrementAndGet(), t, bot.getId(), bot.getName(), src, c, shown,
                        target == null ? 0 : target.getId(), s);
                Event lead = lastRare.get(bot.getId());
                if (lead != null && t - lead.at() <= LEAD_MS) {
                    o.events.add(lead);
                }
                list.add(o);
            }
        }
        if (QaLog.on(QaLog.Topic.CONVO)) {
            QaLog.map(QaLog.Topic.CONVO, bot.getMapId(), "SAYDO CLAIM " + bot.getName() + " [" + src + "] " + claims
                    + " \"" + shown + "\"");
        }
        ensureResolver();
    }

    /** A public line with the names of the real players on the bot's map replaced by {player} (privacy). */
    static String anonymiseOnMap(Character bot, String line) {
        String out = line;
        try {
            if (bot.getMap() != null) {
                for (Character c : bot.getMap().getAllPlayers()) {
                    if (!soloMapling.ArtificialPlayer.BotHelpers.isBot(c)) {
                        out = ConvoQuality.anonymise(out, c.getName());
                    }
                }
            }
        } catch (RuntimeException e) {
            // no map (tests): as it is
        }
        return out;
    }

    /** "ad" for a scripted S>/B>/LFG line; otherwise the source as given. */
    static String labelFor(String source, List<ClaimRules.Claim> claims) {
        if ("scripted".equals(source)) {
            for (ClaimRules.Claim c : claims) {
                if (c.kind() == ClaimRules.Kind.SELL || c.kind() == ClaimRules.Kind.BUY
                        || c.kind() == ClaimRules.Kind.PARTY_ASK) {
                    return "ad";
                }
            }
        }
        return source;
    }

    static String normLine(String line) {
        return line == null ? "" : line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    // ── events ──

    /** True when the bot has an open claim (the hooks' fast path: one map lookup). */
    public static boolean watching(int botId) {
        return !open.isEmpty() && open.containsKey(botId);
    }

    /** Something the bot did; ignored unless it has an open claim. */
    public static void event(Character bot, Ev kind, int mapId) {
        if (bot != null) {
            event(bot.getId(), kind, mapId);
        }
    }

    /** Events that may come a moment BEFORE the line that claims them (the trip starts, then the bot says so). */
    static final java.util.Set<Ev> RARE = java.util.EnumSet.of(Ev.RESUPPLY, Ev.IDLE, Ev.TRAVEL, Ev.WALK, Ev.PARTY,
            Ev.BUFF, Ev.TRADE, Ev.GIFT, Ev.SHOP, Ev.LEAVE_GROUP, Ev.LOGOUT);
    static final long LEAD_MS = 10_000;
    private static final ConcurrentHashMap<Integer, Event> lastRare = new ConcurrentHashMap<>();

    public static void event(int botId, Ev kind, int mapId) {
        if (RARE.contains(kind) && enabled()) {
            if (lastRare.size() > 20_000) {
                long cut = now() - LEAD_MS;
                lastRare.values().removeIf(e -> e.at() < cut);
            }
            lastRare.put(botId, new Event(kind, mapId, now()));
        }
        if (open.isEmpty()) {
            return;
        }
        List<Open> mine = open.get(botId);
        if (mine == null) {
            return;
        }
        long t = now();
        synchronized (mine) {
            for (Open o : mine) {
                if (o.events.size() < 64) {
                    o.events.add(new Event(kind, mapId, t));
                }
            }
        }
    }

    // ── judging ──

    /** How long a claim may take to come true. */
    public static long windowMs(ClaimRules.Kind k) {
        return switch (k) {
            case BUFF -> 30_000;
            case FOLLOW, AWAY, GIFT -> 60_000;
            case PARTY_OFFER, PARTY_ASK -> 90_000;
            case LEAVING, RESUPPLY -> 180_000;
            default -> 120_000;
        };
    }

    /**
     * Whether a claim came true. {@code windowOver}: the claim's window has passed (rules that need the whole window,
     * like "brb = it stayed put", only decide then). Pure.
     */
    public static Verdict judge(ClaimRules.Claim c, Start s, Now n, List<Event> evs, boolean windowOver) {
        boolean moved = n != null && s != null && (n.mapId() != s.mapId()
                || Math.hypot(n.x() - s.x(), n.y() - s.y()) >= 150);
        switch (c.kind()) {
            case COME -> {
                Event e = first(evs, Ev.WALK, Ev.TRAVEL, Ev.MAP);
                if (e != null) {
                    return Verdict.yes(e.kind().name().toLowerCase(Locale.ROOT) + " " + e.mapId());
                }
                if (s != null && s.targetMap() > 0 && s.mapId() == s.targetMap()
                        && Math.hypot(s.x() - s.targetX(), s.y() - s.targetY()) <= 150) {
                    return Verdict.yes("already next to them");
                }
                if (n != null && n.targetMap() > 0 && n.mapId() == n.targetMap()
                        && Math.hypot(n.x() - n.targetX(), n.y() - n.targetY()) <= 400) {
                    return Verdict.yes("came to them");
                }
                return windowOver ? Verdict.no("never moved toward them") : Verdict.WAIT;
            }
            case GO_TO, TRAIN_AT -> {
                for (Event e : evs) {
                    if ((e.kind() == Ev.MAP || e.kind() == Ev.TRAVEL || e.kind() == Ev.WALK)
                            && ClaimRules.sameArea(e.mapId(), c.mapId())) {
                        return Verdict.yes(e.kind().name().toLowerCase(Locale.ROOT) + " " + e.mapId());
                    }
                }
                if (s != null && ClaimRules.sameArea(s.mapId(), c.mapId())) {
                    return Verdict.yes("already there");
                }
                if (n != null && ClaimRules.sameArea(n.mapId(), c.mapId())) {
                    return Verdict.yes("there now");
                }
                return windowOver ? Verdict.no("never went to " + c.mapId()) : Verdict.WAIT;
            }
            case FOLLOW -> {
                Event e = first(evs, Ev.WALK, Ev.TRAVEL, Ev.MAP);
                if (e != null || moved) {
                    return Verdict.yes(e != null ? e.kind().name().toLowerCase(Locale.ROOT) : "moved");
                }
                return windowOver ? Verdict.no("stayed put") : Verdict.WAIT;
            }
            case PARTY_OFFER -> {
                return first(evs, Ev.PARTY) != null ? Verdict.yes("party invite/join")
                        : windowOver ? Verdict.no("no party invite") : Verdict.WAIT;
            }
            case PARTY_ASK -> {
                if (first(evs, Ev.PARTY) != null) {
                    return Verdict.yes("joined a party");
                }
                if (s != null && !s.partied()) {
                    return Verdict.yes("really partyless");
                }
                return Verdict.no("asks for a party while in one");
            }
            case BUFF -> {
                return first(evs, Ev.BUFF) != null ? Verdict.yes("buff cast")
                        : windowOver ? Verdict.no("no buff cast") : Verdict.WAIT;
            }
            case SELL, BUY -> {
                if (s != null && s.holds()) {
                    return Verdict.yes(c.kind() == ClaimRules.Kind.SELL ? "has the item" : "wants it (wish list)");
                }
                if (s != null && s.shop()) {
                    return Verdict.yes("has a shop open");
                }
                Event e = first(evs, Ev.TRADE, Ev.SHOP);
                if (e != null) {
                    return Verdict.yes(e.kind().name().toLowerCase(Locale.ROOT));
                }
                return windowOver ? Verdict.no(c.kind() == ClaimRules.Kind.SELL ? "does not hold "
                        + quoted(c.item()) : "no want for " + quoted(c.item())) : Verdict.WAIT;
            }
            case TRADE -> {
                return first(evs, Ev.TRADE) != null ? Verdict.yes("trade window")
                        : windowOver ? Verdict.no("no trade") : Verdict.WAIT;
            }
            case RESUPPLY -> {
                return first(evs, Ev.RESUPPLY) != null ? Verdict.yes("resupply trip")
                        : windowOver ? Verdict.no("no resupply trip") : Verdict.WAIT;
            }
            case AWAY -> {
                Event e = first(evs, Ev.IDLE, Ev.LOGOUT);
                if (e != null) {
                    return Verdict.yes(e.kind() == Ev.IDLE ? "went idle" : "logged out");
                }
                if (n == null) {
                    return Verdict.yes("logged out");
                }
                if (!windowOver) {
                    return Verdict.WAIT;
                }
                if (first(evs, Ev.MAP, Ev.TRAVEL, Ev.KILL) != null || moved) {
                    return Verdict.no("kept playing");
                }
                return n.laterLines() > 0 ? Verdict.no("kept chatting") : Verdict.yes("stayed put and quiet");
            }
            case LEAVING -> {
                if (first(evs, Ev.LOGOUT) != null || n == null || !n.online()) {
                    return Verdict.yes("logged out");
                }
                return windowOver ? Verdict.no("still online") : Verdict.WAIT;
            }
            case GIFT -> {
                return first(evs, Ev.GIFT) != null ? Verdict.yes("gave it")
                        : windowOver ? Verdict.no("gave nothing") : Verdict.WAIT;
            }
            case LEAVE_GROUP -> {
                Event e = first(evs, Ev.LEAVE_GROUP, Ev.LOGOUT, Ev.MAP);
                if (e != null || n == null) {
                    return Verdict.yes(e == null ? "logged out" : e.kind().name().toLowerCase(Locale.ROOT));
                }
                return windowOver ? Verdict.no("stayed") : Verdict.WAIT;
            }
            default -> {
                return Verdict.yes("not judged");
            }
        }
    }

    private static String quoted(String s) {
        return s == null || s.isEmpty() ? "it" : "'" + s + "'";
    }

    private static Event first(List<Event> evs, Ev... kinds) {
        for (Event e : evs) {
            for (Ev k : kinds) {
                if (e.kind() == k) {
                    return e;
                }
            }
        }
        return null;
    }

    private static synchronized void ensureResolver() {
        if (scheduled) {
            return;
        }
        try {
            soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
                try {
                    resolveDue(false);
                } catch (RuntimeException e) {
                    log.debug("say-do resolver", e);
                }
            }, 5, 5, TimeUnit.SECONDS);
            scheduled = true;
        } catch (RuntimeException | LinkageError e) {
            // no executor (tests): resolveDue is called by hand
        }
    }

    /** Judges the open claims that are decided or past their window; {@code all} judges every one now (tests, QA). */
    public static List<Resolved> resolveDue(boolean all) {
        List<Resolved> out = new ArrayList<>();
        long t = now();
        for (Iterator<Map.Entry<Integer, List<Open>>> it = open.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, List<Open>> e = it.next();
            List<Open> list = e.getValue();
            synchronized (list) {
                for (Iterator<Open> oi = list.iterator(); oi.hasNext(); ) {
                    Open o = oi.next();
                    boolean over = all || t - o.at >= windowMs(o.claim.kind());
                    Now n = state.now(o.botId, o.targetId);
                    if (n != null) {
                        n = new Now(n.online(), n.mapId(), n.x(), n.y(), n.targetMap(), n.targetX(), n.targetY(),
                                o.laterLines);
                    }
                    Verdict v = judge(o.claim, o.start, n, List.copyOf(o.events), over);
                    if (v.backed() == null) {
                        continue;
                    }
                    oi.remove();
                    Resolved r = new Resolved(o.at, t, o.botName, o.source, o.claim.kind(), o.claim.toString(),
                            o.claim.weak(), o.line, v.backed(), v.why(), o.targetHash);
                    out.add(r);
                    keep(r);
                    if (QaLog.on(QaLog.Topic.CONVO)) {
                        QaLog.map(QaLog.Topic.CONVO, o.start == null ? 0 : o.start.mapId(), "SAYDO "
                                + (v.backed() ? "BACKED " : "UNBACKED ") + o.claim.kind().tag() + (o.claim.weak() ? " (weak)" : "")
                                + " " + o.botName + " ["
                                + o.source + "] \"" + o.line + "\" (" + v.why() + ")");
                    }
                    try {
                        sink.resolved(r);
                    } catch (RuntimeException ex) {
                        log.debug("say-do sink", ex);
                    }
                }
                if (list.isEmpty()) {
                    it.remove();
                }
            }
        }
        return out;
    }

    private static void keep(Resolved r) {
        synchronized (recent) {
            recent.addLast(r);
            while (recent.size() > RECENT) {
                recent.removeFirst();
            }
            if (!r.weak()) {
                long[] s = bySource.computeIfAbsent(r.source(), k -> new long[2]);
                s[0]++;
                s[1] += r.backed() ? 1 : 0;
                long[] k = byKind.computeIfAbsent(r.kind(), x -> new long[2]);
                k[0]++;
                k[1] += r.backed() ? 1 : 0;
            }
        }
    }

    /** Claims made at or after {@code sinceMs} that are still open (auto-QA waits for them before judging). */
    public static int openSince(long sinceMs) {
        int n = 0;
        for (List<Open> l : open.values()) {
            synchronized (l) {
                for (Open o : l) {
                    if (o.at >= sinceMs) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** The last verdicts (newest last). */
    public static List<Resolved> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /** Since boot: strong claims and how many were backed, per source and per kind. */
    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (recent) {
            long claims = 0, backed = 0;
            Map<String, Object> src = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> e : bySource.entrySet()) {
                src.put(e.getKey(), rate(e.getValue()));
                claims += e.getValue()[0];
                backed += e.getValue()[1];
            }
            Map<String, Object> kinds = new LinkedHashMap<>();
            byKind.forEach((k, v) -> kinds.put(k.tag(), rate(v)));
            m.put("claims", claims);
            m.put("backed", backed);
            m.put("rate", claims == 0 ? null : Math.round(1000.0 * backed / claims) / 1000.0);
            m.put("bySource", src);
            m.put("byKind", kinds);
            int openNow = 0;
            for (List<Open> l : open.values()) {
                openNow += l.size();
            }
            m.put("open", openNow);
        }
        return m;
    }

    static Map<String, Object> rate(long[] v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claims", v[0]);
        m.put("backed", v[1]);
        m.put("rate", v[0] == 0 ? null : Math.round(1000.0 * v[1] / v[0]) / 1000.0);
        return m;
    }

    // ── the file (auto-QA servers only) ──

    /** {@code data/autoqa/saydo/<UTC day>.jsonl}. */
    public static Path file(Path autoqaDir, long atMs) {
        return autoqaDir.resolve("saydo").resolve(DAY.format(Instant.ofEpochMilli(atMs)) + ".jsonl");
    }

    private static void defaultSink(Resolved r) {
        if (!soloMapling.autoqa.AutoQa.isEnabled()) {
            return; // live: memory only
        }
        Path f = file(soloMapling.autoqa.AutoQa.dataDir(), r.resolvedAt());
        String row = Json.write(r.row()) + "\n";
        writer.execute(() -> {
            try {
                Files.createDirectories(f.toAbsolutePath().getParent());
                Files.writeString(f, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.debug("say-do row not written", e);
            }
        });
    }

    // ── the game side ──

    static final class GameState implements StateSource {
        @Override
        public Start start(Character bot, Character target, ClaimRules.Claim claim) {
            Point p = bot.getPosition();
            Point tp = target == null ? null : target.getPosition();
            boolean holds = false;
            boolean shop = false;
            if (claim.kind() == ClaimRules.Kind.SELL || claim.kind() == ClaimRules.Kind.BUY) {
                holds = ClaimActions.holdsOrWants(bot, claim);
                try {
                    shop = soloMapling.Economy.BotShops.hasShop(bot.getId()) || bot.getPlayerShop() != null
                            || bot.getHiredMerchant() != null;
                } catch (RuntimeException | LinkageError e) {
                    shop = false;
                }
            }
            return new Start(bot.getMapId(), p.x, p.y, bot.getParty() != null,
                    target == null ? -1 : target.getMapId(), tp == null ? 0 : tp.x, tp == null ? 0 : tp.y, holds, shop);
        }

        @Override
        public Now now(int botId, int targetId) {
            Character bot = lookup(botId);
            if (bot == null) {
                return null;
            }
            Character target = targetId > 0 ? lookup(targetId) : null;
            Point p = bot.getPosition();
            Point tp = target == null ? null : target.getPosition();
            return new Now(true, bot.getMapId(), p.x, p.y, target == null ? -1 : target.getMapId(),
                    tp == null ? 0 : tp.x, tp == null ? 0 : tp.y, 0);
        }

        private static Character lookup(int id) {
            try {
                return soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(id);
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }
    }
}
