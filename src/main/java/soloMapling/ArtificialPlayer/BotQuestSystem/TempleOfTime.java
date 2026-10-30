package soloMapling.ArtificialPlayer.BotQuestSystem;

import client.BuffStat;
import client.Character;
import client.QuestStatus;
import client.inventory.Item;
import server.quest.Quest;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ticket 158 item 5: the Temple of Time mainline driver (3500 to 3520) as a BotLife goal, on 162-questing's quest
 * path. It does not run quests itself: when a bot of the right level asks {@link BotQuests#consider} for a quest, the
 * driver hands it the next mainline quest as the bot's active quest ({@link BotQuestLog}), so the bot's life brain
 * makes it a {@code QUEST} goal and plays it the way it plays any quest: TAKE_QUEST at the giver, HUNT_QUEST on the
 * lane, TURN_IN_QUEST at the taker, with the real {@code Quest.start}/{@code complete} where a player can see it and
 * a recorded completion (no rewards) where nobody can. Ideas from nutnnut/Cosmic 77e0c5bc9
 * ({@code BotTempleProgressionManager}, AGPL-3.0): the chain, a personal "ambition" level to start at, the crowd
 * step-aside; ours otherwise.
 *
 * <p>What it adds to the quest path:
 * <ul>
 *   <li><b>Lane dispersal</b> ({@link TempleLanes}): the lane map and channel are chosen when the quest is picked, so
 *   at most {@link TempleLanes#cap()} Temple hunters share one lane map of one channel; when every lane is full the
 *   bot grinds elsewhere for a while.</li>
 *   <li><b>The corridor</b>: the lane exits ({@code timeQuest.js}) are bot transit edges that only a bot that
 *   completed the exit's quest may take ({@code BotTransit}, {@code GCTravel}), and a bot may only be sent to a
 *   Temple map its progress opens ({@code BotMapGates}).</li>
 *   <li><b>Scripted steps through their scripts</b> ({@link BotQuestScript}): 3507's end at the Memory Keeper after the
 *   job's sub-quest at its first instructor (a detour the driver makes), and 3514's start (pay 1,000,000 mesos for
 *   the potion) and end (after drinking it). Never {@code forceStart}/{@code forceComplete} from outside a script.</li>
 *   <li><b>Progress</b> in the bot's quest log ({@link BotQuestLog#templeDone()}), so the chain survives the capped
 *   completed list.</li>
 * </ul>
 * 3521 (Force Field: six items from the lane bosses and masks) is not driven: an item turn-in has no place in the
 * kill-quest path yet.
 *
 * <p>Switch: {@code -Dslums.temple.enabled=true} (default off) on top of ticket 158's {@code -Dslums.botquests.enabled}.
 */
public final class TempleOfTime {

    public static final String PROP = "slums.temple.enabled";

    /** Bots start the chain between these levels (a steady per-bot "ambition"), so they don't all arrive at 90. */
    static final int AMBITION_MIN = 90, AMBITION_BAND = 36;
    /**
     * Audit #2 (b111): a scripted step that ran but could not finish (NOT_READY: no potion, not enough mesos, the
     * script said "not yet") is put back and asked again after a backoff that doubles; after this many such waits it
     * is dropped for the run and the bot grinds and does normal quests instead.
     */
    static final int MAX_NOT_READY = 3;
    static final long NOT_READY_BACKOFF_MS = 20 * 60_000L;
    /** A bot whose lanes are all full asks again after this long (jittered). */
    static final long CROWD_DEFER_MIN_MS = 60_000, CROWD_DEFER_MAX_MS = 180_000;
    /** Mesos a bot keeps on top of the Sorcerer's fee before it goes to him. */
    static final int FEE_BUFFER = 100_000;

    private static final Map<Integer, Integer> progress = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> deferUntil = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> wanted = new ConcurrentHashMap<>();
    /** botId -> {mainline quest id, NOT_READY waits so far} (audit #2). */
    private static final Map<Integer, int[]> notReady = new ConcurrentHashMap<>();

    private TempleOfTime() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROP) && BotQuests.enabled();
    }

    /** The level this bot starts the Temple chain at, 90 to 125, the same every time. */
    public static int ambition(int botId) {
        long h = botId * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        return AMBITION_MIN + (int) Math.floorMod(h, (long) AMBITION_BAND);
    }

    // ── picking (BotQuests.consider) ──

    /**
     * Picks the bot's next Temple quest as its active quest. Null when the Temple has nothing for it now (off, done,
     * below its ambition or the quest's level, the Sorcerer's fee not saved up, every lane full): the normal kill
     * quests may be picked instead. True when a Temple quest was picked.
     */
    public static Boolean consider(Character chr, BotQuestLog log, long now) {
        if (!enabled() || chr == null || log == null) {
            return null;
        }
        note(chr.getId(), log);
        int done = log.templeDone();
        if (done >= TempleRoute.MAINLINE.size() || chr.getLevel() < ambition(chr.getId())) {
            return null;
        }
        TempleRoute.Step step = TempleRoute.MAINLINE.get(done);
        if (chr.getLevel() < step.lvmin()) {
            return null;
        }
        if (log.skipped(step.questId()) || log.completed(step.questId())) {
            return null; // audit #2: a step dropped as unfinishable is not picked again this run
        }
        if (step.questId() == TempleRoute.Q_SORCERER && chr.getMeso() < TempleRoute.SORCERER_FEE + FEE_BUFFER) {
            return null; // saves up for the potion first, as upstream does
        }
        Long wait = deferUntil.get(chr.getId());
        if (wait != null && now < wait) {
            return null;
        }
        restore(chr, log);
        int hunt = -1;
        if (!step.talk()) {
            TempleLanes.Choice c = TempleLanes.choose(step.lanes(), channels(chr), channelOf(chr),
                    (ch, map) -> TempleLanes.hunters(ch, map, chr.getId(), now) + 2 * players(chr, ch, map),
                    TempleLanes.cap(), chr.getId());
            if (c == null) {
                deferUntil.put(chr.getId(), now + ThreadLocalRandom.current().nextLong(CROWD_DEFER_MIN_MS,
                        CROWD_DEFER_MAX_MS));
                return null; // every lane is full: grind elsewhere and ask again later
            }
            if (c.channel() != channelOf(chr)) {
                // this channel's lane is full but another's has room: the brain is offered a "cc" there
                // (LifeRuntime's channel target, carried out on the bot's own update with LifeChannelMove's checks)
                wanted.put(chr.getId(), c.channel());
                deferUntil.put(chr.getId(), now + CROWD_DEFER_MIN_MS);
                return null;
            }
            wanted.remove(chr.getId());
            hunt = c.mapId();
            TempleLanes.register(chr.getId(), c.channel(), hunt, step.questId(), now);
        }
        log.pick(active(step, hunt, now));
        return true;
    }

    /** The active quest for a mainline step: its NPCs and maps, its kills, the dispersed lane. */
    static BotQuestLog.Active active(TempleRoute.Step step, int huntMap, long now) {
        return new BotQuestLog.Active(step.questId(), step.startNpc(), step.endNpc(), step.startMap(), step.endMap(),
                huntMap > 0 ? huntMap : step.endMap(), step.mobs(), now);
    }

    /**
     * The channel whose lane has room for this bot's next Temple quest when its own channel's is full, or -1
     * ({@code LifeRuntime} offers the life brain a CHANGE_CHANNEL there).
     */
    public static int wantedChannel(Character chr) {
        if (!enabled() || chr == null) {
            return -1;
        }
        Integer ch = wanted.get(chr.getId());
        if (ch == null) {
            return -1;
        }
        if (ch == channelOf(chr)) {
            wanted.remove(chr.getId()); // got there
            return -1;
        }
        return ch;
    }

    /** Keeps the progress the corridor gates read in step with the bot's log. */
    public static void note(int botId, BotQuestLog log) {
        progress.put(botId, log.templeDone());
        BotQuestLog.Active a = log.active();
        if (a == null || TempleRoute.indexOf(a.questId) < 0 || a.need.isEmpty()) {
            TempleLanes.unregister(botId);
        }
    }

    /** Mainline quests this bot has done, as far as the gates know (0 when its log was never read). */
    public static int progressOf(int botId) {
        return progress.getOrDefault(botId, 0);
    }

    /**
     * Fills the bot's in-memory quest map with the mainline quests its log says are done (the capped completed list
     * may have dropped them), so the real {@code Quest.canStart} sees the chain. Quiet, like {@link BotQuests#restore}.
     */
    public static void restore(Character chr, BotQuestLog log) {
        if (chr == null || log == null || log.templeDone() == 0) {
            return;
        }
        if (!chr.botQuestState()) {
            BotQuests.restore(chr, log);
        }
        if (!chr.botQuestState()) {
            return;
        }
        for (int i = 0; i < log.templeDone() && i < TempleRoute.MAINLINE.size(); i++) {
            Quest q = Quest.getInstance(TempleRoute.MAINLINE.get(i).questId());
            QuestStatus qs = chr.getQuest(q);
            if (qs == null || qs.getStatus() != QuestStatus.Status.COMPLETED) {
                chr.getQuestRemove(q);
                chr.setQuestAdd(q, (byte) QuestStatus.Status.COMPLETED.getId(), "");
            }
        }
    }

    // ── the corridor gates ──

    /**
     * Whether a bot may be sent to this map (BotMapGates): a Temple map needs the quest that opens its stretch. Audit
     * 163 (K12): an owned alt too - by its own real quest record - since it does not go through the lane portals'
     * scripts either (it follows, or is caught up beside its owner).
     */
    public static boolean mayEnter(int mapId, Character chr) {
        if (!enabled() || chr == null || !(chr.isBot() || chr.isOwnedAlt())) {
            return true;
        }
        int quest = TempleRoute.questToEnter(mapId);
        return quest == 0 || passes(chr, quest);
    }

    /** Whether this bot completed a mainline quest (its quest map, or its log's progress). */
    public static boolean passes(Character chr, int questId) {
        if (chr == null) {
            return false;
        }
        try {
            QuestStatus qs = chr.getQuest(Quest.getInstance(questId));
            if (qs != null && qs.getStatus() == QuestStatus.Status.COMPLETED) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no quest data (tests): the log decides
        }
        int idx = TempleRoute.indexOf(questId);
        return idx >= 0 && progressOf(chr.getId()) > idx;
    }

    // ── the scripted steps (BotQuests.atNpc) ──

    /**
     * The Temple's scripted steps at their NPC, or null to let the normal quest path handle the step. Handles: 3507's
     * turn-in (with the detour to the job's first instructor), the sub-quests, 3514's take and turn-in, and a 3507 or
     * 3514 that is already under way when the bot comes back (after a restart).
     */
    public static BotQuests.Visit atNpc(Character chr, BotQuestLog log, LifePlanner.Step step, boolean watched,
                                        long now) {
        if (!enabled() || chr == null || log == null || log.active() == null) {
            return null;
        }
        BotQuestLog.Active a = log.active();
        int q = a.questId;
        boolean take = step == LifePlanner.Step.TAKE_QUEST && !a.taken();
        boolean turnIn = step == LifePlanner.Step.TURN_IN_QUEST && a.taken();
        if (take && chr.getMapId() != a.startMap || turnIn && chr.getMapId() != a.endMap) {
            return null;
        }
        if (TempleRoute.isSubQuest(q) && take) {
            return subQuest(chr, log, a, watched, now);
        }
        if (q == TempleRoute.Q_MEMORY_KEEPER) {
            if (take && watched && started(chr, log, q)) {
                log.taken(BotQuestLog.Mode.REAL);
                return BotQuests.Visit.STARTED;
            }
            return turnIn && watched && a.mode() == BotQuestLog.Mode.REAL ? memoryKeeper(chr, log, a, now) : null;
        }
        if (q == TempleRoute.Q_SORCERER) {
            if (take) {
                return sorcererStart(chr, log, a, watched, now);
            }
            return turnIn && watched && a.mode() == BotQuestLog.Mode.REAL ? sorcererEnd(chr, log, now) : null;
        }
        return null;
    }

    private static boolean startedQuietly(Character chr, BotQuestLog log, int questId) {
        try {
            return started(chr, log, questId);
        } catch (RuntimeException | LinkageError e) {
            return false; // no quest data (tests)
        }
    }

    private static boolean started(Character chr, BotQuestLog log, int questId) {
        restore(chr, log);
        if (!chr.botQuestState()) {
            BotQuests.restore(chr, log);
        }
        return chr.botQuestState()
                && chr.getQuest(Quest.getInstance(questId)).getStatus() == QuestStatus.Status.STARTED;
    }

    /** 3507's end: after the sub-quest its script completes it; without it, off to the first instructor. */
    private static BotQuests.Visit memoryKeeper(Character chr, BotQuestLog log, BotQuestLog.Active a, long now) {
        if (!BotQuests.nearNpc(chr, a.endNpc)) {
            return BotQuests.Visit.TOO_FAR;
        }
        restore(chr, log);
        TempleRoute.SubQuest sub = TempleRoute.subQuestFor(chr.getJob().getId());
        if (!chr.getQuest(Quest.getInstance(sub.questId())).getStatus().equals(QuestStatus.Status.COMPLETED)) {
            // "You have not yet checked with your first teacher about your memories?": go and see him
            log.replace(new BotQuestLog.Active(sub.questId(), sub.npc(), sub.npc(), sub.map(), sub.map(), sub.map(),
                    Map.of(), now), false, BotQuestLog.Mode.MODELLED);
            return BotQuests.Visit.DEFERRED;
        }
        BotQuestScript.Result r = BotQuestScript.INSTANCE.run(chr, a.questId, a.endNpc, false, 1);
        if (chr.getQuest(Quest.getInstance(a.questId)).getStatus() == QuestStatus.Status.COMPLETED) {
            notReady.remove(chr.getId());
            log.complete(true, now);
            return BotQuests.Visit.COMPLETED;
        }
        return r.ran() ? notReady(chr, log, now) : drop(log);
    }

    /** The first instructor's sub-quest: its start script starts and completes it; then back to the Memory Keeper. */
    private static BotQuests.Visit subQuest(Character chr, BotQuestLog log, BotQuestLog.Active a, boolean watched,
                                            long now) {
        TempleRoute.Step keeper = TempleRoute.MAINLINE.get(TempleRoute.indexOf(TempleRoute.Q_MEMORY_KEEPER));
        BotQuestLog.Active back = active(keeper, -1, now);
        if (!watched) {
            log.replace(back, true, BotQuestLog.Mode.MODELLED); // nobody saw: the turn-in will just be recorded
            return BotQuests.Visit.TAKEN_OFFSCREEN;
        }
        if (!BotQuests.nearNpc(chr, a.startNpc)) {
            return BotQuests.Visit.TOO_FAR;
        }
        restore(chr, log);
        BotQuestScript.Result r = BotQuestScript.INSTANCE.run(chr, a.questId, a.startNpc, true, 1);
        if (chr.getQuest(Quest.getInstance(a.questId)).getStatus() != QuestStatus.Status.COMPLETED) {
            return r.ran() ? notReady(chr, log, now) : drop(log);
        }
        log.replace(back, true, BotQuestLog.Mode.REAL);
        return BotQuests.Visit.STARTED;
    }

    /** 3514's start: the Sorcerer charges the fee and hands over the potion (his script); offscreen just the fee. */
    private static BotQuests.Visit sorcererStart(Character chr, BotQuestLog log, BotQuestLog.Active a, boolean watched,
                                                long now) {
        if (!watched) {
            if (startedQuietly(chr, log, a.questId)) {
                log.taken(BotQuestLog.Mode.MODELLED); // paid before (put back after a wait): never charge twice
                return BotQuests.Visit.TAKEN_OFFSCREEN;
            }
            if (!BotHoldings.spendMeso(chr, TempleRoute.SORCERER_FEE)) {
                return notReady(chr, log, now);
            }
            log.taken(BotQuestLog.Mode.MODELLED);
            return BotQuests.Visit.TAKEN_OFFSCREEN;
        }
        if (!BotQuests.nearNpc(chr, a.startNpc)) {
            return BotQuests.Visit.TOO_FAR;
        }
        if (started(chr, log, a.questId)) {
            log.taken(BotQuestLog.Mode.REAL); // bought before (a restart): carry on
            return BotQuests.Visit.STARTED;
        }
        BotQuestScript.Result r = BotQuestScript.INSTANCE.run(chr, a.questId, a.startNpc, true, 0);
        if (chr.getQuest(Quest.getInstance(a.questId)).getStatus() == QuestStatus.Status.STARTED) {
            log.taken(BotQuestLog.Mode.REAL);
            return BotQuests.Visit.STARTED;
        }
        return r.ran() ? notReady(chr, log, now) : drop(log);
    }

    /** 3514's end: drink the potion (a player's own act), then the Sorcerer's end script gives the EXP and completes. */
    private static BotQuests.Visit sorcererEnd(Character chr, BotQuestLog log, long now) {
        BotQuestLog.Active a = log.active();
        if (!BotQuests.nearNpc(chr, a.endNpc)) {
            return BotQuests.Visit.TOO_FAR;
        }
        restore(chr, log);
        if (!drankPotion(chr)) {
            if (chr.getItemQuantity(TempleRoute.SORCERER_POTION, false) <= 0) {
                BotQuestScript.INSTANCE.run(chr, a.questId, a.endNpc, false, 0); // "Lost it? ... Take it."
            }
            drinkPotion(chr);
        }
        if (!drankPotion(chr)) {
            return notReady(chr, log, now);
        }
        BotQuestScript.Result r = BotQuestScript.INSTANCE.run(chr, a.questId, a.endNpc, false, 1);
        if (chr.getQuest(Quest.getInstance(a.questId)).getStatus() == QuestStatus.Status.COMPLETED) {
            notReady.remove(chr.getId());
            log.complete(true, now);
            return BotQuests.Visit.COMPLETED;
        }
        return r.ran() ? notReady(chr, log, now) : drop(log);
    }

    /** The script's own test (q3514e: {@code getBuffSource(HPREC) == 2022337}). */
    static boolean drankPotion(Character chr) {
        try {
            return chr.getBuffSource(BuffStat.HPREC) == TempleRoute.SORCERER_POTION;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Uses one potion the way the item-use handler does: its effect on the bot, one taken from the bag. */
    static void drinkPotion(Character chr) {
        try {
            var effect = server.ItemInformationProvider.getInstance().getItemEffect(TempleRoute.SORCERER_POTION);
            List<Item> stacks = new ArrayList<>(chr.getInventory(client.inventory.InventoryType.USE)
                    .listById(TempleRoute.SORCERER_POTION));
            if (effect == null || stacks.isEmpty()) {
                return;
            }
            if (effect.applyTo(chr)) {
                BotHoldings.consumeHeld(chr, stacks.get(0), 1);
            }
        } catch (RuntimeException e) {
            // no potion data or no bag: the end waits
        }
    }

    private static BotQuests.Visit drop(BotQuestLog log) {
        int step = mainlineStep(log);
        log.drop();
        if (step > 0) {
            log.skip(step); // a detour (the sub-quest) dropped drops the mainline step it serves, or consider re-picks it
        }
        return BotQuests.Visit.DROPPED;
    }

    /** The mainline quest the bot is on (its log's next step), or 0 when the chain is done. */
    private static int mainlineStep(BotQuestLog log) {
        int done = log.templeDone();
        return done < TempleRoute.MAINLINE.size() ? TempleRoute.MAINLINE.get(done).questId() : 0;
    }

    /**
     * Audit #2: the step ran but could not finish. The active quest is put back (the bot grinds meanwhile) and the
     * driver waits NOT_READY_BACKOFF_MS, doubling each time, before it picks the step again; the
     * (MAX_NOT_READY + 1)th wait drops it for the run.
     */
    static BotQuests.Visit notReady(Character chr, BotQuestLog log, long now) {
        int step = mainlineStep(log);
        int[] s = notReady.compute(chr.getId(), (k, v) -> v == null || v[0] != step ? new int[]{step, 1}
                : new int[]{step, v[1] + 1});
        if (s[1] > MAX_NOT_READY) {
            notReady.remove(chr.getId());
            return drop(log);
        }
        log.release();
        TempleLanes.unregister(chr.getId());
        deferUntil.put(chr.getId(), now + (NOT_READY_BACKOFF_MS << (s[1] - 1)));
        return BotQuests.Visit.DEFERRED;
    }

    // ── the world (a seam: tests replace it) ──

    /** What the driver reads about channels and maps, and the channel change it asks for. */
    public interface Server {
        List<Integer> channels(Character chr);

        int channelOf(Character chr);

        /** Real players (not bots, not alts) on a map of a channel. */
        int players(Character chr, int channel, int mapId);
    }

    static volatile Server host = new Server() {
        @Override public List<Integer> channels(Character chr) {
            try {
                List<Integer> out = new ArrayList<>();
                for (var ch : net.server.Server.getInstance().getChannelsFromWorld(chr.getWorld())) {
                    out.add(ch.getId());
                }
                return out;
            } catch (RuntimeException e) {
                return List.of(channelOf(chr));
            }
        }

        @Override public int channelOf(Character chr) {
            try {
                return chr.getClient().getChannel();
            } catch (RuntimeException e) {
                return 1;
            }
        }

        @Override public int players(Character chr, int channel, int mapId) {
            try {
                var ch = net.server.Server.getInstance().getChannel(chr.getWorld(), channel);
                // a channel whose instance isn't loaded has nobody there (never load a map to count it)
                var map = ch == null || !ch.getMapFactory().isMapLoaded(mapId) ? null : ch.getMapFactory().getMap(mapId);
                if (map == null) {
                    return 0;
                }
                int n = 0;
                for (Character c : map.getAllPlayers()) {
                    if (!c.isBot() && !c.isOwnedAlt()) {
                        n++;
                    }
                }
                return n;
            } catch (RuntimeException e) {
                return 0;
            }
        }
    };

    static List<Integer> channels(Character chr) {
        return host.channels(chr);
    }

    static int channelOf(Character chr) {
        return host.channelOf(chr);
    }

    static int players(Character chr, int channel, int mapId) {
        return host.players(chr, channel, mapId);
    }


    static void resetForTests() {
        progress.clear();
        deferUntil.clear();
        wanted.clear();
        notReady.clear();
    }
}
