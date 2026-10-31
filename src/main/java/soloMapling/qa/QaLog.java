package soloMapling.qa;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The QA suite's event log (test server only, {@link QaSuite}). Game code that a {@code !qa} scenario wants to watch
 * calls it like this:
 * <pre>
 *   if (QaLog.on(QaLog.Topic.ROPE)) {
 *       QaLog.map(QaLog.Topic.ROPE, map.getId(), bot.getName() + " stalled on a rope at " + pos);
 *   }
 * </pre>
 * {@link #on} is one volatile read and a bit test, false whenever no running scenario listens to that topic, so the
 * hooks cost nothing on live (where the suite is never registered) and nothing on the test server between runs. The
 * message string is only built inside the {@code if}.
 * <p>
 * A line goes to every running scenario that listens to its topic and matches its scope: {@link #map} lines only to
 * runs that watch that map (or every map), {@link #owner} lines only to the run of that GM, {@link #any} lines to all.
 * Each run writes its own file, {@code logs/qa/<id>-<UTC time>.log} ({@link QaRun}).
 */
public final class QaLog {

    /** What a log line is about. A scenario lists the topics it wants; nothing else reaches its file. */
    public enum Topic {
        OWNED_ALT,      // ticket 13: lifecycle reservations, synchronous saves and login takeover
        ATTACK,         // bot attack packets: skill, pose, weapon, targets (bk-1)
        GUILD,          // guild creation steps (gd-1)
        FM_BUY,         // bot purchases from player shops and the seller cap (fm-1)
        ROPE,           // rope stalls and the rope stall watchdog (ld-1)
        HIDE,           // bots skipping a hidden GM, hide toggles (gh-1)
        MOB_CONTROL,    // which character controls each monster (gh-2)
        BOT_CHAT,       // every line a bot says on a watched map (gd-1, gh-1)
        MERCHANT,       // hired merchant closes, Fredrick retrievals (np-2, np-3)
        STORAGE,        // storage opened, items stored and taken out (np-1)
        EQUIP,          // equips put on, pet equips included (cs-2)
        SCROLL,         // scroll results (gn-2)
        CHANNEL_LOAD,   // channel load bars sent to the login screen (wd-1)
        IDLE,           // idle bots: hits taken while idle, moves to a safe spot (id-1)
        SLIDE,          // the GM's own attacks: how far the character moved during each (mv-1)
        MAP_ENTRY,      // a GM's map entries: time, packets, bytes, deferred spawns, the map's objects (pe-1)
        BOUNCE,         // bounce / Flash Jump uses the server saw on a watched map, refusals, zone pushes (jq-1)
        GACHA,          // gachapon rolls by players and bots, notices, bot ticket buys (ga-1, ga-2)
        WEALTH,         // bot wealth ceiling: intake cut, spending, decay (ga-2)
        AFFINITY,       // bot<->player affinity: events, state changes, and what bots do about it (af-1..af-3)
        COUPLE,         // bot couples and their weddings: forming, hops, parties, chairs, rings, the ceremony (cp-1, we-1)
        BOT_GUILD,      // bot guilds: joins, invites, applications, guild chat, recruiting, jabs, boss runs, quits (gu-1..gu-7)
        COMBAT,         // 1.5.0 rc3: skill choice (mobs in reach, AoE/single, boss, sloppy), buffs, facing, touch steps, pacing (cb-1, cb-2)
        HABITS,         // 1.5.0 rc3: per-bot loot style, potions, restock trips, deaths and the walk back (cb-3)
        MANNERS,        // 1.5.0 rc3: neutral bots yielding a player's platform, moving on, "cc?" (cb-4)
        SPREAD,         // 1.5.0 rc3: spot claims per channel, personal space, rope waits (sp-1)
        TRADE,          // 1.5.0 rc3 economy: bot shops, direct trades and haggles, gifts, price samples, tax (tr-1..tr-6)
        PETS,           // 1.5.0 rc4: bot pets: who has which, pet pickups (pt-1)
        RING,           // 1.5.0: Cash Shop couple/friendship rings: prompts, refusals, both ring records (pr-1, pr-2)
        ROMANCE,        // 1.5.0 bot drama: flirts, dates, proposals, weddings, breakups, gossip (rm-1..rm-4)
        RIVALRY,        // 1.5.0 bot drama: roasts, KS wars, callouts, revenge follows (rv-1..rv-4)
        GUILD_WAR,      // 1.5.0 bot drama: guild wars, turf, poaching, alliances and betrayals (gw-1..gw-4)
        CONVO,          // ticket 59: chat sense, gates, actions, personas, timing, bank, router, threads, ratings (cv-1..cv-7)
        NPC,            // ticket 69: every dialog line a pretend player receives from an NPC (vb-1..vb-7)
        AUTOQA,         // auto-QA: health sweep results and the runner's check verdicts (docs/ops/qa-suite.md "Auto-QA")
        PQ,             // party quests: entry and stage map data, who is in the instance and OPQ bots' lobby
                        //   lines, joins/leaves and stage transitions (pq-1, pq-2) - appended, bits are stable
        LIKENESS,       // 1.6.0 player likeness: AFK campers, walks instead of snaps while watched, messy chat (lk-1)
        SOCIAL_GATE,    // 1.6.0 S5: every unprompted bot->player act's yes/no and why, the started-it ledger (sg-1, sg-2)
    MEMORY,         // 1.6.0 S3: episodes, slots and scams bots remember about players, PQ clears and trades (me-1, me-2)
        WORLD_EVENT,    // 1.6.0 world events: plan, warnings, starts, bots joining and dying, the boss, rewards, gossip (ev-1, ev-2)
        MAP_CHANGE,     // ticket 170 QA event feed: a real player entered a map ("MAP <name> -> <mapId> ch <n>")
        CHAT,           // ticket 170 QA event feed: map chat said by a player or a bot, whispers to and from a player
        MECHANICS,      // ticket 170 QA event feed: every "RUN ..." line a mechanics run (KPQ) reports, any starter
        STAGING;        // b140: a !qa scene staged for the caller ("STAGE FOLLOWER cid=..."), chars = the GM's id

        final long bit = 1L << ordinal(); // a long: 34 topics no longer fit an int
    }

    private static volatile long mask;
    private static final List<QaRun> runs = new CopyOnWriteArrayList<>();
    private static final Map<String, Long> lastByKey = new ConcurrentHashMap<>();

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final List<SinkHandle> sinks = new CopyOnWriteArrayList<>();
    private static final int[] NO_CHARS = new int[0];

    private QaLog() {
    }

    /**
     * Auto-QA (soloMapling.autoqa): hears every line any run writes to its log (events, STAGE, STEP, STOP, CLEANUP,
     * SUMMARY), with the run's GM and scenario. Called on the thread that wrote the line; must be quick.
     */
    public interface Listener {
        void line(int gmId, String scenarioId, String kind, String message);
    }

    /**
     * Ticket 170: hears every hook line of the topics it asked for, whatever runs (the TEST QA event feed,
     * {@code soloMapling.qa.feed.QaEventFeed}). While a sink wants a topic, that topic is on, like a running scenario.
     * {@code charIds} are the characters the line is about ({@link #owner}: the owner; {@link #chars}: those named;
     * {@link #map}/{@link #any}: none). Called on the hook's thread; must be quick and never throw.
     */
    public interface Sink {
        void line(Topic topic, int mapId, int[] charIds, String message);
    }

    /** One registered sink; {@link #topics} changes what it hears (and so the mask), {@link #close} removes it. */
    public static final class SinkHandle {
        private final Sink sink;
        private volatile long bits;

        private SinkHandle(Sink sink) {
            this.sink = sink;
        }

        public void topics(java.util.Collection<Topic> topics) {
            long b = 0;
            for (Topic t : topics) {
                b |= t.bit;
            }
            bits = b;
            synchronized (QaLog.class) {
                recompute();
            }
        }

        public void close() {
            synchronized (QaLog.class) {
                sinks.remove(this);
                recompute();
            }
        }
    }

    /** Adds a sink hearing no topic yet; set them with {@link SinkHandle#topics}. */
    public static synchronized SinkHandle sink(Sink sink) {
        SinkHandle h = new SinkHandle(sink);
        sinks.add(h);
        return h;
    }

    /** Adds a listener; the returned Runnable removes it. */
    public static Runnable listen(Listener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    static void toListeners(int gmId, String scenarioId, String kind, String message) {
        for (Listener l : listeners) {
            try {
                l.line(gmId, scenarioId, kind, message);
            } catch (RuntimeException ignored) {
                // a listener never breaks the hook that logged
            }
        }
    }

    /** True while a running scenario listens to this topic. The only call a hook makes when nothing runs. */
    public static boolean on(Topic topic) {
        return (mask & topic.bit) != 0;
    }

    /** True while any scenario runs. */
    public static boolean active() {
        return mask != 0 || !runs.isEmpty();
    }

    /** A line about something on this map (0 = no map): reaches runs that watch the map, or every map. */
    public static void map(Topic topic, int mapId, String message) {
        dispatch(topic, mapId, 0, message);
    }

    /** A line about this character: reaches only the run this character (a GM) started. */
    public static void owner(Topic topic, int characterId, String message) {
        dispatch(topic, 0, characterId, message);
    }

    /** A line for every run listening to the topic. */
    public static void any(Topic topic, String message) {
        dispatch(topic, 0, 0, message);
    }

    /**
     * Rate limit for a hook that would fire every tick (a bot skipping a hidden GM): true at most once per
     * {@code intervalMs} for the key. Call it inside the {@link #on} check.
     */
    public static boolean every(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = lastByKey.get(key);
        if (last != null && now - last < intervalMs) {
            return false;
        }
        lastByKey.put(key, now);
        return true;
    }

    /**
     * Ticket 170: a line about this map (0 = none) and these characters. Runs hear it as a {@link #map} line; sinks
     * hear it once with every character named (a whisper's sender and receiver, a KPQ run's real players).
     */
    public static void chars(Topic topic, int mapId, int[] charIds, String message) {
        if (!on(topic)) {
            return;
        }
        for (QaRun run : runs) {
            if (run.accepts(topic, mapId, 0)) {
                run.event(topic, message);
            }
        }
        toSinks(topic, mapId, charIds == null ? NO_CHARS : charIds, message);
    }

    private static void dispatch(Topic topic, int mapId, int ownerId, String message) {
        if (!on(topic)) {
            return;
        }
        for (QaRun run : runs) {
            if (run.accepts(topic, mapId, ownerId)) {
                run.event(topic, message);
            }
        }
        toSinks(topic, mapId, ownerId == 0 ? NO_CHARS : new int[]{ownerId}, message);
    }

    private static void toSinks(Topic topic, int mapId, int[] charIds, String message) {
        for (SinkHandle h : sinks) {
            if ((h.bits & topic.bit) != 0) {
                try {
                    h.sink.line(topic, mapId, charIds, message);
                } catch (RuntimeException ignored) {
                    // a sink never breaks the hook that logged
                }
            }
        }
    }

    static synchronized void attach(QaRun run) {
        runs.add(run);
        recompute();
    }

    static synchronized void detach(QaRun run) {
        runs.remove(run);
        recompute();
        if (runs.isEmpty()) {
            lastByKey.clear();
        }
    }

    private static void recompute() {
        long m = 0;
        for (QaRun run : runs) {
            for (Topic t : run.topics()) {
                m |= t.bit;
            }
        }
        for (SinkHandle h : sinks) {
            m |= h.bits;
        }
        mask = m;
    }

    /* Tests: forget every run and sink. */
    static synchronized void reset() {
        runs.clear();
        sinks.clear();
        lastByKey.clear();
        mask = 0;
    }
}
