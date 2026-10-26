package soloMapling.autoqa;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import constants.net.ServerConstants;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeTownLife;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 1.6.0 (owner 2026-10-26): the bot watch. bot-life.json counts what bots decide; this follows a few of them to see
 * the decision turn into action. It picks N online bots stratified by what they are doing (every activity in use gets
 * some), samples each one every 30 s for M minutes from memory only (no DB, no packets, no map loads), judges each
 * track ({@link BotWatchJudge}) and writes {@code data/autoqa/botwatch-latest.json} plus a dated copy under
 * {@code data/autoqa/botwatch/}.
 *
 * <p>Cheap at 3,000 bots: only the picking pass looks at every online bot (once, one lock per bot); after that a
 * 2-second position tick and the 30-second sample touch only the N chosen. Runs on the shared scheduled executor,
 * never a game or bot tick thread. Two hooks feed it while it runs, both one map lookup and gone when it is idle:
 * {@link #said} (a line a bot said, from SayDo) and {@link #killed} (a kill, from BotAttackStats).
 *
 * <p>Started by {@code !botwatch} (GM 3, test server) and by {@code !qa bw-1} / auto-QA {@code t-bw-1}.
 */
public final class BotWatch {

    public static final int DEFAULT_BOTS = 40, DEFAULT_MINUTES = 30, MAX_BOTS = 200, MAX_MINUTES = 240;
    static final long SAMPLE_MS = 30_000, POSITION_MS = 2_000;
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm'Z'").withZone(ZoneOffset.UTC);

    private static final BotWatch INSTANCE = new BotWatch();
    /** The bots being watched, by id; empty while no watch runs (the hooks' whole cost then). */
    private static volatile Map<Integer, Tracked> tracked = Map.of();

    /** What a finished watch hands its starter. */
    public record Result(BotWatchJudge.Summary summary, String line, List<String> botLines, String problem,
                         Path file, Map<String, Object> json) {
    }

    /** One watched bot. The hooks write chat and kills from other threads; the rest only the watch's tick. */
    static final class Tracked {
        final int id;
        final String name;
        final String pickedFor;
        final List<BotWatchJudge.Sample> samples = new ArrayList<>();
        final AtomicInteger kills = new AtomicInteger();
        volatile String chat;
        volatile long chatAt;
        int lastMap = -1, lastChannel, lastX, lastY;
        long lastPosAt;
        int jumpPx;
        boolean jumpWatched;
        int level;
        String job;

        Tracked(int id, String name, String pickedFor) {
            this.id = id;
            this.name = name;
            this.pickedFor = pickedFor;
        }
    }

    private ScheduledFuture<?> future;
    private long startedAt, endsAt, nextSampleAt;
    private int requested, minutes, onlineBots, noBrain;
    private String by;
    private Map<String, Integer> onlineByActivity = Map.of();
    private BotWatchJudge.Limits limits;
    private final List<Consumer<Result>> listeners = new ArrayList<>();
    private final Map<Integer, Boolean> mobMaps = new ConcurrentHashMap<>();
    private volatile Result last;

    private BotWatch() {
    }

    public static BotWatch get() {
        return INSTANCE;
    }

    // ── Hooks (any thread; a map lookup when watching, nothing otherwise) ────────────────────────────────

    /** A bot said a line (SayDo.said): the watch keeps the last one with its time. */
    public static void said(Character bot, String line) {
        Map<Integer, Tracked> m = tracked;
        if (m.isEmpty() || bot == null || line == null) {
            return;
        }
        Tracked t = m.get(bot.getId());
        if (t != null) {
            t.chat = line.length() > 120 ? line.substring(0, 120) : line;
            t.chatAt = System.currentTimeMillis();
        }
    }

    /** A bot's swing killed a mob (BotAttackStats); off-screen grinders kill in arithmetic, so EXP is the proxy. */
    public static void killed(int botId) {
        Map<Integer, Tracked> m = tracked;
        if (m.isEmpty()) {
            return;
        }
        Tracked t = m.get(botId);
        if (t != null) {
            t.kills.incrementAndGet();
        }
    }

    // ── Control ───────────────────────────────────────────────────────────────────────────────────────────

    public synchronized boolean running() {
        return future != null;
    }

    /** The last finished watch, or null. */
    public Result last() {
        return last;
    }

    /**
     * Starts a watch of {@code n} bots for {@code minutes}; {@code onDone} (may be null) hears the result. Lines for
     * whoever asked. Refuses ("already running") while one runs.
     */
    public synchronized List<String> start(int n, int minutes, String by, Consumer<Result> onDone) {
        if (future != null) {
            return List.of("A bot watch is already running (" + this.by + ", " + left() + " left): !botwatch status.");
        }
        n = Math.max(1, Math.min(MAX_BOTS, n));
        minutes = Math.max(1, Math.min(MAX_MINUTES, minutes));
        long now = System.currentTimeMillis();
        Map<String, List<Integer>> byActivity = new TreeMap<>();
        Map<Integer, Character> chars = new HashMap<>();
        int online = 0, none = 0;
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null) {
                continue;
            }
            online++;
            Doing d = doing(bot, now);
            if ("NONE".equals(d.activity())) {
                none++; // merchants, gacha, showcase: no decision to check
                continue;
            }
            byActivity.computeIfAbsent(d.activity(), k -> new ArrayList<>()).add(chr.getId());
            chars.put(chr.getId(), chr);
        }
        Map<Integer, String> picked = BotWatchJudge.stratify(byActivity, n, new Random(now));
        if (picked.isEmpty()) {
            return List.of("No bot could be picked: none with a brain is online (" + online + " bots online, " + none
                    + " without one).");
        }
        Map<Integer, Tracked> m = new LinkedHashMap<>();
        picked.forEach((id, act) -> {
            Character chr = chars.get(id);
            Tracked t = new Tracked(id, chr.getName(), act);
            t.level = chr.getLevel();
            t.job = chr.getJob() == null ? "?" : chr.getJob().name();
            m.put(id, t);
        });
        Map<String, Integer> counts = new TreeMap<>();
        byActivity.forEach((k, v) -> counts.put(k, v.size()));
        this.onlineByActivity = counts;
        this.onlineBots = online;
        this.noBrain = none;
        this.requested = n;
        this.minutes = minutes;
        this.by = by;
        this.startedAt = now;
        this.endsAt = now + minutes * 60_000L;
        this.nextSampleAt = now;
        this.limits = BotWatchJudge.Limits.forWatch(minutes * 60_000L);
        listeners.clear();
        if (onDone != null) {
            listeners.add(onDone);
        }
        tracked = m;
        future = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                AutoQa.log.warn("Bot watch tick failed", e);
            }
        }, 0, POSITION_MS, TimeUnit.MILLISECONDS);
        Map<String, Integer> mix = new TreeMap<>();
        picked.values().forEach(a -> mix.merge(a, 1, Integer::sum));
        String line = "BOTWATCH START " + m.size() + " bots for " + minutes + " min (" + by + "): " + mix
                + "; " + online + " online";
        log(line, true);
        return List.of("Watching " + m.size() + " bots for " + minutes + " min, a look every 30 s: " + mix + ".",
                "Online: " + online + " bots, " + counts + "; " + none + " without a brain are not picked.",
                "!botwatch status for progress; the result goes to data/autoqa/botwatch-latest.json.");
    }

    /** Ends the watch now and judges what it has. */
    public List<String> stop() {
        Result r;
        synchronized (this) {
            if (future == null) {
                return List.of("No bot watch is running." + (last == null ? "" : " Last: " + last.line()));
            }
            r = finish("stopped");
        }
        notify(r);
        return List.of("Stopped. " + r.line());
    }

    public synchronized List<String> status() {
        if (future == null) {
            return List.of(last == null ? "No bot watch has run since the server started." : "Last watch: " + last.line());
        }
        List<String> out = new ArrayList<>();
        Map<Integer, Tracked> m = tracked;
        int samples = m.values().stream().mapToInt(t -> t.samples.size()).max().orElse(0);
        out.add("Bot watch (" + by + "): " + m.size() + " bots, " + samples + " looks so far, " + left() + " left.");
        // a peek: each bot's judgement so far
        List<Map.Entry<String, BotWatchJudge.Verdict>> sofar = new ArrayList<>();
        for (Tracked t : m.values()) {
            List<BotWatchJudge.Sample> copy;
            synchronized (t.samples) {
                copy = List.copyOf(t.samples);
            }
            if (!copy.isEmpty()) {
                sofar.add(Map.entry(t.pickedFor, BotWatchJudge.judge(copy, limits).verdict()));
            }
        }
        out.add("So far: " + BotWatchJudge.summarize(sofar).line((System.currentTimeMillis() - startedAt) / 60_000));
        return out;
    }

    private String left() {
        long ms = Math.max(0, endsAt - System.currentTimeMillis());
        return (ms / 60_000) + " min " + (ms / 1000 % 60) + " s";
    }

    // ── The tick (scheduled executor) ─────────────────────────────────────────────────────────────────────

    private void tick() {
        Result done = null;
        synchronized (this) {
            if (future == null) {
                return;
            }
            long now = System.currentTimeMillis();
            Map<Integer, Tracked> m = tracked;
            for (Tracked t : m.values()) {
                positionTick(t, now);
            }
            if (now >= nextSampleAt || now >= endsAt) {
                nextSampleAt += SAMPLE_MS;
                for (Tracked t : m.values()) {
                    BotWatchJudge.Sample s = sample(t, now);
                    synchronized (t.samples) {
                        t.samples.add(s);
                    }
                }
            }
            if (now >= endsAt) {
                done = finish("done");
            }
        }
        if (done != null) {
            notify(done);
        }
    }

    /**
     * Whether a real player stands on this bot's own map instance (its channel), as the likeness rule
     * ({@link soloMapling.ArtificialPlayer.GCMoveSystem.WatchGate}) counts one: only then may the bot not snap.
     * TEST 2026-10-26 (knucklegolem, TELEPORTY on Kerning City): the watch asked GCMovement.isMapObserved, which is true
     * for a map id when a player stands on it on ANY channel. The observer was on channel 2, the bot on channel 1 where
     * nobody could see it, so an allowed off-screen snap was reported as one in front of a player. The id check stays
     * first as the cheap filter; the per-instance look runs only when it says yes.
     */
    static boolean watchedHere(Character chr) {
        return chr != null && chr.getMap() != null
                && soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(chr.getMapId())
                && soloMapling.ArtificialPlayer.GCMoveSystem.WatchGate.watched(chr);
    }

    /** Every 2 s: the largest same-map jump past a walk since the last sample (TELEPORTY's evidence). */
    void positionTick(Tracked t, long now) {
        try {
            BotSM bot = CharacterStorage.getBotById(t.id);
            Character chr = bot == null ? null : bot.getChr();
            MapleMap map = chr == null ? null : chr.getMap();
            if (map == null) {
                t.lastMap = -1;
                return;
            }
            Point p = chr.getPosition();
            if (p == null) {
                return;
            }
            if (t.lastMap == map.getId() && t.lastChannel == map.getChannelId()) {
                int over = BotWatchJudge.excessJump(t.lastX, t.lastY, p.x, p.y, now - t.lastPosAt);
                if (over > t.jumpPx) {
                    t.jumpPx = over;
                    t.jumpWatched = watchedHere(chr);
                }
            }
            t.lastMap = map.getId();
            t.lastChannel = map.getChannelId();
            t.lastX = p.x;
            t.lastY = p.y;
            t.lastPosAt = now;
        } catch (RuntimeException | LinkageError ignored) {
            // a sample, not a contract: the next tick reads again
        }
    }

    private BotWatchJudge.Sample sample(Tracked t, long now) {
        BotSM bot = CharacterStorage.getBotById(t.id);
        Character chr = bot == null ? null : bot.getChr();
        MapleMap map = chr == null ? null : chr.getMap();
        if (map == null || chr.getPosition() == null) {
            return BotWatchJudge.Sample.offline(now);
        }
        try {
            Point p = chr.getPosition();
            Doing d = doing(bot, now);
            int jump = t.jumpPx;
            boolean jumpWatched = t.jumpWatched;
            t.jumpPx = 0;
            t.jumpWatched = false;
            t.level = chr.getLevel();
            return new BotWatchJudge.Sample(now, true, map.getChannelId(), map.getId(), p.x, p.y, d.activity(),
                    d.target(), d.phase(), chr.getLevel(), chr.getExp(), chr.getMeso(), t.kills.get(), potions(chr),
                    used(chr, InventoryType.ETC), used(chr, InventoryType.EQUIP), t.chat, t.chatAt,
                    chr.getParty() != null, chr.getChair() > 0, map.isTown(), mobMap(map.getId()),
                    watchedHere(chr), jump,
                    jumpWatched, soloMapling.Economy.BotShops.hasShop(chr.getId()));
        } catch (RuntimeException | LinkageError e) {
            AutoQa.log.debug("Bot watch could not sample {}: {}", t.name, e.toString());
            return BotWatchJudge.Sample.offline(now);
        }
    }

    private static int potions(Character chr) {
        try {
            return soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.potionCount(chr, true)
                    + soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.potionCount(chr, false);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static int used(Character chr, InventoryType type) {
        Inventory inv = chr.getInventory(type);
        if (inv == null) {
            return 0;
        }
        inv.lockInventory();
        try {
            return Math.max(0, Byte.toUnsignedInt(inv.getSlotLimit()) - inv.getNumFreeSlot());
        } finally {
            inv.unlockInventory();
        }
    }

    /** A map with mob spawns (MapMobIndex reads WZ once per map; a map it can't read counts as mob-less). */
    private boolean mobMap(int mapId) {
        return mobMaps.computeIfAbsent(mapId, id -> {
            try {
                return soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.level(id) >= 0;
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        });
    }

    /** What a bot is doing now: its brain's activity, else the one its phase stands for; NONE without either. */
    record Doing(String activity, int target, String phase) {
    }

    static Doing doing(BotSM bot, long now) {
        try {
            if (bot instanceof TrainingBot tb) {
                LifeDecision.Activity active = tb.lifeActivityState().activity();
                TrainingBot.LifePhase lp = tb.lifePhase();
                String phase = lp == null ? "INIT" : lp.name();
                String act = active != null ? active.name() : phaseActivity(phase, tb.currentLifeVisit());
                int target = "EVENT".equals(act)
                        ? soloMapling.worldevents.WorldEventSteer.mapFor(tb.getChr().getId()) : tb.watchTarget();
                return new Doing(act, target, phase);
            }
            LifeTownLife life = bot instanceof SocialBot s ? s.life() : bot instanceof TownWandererBot w ? w.life() : null;
            if (life != null) {
                LifeDecision.Activity active = life.lifeActivityState().activity();
                if (active == null && life.lifeRuntime() != null) {
                    LifeDecision.Current c = life.lifeCurrent(life.lifeRuntime().memory().profile(), now);
                    active = c == null ? null : c.activity();
                }
                return new Doing(active == null ? "HANG_OUT" : active.name(), -1, bot.getBotType());
            }
            if (bot instanceof SocialBot || bot instanceof TownWandererBot) {
                return new Doing("HANG_OUT", -1, bot.getBotType()); // a town bot without the brain lives in town
            }
        } catch (RuntimeException | LinkageError ignored) {
            // fall through: nothing to check
        }
        return new Doing("NONE", -1, bot.getBotType());
    }

    /** The activity a training bot's phase stands for when no brain activity is active. */
    static String phaseActivity(String phase, LifeDecision.Activity lifeVisit) {
        return switch (phase) {
            case "GRIND", "GO_TRAIN", "DECIDE" -> "GRIND";
            case "IN_TOWN" -> "HANG_OUT";
            case "LIFE_TRAVEL", "LIFE_DWELL" -> lifeVisit == null ? "HANG_OUT" : lifeVisit.name();
            case "BREAK_TRAVEL", "BREAK_REST" -> "BREAK";
            case "GO_TOWN", "SHOP_TRAVEL", "SHOP_DWELL", "SHOP_RETURN" -> "RESTOCK";
            default -> "NONE"; // INIT: just logged in
        };
    }

    // ── Finish: judge, write, tell ────────────────────────────────────────────────────────────────────────

    /** Under the lock: stop the tick, judge every track, write the files. */
    private Result finish(String status) {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        Map<Integer, Tracked> m = tracked;
        tracked = Map.of();
        long now = System.currentTimeMillis();
        List<Map.Entry<String, BotWatchJudge.Verdict>> verdicts = new ArrayList<>();
        List<Map<String, Object>> bots = new ArrayList<>();
        List<String> botLines = new ArrayList<>();
        for (Tracked t : m.values()) {
            List<BotWatchJudge.Sample> track;
            synchronized (t.samples) {
                track = List.copyOf(t.samples);
            }
            if (track.isEmpty()) {
                continue;
            }
            BotWatchJudge.Judgement j = BotWatchJudge.judge(track, limits);
            verdicts.add(Map.entry(t.pickedFor, j.verdict()));
            botLines.add(t.name + " " + t.pickedFor + " " + j.verdict() + " " + j.reason());
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", t.id);
            b.put("name", t.name);
            b.put("level", t.level);
            b.put("job", t.job);
            b.put("pickedFor", t.pickedFor);
            b.put("verdict", j.verdict().name());
            b.put("reason", j.reason());
            b.put("jumpsOffscreen", j.jumpsOffscreen());
            b.put("segments", j.segments().stream().map(BotWatchJudge.Segment::toJson).toList());
            b.put("track", track.stream().map(BotWatchJudge.Sample::toJson).toList());
            bots.add(b);
        }
        bots.sort(Comparator.comparingInt((Map<String, Object> b) ->
                -BotWatchJudge.Verdict.valueOf((String) b.get("verdict")).rank()).thenComparing(b -> (String) b.get("pickedFor")));
        BotWatchJudge.Summary sum = BotWatchJudge.summarize(verdicts);
        long ranMin = Math.max(0, now - startedAt) / 60_000;
        String line = sum.line(ranMin);
        String problem = BotWatchJudge.checkProblem(sum);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", 1);
        out.put("status", status);
        out.put("build", ServerConstants.SLUMSMS_VERSION);
        out.put("by", by);
        out.put("started", Instant.ofEpochMilli(startedAt).toString());
        out.put("ended", Instant.ofEpochMilli(now).toString());
        out.put("minutesPlanned", minutes);
        out.put("minutesRun", ranMin);
        out.put("samplePeriodSec", SAMPLE_MS / 1000);
        out.put("positionTickSec", POSITION_MS / 1000);
        out.put("requestedBots", requested);
        out.put("watchedBots", bots.size());
        out.put("onlineBots", onlineBots);
        out.put("noBrainBots", noBrain);
        out.put("onlineByActivity", onlineByActivity);
        Map<String, Object> lim = new LinkedHashMap<>();
        lim.put("idleMinutes", limits.idleMs() / 60_000.0);
        lim.put("moveTolerancePx", limits.moveTolPx());
        lim.put("minJudgeSec", limits.minJudgeMs() / 1000);
        lim.put("grindProgressSec", limits.progressMs() / 1000);
        lim.put("travelMinutes", limits.travelMs() / 60_000);
        lim.put("teleport", "same-map move past 300 px + 400 px/s sideways or up, 800 px/s down, on a watched map");
        out.put("limits", lim);
        out.put("summary", line);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("bots", sum.bots());
        for (BotWatchJudge.Verdict v : BotWatchJudge.Verdict.values()) {
            totals.put(BotWatchJudge.key(v), sum.count(v));
        }
        totals.put("okPercent", sum.okPercent());
        totals.put("idlePercent", sum.idlePercent());
        out.put("totals", totals);
        out.put("byActivity", sum.byActivity());
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("pass", problem == null);
        check.put("problem", problem);
        check.put("bar", "OK >= " + BotWatchJudge.CHECK_MIN_OK_PERCENT + "%, IDLE <= "
                + BotWatchJudge.CHECK_MAX_IDLE_PERCENT + "%, TELEPORTY 0, at least " + BotWatchJudge.CHECK_MIN_BOTS + " bots");
        out.put("check", check);
        out.put("bots", bots);
        Path file = write(out, AutoQa.dataDir(), startedAt);
        log("BOTWATCH " + status.toUpperCase(java.util.Locale.ROOT) + ": " + line, problem == null);
        Result r = new Result(sum, line, List.copyOf(botLines), problem, file, out);
        last = r;
        return r;
    }

    /** botwatch-latest.json and the dated copy; the dated one's path (null when it could not be written). */
    static Path write(Map<String, Object> result, Path dir, long startedMs) {
        String json = Json.write(result);
        Path dated = dir.resolve("botwatch").resolve("botwatch-" + STAMP.format(Instant.ofEpochMilli(startedMs)) + ".json");
        try {
            AutoQa.writeAtomic(dir.resolve("botwatch-latest.json"), json);
            AutoQa.writeAtomic(dated, json);
            return dated;
        } catch (IOException e) {
            AutoQa.log.warn("Bot watch could not write its result to {}: {}", dir, e.toString());
            return null;
        }
    }

    private void notify(Result r) {
        List<Consumer<Result>> ls;
        synchronized (this) {
            ls = List.copyOf(listeners);
            listeners.clear();
        }
        for (Consumer<Result> l : ls) {
            try {
                l.accept(r);
            } catch (RuntimeException e) {
                AutoQa.log.warn("Bot watch listener failed", e);
            }
        }
    }

    private static void log(String line, boolean ok) {
        if (QaLog.on(QaLog.Topic.AUTOQA)) {
            QaLog.any(QaLog.Topic.AUTOQA, line);
        }
        if (ok) {
            AutoQa.log.info(line);
        } else {
            AutoQa.log.warn(line);
        }
    }
}
