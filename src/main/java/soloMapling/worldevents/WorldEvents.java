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
 * The automatic scheduler is <b>off</b> unless the server runs with {@code -Dsolomapling.worldEvents=true} (or a GM
 * says {@code !event auto on}); GM commands work either way. See {@link EventScheduler.Settings} for the rate knobs.
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
        long period = settings.tickMs();
        ticker = ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(this::tickSafely,
                period, period, TimeUnit.MILLISECONDS);
        BotShutdown.onQuiesce(this::shutdown);
        log("[WorldEvents] " + r.templates().size() + " templates, " + r.errors().size() + " problems; scheduler "
                + (settings.auto() ? "on (one per ~" + (int) settings.meanGapMinutes() + " min at peak, max "
                + settings.maxActive() + " at once)" : "off (-Dsolomapling.worldEvents=true or !event auto on)"));
    }

    private synchronized void shutdown() {
        for (ActiveEvent e : active) {
            finish(e, "server stopping", false);
        }
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
        EventTemplate pick = EventScheduler.decide(settings, EventTemplates.get().templates(), running(), lastEndedAt,
                lastStartAt, now, curveFraction(), rng);
        if (pick != null) {
            StartResult r = startEvent(pick, null, "scheduler", now, rng);
            if (r.error() != null) {
                log("[WorldEvents] scheduler could not start " + pick.id() + ": " + r.error());
            }
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
        return startEvent(t, spot, by, System.currentTimeMillis(), ThreadLocalRandom.current());
    }

    static String mapsOf(EventTemplate t) {
        List<Integer> ids = new ArrayList<>();
        for (EventTemplate.Spot s : t.spots()) {
            ids.add(s.mapId());
        }
        return ids.toString();
    }

    synchronized StartResult startEvent(EventTemplate t, EventTemplate.Spot chosen, String by, long now, Random rng) {
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
        e.mapName = map.getMapName() == null ? String.valueOf(spot.mapId()) : map.getMapName();
        e.regionName = WorldEventReactions.regionName(spot.mapId());
        String problem = begin(e, map, rng);
        if (problem != null) {
            cleanUp(e, map);
            return new StartResult(null, problem);
        }
        active.add(e);
        lastStartAt = now;
        announce(e, WorldEventText.start(t.kind()));
        WorldEventFeed.started(e, now); // public Discord feed (-Dslumsms.announceFile), off the game thread
        log("[WorldEvents] started " + e.describe(now));
        if (t.kind() != EventTemplate.Kind.TREASURE_HUNT) {
            WorldEventReactions.recruit(e, rng); // treasure: bots only go once the last hint names the map
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

    private static MapleMap mapOf(int mapId) {
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
            default -> bandFor(t.kind(), MapMobIndex.level(spot.mapId()), 0);
        };
    }

    /** Pure band rule (tested): invasion low mob -5 to boss +10; sighting boss -15/+25; a map's mobs -8/+15. */
    static int[] bandFor(EventTemplate.Kind kind, int low, int high) {
        return switch (kind) {
            case INVASION -> new int[]{Math.max(1, low - 5), Math.min(255, Math.max(low, high) + 10)};
            case BOSS_SIGHTING -> new int[]{Math.max(1, low - 15), Math.min(255, low + 25)};
            case WANDERING_NPC -> new int[]{1, 255};
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
                Monster boss = spawnMob(map, e.spot.mobId(), randomSpot(map, rng));
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
                        Monster boss = spawnMob(map, e.template.bossMobId(), randomSpot(map, rng));
                        if (boss != null) {
                            e.bossOid = boss.getObjectId();
                            e.mobOids.add(boss.getObjectId());
                            announce(e, WorldEventText.bossArrives());
                        }
                    }
                } else if (e.bossOid >= 0 && map.getMonsterByOid(e.bossOid) == null) {
                    finish(e, "boss gone", true); // killed without the hook seeing a real player (or by a bot)
                    return;
                }
            }
            case BOSS_SIGHTING -> {
                if (map.getMonsterByOid(e.bossOid) == null) {
                    finish(e, "boss gone", true);
                    return;
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
        if (gathers && now - e.lastRecruitAt >= RECRUIT_EVERY_MS) {
            e.lastRecruitAt = now;
            WorldEventReactions.recruit(e, rng);
        }
        WorldEventReactions.chatter(e, now, rng, false);
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
        MapleMap map = mapOf(e.mapId());
        cleanUp(e, map);
        WorldEventReactions.release(e);
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
        }
        log("[WorldEvents] ended " + e.describe(e.endedAt));
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
        Portal p = map.getRandomPlayerSpawnpoint();
        return p != null ? new Point(p.getPosition()) : new Point(0, 0);
    }

    private static Monster spawnMob(MapleMap map, int mobId, Point at) {
        Monster mob = LifeFactory.getMonster(mobId);
        if (mob == null) {
            return null;
        }
        map.spawnMonsterOnGroundBelow(mob, at);
        return mob;
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
        String text = WorldEventText.fill(template, tokens(e));
        try {
            Server.getInstance().broadcastMessage(SoloMaplingConstants.GameConstants.homeWorld(),
                    PacketCreator.serverNotice(6, text));
        } catch (RuntimeException ex) {
            log("[WorldEvents] notice failed: " + ex);
        }
        log("[WorldEvents] notice: " + text);
    }

    // ── hooks from the game ──────────────────────────────────────────────────────────────────────

    /**
     * MapleMap.killMonster: an event mob died. An invasion boss or sighted boss killed by a real player makes them the
     * winner and ends the event; one killed by a bot ends it without a winner (the ticket-47 boss megaphone still
     * lets the bot flex).
     */
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
                if (killer != null && !BotHelpers.isBot(killer)) {
                    e.winnerId = killer.getId();
                    e.winnerName = killer.getName();
                    giveRewards(killer, e);
                }
                finish(e, e.winnerName != null ? "won" : "boss killed", true);
            }
            return;
        }
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
                e.winnerId = chr.getId();
                e.winnerName = chr.getName();
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
                e.winnerId = chr.getId();
                e.winnerName = chr.getName();
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
        return got.length() == 0 ? "" : "\r\n" + got;
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

    /** Test seam: a clean slate. */
    synchronized void resetForTest() {
        active.clear();
        recent.clear();
        lastEndedAt.clear();
        hotSpots.clear();
        lastStartAt = 0;
        settings = EventScheduler.Settings.fromSystemProperties();
    }
}
