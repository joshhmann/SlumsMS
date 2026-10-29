package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionBossLog;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.6.1 ticket 30 (promise audit, "they earn the key item from boss drops"): regulars at 200 that want their rebirth
 * go into real boss runs for the key's items. Two ways in, both the players' own event path:
 * <ol>
 *   <li><b>They form a run</b> (Von Leon, Cygnus: {@link Boss#botsRunIt}). The seeker takes a Hyper Teleport Rock to
 *   the recruit map with up to five more: other seekers for that boss first, then strong regulars on its channel
 *   that are free (level 150+, no party, nothing in flight, the map's rebirth gate open, a clear left today). The
 *   seeker opens a real {@link Expedition} (the recruiter's quota check), the others register ({@code addMember}:
 *   their quota), and the event starts through {@code EventManager.startInstance} as the recruiter's "Start the
 *   fight!" does; in the room the boss is summoned as its NPC summons it (2161008 / 3003903). The crew fights it with
 *   the bot attack driver; the hits go through the map's boss rules ({@code MapleMap.damageMonster}'s pieces), so the
 *   death runs the event's own {@code monsterKilled} and {@code clearPQ}, logging the clear in {@code bosslog_daily}.
 *   </li>
 *   <li><b>They join a player's run</b> (any key boss): when a real player opens an expedition for a boss a seeker
 *   is waiting on ({@link #waitFor}), up to three seekers on that channel come to the recruiter and register, fight
 *   next to the players inside (following the leader between the instance's rooms) and leave after the clear.</li>
 * </ol>
 * Either way the item is earned from the kill: {@link #onClear} (called by {@code setEventCleared}) hands each bot in
 * the room the item Plunk / the Pink Bean exit NPC hands every member (a bot cannot talk to Plunk), and the crew
 * loots the boss's floor drops of key items it lacks (the real drop table, rolled by the kill). A run that does not
 * clear in {@link #runMinutes} ends and its seekers try again later.
 *
 * <p>Cost: nothing here runs per tick for the population. Only seekers (level 200, under 3 rebirths, bothered this
 * week, missing an item) call {@link #request}/{@link #waitFor}, from their own DECIDE. A run has one beat
 * (250 ms) while it lives, at most {@link #maxRuns} bot-formed runs world-wide, six bots each. Picking helpers scans
 * the online bots once per run formed. {@link #holds} is one map read.
 *
 * <p>Switches: {@code -Dslums.botRebirth.liveRuns} (default true; false sends every boss off screen),
 * {@code .maxRuns} (2), {@code .runMinutes} (40), {@code .waitHours} (2), {@code .retryMinutes} (30).
 */
public final class RebirthBossRuns {

    private static final Logger log = LoggerFactory.getLogger(RebirthBossRuns.class);

    static final int MAX_CREW = 6;
    static final int MAX_JOIN_PLAYER_RUN = 3;
    static final int HELPER_MIN_LEVEL = 150;
    static final long BEAT_MS = 250;
    /** Bots are put on the recruit map; the expedition opens after this. */
    static final long GATHER_MS = 3_000;
    /** After the clear: the crew loots for this long (drops are the killer's for 15 s, then anyone's). */
    static final long LOOT_MS = 17_000;
    /** After the summon: a room with no live monster this long into the fight (and no clear) ends the run. */
    static final long SUMMON_GRACE_MS = 10_000;
    /** A bot out of reach of its target this long is put beside it (a room nobody else is in has no walk data). */
    static final long STUCK_MS = 8_000;

    public enum Phase { GATHER, REGISTERED, FIGHT, LOOT, DONE }

    /** One run: bot-formed, or a real player's expedition some seekers joined. */
    public static final class Run {
        final Boss boss;
        final Character leader;
        final boolean botLed;
        final int world;
        final int channel;
        final List<Character> crew = new CopyOnWriteArrayList<>();
        final List<String> notes = new CopyOnWriteArrayList<>();
        /** Bot id -> how it came by the boss's item ("clear reward", "floor"). */
        final Map<Integer, String> got = new ConcurrentHashMap<>();
        final Map<Integer, Long> stuckSince = new ConcurrentHashMap<>();
        final long createdAt;
        final AtomicBoolean busy = new AtomicBoolean();
        volatile Phase phase;
        volatile long phaseAt;
        volatile Expedition exped;
        volatile EventInstanceManager eim;
        volatile boolean summoned;
        volatile boolean cleared;
        /** QA only: the summoned boss starts at this share of its HP (1 = the real fight). */
        volatile double hpFraction = 1.0;
        volatile String result;
        volatile ScheduledFuture<?> beat;

        Run(Boss boss, Character leader, boolean botLed, int world, int channel, long now) {
            this.boss = boss;
            this.leader = leader;
            this.botLed = botLed;
            this.world = world;
            this.channel = channel;
            this.createdAt = now;
            this.phase = botLed ? Phase.GATHER : Phase.REGISTERED;
            this.phaseAt = now;
        }

        public Boss boss() {
            return boss;
        }

        public Phase phase() {
            return phase;
        }

        public boolean cleared() {
            return cleared;
        }

        public String result() {
            return result;
        }

        public List<String> notes() {
            return List.copyOf(notes);
        }

        public List<String> crewNames() {
            return crew.stream().map(Character::getName).toList();
        }

        public List<Integer> crewIds() {
            return crew.stream().map(Character::getId).toList();
        }

        /** How this bot came by the boss's item in this run, or null. */
        public String gotBy(int botId) {
            return got.get(botId);
        }

        void note(String line) {
            notes.add(line);
            log.info("[Rebirth run] {} {}: {}", boss, leader.getName(), line);
        }
    }

    record Waiting(Boss boss, long until) {
    }

    private static final Map<Integer, Run> byBot = new ConcurrentHashMap<>();
    private static final Set<Run> runs = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Waiting> waiting = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> retryAt = new ConcurrentHashMap<>();
    private static final Object FORM_LOCK = new Object();
    /** Off in unit tests: no beat is scheduled, no warp is made. */
    static volatile boolean live = true;

    private RebirthBossRuns() {
    }

    // ---------------------------------------------------------------- switches

    public static boolean liveRuns() {
        String v = System.getProperty("slums.botRebirth.liveRuns");
        return v == null || Boolean.parseBoolean(v.trim());
    }

    static int maxRuns() {
        return Math.max(0, Integer.getInteger("slums.botRebirth.maxRuns", 2));
    }

    static long runMinutes() {
        return Math.max(1, Integer.getInteger("slums.botRebirth.runMinutes", 40));
    }

    static long waitMs() {
        return Duration.ofHours(Math.max(1, Integer.getInteger("slums.botRebirth.waitHours", 2))).toMillis();
    }

    static long retryMs() {
        return Duration.ofMinutes(Math.max(1, Integer.getInteger("slums.botRebirth.retryMinutes", 30))).toMillis();
    }

    // ---------------------------------------------------------------- queries

    /** True while this bot is in a rebirth boss run: its own life waits (TrainingBot, the director's logouts). */
    public static boolean holds(int botId) {
        return !byBot.isEmpty() && byBot.containsKey(botId);
    }

    /** The run this bot is in, or null. */
    public static Run runOf(int botId) {
        return byBot.get(botId);
    }

    /** Bot-formed and joined runs alive now. */
    public static int liveCount() {
        return runs.size();
    }

    // ---------------------------------------------------------------- seekers

    /** The seeker would join a real player's expedition for this boss (until its next decision, at most a while). */
    public static void waitFor(Character bot, Boss boss, long now) {
        if (bot != null && boss != null) {
            waiting.put(bot.getId(), new Waiting(boss, now + waitMs()));
        }
    }

    /**
     * A seeker wants this boss's item and bots run this boss: it joins a run gathering for it on its channel, or
     * forms one. True when the bot is (now) in a run; false to try again at a later decision.
     */
    public static boolean request(Character bot, Boss boss, long now) {
        if (bot == null || boss == null || !boss.botsRunIt || !liveRuns()) {
            return false;
        }
        if (holds(bot.getId())) {
            return true;
        }
        if (now < retryAt.getOrDefault(bot.getId(), 0L)) {
            return false;
        }
        if (!mayGo(bot, boss)) {
            retryAt.put(bot.getId(), now + retryMs());
            return false;
        }
        synchronized (FORM_LOCK) {
            int channel = channelOf(bot);
            for (Run r : runs) {
                if (r.botLed && r.boss == boss && r.phase == Phase.GATHER && r.world == bot.getWorld()
                        && r.channel == channel && r.crew.size() < MAX_CREW) {
                    add(r, bot, "joins " + r.leader.getName() + "'s run");
                    return true;
                }
            }
            if (botLedCount() >= maxRuns()) {
                return false;
            }
            return form(bot, boss, now, 1.0, null);
        }
    }

    /**
     * Forms a run with this leader and the free regulars it finds, if together they can kill the boss in time (a
     * group that cannot is not formed: players don't start a boss they cannot finish). Null when it is not formed.
     */
    private static Run formRun(Character bot, Boss boss, long now, double hpFraction, String[] why) {
        List<Character> crew = new ArrayList<>();
        crew.add(bot);
        if (live) crew.addAll(pickHelpers(bot, boss, MAX_CREW - 1));
        double minutes = minutesToKill(crew, boss, hpFraction);
        String estimate = Double.isInfinite(minutes) ? "never" : String.format("%.0f min", minutes);
        if (minutes > boss.timeLimitMinutes * FEASIBLE_SHARE) {
            String text = crew.size() + " can kill " + boss + " in " + estimate + " of its " + boss.timeLimitMinutes
                    + ": not tried";
            log.info("[Rebirth run] {} {}", bot.getName(), text);
            if (why != null) why[0] = text;
            retryAt.put(bot.getId(), now + BotRebirth.gatherEveryMs());
            return null;
        }
        int channel = channelOf(bot);
        Run r = new Run(boss, bot, true, bot.getWorld(), channel, now);
        r.hpFraction = hpFraction;
        runs.add(r);
        double full = hpFraction < 1 ? minutesToKill(crew, boss, 1.0) : minutes;
        double dps = 0;
        for (Character c : crew) dps += OffscreenBossRun.live.botDps(c, boss);
        add(r, bot, "forms a run (" + crew.size() + " of them at " + String.format("%,.0f", dps) + " dmg/s, about "
                + estimate + " to kill"
                + (hpFraction < 1 ? ", QA: the boss starts at " + String.format("%.3f", hpFraction * 100) + "% HP; the real fight"
                + " would take about " + String.format("%.0f", full) + " min of " + boss.timeLimitMinutes : "") + ")");
        for (Character helper : crew) {
            if (helper != bot) add(r, helper, helper.getName() + " (lv " + helper.getLevel() + ") comes along");
        }
        schedule(r);
        return r;
    }

    private static boolean form(Character bot, Boss boss, long now, double hpFraction, String[] why) {
        return formRun(bot, boss, now, hpFraction, why) != null;
    }

    /** Only a group expected to kill the boss inside this share of its time limit sets out. */
    static final double FEASIBLE_SHARE = 0.75;

    /** The group's expected kill time (minutes) at the boss's real HP (times the QA's share): the off-screen model. */
    static double minutesToKill(List<Character> crew, Boss boss, double hpFraction) {
        double dps = 0;
        for (Character c : crew) dps += OffscreenBossRun.live.botDps(c, boss);
        long hp = OffscreenBossRun.live.bossHp(boss);
        return dps > 0 && hp > 0 ? hp * hpFraction / dps / 60.0 : Double.POSITIVE_INFINITY;
    }

    /** What lets this bot go for this boss at all: the level floor, the map's gate, a clear left today. */
    static boolean mayGo(Character bot, Boss boss) {
        if (bot.getLevel() < boss.minLevel() || !BotMapGates.mayEnter(boss.gateMap(), bot)) {
            return false;
        }
        if (boss == Boss.CYGNUS && count(bot, RebirthBosses.CYGNUS_EMBLEM) <= 0) {
            return false; // the stronghold's door (in_knights.js) wants the emblem, as a player's does
        }
        return !live || ExpeditionBossLog.hasEntryLeft(bot.getId(), boss.logEntry());
    }

    private static int botLedCount() {
        int n = 0;
        for (Run r : runs) if (r.botLed) n++;
        return n;
    }

    private static void add(Run r, Character bot, String why) {
        r.crew.add(bot);
        byBot.put(bot.getId(), r);
        waiting.remove(bot.getId());
        r.note(bot.getName() + " " + why);
        if (live) {
            warpTo(bot, r.botLed ? null : r.exped, r.boss.recruitMap);
        }
    }

    /** Free strong regulars on the leader's channel: other seekers for the boss first, then the highest levels. */
    private static List<Character> pickHelpers(Character leader, Boss boss, int max) {
        List<Character> seekers = new ArrayList<>();
        List<Character> others = new ArrayList<>();
        int channel = channelOf(leader);
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character c = sm == null ? null : sm.getChr();
            if (c == null || c == leader || !(sm instanceof TrainingBot) || !helperOk(c, leader, channel, boss)
                    || soloMapling.ArtificialPlayer.BotCommitments.inFlight(sm)) {
                continue;
            }
            Waiting w = waiting.get(c.getId());
            (w != null && w.boss == boss ? seekers : others).add(c);
        }
        others.sort(Comparator.comparingInt(Character::getLevel).reversed());
        List<Character> picked = new ArrayList<>();
        for (List<Character> pool : List.of(seekers, others)) {
            for (Character c : pool) {
                if (picked.size() >= max) return picked;
                if (ExpeditionBossLog.hasEntryLeft(c.getId(), boss.logEntry())) picked.add(c);
            }
        }
        return picked;
    }

    /** A helper: same world and channel, strong, free, allowed in. Pure reads of the character. */
    static boolean helperOk(Character c, Character leader, int channel, Boss boss) {
        return BotHelpers.isBot(c) && c.getWorld() == leader.getWorld() && channelOf(c) == channel
                && c.getMap() != null && c.getLevel() >= Math.max(boss.minLevel(), HELPER_MIN_LEVEL)
                && c.getParty() == null && !c.isOwnedAlt() && c.getEventInstance() == null && !holds(c.getId())
                && !BotDeaths.isDead(c.getId()) && BotMapGates.mayEnter(boss.gateMap(), c)
                && (boss != Boss.CYGNUS || count(c, RebirthBosses.CYGNUS_EMBLEM) > 0);
    }

    // ---------------------------------------------------------------- a player's expedition

    /**
     * A real player opened an expedition (AbstractPlayerInteraction.createExpedition): seekers waiting on this boss
     * on that channel come and register, at most {@link #MAX_JOIN_PLAYER_RUN}.
     */
    public static void onPlayerExpedition(Character player, Expedition exped) {
        if (player == null || exped == null || BotHelpers.isBot(player) || !liveRuns() || waiting.isEmpty()) {
            return;
        }
        Boss boss = Boss.byType(exped.getType());
        MapleMap recruit = exped.getRecruitingMap();
        if (boss == null || recruit == null || recruit.getChannelServer() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int channel = recruit.getChannelServer().getId();
        synchronized (FORM_LOCK) {
            Run run = null;
            for (Map.Entry<Integer, Waiting> e : waiting.entrySet()) {
                Waiting w = e.getValue();
                if (now > w.until) {
                    waiting.remove(e.getKey());
                    continue;
                }
                if (w.boss != boss) continue;
                BotSM sm = CharacterStorage.getBotById(e.getKey());
                Character c = sm == null ? null : sm.getChr();
                if (c == null || c.getWorld() != player.getWorld() || channelOf(c) != channel || holds(c.getId())
                        || c.getMap() == null || c.getEventInstance() != null || c.getLevel() < boss.minLevel()
                        || count(c, boss.clearItem) > 0 || c.getParty() != null
                        || boss == Boss.CYGNUS && count(c, RebirthBosses.CYGNUS_EMBLEM) <= 0
                        || !BotMapGates.mayEnter(boss.gateMap(), c) || exped.getMembers().size() >= exped.getMaxSize()
                        || !ExpeditionBossLog.attemptBoss(c.getId(), channel, exped, false)) {
                    continue;
                }
                if (run == null) {
                    run = new Run(boss, player, false, player.getWorld(), channel, now);
                    run.exped = exped;
                }
                exped.addMember(c);   // the recruiter's registration: its quota, the expedition's size
                if (!exped.contains(c)) {
                    continue;
                }
                warpTo(c, exped, boss.recruitMap);
                run.crew.add(c);
                byBot.put(c.getId(), run);
                waiting.remove(c.getId());
                run.note(c.getName() + " joins " + player.getName() + "'s " + boss + " expedition");
                if (run.crew.size() >= MAX_JOIN_PLAYER_RUN) break;
            }
            if (run != null) {
                runs.add(run);
                schedule(run);
            }
        }
    }

    // ---------------------------------------------------------------- the clear (EventInstanceManager.setEventCleared)

    /**
     * The event cleared: each bot in the room is handed the boss's item, as Plunk (or the Pink Bean exit NPC) hands
     * every member theirs; a bot holding one already takes none. Players are untouched (they talk to Plunk).
     */
    public static void onClear(String script, List<Character> players) {
        Boss boss = Boss.byScript(script);
        if (boss == null || players == null) {
            return;
        }
        for (Character c : players) {
            if (c == null || !BotHelpers.isBot(c)) continue;
            Run r = byBot.get(c.getId());
            if (r != null) r.cleared = true;
            if (count(c, boss.clearItem) > 0) continue;
            if (BotHoldings.grantItem(c, new Item(boss.clearItem, (short) 0, (short) 1))) {
                log.info("[Rebirth] {} (lv {}) was handed {} for the {} clear", c.getName(), c.getLevel(),
                        boss.clearItem, boss);
                if (r != null) {
                    r.got.putIfAbsent(c.getId(), "clear reward");
                    r.note(c.getName() + " was handed " + boss.clearItem + " for the clear");
                }
            }
        }
    }

    // ---------------------------------------------------------------- the beat

    private static void schedule(Run r) {
        if (!live) return;
        r.beat = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            if (!r.busy.compareAndSet(false, true)) return;
            ExecutorServiceManager.runAsync(() -> {
                try {
                    step(r, System.currentTimeMillis());
                } catch (RuntimeException e) {
                    log.warn("Rebirth boss run {} failed", r.boss, e);
                    finish(r, "error: " + e);
                } finally {
                    r.busy.set(false);
                }
            });
        }, BEAT_MS, BEAT_MS, TimeUnit.MILLISECONDS);
    }

    static void step(Run r, long now) {
        if (r.phase == Phase.DONE) return;
        for (Character c : r.crew) {
            if (!c.isPresentInWorld()) { // a bot never logs in: isLoggedinWorld dropped every crew at once (b034)
                r.note(c.getName() + " is gone");
                drop(r, c);   // out of the run and out of the hold
            }
        }
        if (r.crew.isEmpty()) {
            finish(r, "the crew is gone");
            return;
        }
        long limit = r.botLed ? runMinutes() : r.boss.timeLimitMinutes + 10L; // a player's run keeps its own clock
        if (now - r.createdAt > Duration.ofMinutes(limit).toMillis() && r.phase != Phase.LOOT) {
            finish(r, "out of time");
            return;
        }
        switch (r.phase) {
            case GATHER -> {
                if (now - r.phaseAt >= GATHER_MS) start(r, now);
            }
            case REGISTERED -> registered(r, now);
            case FIGHT -> fight(r, now);
            case LOOT -> loot(r, now);
            default -> {
            }
        }
    }

    /** The recruiter's steps: the expedition, its members, "Start the fight!". */
    private static void start(Run r, long now) {
        Character leader = r.leader;
        if (!r.crew.contains(leader)) {
            finish(r, "the leader is gone");
            return;
        }
        Channel ch = leader.getClient().getChannelServer();
        if (ch == null || leader.getMapId() != r.boss.recruitMap) {
            finish(r, "the leader is not at the recruiter");
            return;
        }
        if (ch.getExpedition(r.boss.type) != null) {
            finish(r, "channel " + ch.getId() + " has a " + r.boss + " expedition already");
            return;
        }
        Expedition ex = new Expedition(leader, r.boss.type, true, 0, 0);
        if (!ExpeditionBossLog.attemptBoss(leader.getId(), ch.getId(), ex, false)) {
            finish(r, "the leader has no clears left today");
            return;
        }
        if (!ex.addChannelExpedition(ch)) {
            finish(r, "the expedition could not be opened");
            return;
        }
        r.exped = ex;
        for (Character c : r.crew) {
            if (c == leader) continue;
            String said = ex.addMember(c);
            if (!ex.contains(c)) {
                r.note(c.getName() + " could not register: " + said);
                drop(r, c);
            }
        }
        if (ex.getMembers().size() < ex.getMinSize()) {
            endExpedition(r, ch);
            finish(r, "only " + ex.getMembers().size() + " of " + ex.getMinSize() + " registered");
            return;
        }
        EventManager em = ch.getEventSM().getEventManager(r.boss.script);
        if (em == null) {
            endExpedition(r, ch);
            finish(r, "no " + r.boss.script + " event");
            return;
        }
        em.setProperty("leader", leader.getName());
        em.setProperty("channel", String.valueOf(ch.getId()));
        if (!em.startInstance(ex)) {
            endExpedition(r, ch);
            finish(r, "another " + r.boss + " run holds the room");
            return;
        }
        r.eim = leader.getEventInstance();
        if (r.eim == null) {
            finish(r, "the event did not take the leader in");
            return;
        }
        r.note("expedition started with " + r.crewNames());
        enter(r, Phase.FIGHT, now);
    }

    private static void registered(Run r, long now) {
        Expedition ex = r.exped;
        for (Character c : r.crew) {
            EventInstanceManager eim = c.getEventInstance();
            if (eim != null) {
                r.eim = eim;
                r.note("the expedition started; " + c.getName() + " is in");
                enter(r, Phase.FIGHT, now);
                return;
            }
        }
        Channel ch = r.leader.getClient() == null ? null : r.leader.getClient().getChannelServer();
        boolean alive = ex != null && ch != null && ch.getExpedition(r.boss.type) == ex;
        if (!alive) {
            finish(r, "the expedition broke up before it took the bots in");
        } else if (now - r.phaseAt > Duration.ofMinutes(r.boss.type.getRegistrationMinutes() + 1L).toMillis()) {
            finish(r, "the expedition did not take the bots in");
        }
    }

    private static void fight(Run r, long now) {
        EventInstanceManager eim = r.eim;
        if (eim == null || r.cleared || eim.isEventCleared()) {
            r.cleared = true;
            r.note("cleared");
            enter(r, Phase.LOOT, now);
            return;
        }
        if (eim.isEventDisposed()) {
            finish(r, "the instance ended without a clear");
            return;
        }
        if (r.botLed && !r.summoned) {
            MapleMap room = eim.getMapInstance(r.boss.roomMap);
            room.spawnMonsterOnGroundBelow(r.boss.summonMob, r.boss.summonX, r.boss.summonY);
            room.destroyNPC(r.boss.summonNpc);
            room.killMonster(RebirthBosses.SUMMON_PLACEHOLDER_MOB);
            r.summoned = true;
            if (r.hpFraction < 1) {
                for (Monster m : room.getAllMonsters()) {
                    if (m.getId() == r.boss.summonMob && m.getHp() > 1) {
                        long target = Math.max(1, (long) (m.getMaxHp() * r.hpFraction));
                        m.addHp(-(m.getHp() - target)); // QA staging: the fight and its death rules stay real
                    }
                }
            }
            r.note("summoned " + r.boss.summonMob + " in " + r.boss.roomMap
                    + (r.hpFraction < 1 ? " at " + String.format("%.3f", r.hpFraction * 100) + "% HP (QA)" : ""));
        }
        if (r.botLed && r.summoned && now - r.phaseAt > SUMMON_GRACE_MS) {
            boolean any = false;
            for (Monster m : eim.getMapInstance(r.boss.roomMap).getAllMonsters()) {
                if (m != null && m.isAlive()) {
                    any = true;
                    break;
                }
            }
            if (!any && !r.cleared && !eim.isEventCleared()) {
                finish(r, "the boss is not in the room");
                return;
            }
        }
        int inside = 0;
        for (Character c : r.crew) {
            if (c.getEventInstance() != eim) continue;
            inside++;
            if (BotDeaths.isDead(c.getId()) || !c.isAlive()) continue;
            if (!r.botLed) followLeader(r, c, eim);
            fightStep(r, c, now);
        }
        if (inside == 0) {
            finish(r, "no one of the crew is inside any more");
        }
    }

    /** In a player's run: the bot goes to whichever of the instance's rooms its leader is in. */
    private static void followLeader(Run r, Character c, EventInstanceManager eim) {
        Character leader = r.leader;
        if (leader.getEventInstance() == eim && leader.getMapId() != c.getMapId()) {
            GCMovement.stop(c);
            c.changeMap(eim.getMapInstance(leader.getMapId()), 0);
        }
    }

    /** Walk to the nearest live monster and swing at it (FollowerFight's beat, with a blink when stuck). */
    private static void fightStep(Run r, Character c, long now) {
        MapleMap map = c.getMap();
        Point at = c.getPosition();
        if (map == null || at == null) return;
        Monster target = null;
        double best = Double.MAX_VALUE;
        for (Monster m : map.getAllMonsters()) {
            if (m == null || !m.isAlive() || m.getPosition() == null || !BotAttackDriver.botMayTarget(m)) continue;
            double d = at.distanceSq(m.getPosition());
            if (d < best) {
                best = d;
                target = m;
            }
        }
        if (target == null) {
            r.stuckSince.remove(c.getId());
            return;
        }
        Point mob = target.getPosition();
        int reach = Math.max(60, (int) (BotAttackDriver.attackReachX(c) * 0.8));
        int dx = mob.x - at.x;
        if (Math.abs(dx) > reach || Math.abs(mob.y - at.y) > 200) {
            long since = r.stuckSince.computeIfAbsent(c.getId(), k -> now);
            if (now - since >= STUCK_MS) {
                GCMovement.stop(c);
                c.setPosition(new Point(mob.x - Integer.signum(dx == 0 ? 1 : dx) * (reach / 2), mob.y));
                r.stuckSince.remove(c.getId());
            } else if (!GCMovement.isMoving(c)) {
                GCMovement.move(c, mob.x - Integer.signum(dx == 0 ? 1 : dx) * (reach / 2), mob.y);
            }
            return;
        }
        BotAttackDriver.AttackResult hit = BotAttackDriver.botAttack(c);
        boolean blocked = hit != null && !hit.hit() && hit.reason() != null && hit.reason().startsWith("no targetable");
        if (!blocked) {
            r.stuckSince.remove(c.getId());
            return;
        }
        long since = r.stuckSince.computeIfAbsent(c.getId(), k -> now); // in reach but on another ledge
        if (now - since >= STUCK_MS) {
            GCMovement.stop(c);
            c.setPosition(new Point(mob.x, mob.y));
            r.stuckSince.remove(c.getId());
        }
    }

    /** After the clear: each bot picks up the key items lying in its room that it lacks (the boss's floor drops). */
    private static void loot(Run r, long now) {
        boolean left = false;
        for (Character c : r.crew) {
            MapleMap map = c.getMap();
            if (map == null) continue;
            for (MapObject o : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                    List.of(MapObjectType.ITEM))) {
                MapItem drop = (MapItem) o;
                if (drop.getItem() == null || drop.getMeso() > 0) continue;
                int id = drop.getItemId();
                if (!BotRebirth.keeps(id) || count(c, id) > 0) continue;
                if (!DropCommands.botCanLoot(c, drop)) {
                    left = true;
                    continue;
                }
                DropCommands.botLootSingleDrop(c, drop);
                if (count(c, id) > 0) {
                    r.got.putIfAbsent(c.getId(), "floor");
                    r.note(c.getName() + " looted " + id + " off the floor");
                }
            }
        }
        if (!left || now - r.phaseAt >= LOOT_MS) {
            finish(r, r.cleared ? "cleared" : "ended");
        }
    }

    private static void enter(Run r, Phase p, long now) {
        r.phase = p;
        r.phaseAt = now;
    }

    // ---------------------------------------------------------------- the end

    /** Everyone out of the instance, the expedition closed if it is ours, every bot back to its own life. */
    static void finish(Run r, String result) {
        if (r.phase == Phase.DONE) return;
        r.phase = Phase.DONE;
        r.result = result;
        r.note("done: " + result);
        ScheduledFuture<?> beat = r.beat;
        if (beat != null) beat.cancel(false);
        for (Character c : r.crew) {
            if (live) leave(r, c);
            drop(r, c);
        }
        if (live && r.botLed && r.exped != null && r.exped.isRegistering()) {
            Channel ch = r.leader.getClient() == null ? null : r.leader.getClient().getChannelServer();
            if (ch != null) endExpedition(r, ch);
        }
        if (!r.cleared) {
            long at = System.currentTimeMillis() + retryMs();
            for (Character c : r.crew) retryAt.put(c.getId(), at);
        }
        runs.remove(r);
        lastRun = r;
    }

    private static void leave(Run r, Character c) {
        EventInstanceManager eim = c.getEventInstance();
        if (eim != null && (eim == r.eim || r.eim == null)) {
            try {
                GCMovement.stop(c);
                eim.exitPlayer(c);   // the script's playerExit: out to the boss's exit map
            } catch (RuntimeException e) {
                log.warn("Rebirth run: {} could not leave the {} instance by its script", c.getName(), r.boss, e);
            }
        }
        try {
            if (c.getEventInstance() != null && c.getEventInstance() == r.eim) {
                c.setEventInstance(null);
            }
            if (isInstanceMap(c, r)) {
                Channel ch = c.getClient().getChannelServer();
                if (ch != null) c.changeMap(ch.getMapFactory().getMap(r.boss.exitMap), 0);
            }
        } catch (RuntimeException e) {
            log.warn("Rebirth run: {} could not be walked out of the {} instance", c.getName(), r.boss, e);
        }
    }

    private static boolean isInstanceMap(Character c, Run r) {
        MapleMap m = c.getMap();
        return m != null && r.eim != null && m.getEventInstance() == r.eim;
    }

    private static void drop(Run r, Character c) {
        byBot.remove(c.getId(), r);
        if (r.phase != Phase.DONE) r.crew.remove(c);
    }

    private static void endExpedition(Run r, Channel ch) {
        Expedition ex = r.exped;
        if (ex == null) return;
        try {
            ex.dispose(false);
            ex.removeChannelExpedition(ch);
        } catch (RuntimeException e) {
            log.warn("Rebirth run: the {} expedition could not be closed", r.boss, e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * By Hyper Teleport Rock to the recruiter (a player's expedition: his recruiting map). Cygnus' recruiter is inside
     * the stronghold, which a player walks into from Future Ereve with the emblem (mayGo checked both); the walk is
     * taken as made.
     */
    private static void warpTo(Character c, Expedition exped, int mapId) {
        try {
            MapleMap to = exped != null ? exped.getRecruitingMap() : null;
            if (to == null) {
                Channel ch = c.getClient().getChannelServer();
                to = ch == null ? null : ch.getMapFactory().getMap(mapId);
            }
            if (to == null || c.getMap() == to) return;
            GCMovement.stop(c);
            c.changeMap(to, 0);
        } catch (RuntimeException e) {
            log.warn("Rebirth run: {} could not go to map {}", c.getName(), mapId, e);
        }
    }

    private static int channelOf(Character c) {
        try {
            return c.getClient() == null ? -1 : c.getClient().getChannel();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static int count(Character c, int itemId) {
        return c.getInventory(InventoryType.ETC).countById(itemId);
    }

    // ---------------------------------------------------------------- QA and tests

    private static volatile Run lastRun;

    /** QA: the last run that ended. */
    public static Run lastRun() {
        return lastRun;
    }

    /**
     * QA: forms a run for this bot now (the retry wait skipped), the summoned boss at {@code hpFraction} of its HP so
     * the check fits in minutes; the run, or null with why in {@code why[0]}.
     */
    public static Run startForQa(Character bot, Boss boss, double hpFraction, String[] why) {
        retryAt.remove(bot.getId());
        if (!liveRuns()) {
            why[0] = "-Dslums.botRebirth.liveRuns=false";
            return null;
        }
        Run held = runOf(bot.getId());
        if (held != null) {
            return held;
        }
        if (!mayGo(bot, boss)) {
            why[0] = "the bot may not go (level, gate, clears left)";
            return null;
        }
        synchronized (FORM_LOCK) {
            if (botLedCount() >= maxRuns()) {
                why[0] = "no run slot (" + liveCount() + " runs, at most " + maxRuns() + ")";
                return null;
            }
            return formRun(bot, boss, System.currentTimeMillis(), hpFraction, why);
        }
    }

    /** QA: ends this run now (everyone out, every bot back to its own life). */
    public static void endForQa(Run r, String why) {
        if (r != null) finish(r, why);
    }

    static void resetForTest() {
        byBot.clear();
        runs.clear();
        waiting.clear();
        retryAt.clear();
        lastRun = null;
        live = false;
    }

    static void liveForTest(boolean on) {
        live = on;
    }

    static boolean waitingOn(int botId, Boss boss) {
        Waiting w = waiting.get(botId);
        return w != null && w.boss == boss;
    }
}
