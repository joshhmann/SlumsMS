package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.NPC;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.WatchGate;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 1.6.2 ticket 159: a bot a player can see walks to its job instructor and advances there, instead of {@link
 * BotProgression} changing its job on the spot. Switch: {@code -Dsolomapling.jobErrand=true} (default off).
 *
 * <p>How it works. {@link BotProgression} asks {@link #gateFor} before every advancement. With the switch off the gate
 * is {@code null} and nothing changes. With it on, a bot that is watched (a real player is on its map instance, the
 * same test {@link WatchGate} uses for "do not snap in front of a player") and whose instructor is known and reachable
 * is held at its job: {@link #begin} starts a trip (the ordinary travel system: ferries, fares, the world graph) to the
 * instructor's town, then a walk to the NPC. On arrival the held advancement happens at once by the normal path
 * ({@link BotProgression#afterErrand}) and the bot says a short line. Everything else keeps today's instant advance:
 * <ul>
 *   <li>offscreen bots (nobody to see them walk);</li>
 *   <li>jobs without a mapped instructor (Cygnus, Aran, 3rd and 4th job; the Leafre and El Nath trips are quests and
 *       tests a bot does not run);</li>
 *   <li>a bot with no route to the town, or one that cannot pay the fares on it ("blocked", checked up front);</li>
 *   <li>a trip that fails, or one still unfinished after {@link #timeoutMs()}: the errand is dropped, the trip is
 *       cancelled and the bot advances where it stands (upstream's two ferry/fare deadlocks end here).</li>
 * </ul>
 * While an errand runs the bot's grind life waits ({@link #holds}), like a boss run's.
 *
 * <p>Ported in design from nutnnut/Cosmic dev (BotAutopilotManager's job-change detour errand and its
 * "unreachable route" give-up), see docs/PROVENANCE.md; the code is our own, on our travel system.
 */
public final class JobErrand {

    private static final Logger log = LoggerFactory.getLogger(JobErrand.class);

    public static final String PROP = "solomapling.jobErrand";
    public static final String TIMEOUT_PROP = "solomapling.jobErrand.timeoutMs";
    /** How long an errand may take, from its start to the bot standing at the NPC. */
    static final long DEFAULT_TIMEOUT_MS = 6 * 60_000L;
    private static final int ALL = -1;

    /** The NPC that advances a job, where he stands. */
    public record Instructor(int npcId, int mapId, String town) {
    }

    private static final Instructor WARRIOR = new Instructor(1022000, 102000003, "Perion");
    private static final Instructor MAGICIAN = new Instructor(1032001, 101000003, "Ellinia");
    private static final Instructor BOWMAN = new Instructor(1012100, 100000201, "Henesys");
    private static final Instructor THIEF = new Instructor(1052001, 103000003, "Kerning City");
    private static final Instructor PIRATE = new Instructor(1090000, 120000101, "Nautilus");

    private static final String[] LINES = {"finally, job advanced!", "new job get", "advanced!! lets go", "job advance done :D",
            "ok im a real one now", "that took a while but done", "advanced!"};

    /** How the errand moves the bot; replaced in tests. */
    public interface Mover {
        /** A route to the town exists and the bot can pay the fares on it. */
        boolean canReach(Character bot, int mapId);

        /** Travel to a map; {@code done} hears whether the bot arrived. */
        void travel(Character bot, int mapId, Consumer<Boolean> done);

        /** The map the bot stands on, or -1. */
        int mapOf(Character bot);

        /** Walk to the NPC on the bot's current map; {@code done} hears true on arrival. */
        void walkToNpc(Character bot, int npcId, Consumer<Boolean> done);

        /** Abandon whatever the bot is doing for the errand. */
        void cancel(Character bot);
    }

    /** Runs a task later; replaced in tests. */
    public interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    private static final ScheduledExecutorService POOL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "job-errand");
        t.setDaemon(true);
        return t;
    });

    private static final Mover LIVE = new Mover() {
        @Override
        public boolean canReach(Character bot, int mapId) {
            if (bot.getMap() == null) {
                return false;
            }
            if (bot.getMapId() == mapId) {
                return true;
            }
            List<Integer> route = GCMovement.route(bot, mapId);
            return route != null && GCMovement.routeFare(bot.getMapId(), route, bot.getJob().getId()) <= bot.getMeso();
        }

        @Override
        public int mapOf(Character bot) {
            return bot.getMap() == null ? -1 : bot.getMapId();
        }

        @Override
        public void travel(Character bot, int mapId, Consumer<Boolean> done) {
            GCMovement.travel(bot, mapId, done);
        }

        @Override
        public void walkToNpc(Character bot, int npcId, Consumer<Boolean> done) {
            NPC npc = bot.getMap() == null ? null : bot.getMap().getNPCById(npcId);
            if (npc == null) {
                done.accept(false);
                return;
            }
            Point at = npc.getPosition();
            GCMovement.move(bot, at.x + ThreadLocalRandom.current().nextInt(-40, 41), at.y, () -> done.accept(true));
        }

        @Override
        public void cancel(Character bot) {
            GCMovement.cancelTravel(bot);
            GCMovement.stop(bot);
        }
    };

    private static volatile Mover mover = LIVE;
    private static volatile Scheduler scheduler = (task, ms) -> POOL.schedule(() -> {
        try {
            task.run();
        } catch (Throwable t) {
            log.warn("job errand task failed", t);
        }
    }, ms, TimeUnit.MILLISECONDS);
    private static volatile java.util.function.Predicate<Character> watched = WatchGate::watched;
    private static volatile java.util.function.BooleanSupplier switchOn = JobErrand::propOn;
    private static volatile Consumer<Character> celebrate = bot ->
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot, LINES[ThreadLocalRandom.current().nextInt(LINES.length)]);

    private static final class Errand {
        final Character bot;
        final int target;
        final Instructor where;
        volatile boolean ended;

        Errand(Character bot, int target, Instructor where) {
            this.bot = bot;
            this.target = target;
            this.where = where;
        }
    }

    private static final Map<Integer, Errand> PENDING = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> ALLOW = new ConcurrentHashMap<>();
    private static final AtomicLong STARTED = new AtomicLong();
    private static final AtomicLong ARRIVED = new AtomicLong();
    private static final AtomicLong FELL_BACK = new AtomicLong();
    private static final AtomicLong BLOCKED = new AtomicLong();

    private JobErrand() {
    }

    private static boolean propOn() {
        return "true".equalsIgnoreCase(System.getProperty(PROP, "false").trim());
    }

    /** On only with {@code -Dsolomapling.jobErrand=true}. */
    public static boolean enabled() {
        return switchOn.getAsBoolean();
    }

    static long timeoutMs() {
        try {
            return Math.max(1_000L, Long.parseLong(System.getProperty(TIMEOUT_PROP, "").trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_MS;
        }
    }

    /** The instructor that advances a bot into {@code targetJob}, or null when it advances instantly. */
    public static Instructor instructorFor(int targetJob) {
        if (targetJob < 100 || targetJob >= 600) {
            return null;   // beginners, Cygnus, Aran, 5th tier and the special jobs
        }
        int tier = BotBuild.tier(targetJob);
        if (tier != 1 && tier != 2) {
            return null;   // 3rd job is a quest and a test, 4th job is Leafre: not walked
        }
        return switch (targetJob / 100) {
            case 1 -> WARRIOR;
            case 2 -> MAGICIAN;
            case 3 -> BOWMAN;
            case 4 -> THIEF;
            case 5 -> PIRATE;
            default -> null;
        };
    }

    /** True while this bot is walking to its instructor: its own life waits. */
    public static boolean holds(int botId) {
        return !PENDING.isEmpty() && PENDING.containsKey(botId);
    }

    /** One line for {@code !economy}-style status and the QA log. */
    public static String stats() {
        return "jobErrand started=" + STARTED.get() + " arrived=" + ARRIVED.get() + " fellBack=" + FELL_BACK.get()
                + " blocked=" + BLOCKED.get() + " active=" + PENDING.size();
    }

    public static void forget(int botId) {
        Errand e = PENDING.remove(botId);
        if (e != null) {
            e.ended = true;
        }
        ALLOW.remove(botId);
    }

    // ---------------------------------------------------------------- the gate

    /** The advancement gate for this bot's next settle, or null when advancements are instant (switch off). */
    static BotBuild.AdvanceGate gateFor(Character bot) {
        if (!enabled()) {
            return null;
        }
        return next -> mayAdvance(bot, next);
    }

    /** False holds the bot at its job (an errand runs); true lets it advance now. */
    private static boolean mayAdvance(Character bot, int next) {
        int id = bot.getId();
        Integer allowed = ALLOW.get(id);
        if (allowed != null && (allowed == ALL || allowed == next)) {
            return true;
        }
        Errand running = PENDING.get(id);
        if (running != null) {
            return false;
        }
        Instructor where = instructorFor(next);
        if (where == null || bot.isOwnedAlt() || !watched.test(bot)) {
            return true;
        }
        if (!mover.canReach(bot, where.mapId())) {
            BLOCKED.incrementAndGet();
            log.debug("Bot {} ({}) cannot reach {} for job {}: instant advance", id, bot.getName(), where.town(), next);
            return true;
        }
        begin(bot, next, where);
        return false;
    }

    // ---------------------------------------------------------------- the errand

    private static void begin(Character bot, int target, Instructor where) {
        Errand e = new Errand(bot, target, where);
        if (PENDING.putIfAbsent(bot.getId(), e) != null) {
            return;
        }
        STARTED.incrementAndGet();
        log.debug("Bot {} ({}) goes to {} to advance to {}", bot.getId(), bot.getName(), where.town(), target);
        // Never start the trip inside the caller's settle: a bot already on the map answers at once.
        scheduler.schedule(() -> {
            if (e.ended) {
                return;
            }
            mover.travel(bot, where.mapId(), ok -> {
                if (e.ended) {
                    return;
                }
                if (!ok || mover.mapOf(bot) != where.mapId()) {
                    fallBack(e, "trip failed");
                    return;
                }
                mover.walkToNpc(bot, where.npcId(), reached -> {
                    if (reached) {
                        arrive(e);
                    } else {
                        fallBack(e, "no way to the instructor");
                    }
                });
            });
        }, 0);
        scheduler.schedule(() -> fallBack(e, "timed out"), timeoutMs());
    }

    private static void arrive(Errand e) {
        if (!end(e)) {
            return;
        }
        ARRIVED.incrementAndGet();
        advance(e, true);
    }

    private static void fallBack(Errand e, String why) {
        if (!end(e)) {
            return;
        }
        FELL_BACK.incrementAndGet();
        log.debug("Bot {} ({}) gave up the job errand ({}): instant advance", e.bot.getId(), e.bot.getName(), why);
        try {
            mover.cancel(e.bot);
        } catch (RuntimeException ex) {
            log.debug("cancel failed", ex);
        }
        advance(e, false);
    }

    /** Ends the errand once; false when something else already did. */
    private static boolean end(Errand e) {
        synchronized (e) {
            if (e.ended) {
                return false;
            }
            e.ended = true;
        }
        PENDING.remove(e.bot.getId(), e);
        return true;
    }

    private static void advance(Errand e, boolean celebrateIt) {
        Character bot = e.bot;
        int before = bot.getJob().getId();
        ALLOW.put(bot.getId(), e.target);
        try {
            BotProgression.afterErrand(bot);
        } finally {
            ALLOW.remove(bot.getId());
        }
        if (celebrateIt && bot.getJob().getId() != before) {
            try {
                celebrate.accept(bot);
            } catch (RuntimeException ex) {
                log.debug("celebration failed", ex);
            }
        }
    }

    // ---------------------------------------------------------------- tests

    public static void useForTest(Mover m, Scheduler s, java.util.function.Predicate<Character> w,
                           java.util.function.BooleanSupplier on, Consumer<Character> c) {
        mover = m == null ? LIVE : m;
        scheduler = s == null ? (task, ms) -> POOL.schedule(task, ms, TimeUnit.MILLISECONDS) : s;
        watched = w == null ? WatchGate::watched : w;
        switchOn = on == null ? JobErrand::propOn : on;
        celebrate = c == null ? bot -> { } : c;
        PENDING.clear();
        ALLOW.clear();
    }
}
