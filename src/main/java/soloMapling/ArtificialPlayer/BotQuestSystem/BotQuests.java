package soloMapling.ArtificialPlayer.BotQuestSystem;

import client.Character;
import client.QuestStatus;
import client.inventory.Item;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.life.NPC;
import server.quest.Quest;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ticket 158 item 2: a bot training on a map takes a kill quest whose monsters live there, goes to the quest's NPC,
 * hunts, and turns the quest in. Ideas from nutnnut/Cosmic 77e0c5bc9 ({@code BotQuestManager}, AGPL-3.0); the
 * runtime is ours, built on the bot's life brain.
 *
 * <p><b>A goal, not an errand.</b> A picked quest becomes the bot's {@code QUEST} goal ({@code LifeGoals}); the
 * planner ({@code LifePlanner}) turns it into three steps: TAKE_QUEST (a town stop at the start NPC's map), HUNT_QUEST
 * (grind the quest's map) and TURN_IN_QUEST (a town stop at the end NPC's map). The steps only lean the brain's
 * choice, as every plan step does; {@code !botwhy} shows them.
 *
 * <p><b>Legal path only.</b> A bot where a player can see it (its map is in full simulation,
 * {@code GCMovement.isMapObserved}) walks up to the NPC and runs the real {@code Quest.canStart}/{@code start} and
 * {@code canComplete}/{@code complete}, with the player handler's NPC distance rule; kills count through the normal
 * {@code raiseQuestMobCount}. Nothing is forced: no {@code forceStart}, no {@code forceComplete}, no rewards handed
 * out by this class. A bot nobody watches only records the quest in its log: taken, kills modelled from its offscreen
 * kill rate, and a completion with no rewards (the ticket's "offscreen bots just record a completion").
 *
 * <p><b>Economy.</b> The quest's meso and item rewards are taken through {@code BotYieldCaps} (the daily meso cap and
 * the map meters) before {@code complete} runs; when the caps refuse, the turn-in waits (as a full bag would), and
 * after {@link #MAX_DEFERRALS} waits the quest is recorded as done without rewards. Quest EXP is not in the yield
 * caps (they meter mesos and items only); it is bounded by {@link #perDay()} quests a day per bot.
 *
 * <p><b>Storage.</b> {@link BotQuestLog} in the bot's life profile row (active quest + capped completed ids). A bot's
 * in-memory quest map is filled from that log the first time it needs it ({@link #restore}); roster bots are never
 * written to {@code queststatus}.
 *
 * <p>Switch: {@code -Dslums.botquests.enabled=true} (default off), and the life brain must be on
 * ({@code LifeRuntime.enabled()}). Tunables: {@code -Dslums.botquests.perDay} (6), {@code -Dslums.botquests.keepDone}
 * (32).
 */
public final class BotQuests {

    private static final Logger LOG = LoggerFactory.getLogger(BotQuests.class);

    public static final String PROP = "slums.botquests.enabled";
    /** Default daily cap of quest completions per bot ({@code -Dslums.botquests.perDay}). */
    public static final int DEFAULT_PER_DAY = 6;
    /** A bot looks for a quest at most this often while it grinds. */
    static final long CONSIDER_EVERY_MS = 10 * 60_000L;
    /** Seconds a bot needs per map hop on a trip (a conservative walk with a portal). */
    static final double SECONDS_PER_HOP = 20;
    /** NPCs further than this many map hops from the grind map are not worth a kill quest. */
    static final int MAX_HOPS = 4;
    /** The player handler's NPC distance rule (QuestActionHandler.isNpcNearby). */
    static final int NPC_REACH_X = 1200, NPC_REACH_Y = 800;
    /** A picked quest not taken within this long, or a taken one not done within this long, is dropped. */
    static final long STALE_PICK_MS = 2 * 3_600_000L, STALE_TAKEN_MS = 24 * 3_600_000L;
    /** Turn-ins the yield caps may hold back before the quest is recorded as done without rewards. */
    static final int MAX_DEFERRALS = 3;

    private static final Map<Integer, Long> nextConsider = new ConcurrentHashMap<>();
    private static final LongAdder picks = new LongAdder(), realStarts = new LongAdder(), realCompletes = new LongAdder(),
            modelledCompletes = new LongAdder(), deferred = new LongAdder(), dropped = new LongAdder();

    private BotQuests() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROP) && soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled();
    }

    public static int perDay() {
        return Math.max(0, Integer.getInteger("slums.botquests.perDay", DEFAULT_PER_DAY));
    }

    // ── seams (tests replace these; production reads the game) ──

    /** The real quest calls. */
    public interface Gate {
        boolean canStart(Character chr, int questId, int npc);

        boolean canComplete(Character chr, int questId, int npc);

        void start(Character chr, int questId, int npc);

        void complete(Character chr, int questId, int npc);

        QuestStatus.Status status(Character chr, int questId);

        /** Real kill progress of one mob on a started quest (0 when none). */
        int progress(Character chr, int questId, int mobId);
    }

    static volatile Gate gate = new Gate() {
        @Override public boolean canStart(Character chr, int questId, int npc) {
            return Quest.getInstance(questId).canStart(chr, npc);
        }
        @Override public boolean canComplete(Character chr, int questId, int npc) {
            return Quest.getInstance(questId).canComplete(chr, npc);
        }
        @Override public void start(Character chr, int questId, int npc) {
            Quest.getInstance(questId).start(chr, npc);
        }
        @Override public void complete(Character chr, int questId, int npc) {
            Quest.getInstance(questId).complete(chr, npc);
        }
        @Override public QuestStatus.Status status(Character chr, int questId) {
            QuestStatus qs = chr.getQuestNoAdd(Quest.getInstance(questId));
            return qs == null ? QuestStatus.Status.NOT_STARTED : qs.getStatus();
        }
        @Override public int progress(Character chr, int questId, int mobId) {
            QuestStatus qs = chr.getQuestNoAdd(Quest.getInstance(questId));
            if (qs == null) return 0;
            try {
                String p = qs.getProgress(mobId);
                return p.isEmpty() ? 0 : Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    };

    /** What the bot can read about maps and fights. */
    public interface World {
        /** Mob ids spawned on a map, one entry per spawn point (empty for towns). */
        List<Integer> mobsOn(int mapId);

        /** Whether this NPC stands on this map (from Map.wz). */
        boolean npcOn(int mapId, int npcId);

        /** The map's return town, or -1. */
        int returnMap(int mapId);

        /** Map hops between two maps for this bot, or -1 when unreachable. */
        int hops(Character chr, int fromMapId, int toMapId);

        /** The bot's kills per minute and EXP per kill on a map (offscreen model); null when unknown. */
        double[] rate(Character chr, int mapId);

        /** Reward EXP in kill-EXP units (the quest EXP rate over the kill EXP rate). */
        double questExpFactor(Character chr);
    }

    static volatile World world = new World() {
        @Override public List<Integer> mobsOn(int mapId) {
            return soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.mobIds(mapId);
        }
        @Override public boolean npcOn(int mapId, int npcId) {
            return npcsOn(mapId).contains(npcId);
        }
        @Override public int returnMap(int mapId) {
            int r = soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.returnMapId(mapId);
            return r <= 0 || r == 999999999 ? -1 : r;
        }
        @Override public int hops(Character chr, int fromMapId, int toMapId) {
            if (fromMapId == toMapId) return 0;
            List<Integer> route = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.route(fromMapId, toMapId,
                    soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.rebirthsOf(chr), chr.getLevel());
            return route == null ? -1 : route.size();
        }
        @Override public double[] rate(Character chr, int mapId) {
            var rate = soloMapling.ArtificialPlayer.BotAttackSystem.OffscreenCombat.rate(chr, mapId);
            return rate == null ? null : new double[]{rate.killsPerMinute(), rate.expPerKill()};
        }
        @Override public double questExpFactor(Character chr) {
            return YamlConfig.config.server.USE_QUEST_RATE
                    ? (double) chr.getQuestExpRate() / Math.max(1, chr.getExpRate()) : 1.0;
        }
    };

    /** The economy meters the rewards go through. */
    public interface Yield {
        /** Mesos the caps let this bot take (all, part or none). */
        long takeMesos(Character chr, long mesos);

        void returnMesos(Character chr, long mesos);

        boolean takeItem(Character chr, int itemId, int count);

        void returnItem(Character chr, int itemId, int count);

        boolean canHold(Character chr, int itemId, int count);
    }

    static volatile Yield yield = new Yield() {
        @Override public long takeMesos(Character chr, long mesos) {
            return soloMapling.ArtificialPlayer.BotYieldCaps.takeMesos(chr, mesos);
        }
        @Override public void returnMesos(Character chr, long mesos) {
            soloMapling.ArtificialPlayer.BotYieldCaps.returnMesos(chr, mesos);
        }
        @Override public boolean takeItem(Character chr, int itemId, int count) {
            return soloMapling.ArtificialPlayer.BotYieldCaps.takeItem(chr, new Item(itemId, (short) 0, (short) count));
        }
        @Override public void returnItem(Character chr, int itemId, int count) {
            soloMapling.ArtificialPlayer.BotYieldCaps.returnItem(chr, new Item(itemId, (short) 0, (short) count));
        }
        @Override public boolean canHold(Character chr, int itemId, int count) {
            return chr.canHold(itemId, count);
        }
    };

    // ── NPCs on maps (Map.wz life, cached per map) ──

    private static final Map<Integer, Set<Integer>> NPCS = new ConcurrentHashMap<>();
    private static final ThreadLocal<DataProvider> MAP_SOURCE =
            ThreadLocal.withInitial(() -> DataProviderFactory.getDataProvider(WZFiles.MAP));

    static Set<Integer> npcsOn(int mapId) {
        return NPCS.computeIfAbsent(mapId, id -> {
            try {
                Data map = MAP_SOURCE.get().getData("Map/Map" + (id / 100000000) + "/" + String.format("%09d", id) + ".img");
                Data life = map == null ? null : map.getChildByPath("life");
                if (life == null) return Set.of();
                Set<Integer> out = new HashSet<>();
                for (Data entry : life) {
                    if (!"n".equals(DataTool.getString("type", entry, ""))) continue;
                    try {
                        out.add(Integer.parseInt(DataTool.getString("id", entry, "")));
                    } catch (NumberFormatException ignored) {
                        // not an npc id
                    }
                }
                return Set.copyOf(out);
            } catch (RuntimeException e) {
                return Set.of();
            }
        });
    }

    // ── picking (item 3: worth) ──

    /** A quest worth taking: its meta, the maps of its NPCs and its score. */
    public record Candidate(BotQuestIndex.QuestMeta quest, int startMap, int endMap, int huntMap, double score) {}

    /**
     * Decision hook (LifeRuntime, at a decision while the bot grinds): maybe picks a quest whose monsters are on this
     * map. Cheap when it does nothing: a per-bot clock, the daily cap and an active quest all return at once.
     */
    public static boolean consider(Character chr, BotQuestLog log, boolean grinding, int homeMap, long now) {
        if (!enabled() || chr == null || log == null) return false;
        BotQuestLog.Active current = log.active();
        if (current != null && now - current.pickedAt > (current.taken() ? STALE_TAKEN_MS : STALE_PICK_MS)) {
            log.drop(); // never got round to it (the brain kept choosing other things): let it go, as players do
            dropped.increment();
        }
        if (!grinding || log.active() != null) return false;
        Long due = nextConsider.get(chr.getId());
        if (due != null && now < due) return false;
        nextConsider.put(chr.getId(), now + CONSIDER_EVERY_MS);
        if (log.doneToday(now) >= perDay()) return false;
        if (!log.done().isEmpty()) restore(chr, log); // earlier quests count as prerequisites in canStart
        Candidate best = best(chr, log, chr.getMapId(), homeMap, BotQuestScorer.TAKE_MIN_SCORE);
        if (best == null) return false;
        BotQuestIndex.QuestMeta q = best.quest();
        log.pick(new BotQuestLog.Active(q.id(), q.startNpc(), q.endNpc(), best.startMap(), best.endMap(),
                best.huntMap(), q.mobs(), now));
        picks.increment();
        return true;
    }

    /** The best runnable quest for this bot on this map scoring at least {@code minScore}, or null. */
    public static Candidate best(Character chr, BotQuestLog log, int mapId, int homeMap, double minScore) {
        List<Candidate> ranked = ranked(chr, log, mapId, homeMap, minScore, 1);
        return ranked.isEmpty() ? null : ranked.getFirst();
    }

    /** Runnable quests on this map for this bot, best first, at most {@code limit}. */
    public static List<Candidate> ranked(Character chr, BotQuestLog log, int mapId, int homeMap, double minScore,
                                         int limit) {
        List<Integer> spawns = world.mobsOn(mapId);
        if (spawns.isEmpty()) return List.of();
        Set<Integer> here = new LinkedHashSet<>(spawns);
        BotQuestIndex.Index index = BotQuestIndex.get();
        double[] rate = world.rate(chr, mapId);
        double kpm = rate == null ? 0 : rate[0];
        double expPerKill = rate == null ? 0 : rate[1];
        double baseline = kpm * expPerKill;
        double killSeconds = kpm > 0 ? 60.0 / kpm : 60;
        double expFactor = world.questExpFactor(chr);
        int level = chr.getLevel();
        Set<Integer> seen = new HashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (int mob : here) {
            for (BotQuestIndex.QuestMeta q : index.forMob(mob)) {
                if (!seen.add(q.id()) || log != null && (log.completed(q.id()) || log.skipped(q.id()))) continue;
                if (q.lvmin() > level || q.lvmax() > 0 && level > q.lvmax()) continue;
                if (!here.containsAll(q.mobs().keySet())) continue; // piggyback: every kill on the grind map
                int startMap = npcMap(q.startNpc(), mapId, homeMap);
                int endMap = startMap >= 0 && world.npcOn(startMap, q.endNpc()) ? startMap : npcMap(q.endNpc(), mapId, homeMap);
                if (startMap < 0 || endMap < 0) continue;
                int toStart = world.hops(chr, mapId, startMap), toEnd = world.hops(chr, mapId, endMap);
                if (toStart < 0 || toEnd < 0 || toStart > MAX_HOPS || toEnd > MAX_HOPS) continue;
                if (!gate.canStart(chr, q.id(), q.startNpc())) continue; // level, job, earlier quests: the real rule
                double travel = 2.0 * (toStart + toEnd) * SECONDS_PER_HOP;
                double score = BotQuestScorer.score(q.mobs(), (int) Math.round(q.rewardExp() * expFactor), 0, here,
                        mobId -> (int) Math.round(expPerKill), killSeconds, travel, baseline);
                if (score >= minScore) out.add(new Candidate(q, startMap, endMap, mapId, score));
            }
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }

    /** The map an NPC stands on: the grind map, its return town or the bot's home town; -1 for none of them. */
    static int npcMap(int npcId, int grindMap, int homeMap) {
        if (world.npcOn(grindMap, npcId)) return grindMap;
        int town = world.returnMap(grindMap);
        if (town > 0 && world.npcOn(town, npcId)) return town;
        if (homeMap > 0 && world.npcOn(homeMap, npcId)) return homeMap;
        return -1;
    }

    // ── the planner's view ──

    /** The quest facts the planner reads (QUEST_TAKEN, QUEST_HUNTED). */
    public static Set<LifePlanner.Fact> facts(BotQuestLog log) {
        BotQuestLog.Active a = log == null ? null : log.active();
        if (a == null || !a.taken()) return Set.of();
        return a.hunted() ? EnumSet.of(LifePlanner.Fact.QUEST_TAKEN, LifePlanner.Fact.QUEST_HUNTED)
                : EnumSet.of(LifePlanner.Fact.QUEST_TAKEN);
    }

    /** Where a quest step takes the bot (-1 for a non-quest step or no quest). */
    public static int destination(BotQuestLog log, LifePlanner.Step step) {
        BotQuestLog.Active a = log == null ? null : log.active();
        if (a == null || step == null) return -1;
        return switch (step) {
            case TAKE_QUEST -> a.startMap;
            case HUNT_QUEST -> a.huntMap;
            case TURN_IN_QUEST -> a.endMap;
            default -> -1;
        };
    }

    public static boolean questStep(LifePlanner.Step step) {
        return step == LifePlanner.Step.TAKE_QUEST || step == LifePlanner.Step.TURN_IN_QUEST
                || step == LifePlanner.Step.HUNT_QUEST;
    }

    /** TrainingBot's DECIDE: the map to hunt the taken quest on, or -1. */
    public static int huntMap(BotQuestLog log) {
        if (!enabled() || log == null) return -1;
        BotQuestLog.Active a = log.active();
        return a != null && a.taken() && !a.hunted() ? a.huntMap : -1;
    }

    // ── kills ──

    /** Offscreen grind (TrainingBot.doGrind's unobserved stretch): kills split by the map's spawn share. */
    public static void offscreenKills(BotQuestLog log, int mapId, double kills) {
        if (!enabled() || log == null || kills <= 0) return;
        BotQuestLog.Active a = log.active();
        if (a == null || !a.taken() || a.huntMap != mapId) return;
        List<Integer> spawns = world.mobsOn(mapId);
        if (spawns.isEmpty()) return;
        for (int mob : a.need.keySet()) {
            long share = spawns.stream().filter(m -> m == mob).count();
            if (share > 0) log.addKills(mob, kills * share / spawns.size());
        }
    }

    /** Real progress (kills a player could see) copied into the log so it survives the session. */
    public static void syncReal(Character chr, BotQuestLog log) {
        if (chr == null || log == null) return;
        BotQuestLog.Active a = log.active();
        if (a == null || !a.taken() || a.mode() != BotQuestLog.Mode.REAL || !chr.botQuestState()) return;
        for (int mob : a.need.keySet()) log.syncKills(mob, gate.progress(chr, a.questId, mob));
    }

    // ── the bot's quest map ──

    /**
     * Fills the bot's in-memory quest map from its log, once per character object: completed quests (so the real
     * {@code canStart} sees prerequisites and never restarts one) and a really started active quest with its
     * progress. Quiet: no packets, no quest points, no rewards (the same as a player's quests loading at login).
     */
    public static void restore(Character chr, BotQuestLog log) {
        if (chr == null || log == null || chr.botQuestState()) return;
        chr.enableBotQuestState();
        try {
            for (int id : log.done()) chr.setQuestAdd(Quest.getInstance(id), (byte) QuestStatus.Status.COMPLETED.getId(), "");
            BotQuestLog.Active a = log.active();
            if (a != null && a.taken() && a.mode() == BotQuestLog.Mode.REAL) {
                Quest quest = Quest.getInstance(a.questId);
                chr.setQuestAdd(quest, (byte) QuestStatus.Status.STARTED.getId(), "");
                QuestStatus qs = chr.getQuestNoAdd(quest);
                if (qs != null) {
                    for (int mob : a.need.keySet()) qs.setProgress(mob, String.format("%03d", Math.min(999, a.killed(mob))));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("bot quest restore failed for {}", chr.getId(), e); // the log stays; the bot just has less state
        }
    }

    // ── at the NPC ──

    /** What happened at a quest NPC. */
    public enum Visit { STARTED, TAKEN_OFFSCREEN, COMPLETED, RECORDED, DEFERRED, NOT_READY, TOO_FAR, DROPPED, NONE }

    /**
     * The bot stands on the step's NPC map (TrainingBot's town stop). {@code watched}: a player can see this map (full
     * simulation); then the real quest path runs, and the bot must be within the player's NPC distance. Otherwise the
     * log just records what a player would have done.
     */
    public static Visit atNpc(Character chr, BotQuestLog log, LifePlanner.Step step, boolean watched, long now) {
        if (!enabled() || chr == null || log == null || step == null) return Visit.NONE;
        BotQuestLog.Active a = log.active();
        if (a == null) return Visit.NONE;
        if (step == LifePlanner.Step.TAKE_QUEST && !a.taken()) {
            if (chr.getMapId() != a.startMap) return Visit.NONE;
            if (!watched) {
                log.taken(BotQuestLog.Mode.MODELLED);
                return Visit.TAKEN_OFFSCREEN;
            }
            if (!nearNpc(chr, a.startNpc)) return Visit.TOO_FAR;
            restore(chr, log);
            if (!gate.canStart(chr, a.questId, a.startNpc)) {
                log.drop();
                dropped.increment();
                return Visit.DROPPED;
            }
            gate.start(chr, a.questId, a.startNpc);
            if (gate.status(chr, a.questId) != QuestStatus.Status.STARTED) {
                log.drop();
                dropped.increment();
                return Visit.DROPPED;
            }
            log.taken(BotQuestLog.Mode.REAL);
            realStarts.increment();
            return Visit.STARTED;
        }
        if (step == LifePlanner.Step.TURN_IN_QUEST && a.taken()) {
            if (chr.getMapId() != a.endMap) return Visit.NONE;
            syncReal(chr, log);
            if (!a.hunted()) return Visit.NOT_READY;
            if (!watched || a.mode() != BotQuestLog.Mode.REAL) return record(chr, log, now);
            if (!nearNpc(chr, a.endNpc)) return Visit.TOO_FAR;
            restore(chr, log);
            if (!gate.canComplete(chr, a.questId, a.endNpc)) {
                // the log counted modelled kills the quest itself never saw (it hunted offscreen): no rewards
                return record(chr, log, now);
            }
            BotQuestIndex.QuestMeta q = BotQuestIndex.get().byId().get(a.questId);
            Reservation held = q == null ? Reservation.NONE : reserve(chr, q);
            if (held == null) {
                if (log.defer() > MAX_DEFERRALS) return record(chr, log, now);
                deferred.increment();
                return Visit.DEFERRED;
            }
            gate.complete(chr, a.questId, a.endNpc);
            if (gate.status(chr, a.questId) != QuestStatus.Status.COMPLETED) {
                held.giveBack(chr);
                log.drop();
                dropped.increment();
                return Visit.DROPPED;
            }
            log.complete(true, now);
            realCompletes.increment();
            return Visit.COMPLETED;
        }
        return Visit.NONE;
    }

    /** A completion only the log knows about: no rewards; a really started quest is marked done in the quest map. */
    private static Visit record(Character chr, BotQuestLog log, long now) {
        BotQuestLog.Active a = log.active();
        if (a == null) return Visit.NONE;
        if (chr.botQuestState()) {
            try {
                Quest quest = Quest.getInstance(a.questId);
                chr.getQuestRemove(quest);
                chr.setQuestAdd(quest, (byte) QuestStatus.Status.COMPLETED.getId(), "");
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("bot quest record failed for {}", chr.getId(), e);
            }
        }
        log.complete(false, now);
        modelledCompletes.increment();
        return Visit.RECORDED;
    }

    /** The player handler's rule: the NPC is on the bot's map and within 1200 x 800 px. */
    static boolean nearNpc(Character chr, int npcId) {
        NPC npc = chr.getMap() == null ? null : chr.getMap().getNPCById(npcId);
        if (npc == null) return false;
        Point n = npc.getPosition(), p = chr.getPosition();
        return n != null && p != null && Math.abs(n.x - p.x) <= NPC_REACH_X && Math.abs(n.y - p.y) <= NPC_REACH_Y;
    }

    /** Where to stand to talk to the NPC on the bot's map, or null when it is not there. */
    public static Point npcSpot(Character chr, LifePlanner.Step step, BotQuestLog log) {
        BotQuestLog.Active a = log == null ? null : log.active();
        if (a == null || chr.getMap() == null) return null;
        int npcId = step == LifePlanner.Step.TAKE_QUEST ? a.startNpc : a.endNpc;
        NPC npc = chr.getMap().getNPCById(npcId);
        return npc == null ? null : npc.getPosition();
    }

    // ── rewards through the yield caps ──

    /** What was taken from the meters for one turn-in. */
    record Reservation(long mesos, Map<Integer, Integer> items) {
        static final Reservation NONE = new Reservation(0, Map.of());

        void giveBack(Character chr) {
            if (mesos > 0) yield.returnMesos(chr, mesos);
            items.forEach((id, n) -> yield.returnItem(chr, id, n));
        }
    }

    /** Takes the quest's meso and item rewards from the yield caps, all or nothing; null when refused or no room. */
    static Reservation reserve(Character chr, BotQuestIndex.QuestMeta q) {
        Map<Integer, Integer> items = new java.util.LinkedHashMap<>(q.rewardItems());
        if (q.randomReward()) items.merge(RANDOM_REWARD_UNIT, 1, Integer::sum);
        for (Map.Entry<Integer, Integer> e : items.entrySet()) {
            if (e.getKey() != RANDOM_REWARD_UNIT && !yield.canHold(chr, e.getKey(), e.getValue())) return null;
        }
        long mesos = q.rewardMesos() <= 0 ? 0 : (long) q.rewardMesos() * mesoRate(chr);
        long gotMesos = mesos <= 0 ? 0 : yield.takeMesos(chr, mesos);
        if (gotMesos < mesos) {
            if (gotMesos > 0) yield.returnMesos(chr, gotMesos);
            return null;
        }
        Map<Integer, Integer> taken = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : items.entrySet()) {
            if (!yield.takeItem(chr, e.getKey(), e.getValue())) {
                new Reservation(mesos, taken).giveBack(chr);
                return null;
            }
            taken.put(e.getKey(), e.getValue());
        }
        return new Reservation(mesos, taken);
    }

    /** Stand-in item id for "one item from a random pool": the meters count units, not ids. */
    static final int RANDOM_REWARD_UNIT = 4000000;

    private static int mesoRate(Character chr) {
        try {
            return Math.max(1, YamlConfig.config.server.USE_QUEST_RATE ? chr.getQuestMesoRate() : chr.getMesoRate());
        } catch (RuntimeException | LinkageError e) {
            return 1;
        }
    }

    /** {@code !botyield}/metrics: picks, real starts and turn-ins, recorded completions, waits and drops. */
    public static Map<String, Long> metrics() {
        return Map.of("picks", picks.sum(), "realStarts", realStarts.sum(), "realCompletes", realCompletes.sum(),
                "recorded", modelledCompletes.sum(), "deferred", deferred.sum(), "dropped", dropped.sum());
    }

    /** Test seam: forget per-bot clocks. */
    static void resetForTests() {
        nextConsider.clear();
    }
}
