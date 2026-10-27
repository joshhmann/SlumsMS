package soloMapling.worldevents;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.server.Server;
import server.ItemInformationProvider;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.NPC;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingConstants;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.BotLogger.log;

/**
 * Scripted world events (ticket 21; spec in SoloMapling {@code .pi-flow/world-events/spec.md}). Templates from
 * {@code WorldEvents.yaml}, picked by {@link EventScheduler} - never by a model - and run here on channel 1, where the
 * bots live. Five kinds: invasion (waves then a boss), boss sighting, treasure hunt (a hidden chest and hints), hot
 * spot (bonus EXP on one map for real players), wandering NPC (a one-off fetch quest). Rates, drops and shops are never
 * touched; what an event gives is its own reward table, to real players only.
 * <p>
 * 1.6.0: the {@link EventDirector} plans each day - a headline event at ~8 PM PT (10- and 1-minute warnings) and 1-3
 * small ones at peak hours - and its plan is saved ({@code -Dsolomapling.events.stateFile}, default
 * {@value #DEFAULT_STATE_FILE}) so a restart never fires one twice. It runs unless {@code -Dsolomapling.events=false}
 * (or a GM says {@code !event auto off}); GM commands work either way. A giant cake (kind GIANT_CAKE) is a boss with
 * an event HP for a whole town to whack; an invasion's map lets heavy hits kill bots ({@link #swarmed}), who respawn as
 * usual. Bots with the brain on are offered the event (the EVENT activity) and go if their brain picks it.
 * {@code -Dsolomapling.worldEvents.rewards=false} turns every reward table off. {@code -Dslumsms.announceFile=PATH}
 * also writes the public moments to a feed file for Discord ({@link WorldEventFeed}).
 */
public final class WorldEvents {

    /** One NPC dialog step, for {@code scripts/npc/worldEventNpc.js}. */
    public record Reply(String text, boolean yesNo) {
    }

    /** The outcome of a start request. */
    public record StartResult(ActiveEvent event, String error) {
    }

    private static final WorldEvents INSTANCE = new WorldEvents();
    static final long RECRUIT_EVERY_MS = 2 * 60_000L;
    /** 1.6.0 madness: an invasion keeps topping its crowd up every tick (30 s) and on every wave. */
    static final long INVASION_RECRUIT_EVERY_MS = 25_000L;
    public static final String DEFAULT_STATE_FILE = "data/worldevents/schedule.json";

    /** Invasion maps (channel << 32 | map id) where a heavy hit may kill a bot. Read on every bot hit: kept flat. */
    private final java.util.Set<Long> swarmMaps = ConcurrentHashMap.newKeySet();
    /** When each bot last died to a swarm (per-bot cooldown {@link #SWARM_BOT_COOLDOWN_MS}). */
    private final Map<Integer, Long> lastSwarmDeath = new ConcurrentHashMap<>();
    /** A hit that leaves a bot under half its max HP on an invasion map may kill it (bots pot at 40-60%: a swarm
     * gets them before the potion does). */
    static final double SWARM_HP_SHARE = 0.5;
    static final double SWARM_DEATH_CHANCE = 0.6;
    static final long SWARM_BOT_COOLDOWN_MS = 45_000L;
    /**
     * 1.6.0 madness (owner 2026-10-27: "a handful of bot deaths", 2-6 or more if it reads as chaos, "not dozens"): with
     * lv 1 bots and town bots in the fight a hit kills easily, so the event's deaths are paced (at most one every
     * {@link #SWARM_EVENT_GAP_MS}) and capped. Owner 2026-10-27 ("It needs to be harder mobs"): the Henesys invasion
     * now runs 6-12 minutes against lv 40-80 invaders, so the cap is two dozen (a long fight can reach it at this pace).
     */
    static final int SWARM_DEATHS_PER_EVENT = 24;
    static final long SWARM_EVENT_GAP_MS = 15_000L;

    private final EventDirector director = new EventDirector(EventDirector.Config.fromSystemProperties(zone()));
    private volatile String savedDay = "";

    private final List<ActiveEvent> active = new CopyOnWriteArrayList<>();
    private final List<ActiveEvent> recent = new CopyOnWriteArrayList<>();
    private final Map<String, Long> lastEndedAt = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile long lastStartAt;
    private volatile EventScheduler.Settings settings = EventScheduler.Settings.fromSystemProperties();
    private volatile ScheduledFuture<?> ticker;
    /** Hot spots by map id (channel 1): the EXP multiplier. Read on every mob kill, so kept flat. */
    private final Map<Integer, Double> hotSpots = new ConcurrentHashMap<>();

    private WorldEvents() {
    }

    public static WorldEvents getInstance() {
        return INSTANCE;
    }

    public EventScheduler.Settings settings() {
        return settings;
    }

    /** GM: switch the automatic scheduler on or off until the next restart. */
    public void setAuto(boolean value) {
        settings = settings.withAuto(value);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────

    /** Called when SoloMapling's world comes up: loads the templates and starts the tick. */
    public synchronized void start() {
        if (ticker != null) {
            return;
        }
        EventTemplates.Result r = EventTemplates.reload();
        for (String err : r.errors()) {
            log("[WorldEvents] template problem: " + err);
        }
        WorldEventBias.register(); // 1.6.0: the brain's EVENT activity leans by Bartle type
        if (director.load(stateFile())) {
            savedDay = director.state().day();
            lastEndedAt.putAll(director.state().lastEndedAt());
            log("[WorldEvents] schedule restored from " + stateFile() + " (day " + savedDay + ", headline "
                    + director.state().headlineTemplate() + " " + director.state().headlineStatus() + ")");
        }
        long period = settings.tickMs();
        ticker = ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(this::tickSafely,
                period, period, TimeUnit.MILLISECONDS);
        BotShutdown.onQuiesce(this::shutdown);
        log("[WorldEvents] " + r.templates().size() + " templates, " + r.errors().size() + " problems; director "
                + (settings.auto() ? "on (headline at " + director.config().headlineAt() + " " + director.config().zone()
                + ", 1-3 small at " + director.config().peaks() + ", max " + EventScheduler.MAX_ACTIVE_CAP
                + " at once)" : "off (-Dsolomapling.events=false; !event auto on)"));
    }

    private synchronized void shutdown() {
        for (ActiveEvent e : active) {
            finish(e, "server stopping", false);
        }
        saveState();
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }

    private void tickSafely() {
        try {
            tick(System.currentTimeMillis(), ThreadLocalRandom.current());
        } catch (Exception e) {
            log("[WorldEvents] tick failed: " + e);
        }
    }

    void tick(long now, Random rng) {
        if (BotShutdown.isQuiesced()) {
            return;
        }
        for (EventScheduler.Running due : EventScheduler.overdue(running(), now)) {
            ActiveEvent e = byId(due.eventId());
            if (e != null) {
                finish(e, "time up", true);
            }
        }
        for (ActiveEvent e : active) {
            try {
                step(e, now, rng);
            } catch (RuntimeException ex) {
                log("[WorldEvents] #" + e.id + " step failed: " + ex);
            }
        }
        recent.removeIf(e -> now - e.endedAt > WorldEventReactions.AFTERMATH_MS);
        for (ActiveEvent e : recent) {
            WorldEventReactions.chatter(e, now, rng, true);
        }
        if (settings.auto()) {
            runDirector(now, rng);
        }
    }

    /** 1.6.0: the day's plan - warnings, the headline, the small ones - then the plan is saved when it moved. */
    void runDirector(long now, Random rng) {
        List<EventTemplate> templates = EventTemplates.get().templates();
        List<EventDirector.Action> actions = director.tick(now, templates, active.size(), lastEndedAt, rng);
        for (EventDirector.Action a : actions) {
            EventTemplate t = EventTemplates.byId(a.templateId());
            if (t == null) {
                if (a.kind() == EventDirector.Kind.START_HEADLINE) {
                    director.headlineFailed();
                } else if (a.kind() == EventDirector.Kind.START_SMALL) {
                    director.smallStarted();
                }
                continue;
            }
            switch (a.kind()) {
                case WARN -> warn(t, a.mapId(), a.minutesLeft(), rng);
                case START_HEADLINE -> {
                    StartResult r = startEvent(t, spotOn(t, a.mapId()), "director (headline)", now, rng, true);
                    if (r.error() == null) {
                        director.headlineStarted(t.id());
                    } else if (!BotShutdown.isQuiesced()) {
                        log("[WorldEvents] headline " + t.id() + " could not start: " + r.error());
                        director.headlineFailed();
                    }
                }
                case START_SMALL -> {
                    StartResult r = startEvent(t, null, "director", now, rng, false);
                    if (r.error() != null) {
                        log("[WorldEvents] small event " + t.id() + " could not start: " + r.error());
                    }
                    director.smallStarted();
                }
            }
        }
        if (!actions.isEmpty() || !director.state().day().equals(savedDay)) {
            saveState();
        }
    }

    /** The template's spot on this map (the planned headline map), or null for a random one. */
    static EventTemplate.Spot spotOn(EventTemplate t, int mapId) {
        for (EventTemplate.Spot s : t.spots()) {
            if (s.mapId() == mapId) {
                return s;
            }
        }
        return null;
    }

    /** The headline's warning: a blue notice to the world, and maybe a bot's megaphone (through its gate). */
    private void warn(EventTemplate t, int mapId, int minutesLeft, Random rng) {
        String mapName = WorldEventReactions.mapName(mapId);
        String text = WorldEventText.fill(WorldEventText.warning(t.kind(), minutesLeft), Map.of("EVENT", t.name(),
                "MAP", mapName, "CH", String.valueOf(channel()), "MIN", String.valueOf(minutesLeft)));
        announceText(text);
        try {
            WorldEventReactions.warned(t, mapId, mapName, minutesLeft, rng);
        } catch (RuntimeException ex) {
            log("[WorldEvents] warning chatter failed: " + ex);
        }
        qa("WEVENT WARN " + t.id() + " on " + mapId + " in " + minutesLeft + " min: " + text);
    }

    /** {@code !qa ev-1/ev-2}: the headline warning for this template and map, as the director sends it. */
    public void qaWarn(String templateId, int mapId, int minutesLeft) {
        EventTemplate t = EventTemplates.byId(templateId);
        if (t != null) {
            warn(t, mapId, minutesLeft, ThreadLocalRandom.current());
        }
    }

    /** The server's zone (BOT_ROSTER_TIMEZONE), the same clock the population director runs on. */
    static java.time.ZoneId zone() {
        try {
            return BotPopulationDirector.localNow().getZone();
        } catch (RuntimeException | LinkageError e) {
            return java.time.ZoneId.of("America/Los_Angeles");
        }
    }

    public static java.nio.file.Path stateFile() {
        return java.nio.file.Path.of(System.getProperty("solomapling.events.stateFile", DEFAULT_STATE_FILE));
    }

    void saveState() {
        director.mergeEnded(lastEndedAt);
        if (!director.save(stateFile())) {
            log("[WorldEvents] could not save the schedule to " + stateFile());
        }
        savedDay = director.state().day();
    }

    public EventDirector director() {
        return director;
    }

    /** {@code !event next}: the day's plan. */
    public List<String> next(long now) {
        List<String> out = new ArrayList<>();
        out.add("Director " + (settings.auto() ? "ON" : "OFF (!event auto on, or -Dsolomapling.events=true)")
                + "; headline daily at " + director.config().headlineAt() + " " + director.config().zone()
                + ", small events in " + director.config().peaks() + ".");
        if (director.state().day().isEmpty() || !director.state().day().equals(
                EventDirector.localDate(now, director.config().zone()).toString())) {
            out.add("(Today's plan is made at the next tick.)");
        }
        out.addAll(director.describe(now));
        return out;
    }

    /** QA lines on the WORLD_EVENT topic (only while a run listens). */
    static void qa(String line) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.WORLD_EVENT)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.WORLD_EVENT, line);
        }
    }

    /** The population director's curve right now, as a fraction of peak (weekend lift included). */
    public static double curveFraction() {
        try {
            return BotPopulationDirector.curve().target(1000, BotPopulationDirector.localNow()) / 1000.0;
        } catch (RuntimeException e) {
            return 0.5;
        }
    }

    List<EventScheduler.Running> running() {
        List<EventScheduler.Running> out = new ArrayList<>();
        for (ActiveEvent e : active) {
            out.add(e.running());
        }
        return out;
    }

    public List<ActiveEvent> active() {
        return List.copyOf(active);
    }

    public List<ActiveEvent> recent() {
        return List.copyOf(recent);
    }

    public Map<String, Long> lastEndedAt() {
        return Map.copyOf(lastEndedAt);
    }

    public ActiveEvent byId(int id) {
        for (ActiveEvent e : active) {
            if (e.id == id) {
                return e;
            }
        }
        return null;
    }

    // ── start ────────────────────────────────────────────────────────────────────────────────────

    /** GM start: the template by id, optionally on one of its maps. */
    public StartResult startById(String templateId, int mapId, String by) {
        EventTemplate t = EventTemplates.byId(templateId);
        if (t == null) {
            return new StartResult(null, "no template '" + templateId + "' (see !event list)");
        }
        EventTemplate.Spot spot = null;
        if (mapId > 0) {
            for (EventTemplate.Spot s : t.spots()) {
                if (s.mapId() == mapId) {
                    spot = s;
                }
            }
            if (spot == null) {
                return new StartResult(null, t.id() + " cannot run on map " + mapId + "; its maps: " + mapsOf(t));
            }
        }
        StartResult r = startEvent(t, spot, by, System.currentTimeMillis(), ThreadLocalRandom.current(), false);
        if (r.error() == null) {
            director.noteManualStart(t);
        }
        return r;
    }

    /**
     * {@code !qa ev-1 / ev-2}: a template started with a test-sized boss and length (the template file is untouched;
     * the copy lives only for this event). {@code bossHp} 0 keeps the template's.
     */
    public StartResult startForQa(String templateId, int mapId, String by, long bossHp, int minutes) {
        EventTemplate t = EventTemplates.byId(templateId);
        if (t == null) {
            return new StartResult(null, "no template '" + templateId + "'");
        }
        EventTemplate copy = new EventTemplate(t.id(), t.kind(), t.name(), t.weight(),
                minutes > 0 ? Math.min(EventTemplates.MAX_MINUTES, minutes) : t.minutes(), t.cooldownMinutes(),
                t.minLevel(), t.maxLevel(), t.botCap(), t.spots(), t.waves(), t.bossMobId(), t.npcId(), t.expBonus(),
                t.rewards(), t.enabled(), t.slot(), bossHp > 0 ? bossHp : t.bossHp(), t.participation(),
                t.participationExpPct());
        EventTemplate.Spot spot = mapId > 0 ? spotOn(copy, mapId) : null;
        if (mapId > 0 && spot == null) {
            return new StartResult(null, t.id() + " cannot run on map " + mapId + "; its maps: " + mapsOf(t));
        }
        return startEvent(copy, spot, by, System.currentTimeMillis(), ThreadLocalRandom.current(), false);
    }

    static String mapsOf(EventTemplate t) {
        List<Integer> ids = new ArrayList<>();
        for (EventTemplate.Spot s : t.spots()) {
            ids.add(s.mapId());
        }
        return ids.toString();
    }

    synchronized StartResult startEvent(EventTemplate t, EventTemplate.Spot chosen, String by, long now, Random rng) {
        return startEvent(t, chosen, by, now, rng, false);
    }

    synchronized StartResult startEvent(EventTemplate t, EventTemplate.Spot chosen, String by, long now, Random rng,
                                        boolean headline) {
        if (BotShutdown.isQuiesced()) {
            return new StartResult(null, "server is stopping");
        }
        if (!EventScheduler.roomFor(running())) {
            return new StartResult(null, "already " + active.size() + " events running (max "
                    + EventScheduler.MAX_ACTIVE_CAP + ")");
        }
        if (active.stream().anyMatch(e -> e.template.id().equals(t.id()))) {
            return new StartResult(null, t.id() + " is already running");
        }
        EventTemplate.Spot spot = chosen != null ? chosen : pickSpot(t, rng);
        if (spot == null) {
            return new StartResult(null, "no free map for " + t.id());
        }
        MapleMap map = mapOf(spot.mapId());
        if (map == null) {
            return new StartResult(null, "map " + spot.mapId() + " does not load");
        }
        int[] band = band(t, spot);
        ActiveEvent e = new ActiveEvent(ids.incrementAndGet(), t, spot, channel(), now, by, band[0], band[1]);
        e.headline = headline;
        e.mapName = map.getMapName() == null ? String.valueOf(spot.mapId()) : map.getMapName();
        e.regionName = WorldEventReactions.regionName(spot.mapId());
        String problem = begin(e, map, rng);
        if (problem != null) {
            cleanUp(e, map);
            return new StartResult(null, problem);
        }
        active.add(e);
        lastStartAt = now;
        if (t.kind() == EventTemplate.Kind.INVASION) {
            swarmMaps.add(mapKey(e.channel, e.mapId()));
            soloMapling.ArtificialPlayer.BotMapCap.raise(e.channel, e.mapId()); // WORLD_EVENT_MAP_MAX while it runs
            // 1.6.0 madness: its mobs hit every bot in them, whatever moves the bot (InvasionSwarmTouch)
            e.touchTask = ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(
                    InvasionSwarmTouch.task(e), InvasionSwarmTouch.BEAT_MS, InvasionSwarmTouch.BEAT_MS,
                    TimeUnit.MILLISECONDS);
        }
        announce(e, WorldEventText.start(t.kind()));
        qa("WEVENT START #" + e.id + " " + t.id() + " (" + t.kind().label() + ") on " + e.mapId() + " " + e.mapName
                + " ch" + e.channel + ", bots lv " + e.minLevel + "-" + e.maxLevel + ", cap " + crowdCap(t)
                + (e.bossOid >= 0 ? ", boss HP " + bossHpOf(e, map) : "") + ", by " + by);
        WorldEventFeed.started(e, now); // public Discord feed (-Dslumsms.announceFile), off the game thread
        boolean hidden = t.kind() == EventTemplate.Kind.TREASURE_HUNT; // the site names the region, as the notice does
        soloMapling.site.SiteFeed.eventStarted(t.name(), hidden ? e.regionName : e.mapName, hidden ? 0 : e.mapId(),
                e.channel); // 1.6.0 Track D: the website's feed (memory only)
        log("[WorldEvents] started " + e.describe(now));
        if (t.kind() != EventTemplate.Kind.TREASURE_HUNT) {
            WorldEventReactions.recruit(e, rng); // treasure: bots only go once the last hint names the map
        }
        if (t.kind() == EventTemplate.Kind.INVASION) {
            InvasionTownCrowd.rally(e, map, now, rng); // the town bots already there fight or run, within seconds
        }
        e.lastRecruitAt = now;
        e.lastChatterAt = now - 30_000; // first line a minute in, not at once
        WorldEventReactions.megaphoneStart(e, rng);
        return new StartResult(e, null);
    }

    /** A spot not already used by a running event. */
    private EventTemplate.Spot pickSpot(EventTemplate t, Random rng) {
        List<EventTemplate.Spot> free = new ArrayList<>();
        for (EventTemplate.Spot s : t.spots()) {
            if (active.stream().noneMatch(e -> e.mapId() == s.mapId())) {
                free.add(s);
            }
        }
        return free.isEmpty() ? null : free.get(rng.nextInt(free.size()));
    }

    private static int channel() {
        try {
            return SoloMaplingConstants.mainChannel().getId();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    static MapleMap mapOf(int mapId) {
        try {
            return SoloMaplingConstants.mainChannel().getMapFactory().getMap(mapId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The bot levels that react: the template's own, else from the mobs, boss or map. */
    static int[] band(EventTemplate t, EventTemplate.Spot spot) {
        if (t.minLevel() > 0) {
            return new int[]{t.minLevel(), t.maxLevel()};
        }
        return switch (t.kind()) {
            case INVASION -> {
                int low = 255;
                for (EventTemplate.Wave w : t.waves()) {
                    low = Math.min(low, mobLevel(w.mobId()));
                }
                yield bandFor(t.kind(), low, mobLevel(t.bossMobId()));
            }
            case BOSS_SIGHTING -> bandFor(t.kind(), mobLevel(spot.mobId()), mobLevel(spot.mobId()));
            case GIANT_CAKE -> new int[]{1, 255};
            default -> bandFor(t.kind(), MapMobIndex.level(spot.mapId()), 0);
        };
    }

    /** Pure band rule (tested): invasion low mob -5 to boss +10; sighting boss -15/+25; a map's mobs -8/+15. */
    static int[] bandFor(EventTemplate.Kind kind, int low, int high) {
        return switch (kind) {
            case INVASION -> new int[]{Math.max(1, low - 5), Math.min(255, Math.max(low, high) + 10)};
            case BOSS_SIGHTING -> new int[]{Math.max(1, low - 15), Math.min(255, low + 25)};
            case WANDERING_NPC, GIANT_CAKE -> new int[]{1, 255};
            default -> low <= 0 ? new int[]{1, 255} : new int[]{Math.max(1, low - 8), Math.min(255, low + 15)};
        };
    }

    private static int mobLevel(int mobId) {
        try {
            return Math.max(1, LifeFactory.getMonsterLevel(mobId));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Sets the event up on its map; returns a problem or null. */
    private String begin(ActiveEvent e, MapleMap map, Random rng) {
        EventTemplate t = e.template;
        switch (t.kind()) {
            case INVASION -> {
                Monster boss = LifeFactory.getMonster(t.bossMobId());
                if (boss == null) {
                    return "boss " + t.bossMobId() + " does not load";
                }
                e.subject = boss.getName();
                spawnWave(e, map, 0, rng);
                if (e.mobOids.isEmpty()) {
                    return "no mobs could be spawned on " + e.mapId();
                }
            }
            case BOSS_SIGHTING -> {
                Monster boss = spawnMob(map, e.spot.mobId(), randomSpot(map, rng), t.bossHp());
                if (boss == null) {
                    return "boss " + e.spot.mobId() + " could not be spawned";
                }
                e.subject = boss.getName();
                e.bossOid = boss.getObjectId();
                e.mobOids.add(boss.getObjectId());
            }
            case TREASURE_HUNT -> {
                NPC npc = spawnNpc(map, t.npcId(), randomSpot(map, rng));
                if (npc == null) {
                    return "npc " + t.npcId() + " could not be spawned";
                }
                e.npcOid = npc.getObjectId();
                e.npcId = t.npcId();
                e.npcName = npc.getName();
            }
            case GIANT_CAKE -> {
                Monster cake = spawnMob(map, t.bossMobId(), centerSpot(map, rng), t.bossHp());
                if (cake == null) {
                    return "cake " + t.bossMobId() + " could not be spawned";
                }
                e.subject = cake.getName();
                e.bossOid = cake.getObjectId();
                e.mobOids.add(cake.getObjectId());
                if (!t.waves().isEmpty()) {
                    spawnCakeWave(e, map, rng);
                }
            }
            case HOT_SPOT -> hotSpots.put(e.mapId(), Math.min(EventTemplates.MAX_EXP_BONUS, t.expBonus()));
            case WEDDING -> {
                // Ticket 58: a bot couple's staged ceremony; it runs its own few-second beat until the event ends.
                String problem = soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings.begin(e, map);
                if (problem != null) {
                    return problem;
                }
            }
            case WANDERING_NPC -> {
                Portal p = map.getRandomPlayerSpawnpoint();
                Point at = p != null ? p.getPosition() : randomSpot(map, rng);
                NPC npc = spawnNpc(map, t.npcId(), new Point(at.x + 60 - rng.nextInt(120), at.y));
                if (npc == null) {
                    return "npc " + t.npcId() + " could not be spawned";
                }
                e.npcOid = npc.getObjectId();
                e.npcId = t.npcId();
                e.npcName = npc.getName();
                String item = ItemInformationProvider.getInstance().getName(e.spot.itemId());
                e.subject = item == null ? "item " + e.spot.itemId() : item;
            }
        }
        return null;
    }

    // ── running ──────────────────────────────────────────────────────────────────────────────────

    private void step(ActiveEvent e, long now, Random rng) {
        if (e.finished) {
            return;
        }
        MapleMap map = mapOf(e.mapId());
        if (map == null) {
            return;
        }
        switch (e.template.kind()) {
            case INVASION -> {
                e.mobOids.removeIf(oid -> map.getMonsterByOid(oid) == null);
                if (e.mobOids.isEmpty() && e.bossOid < 0) {
                    if (e.waveIndex + 1 < e.template.waves().size()) {
                        spawnWave(e, map, e.waveIndex + 1, rng);
                    } else {
                        Monster boss = spawnMob(map, e.template.bossMobId(), centerSpot(map, rng),
                                e.template.bossHp());
                        if (boss != null) {
                            e.bossOid = boss.getObjectId();
                            e.mobOids.add(boss.getObjectId());
                            announce(e, WorldEventText.bossArrives());
                        }
                    }
                    e.lastRecruitAt = now; // 1.6.0 madness: every new wave tops the crowd up
                    WorldEventReactions.recruit(e, rng);
                } else if (e.bossOid >= 0 && map.getMonsterByOid(e.bossOid) == null) {
                    finish(e, "boss gone", true); // killed without the hook seeing a real player (or by a bot)
                    return;
                }
                InvasionTownCrowd.rally(e, map, now, rng); // newcomers to the map (and the respawned) join in
            }
            case BOSS_SIGHTING -> {
                if (map.getMonsterByOid(e.bossOid) == null) {
                    finish(e, "boss gone", true);
                    return;
                }
            }
            case GIANT_CAKE -> {
                if (map.getMonsterByOid(e.bossOid) == null) {
                    finish(e, "boss gone", true);
                    return;
                }
                e.mobOids.removeIf(oid -> oid != e.bossOid && map.getMonsterByOid(oid) == null);
                if (e.mobOids.size() <= 1 && !e.template.waves().isEmpty()) {
                    spawnCakeWave(e, map, rng); // the little cakes keep coming while the big one stands
                }
            }
            case TREASURE_HUNT -> {
                long third = e.template.durationMs() / 3;
                int due = (int) Math.min(2, (now - e.startedAt) / Math.max(1, third));
                if (due > e.hintsGiven) {
                    e.hintsGiven = due;
                    announce(e, WorldEventText.hint(due));
                    if (due >= 2) {
                        WorldEventReactions.recruit(e, rng); // bots only go once the map is public
                    }
                }
            }
            default -> {
            }
        }
        boolean gathers = e.template.kind() != EventTemplate.Kind.TREASURE_HUNT || e.hintsGiven >= 2;
        long every = e.template.kind() == EventTemplate.Kind.INVASION ? INVASION_RECRUIT_EVERY_MS : RECRUIT_EVERY_MS;
        if (gathers && now - e.lastRecruitAt >= every) {
            e.lastRecruitAt = now;
            WorldEventReactions.recruit(e, rng);
        }
        WorldEventReactions.chatter(e, now, rng, false);
    }

    /** The giant cake's little cakes: the template's waves in turn, over and over. */
    private void spawnCakeWave(ActiveEvent e, MapleMap map, Random rng) {
        EventTemplate.Wave wave = e.template.waves().get(e.cakeWave % e.template.waves().size());
        e.cakeWave++;
        for (int i = 0; i < wave.count(); i++) {
            Monster mob = spawnMob(map, wave.mobId(), randomSpot(map, rng));
            if (mob != null) {
                e.mobOids.add(mob.getObjectId());
            }
        }
    }

    private void spawnWave(ActiveEvent e, MapleMap map, int index, Random rng) {
        EventTemplate.Wave wave = e.template.waves().get(index);
        e.waveIndex = index;
        for (int i = 0; i < wave.count(); i++) {
            Monster mob = spawnMob(map, wave.mobId(), randomSpot(map, rng));
            if (mob != null) {
                e.mobOids.add(mob.getObjectId());
            }
        }
        if (index > 0) {
            announce(e, WorldEventText.wave(index, e.template.waves().size()));
        }
        String mini = "";
        if (wave.miniBossId() > 0) {
            // 1.6.0 tuning: a later wave brings a mini-boss at its own WZ stats (its normal EXP and drops), so the
            // band's bots really get hurt before the boss; the wave is cleared once it falls too
            Monster boss = spawnMob(map, wave.miniBossId(), centerSpot(map, rng));
            if (boss != null) {
                e.mobOids.add(boss.getObjectId());
                mini = ", mini-boss " + boss.getName();
                Map<String, String> tokens = tokens(e);
                tokens.put("MINIBOSS", boss.getName());
                announceText(WorldEventText.fill(WorldEventText.miniBoss(), tokens));
            }
        }
        qa("WEVENT WAVE #" + e.id + " " + (index + 1) + "/" + e.template.waves().size() + ": " + e.mobOids.size()
                + " mobs up" + mini);
    }

    /** GM stop. */
    public boolean stop(int id, String by) {
        ActiveEvent e = byId(id);
        if (e == null) {
            return false;
        }
        finish(e, "stopped by " + by, true);
        return true;
    }

    public int stopAll(String by) {
        int n = 0;
        for (ActiveEvent e : active) {
            finish(e, "stopped by " + by, true);
            n++;
        }
        return n;
    }

    /** Ends an event once: cleans its map, announces, pays the winner, lets the bots go. */
    synchronized void finish(ActiveEvent e, String reason, boolean announce) {
        if (e.finished) {
            return;
        }
        e.finished = true;
        e.endReason = reason;
        e.endedAt = System.currentTimeMillis();
        active.remove(e);
        lastEndedAt.put(e.template.id(), e.endedAt);
        swarmMaps.remove(mapKey(e.channel, e.mapId()));
        if (e.template.kind() == EventTemplate.Kind.INVASION) {
            soloMapling.ArtificialPlayer.BotMapCap.lower(e.channel, e.mapId()); // back to BOT_MAP_CAP
        }
        java.util.concurrent.ScheduledFuture<?> touch = e.touchTask;
        if (touch != null) {
            touch.cancel(false);
            e.touchTask = null;
        }
        InvasionSwarmTouch.forget(e.endedAt);
        for (int botId : e.deaths.keySet()) {
            lastSwarmDeath.remove(botId);
        }
        MapleMap map = mapOf(e.mapId());
        cleanUp(e, map);
        WorldEventReactions.release(e);
        InvasionTownCrowd.release(e.id); // the town bots go back to their day
        if (announce) {
            boolean won = e.winnerName != null && e.template.kind() != EventTemplate.Kind.WANDERING_NPC;
            announce(e, won ? WorldEventText.won(e.template.kind()) : WorldEventText.timedOut(e.template.kind()));
            if (won) {
                WorldEventFeed.won(e, e.endedAt);
            }
        }
        if (e.winnerName != null) {
            WorldEventReactions.megaphoneWon(e, ThreadLocalRandom.current());
            WorldEventReactions.rememberWinner(e, map, e.endedAt);
        }
        if (!BotShutdown.isQuiesced()) {
            recent.add(e);
            WorldEventReactions.news(e, map); // 1.6.0 S4: "remember the cake lol" for hours after
        }
        log("[WorldEvents] ended " + e.describe(e.endedAt));
        if (!"server stopping".equals(reason)) {
            siteFeedEnded(e, reason); // 1.6.0 Track D: the website's feed (memory only)
        }
        qa("WEVENT END #" + e.id + " " + e.template.id() + " (" + reason + "), " + e.joinedBots.size()
                + " bots joined, " + e.botDeaths() + " bot deaths" + (e.winnerName != null ? ", winner "
                + e.winnerName + " (" + e.winnerDamage + " dmg)" : ", no real winner"));
        if (!BotShutdown.isQuiesced()) {
            ExecutorServiceManager.runAsync(this::saveState); // lastEndedAt: a restart keeps the cooldowns
        }
    }

    /**
     * 1.6.0 Track D: an ended event for the website's feed. Won: a boss or cake beaten (by anyone, bots included), the
     * treasure found or the quest done; lost: time ran out on something that could be won; ended: a hot spot, a
     * wedding or a visit is over. The name is the top damage (bot or player), else the real winner.
     */
    static String siteOutcome(EventTemplate.Kind kind, String reason, String winnerName, String topDamageName) {
        if (kind == EventTemplate.Kind.HOT_SPOT || kind == EventTemplate.Kind.WEDDING
                || (kind == EventTemplate.Kind.WANDERING_NPC && winnerName == null)) {
            return "ended";
        }
        if (winnerName != null || topDamageName != null || "boss killed".equals(reason) || "boss gone".equals(reason)
                || "won".equals(reason)) {
            return "won";
        }
        return reason != null && reason.startsWith("stopped by") ? "ended" : "lost";
    }

    /** The name the feed shows: the top damage (already public-only), else the winner when it may be named. Pure. */
    static String siteName(String topDamageName, String winnerName, boolean winnerPublic) {
        return topDamageName != null ? topDamageName : winnerPublic ? winnerName : null;
    }

    /** The name an ended event shows on the site: only a win names anyone, and only a public character. */
    static String siteEndName(ActiveEvent e, String outcome) {
        return "won".equals(outcome) ? siteName(e.topDamageName, e.winnerName, e.winnerPublic) : null;
    }

    /**
     * Records the event's winner. Every winner path (boss down, treasure found, wandering NPC turn-in) goes through
     * here so the site's public-name check (no GM, no auto-QA character) is never skipped.
     */
    static void setWinner(ActiveEvent e, Character chr) {
        e.winnerId = chr.getId();
        e.winnerName = chr.getName();
        e.winnerPublic = soloMapling.site.SiteFeed.isPublic(chr);
    }

    private static void siteFeedEnded(ActiveEvent e, String reason) {
        try {
            EventTemplate.Kind kind = e.template.kind();
            String outcome = siteOutcome(kind, reason, e.winnerName, e.topDamageName);
            boolean hidden = kind == EventTemplate.Kind.TREASURE_HUNT && !"won".equals(outcome);
            soloMapling.site.SiteFeed.eventEnded(e.template.name(), hidden ? e.regionName : e.mapName,
                    hidden ? 0 : e.mapId(), e.channel, outcome, siteEndName(e, outcome), e.topDamage);
        } catch (RuntimeException ex) {
            log("[WorldEvents] site feed: " + ex);
        }
    }

    private void cleanUp(ActiveEvent e, MapleMap map) {
        hotSpots.remove(e.mapId());
        if (e.template.kind() == EventTemplate.Kind.WEDDING) {
            soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings.end(e); // ticket 58: guests leave, couple goes home
        }
        if (map == null) {
            return;
        }
        for (int oid : List.copyOf(e.mobOids)) {
            Monster mob = map.getMonsterByOid(oid);
            if (mob != null && mob.isAlive()) {
                map.killMonster(mob, null, false); // no drops, no EXP: the event's mobs just leave
            }
        }
        e.mobOids.clear();
        if (e.npcOid >= 0) {
            map.removeMapObject(e.npcOid);
            map.broadcastMessage(PacketCreator.removeNPCController(e.npcOid));
            map.broadcastMessage(PacketCreator.removeNPC(e.npcOid));
        }
    }

    // ── spawning ─────────────────────────────────────────────────────────────────────────────────

    private static Point randomSpot(MapleMap map, Random rng) {
        List<MapMobIndex.SpawnPos> spawns = MapMobIndex.spawnPoints(map.getId());
        if (spawns != null && !spawns.isEmpty()) {
            MapMobIndex.SpawnPos s = spawns.get(rng.nextInt(spawns.size()));
            return new Point(s.x(), s.y());
        }
        Point ground = groundSpot(map, rng, false);
        if (ground != null) {
            return ground; // 1.6.0: a town has no mob spawns; spread the invaders over its walkable ground
        }
        Portal p = map.getRandomPlayerSpawnpoint();
        return p != null ? new Point(p.getPosition()) : new Point(0, 0);
    }

    /** Where a boss or the cake stands: on the town's widest ground near its middle, else a random spot. */
    private static Point centerSpot(MapleMap map, Random rng) {
        if (MapMobIndex.spawnPoints(map.getId()) == null || MapMobIndex.spawnPoints(map.getId()).isEmpty()) {
            Point ground = groundSpot(map, rng, true);
            if (ground != null) {
                return ground;
            }
        }
        return randomSpot(map, rng);
    }

    /**
     * A point on the map's walkable ground (the bots' nav ledges), picked by width so wide ground gets most of the
     * mobs; {@code widest} takes the middle third of the widest ledge. Null when the map has no nav graph yet.
     */
    private static Point groundSpot(MapleMap map, Random rng, boolean widest) {
        List<soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.Ledge> ledges;
        try {
            ledges = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.walkableLedges(map);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (ledges.isEmpty()) {
            return null;
        }
        soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.Ledge pick = null;
        if (widest) {
            for (var l : ledges) {
                if (pick == null || l.maxX() - l.minX() > pick.maxX() - pick.minX()) {
                    pick = l;
                }
            }
            int third = Math.max(1, (pick.maxX() - pick.minX()) / 3);
            return new Point(pick.minX() + third + rng.nextInt(third), pick.centerY() - 40);
        }
        // the main ground only (ledges at least 40% as wide as the widest: the street, not rooftops and small
        // platforms), so invaders stand where the bots and players are and can reach them
        int widestSpan = 0;
        for (var l : ledges) {
            widestSpan = Math.max(widestSpan, l.maxX() - l.minX());
        }
        List<soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.Ledge> main = new ArrayList<>();
        for (var l : ledges) {
            if (l.maxX() - l.minX() >= widestSpan * 0.4) {
                main.add(l);
            }
        }
        ledges = main;
        long total = 0;
        for (var l : ledges) {
            total += Math.max(1, l.maxX() - l.minX());
        }
        long roll = (long) (rng.nextDouble() * total);
        for (var l : ledges) {
            roll -= Math.max(1, l.maxX() - l.minX());
            if (roll < 0) {
                pick = l;
                break;
            }
        }
        if (pick == null) {
            pick = ledges.get(ledges.size() - 1);
        }
        int span = Math.max(1, pick.maxX() - pick.minX() - 40);
        return new Point(pick.minX() + 20 + rng.nextInt(span), pick.centerY() - 40);
    }

    private static Monster spawnMob(MapleMap map, int mobId, Point at) {
        return spawnMob(map, mobId, at, 0);
    }

    /** {@code hp} above 0: the event's HP for this one mob (a crowd's giant cake); the WZ stats are untouched. */
    private static Monster spawnMob(MapleMap map, int mobId, Point at, long hp) {
        Monster mob = LifeFactory.getMonster(mobId);
        if (mob == null) {
            return null;
        }
        if (hp > 0) {
            mob.setStartingHp(Math.min(Integer.MAX_VALUE, hp));
        }
        map.spawnMonsterOnGroundBelow(mob, at);
        return mob;
    }

    static long mapKey(int channel, int mapId) {
        return ((long) channel << 32) | (mapId & 0xffffffffL);
    }

    private static long bossHpOf(ActiveEvent e, MapleMap map) {
        Monster boss = map == null || e.bossOid < 0 ? null : map.getMonsterByOid(e.bossOid);
        return boss == null ? 0 : boss.getMaxHp();
    }

    /** The most bots one event calls: its own cap, the -D cap (default and ceiling {@link EventTemplates#MAX_BOT_CAP}). */
    static int crowdCap(EventTemplate t) {
        return Math.min(t.botCap(), Math.max(0, Math.min(EventTemplates.MAX_BOT_CAP,
                Integer.getInteger("solomapling.worldEvents.botCap", EventTemplates.MAX_BOT_CAP))));
    }

    private static NPC spawnNpc(MapleMap map, int npcId, Point pos) {
        NPC npc = LifeFactory.getNPC(npcId);
        if (npc == null || npc.getName() == null || npc.getName().equals("MISSINGNO")) {
            return null;
        }
        Foothold fh = map.getFootholds().findBelow(pos);
        if (fh == null) {
            return null;
        }
        Point ground = new Point(pos.x, fh.calculateFooting(pos.x));
        npc.setPosition(ground);
        npc.setCy(ground.y);
        npc.setRx0(ground.x + 50);
        npc.setRx1(ground.x - 50);
        npc.setFh(fh.getId());
        map.addMapObject(npc);
        map.broadcastMessage(PacketCreator.spawnNPC(npc));
        map.broadcastMessage(PacketCreator.spawnNPCRequestController(npc, true));
        return npc;
    }

    // ── announcing ───────────────────────────────────────────────────────────────────────────────

    Map<String, String> tokens(ActiveEvent e) {
        Map<String, String> t = new HashMap<>();
        t.put("MAP", e.mapName);
        t.put("CH", String.valueOf(e.channel));
        t.put("MIN", String.valueOf(Math.max(1, (e.endsAt - System.currentTimeMillis() + 30_000) / 60_000)));
        t.put("REGION", e.regionName);
        t.put("BOSS", e.subject);
        t.put("ITEM", e.subject);
        t.put("NPC", e.npcName);
        t.put("PLAYER", e.winnerName);
        t.put("INITIAL", WorldEventText.initial(e.mapName));
        t.put("BONUS", String.valueOf(Math.round((e.template.expBonus() - 1) * 100)));
        t.put("EVENT", e.template.name());
        return t;
    }

    /** A blue server notice to the whole world: a system message, never filtered by {@code @quiet}. */
    private void announce(ActiveEvent e, String template) {
        announceText(WorldEventText.fill(template, tokens(e)));
    }

    private void announceText(String text) {
        try {
            Server.getInstance().broadcastMessage(SoloMaplingConstants.GameConstants.homeWorld(),
                    PacketCreator.serverNotice(6, text));
        } catch (RuntimeException ex) {
            log("[WorldEvents] notice failed: " + ex);
        }
        log("[WorldEvents] notice: " + text);
        qa("WEVENT NOTICE " + text);
    }

    // ── hooks from the game ──────────────────────────────────────────────────────────────────────

    /**
     * MapleMap.killMonster: an event mob died. An invasion boss or sighted boss killed by a real player makes them the
     * winner and ends the event; one killed by a bot ends it without a winner (the ticket-47 boss megaphone still
     * lets the bot flex).
     */
    /** True while {@code mob} is one an event spawned on this map (checked before {@link #onMonsterKilled} forgets it). */
    public boolean isEventMob(MapleMap map, Monster mob) {
        if (active.isEmpty() || mob == null || map == null) {
            return false;
        }
        for (ActiveEvent e : active) {
            if (e.mapId() == map.getId() && e.channel == map.getChannelServer().getId() && e.mobOids.contains(mob.getObjectId())) {
                return true;
            }
        }
        return false;
    }

    public void onMonsterKilled(MapleMap map, Monster mob, Character killer) {
        if (active.isEmpty() || mob == null || map == null) {
            return;
        }
        for (ActiveEvent e : active) {
            if (e.mapId() != map.getId() || e.channel != map.getChannelServer().getId()) {
                continue;
            }
            int oid = mob.getObjectId();
            if (!e.mobOids.remove(oid)) {
                continue;
            }
            if (oid == e.bossOid) {
                bossDown(e, map, mob, killer);
                finish(e, e.winnerName != null ? "won" : "boss killed", true);
            }
            return;
        }
    }

    /**
     * 1.6.0: the boss fell. The winner is the real player who did the most damage to it (else the real player who
     * got the kill); every real player who hit it or stood on the map takes part (participation table, once each).
     */
    private void bossDown(ActiveEvent e, MapleMap map, Monster boss, Character killer) {
        Map<Integer, Long> damage = boss.damageSnapshot();
        Map<Integer, Character> here = new HashMap<>();
        for (Character chr : map.getAllPlayers()) {
            here.put(chr.getId(), chr);
        }
        Winner w = topReal(damage, id -> here.containsKey(id) ? !BotHelpers.isBot(here.get(id))
                : !BotHelpers.isArtificial(id));
        Character winner = w == null ? null : here.get(w.charId());
        e.topDamage = siteTop(damage, here); // 1.6.0 Track D: the top 3 damage, bots and players, for the site's feed
        e.topDamageName = e.topDamage.isEmpty() ? null : e.topDamage.getFirst().name();
        if (winner == null && killer != null && !BotHelpers.isBot(killer)) {
            winner = killer;
        }
        if (winner != null) {
            setWinner(e, winner);
            e.winnerDamage = w != null && w.charId() == winner.getId() ? w.damage() : damage.getOrDefault(winner.getId(), 0L);
            giveRewards(winner, e);
        }
        long total = 0;
        for (long d : damage.values()) {
            total += d;
        }
        long botDamage = 0;
        for (Map.Entry<Integer, Long> d : damage.entrySet()) {
            if (BotHelpers.isArtificial(d.getKey())) {
                botDamage += d.getValue();
            }
        }
        java.util.Set<Character> takers = new java.util.LinkedHashSet<>();
        for (Character chr : here.values()) {
            if (!BotHelpers.isBot(chr)) {
                takers.add(chr);
            }
        }
        for (int id : damage.keySet()) {
            Character chr = here.get(id);
            if (chr != null && !BotHelpers.isBot(chr)) {
                takers.add(chr);
            }
        }
        for (Character chr : takers) {
            if (e.participants.add(chr.getId())) {
                giveParticipation(chr, e);
            }
        }
        qa("WEVENT BOSS DOWN #" + e.id + " " + boss.getName() + ": " + damage.size() + " attackers, "
                + countBots(damage.keySet()) + " of them bots (" + botDamage + " of " + total + " dmg), winner "
                + (e.winnerName == null ? "none (no real player hit it)" : e.winnerName + " " + e.winnerDamage + " dmg")
                + ", " + takers.size() + " real player(s) take part");
    }

    /** The top 3 damage dealers still on the map who may appear on the site, most damage first. */
    static List<soloMapling.site.SiteFeed.Top> siteTop(Map<Integer, Long> damage, Map<Integer, Character> here) {
        List<Map.Entry<Integer, Long>> rows = new ArrayList<>(damage.entrySet());
        rows.sort(Map.Entry.<Integer, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<soloMapling.site.SiteFeed.Top> out = new ArrayList<>();
        for (Map.Entry<Integer, Long> r : rows) {
            Character chr = here.get(r.getKey());
            if (chr != null && r.getValue() > 0 && soloMapling.site.SiteFeed.isPublic(chr)) {
                out.add(new soloMapling.site.SiteFeed.Top(chr.getName(), r.getValue()));
                if (out.size() == 3) {
                    break;
                }
            }
        }
        return out;
    }

    /** The real attacker with the most damage. Pure. */
    record Winner(int charId, long damage) {
    }

    static Winner topReal(Map<Integer, Long> damage, java.util.function.IntPredicate real) {
        Winner best = null;
        for (Map.Entry<Integer, Long> d : damage.entrySet()) {
            if (!real.test(d.getKey())) {
                continue;
            }
            if (best == null || d.getValue() > best.damage()
                    || d.getValue() == best.damage() && d.getKey() < best.charId()) {
                best = new Winner(d.getKey(), d.getValue());
            }
        }
        return best;
    }

    private static int countBots(java.util.Set<Integer> ids) {
        int n = 0;
        for (int id : ids) {
            if (BotHelpers.isArtificial(id)) {
                n++;
            }
        }
        return n;
    }

    // ── swarms: bots really die in invasions (1.6.0) ─────────────────────────────────────────────────

    /**
     * BotVitals.hurt: on an invasion map a hit that leaves a bot under {@link #SWARM_HP_SHARE} of its max HP counts
     * as a killing hit (it still has to win {@link #swarmDeath}'s roll). One set lookup when no invasion runs.
     */
    public static boolean swarmed(Character bot, long hpAfter) {
        WorldEvents w = INSTANCE;
        if (w.swarmMaps.isEmpty() || bot == null || bot.getMap() == null) {
            return false;
        }
        if (!w.swarmMaps.contains(mapKey(bot.getMap().getChannelId(), bot.getMapId()))) {
            return false;
        }
        return lowAfterHit(hpAfter, bot.getCurrentMaxHp());
    }

    /** BotContactDamage: the bot stands on a running invasion's map (its mobs swarm: a near mob is a hit). */
    public static boolean swarmMap(Character bot) {
        WorldEvents w = INSTANCE;
        return !w.swarmMaps.isEmpty() && bot != null && bot.getMap() != null
                && w.swarmMaps.contains(mapKey(bot.getMap().getChannelId(), bot.getMapId()));
    }

    /** RoamStrategy: this mob is one of the running event's on the bot's map. */
    public static boolean isEventMob(Character chr, Monster m) {
        WorldEvents w = INSTANCE;
        if (w.active.isEmpty() || chr == null || chr.getMap() == null || m == null) {
            return false;
        }
        ActiveEvent e = w.eventOn(chr.getMap().getChannelId(), chr.getMapId());
        return e != null && e.mobOids.contains(m.getObjectId());
    }

    /**
     * RoamStrategy at an event: the nearest live event mob on the bot's map that {@code ok} accepts (not a player's),
     * wherever it stands; null when there is none. Height counts double (a climb costs more than a walk).
     */
    public static Monster nearestEventMob(Character chr, java.util.function.Predicate<Monster> ok) {
        WorldEvents w = INSTANCE;
        if (w.active.isEmpty() || chr == null || chr.getMap() == null || chr.getPosition() == null) {
            return null;
        }
        ActiveEvent e = w.eventOn(chr.getMap().getChannelId(), chr.getMapId());
        if (e == null) {
            return null;
        }
        Point from = chr.getPosition();
        Monster best = null;
        long bestCost = Long.MAX_VALUE;
        for (int oid : e.mobOids) {
            Monster m = chr.getMap().getMonsterByOid(oid);
            if (m == null || !m.isAlive() || m.getPosition() == null) {
                continue;
            }
            long cost = chaseCost(from, m.getPosition());
            if (cost < bestCost && ok.test(m)) {
                best = m;
                bestCost = cost;
            }
        }
        return best;
    }

    /** Pure: how far a bot has to go for a mob; height counts double. */
    static long chaseCost(Point from, Point to) {
        long dx = Math.abs((long) from.x - to.x);
        long dy = 2L * Math.abs((long) from.y - to.y);
        return dx * dx + dy * dy;
    }

    static boolean lowAfterHit(long hpAfter, long maxHp) {
        return maxHp > 0 && hpAfter < maxHp * SWARM_HP_SHARE;
    }

    /**
     * BotDeaths.rollsDeath: null when the bot is not on an invasion map (the normal rare-death rules apply);
     * otherwise whether this hit kills it. Invasions skip the harder-map rule and the world's 10-minute cap (the point
     * is a lot of bots dying) but keep a per-bot cooldown of {@link #SWARM_BOT_COOLDOWN_MS} and a per-event cap of
     * {@link #SWARM_DEATHS_PER_EVENT}. No immunity either way: a bot in a party with a player or a GM hold is still
     * protected by BotDeaths' own checks first.
     */
    public static Boolean swarmDeath(Character bot, long now, Random rng) {
        WorldEvents w = INSTANCE;
        if (w.swarmMaps.isEmpty() || bot == null || bot.getMap() == null
                || !w.swarmMaps.contains(mapKey(bot.getMap().getChannelId(), bot.getMapId()))) {
            return null;
        }
        ActiveEvent e = w.eventOn(bot.getMap().getChannelId(), bot.getMapId());
        if (e == null) {
            return null;
        }
        Long last = w.lastSwarmDeath.get(bot.getId());
        return swarmRoll(last == null ? 0 : last, now, e.botDeaths(), e.lastBotDeathAt, rng.nextDouble());
    }

    /** Pure: the bot's cooldown, the event's cap and pace, then the roll. */
    static boolean swarmRoll(long lastDeathAt, long now, int eventDeaths, long eventLastDeathAt, double roll) {
        if (lastDeathAt > 0 && now - lastDeathAt < SWARM_BOT_COOLDOWN_MS) {
            return false;
        }
        if (eventDeaths >= SWARM_DEATHS_PER_EVENT) {
            return false;
        }
        if (eventLastDeathAt > 0 && now - eventLastDeathAt < SWARM_EVENT_GAP_MS) {
            return false; // one at a time: deaths spread over the fight instead of a pile in the first wave
        }
        return roll < SWARM_DEATH_CHANCE;
    }

    private ActiveEvent eventOn(int channel, int mapId) {
        for (ActiveEvent e : active) {
            if (e.mapId() == mapId && e.channel == channel) {
                return e;
            }
        }
        return null;
    }

    /** BotDeaths.die: a bot died; on an event map it is counted, and it may grumble after it respawns. */
    public void onBotDied(Character bot) {
        if (active.isEmpty() || bot == null || bot.getMap() == null) {
            return;
        }
        ActiveEvent e = eventOn(bot.getMap().getChannelId(), bot.getMapId());
        if (e == null) {
            return;
        }
        long now = System.currentTimeMillis();
        lastSwarmDeath.put(bot.getId(), now);
        e.lastBotDeathAt = now;
        int n = e.deaths.merge(bot.getId(), 1, Integer::sum);
        qa("WEVENT BOT DIED #" + e.id + " " + bot.getName() + " (lv " + bot.getLevel() + ") on " + e.mapId()
                + ", death " + n + " for it, " + e.botDeaths() + " this event");
        WorldEventReactions.grumble(e, bot, n, now, ThreadLocalRandom.current());
    }

    // ── who goes (1.6.0: the brain's EVENT activity) ──────────────────────────────────────────────────

    /** TrainingBot.canStartLifeActivity(EVENT): the bot has a standing offer for an event that still has room. */
    public boolean mayAccept(Character chr, long now) {
        if (chr == null || active.isEmpty()) {
            return false;
        }
        WorldEventSteer.Offer o = WorldEventSteer.offerFor(chr.getId(), now);
        if (o == null) {
            return false;
        }
        ActiveEvent e = byId(o.eventId());
        return e != null && !e.finished && e.joinedBots.size() < crowdCap(e.template) && roomOnMap(chr, e);
    }

    /** The brain picked EVENT: the event takes the bot if there is still room (crowd cap, then the map cap). */
    public boolean accept(Character chr, long now) {
        if (chr == null) {
            return false;
        }
        WorldEventSteer.Offer o = WorldEventSteer.takeOffer(chr.getId(), now);
        ActiveEvent e = o == null ? null : byId(o.eventId());
        if (e == null || e.finished) {
            return false;
        }
        synchronized (e) {
            if (e.joinedBots.size() >= crowdCap(e.template) || !roomOnMap(chr, e)) {
                return false;
            }
            e.joinedBots.add(chr.getId());
        }
        WorldEventSteer.steer(chr.getId(), e.id, e.mapId());
        qa("WEVENT JOIN #" + e.id + " " + chr.getName() + " (lv " + chr.getLevel() + ", "
                + WorldEventReactions.typeOf(chr.getId()) + ") picks EVENT -> " + e.mapId() + " ("
                + e.joinedBots.size() + "/" + crowdCap(e.template) + ")");
        return true;
    }

    private static boolean roomOnMap(Character chr, ActiveEvent e) {
        if (chr.getMapId() == e.mapId()) {
            return true;
        }
        try {
            MapleMap map = mapOf(e.mapId());
            return map != null && soloMapling.ArtificialPlayer.BotMapCap.hasRoom(map);
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
    }

    /**
     * {@code !qa}: these staged bots answer the event at once (no offer, no roll); returns how many were taken. Not
     * held to the crowd cap (1.6.0 madness: the bots nearby may fill it first): staged bots already stand on the map.
     */
    public int qaJoin(ActiveEvent e, List<Integer> botIds) {
        int n = 0;
        for (int id : botIds) {
            soloMapling.ArtificialPlayer.BotSM bot =
                    soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(id);
            if (!(bot instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot tb)) {
                continue;
            }
            e.joinedBots.add(id);
            WorldEventSteer.steer(id, e.id, e.mapId());
            tb.callToEvent();
            n++;
        }
        qa("WEVENT QA JOIN #" + e.id + " " + n + " staged bots head to " + e.mapId());
        return n;
    }

    /** Monster.giveExpToCharacter: the hot spot multiplier for a real player on a hot spot map, else 1. */
    public static float expMultiplier(Character chr) {
        Map<Integer, Double> spots = INSTANCE.hotSpots;
        if (spots.isEmpty() || chr == null) {
            return 1f;
        }
        Double bonus = spots.get(chr.getMapId());
        if (bonus == null || BotHelpers.isBot(chr) || chr.getClient() == null
                || chr.getClient().getChannel() != channel()) {
            return 1f;
        }
        return bonus.floatValue();
    }

    /** NPCTalkHandler: whether this NPC on this map is a world-event NPC (then the event script handles it). */
    public boolean isEventNpc(MapleMap map, int oid) {
        if (active.isEmpty() || map == null) {
            return false;
        }
        for (ActiveEvent e : active) {
            if (e.npcOid == oid && e.mapId() == map.getId() && e.channel == map.getChannelServer().getId()) {
                return true;
            }
        }
        return false;
    }

    private ActiveEvent eventOfNpc(Character chr, int oid) {
        for (ActiveEvent e : active) {
            if (e.npcOid == oid && e.mapId() == chr.getMapId()) {
                return e;
            }
        }
        return null;
    }

    /**
     * One dialog step with an event NPC ({@code scripts/npc/worldEventNpc.js}). {@code yes} is true when the player
     * answered yes to the previous step. Returns null when there is nothing to say (the event is gone).
     */
    public Reply talk(Character chr, int oid, boolean yes) {
        if (chr == null || BotHelpers.isBot(chr)) {
            return null;
        }
        ActiveEvent e = eventOfNpc(chr, oid);
        if (e == null || e.finished) {
            return new Reply("It's empty. Someone got here first.", false);
        }
        synchronized (e) {
            if (e.template.kind() == EventTemplate.Kind.TREASURE_HUNT) {
                if (e.winnerName != null) {
                    return new Reply("It's empty. Someone got here first.", false);
                }
                setWinner(e, chr);
                String got = giveRewards(chr, e);
                ExecutorServiceManager.runAsync(() -> finish(e, "won", true));
                return new Reply("You found the hidden treasure chest!" + got, false);
            }
            int item = e.spot.itemId();
            int count = e.spot.count();
            if (e.completed.contains(chr.getId())) {
                return new Reply("Thanks again, friend! Safe travels.", false);
            }
            if (!e.accepted.contains(chr.getId())) {
                if (!yes) {
                    return new Reply("Oh, an adventurer! I'm just passing through and I'm short on supplies. Could you"
                            + " bring me #b" + count + " #t" + item + "##k? I'll make it worth your while.", true);
                }
                e.accepted.add(chr.getId());
                return new Reply("Wonderful! I'll be around here for about "
                        + Math.max(1, (e.endsAt - System.currentTimeMillis()) / 60_000) + " more minutes.", false);
            }
            int have = chr.getItemQuantity(item, false);
            if (have < count) {
                return new Reply("You still need #b" + (count - have) + " more #t" + item + "##k."
                        + " Hurry, I won't be here long!", false);
            }
            InventoryType type = ItemConstants.getInventoryType(item);
            InventoryManipulator.removeById(chr.getClient(), type, item, count, false, false);
            e.completed.add(chr.getId());
            if (e.winnerName == null) {
                setWinner(e, chr);
            }
            String got = giveRewards(chr, e);
            return new Reply("You brought them all! Thank you, this is just what I needed." + got, false);
        }
    }

    // ── rewards ──────────────────────────────────────────────────────────────────────────────────

    /** Rolls the template's reward table for a real player; returns a short "you got" line for the dialog. */
    String giveRewards(Character chr, ActiveEvent e) {
        if (chr == null || BotHelpers.isBot(chr)
                || "false".equalsIgnoreCase(System.getProperty("solomapling.worldEvents.rewards", "true"))) {
            return "";
        }
        List<EventTemplate.Reward> won = roll(e.template.rewards(), ThreadLocalRandom.current());
        StringBuilder got = new StringBuilder();
        Client c = chr.getClient();
        for (EventTemplate.Reward r : won) {
            if (c == null || !InventoryManipulator.checkSpace(c, r.itemId(), r.qty(), "")) {
                chr.dropMessage(5, "[World Event] Your inventory is full, so a reward was lost.");
                continue;
            }
            InventoryManipulator.addById(c, r.itemId(), (short) r.qty());
            chr.sendPacket(PacketCreator.getShowItemGain(r.itemId(), (short) r.qty(), true));
            got.append("\r\n#i").append(r.itemId()).append("# ").append(r.qty()).append(" #t").append(r.itemId())
                    .append("#");
        }
        log("[WorldEvents] #" + e.id + " reward to " + chr.getName() + ": " + won);
        qa("WEVENT REWARD #" + e.id + " " + chr.getName() + ": " + won.size() + " item line(s)");
        return got.length() == 0 ? "" : "\r\n" + got;
    }

    /** 1.6.0: the participation table and EXP for one real player who took part in a won boss fight. */
    void giveParticipation(Character chr, ActiveEvent e) {
        if (chr == null || BotHelpers.isBot(chr)
                || "false".equalsIgnoreCase(System.getProperty("solomapling.worldEvents.rewards", "true"))) {
            return;
        }
        Client c = chr.getClient();
        List<EventTemplate.Reward> got = roll(e.template.participation(), ThreadLocalRandom.current());
        for (EventTemplate.Reward r : got) {
            if (c == null || !InventoryManipulator.checkSpace(c, r.itemId(), r.qty(), "")) {
                chr.dropMessage(5, "[World Event] Your inventory is full, so a reward was lost.");
                continue;
            }
            InventoryManipulator.addById(c, r.itemId(), (short) r.qty());
            chr.sendPacket(PacketCreator.getShowItemGain(r.itemId(), (short) r.qty(), true));
        }
        int exp = participationExp(chr.getLevel(), e.template.participationExpPct());
        if (exp > 0) {
            chr.gainExp(exp, true, true);
        }
        log("[WorldEvents] #" + e.id + " participation to " + chr.getName() + ": " + got + ", " + exp + " EXP");
        qa("WEVENT PARTICIPATION #" + e.id + " " + chr.getName() + ": " + got.size() + " item line(s), " + exp + " EXP");
    }

    /** Participation EXP: a percent of this level's EXP bar (fair at every level). Pure. */
    static int participationExp(int level, double pct) {
        if (pct <= 0 || level <= 0 || level >= 200) {
            return 0;
        }
        long needed;
        try {
            needed = constants.game.ExpTable.getExpNeededForLevel(level);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, Math.round(needed * Math.min(pct,
                EventTemplates.MAX_PARTICIPATION_EXP_PCT) / 100.0)));
    }

    /** Pure: which lines of a reward table come out this time. */
    static List<EventTemplate.Reward> roll(List<EventTemplate.Reward> table, Random rng) {
        List<EventTemplate.Reward> out = new ArrayList<>();
        for (EventTemplate.Reward r : table) {
            if (r.chance() >= 1 || rng.nextDouble() < r.chance()) {
                out.add(r);
            }
        }
        return out;
    }

    // ── GM read-outs ─────────────────────────────────────────────────────────────────────────────

    /** {@code !event preview}: what the scheduler would do now. */
    public List<String> preview(long now) {
        List<String> out = new ArrayList<>();
        double fraction = curveFraction();
        double chance = EventScheduler.startChance(settings, fraction);
        List<EventTemplate> eligible = EventScheduler.eligible(EventTemplates.get().templates(), running(),
                lastEndedAt, now);
        double perHour = chance * (3_600_000.0 / settings.tickMs());
        out.add(String.format("Scheduler %s. Curve now %.2f of peak -> %.2f%% a tick (~%.2f events/hour).",
                settings.auto() ? "ON" : "OFF", fraction, chance * 100, perHour));
        if (!settings.auto()) {
            out.add("(Nothing starts by itself while it is off: !event auto on, or -Dsolomapling.worldEvents=true.)");
        }
        if (active.size() >= settings.maxActive()) {
            out.add("Blocked now: " + active.size() + " running (max " + settings.maxActive() + ").");
        } else if (lastStartAt > 0 && now - lastStartAt < settings.minGapMs()) {
            out.add("Blocked now: last start " + (now - lastStartAt) / 60_000 + " min ago (gap "
                    + settings.minGapMs() / 60_000 + " min).");
        }
        double total = eligible.stream().mapToDouble(EventTemplate::weight).sum();
        for (EventTemplate t : eligible) {
            out.add(String.format("  %s (%s) %.0f%% of picks, %d min", t.id(), t.kind().label(),
                    total > 0 ? 100 * t.weight() / total : 0, t.minutes()));
        }
        if (eligible.isEmpty()) {
            out.add("  no template is eligible (all running or resting)");
        }
        return out;
    }

    /** {@code !event preview <id>}: where it could run and which bot levels would go. */
    public List<String> preview(String templateId) {
        EventTemplate t = EventTemplates.byId(templateId);
        if (t == null) {
            return List.of("No template '" + templateId + "'.");
        }
        List<String> out = new ArrayList<>();
        out.add(t.id() + ": " + t.name() + " (" + t.kind().label() + "), " + t.minutes() + " min, weight " + t.weight()
                + ", cooldown " + t.cooldownMinutes() + " min, up to " + t.botCap() + " bots"
                + (t.enabled() ? "" : ", DISABLED"));
        for (EventTemplate.Spot s : t.spots()) {
            int[] band = band(t, s);
            out.add("  map " + s.mapId() + " (" + WorldEventReactions.regionName(s.mapId()) + ") bots lv " + band[0]
                    + "-" + band[1] + (s.mobId() > 0 ? ", mob " + s.mobId() : "")
                    + (s.itemId() > 0 ? ", wants " + s.count() + " x " + s.itemId() : ""));
        }
        if (!t.rewards().isEmpty()) {
            out.add("  rewards: " + t.rewards());
        }
        return out;
    }

    /** {@code !qa ev-2}: how far each of these bots stands from the nearest live event mob (px, -1 = off the map). */
    public Map<Integer, Integer> qaMobDistances(ActiveEvent e, List<Integer> botIds) {
        Map<Integer, Integer> out = new HashMap<>();
        MapleMap map = mapOf(e.mapId());
        List<Point> mobs = new ArrayList<>();
        if (map != null) {
            for (int oid : e.mobOids) {
                Monster m = map.getMonsterByOid(oid);
                if (m != null && m.isAlive() && m.getPosition() != null) {
                    mobs.add(m.getPosition());
                }
            }
        }
        for (int id : botIds) {
            soloMapling.ArtificialPlayer.BotSM bot = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage
                    .getBotById(id);
            Character chr = bot == null ? null : bot.getChr();
            if (chr == null || chr.getMapId() != e.mapId() || chr.getPosition() == null || mobs.isEmpty()) {
                out.put(id, -1);
                continue;
            }
            int best = Integer.MAX_VALUE;
            for (Point p : mobs) {
                best = Math.min(best, (int) chr.getPosition().distance(p));
            }
            out.put(id, best);
        }
        return out;
    }

    /** {@code !qa ev-2}: one status line - the wave, the mobs up, where the staged bots are, their HP, deaths. */
    public String qaStatus(ActiveEvent e, List<Integer> botIds) {
        Map<Integer, Integer> dist = qaMobDistances(e, botIds);
        int onMap = 0, near = 0, hpSum = 0, grinding = 0;
        for (int id : botIds) {
            soloMapling.ArtificialPlayer.BotSM bot = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage
                    .getBotById(id);
            Character chr = bot == null ? null : bot.getChr();
            if (chr == null || chr.getMapId() != e.mapId()) {
                continue;
            }
            onMap++;
            hpSum += (int) (100L * chr.getHp() / Math.max(1, chr.getCurrentMaxHp()));
            if (bot instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot tb && tb.isGrinding()) {
                grinding++;
            }
            int d = dist.getOrDefault(id, -1);
            if (d >= 0 && d <= 150) {
                near++;
            }
        }
        List<Integer> ds = new ArrayList<>();
        dist.values().stream().filter(d -> d >= 0).sorted().forEach(ds::add);
        return "wave " + (e.waveIndex + 1) + "/" + e.template.waves().size() + (e.bossOid >= 0 ? " (boss up)" : "")
                + ", " + e.mobOids.size() + " event mobs up, staged on map " + onMap + "/" + botIds.size()
                + ", grinding " + grinding + ", near a mob " + near + ", median mob distance "
                + (ds.isEmpty() ? "-" : ds.get(ds.size() / 2) + " px") + ", HP avg "
                + (onMap == 0 ? "-" : hpSum / onMap + "%") + ", " + e.botDeaths() + " bot deaths";
    }

    /** Test seam: an event counted as running without spawning anything. */
    void addActiveForTest(ActiveEvent e) {
        active.add(e);
    }

    /** Test seam: a clean slate. */
    synchronized void resetForTest() {
        active.clear();
        recent.clear();
        lastEndedAt.clear();
        hotSpots.clear();
        swarmMaps.clear();
        lastSwarmDeath.clear();
        InvasionTownCrowd.clearForTest();
        InvasionSwarmTouch.clearForTest();
        lastStartAt = 0;
        settings = EventScheduler.Settings.fromSystemProperties();
    }
}
