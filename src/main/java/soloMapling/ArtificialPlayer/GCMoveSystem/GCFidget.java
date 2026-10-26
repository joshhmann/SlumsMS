package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import soloMapling.server.BotShutdown;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/*
 * Idle auto-fidget: gives an otherwise-stationary bot some life. While the bot is idle (no
 * move / follow / travel), this coarse poller periodically does one small random action —
 * turn around, duck, a small wander or (rarely) a hop in place — anchored to its rest spot, returning home
 * if it drifts. It stands down the instant a real command takes over (so it never fights movement).
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCFidget {
    private GCFidget() {
    }

    private static final long POLL_MS = 1500;
    private static final long MIN_REST_MS = 5_000;   // gap between fidget actions
    private static final long MAX_REST_MS = 14_000;
    private static final int WANDER_PX = 45;         // small nudge radius around the anchor
    private static final int RETURN_DIST = 70;       // drifted past this -> walk back to the anchor
    private static final int DUCK_MIN_MS = 900;
    private static final int DUCK_MAX_MS = 1900;

    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "gcfidget-poll");
        t.setDaemon(true);
        return t;
    });

    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final class Session {
        final Character bot;
        ScheduledFuture<?> task;
        Point base;                 // rest anchor (null = re-anchor at the next idle position)
        long nextActionAtMs;
        boolean fidgetMoveActive;   // our own nudge/return move is in flight (vs a user command)

        Session(Character bot) {
            this.bot = bot;
        }
    }

    /** A wander may follow its ledge up or down a slope by this much (px); more is another ledge, or none. */
    static final int WANDER_MAX_DY = 26;

    /**
     * 1.6.0 foothold regression: where a wander nudge of {@code dx} from {@code base} goes - the ground at that x on the
     * anchor's own ledge, or null when there is none (past the ledge's end, or a step onto another terrace). The
     * nudge used to target (base.x + dx, base.y) as is: near a ledge's end that point is in the air, and an
     * unobserved bot's coarse arrival put it there (Henesys' 60 px terraces: dy = +-60 in the health sweep).
     */
    static Point wanderTarget(server.maps.MapleMap map, Point base, int dx) {
        if (map == null || base == null) {
            return null;
        }
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(base.x + dx, base.y));
        if (ground == null || Math.abs(ground.y - base.y) > WANDER_MAX_DY) {
            return null;
        }
        return ground;
    }

    enum Action { TURN, DUCK, HOP, WANDER }

    /**
     * 1.6.0 (owner, FM on TEST): hops now come in bursts right after a bot's chat line ({@link AttentionHops}). A lone
     * idle hop was one action in four, which read as random pogoing with no link to chat; now it is rare.
     */
    static final double LONE_HOP_CHANCE = 0.04;

    static Action pickAction(java.util.Random rng) {
        if (rng.nextDouble() < LONE_HOP_CHANCE) {
            return Action.HOP;
        }
        return switch (rng.nextInt(3)) {
            case 0 -> Action.TURN;
            case 1 -> Action.DUCK;
            default -> Action.WANDER;
        };
    }

    static void start(Character bot) {
        if (bot == null) {
            return;
        }
        cancel(bot);
        Session s = new Session(bot);
        s.task = POOL.scheduleAtFixedRate(
                () -> {
                    try {
                        tick(s);
                    } catch (Throwable ignored) {
                        // a thrown poll would cancel the periodic task
                    }
                }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        SESSIONS.put(bot.getId(), s);
    }

    static void cancel(Character bot) {
        if (bot == null) {
            return;
        }
        Session s = SESSIONS.remove(bot.getId());
        if (s != null && s.task != null) {
            s.task.cancel(false);
        }
    }

    static boolean isActive(Character bot) {
        return bot != null && SESSIONS.containsKey(bot.getId());
    }

    private static void tick(Session s) {
        if (BotShutdown.isQuiesced()) {
            cancel(s.bot); // Ticket 09: no fidget against a map that is being disposed
            return;
        }
        Character bot = s.bot;
        if (bot == null || bot.getMap() == null || !GCMovement.isEnabled(bot)) {
            cancel(bot);
            return;
        }

        boolean busy = GCMovement.isMoving(bot) || GCMovement.isTraveling(bot) || GCMovement.isFollowing(bot);
        if (busy && !s.fidgetMoveActive) {
            // A real command is driving the bot — stand down and re-anchor wherever it ends up.
            s.base = null;
            return;
        }
        if (busy) {
            return; // our own nudge/return is still in flight
        }
        s.fidgetMoveActive = false;

        Point pos = bot.getPosition();
        if (s.base == null) {
            s.base = new Point(pos);
        }

        long now = System.currentTimeMillis();
        if (soloMapling.ArtificialPlayer.BotConversation.isHoldingChat(bot)) {
            // Owner report 2026-10-24: no hop or wander-nudge mid-conversation; re-anchor where the bot stands
            // (the chat pair's small step may have moved it) and look again on the normal beat.
            s.base = new Point(pos);
            return;
        }
        if (now < s.nextActionAtMs) {
            return;
        }
        s.nextActionAtMs = now + ThreadLocalRandom.current().nextLong(MIN_REST_MS, MAX_REST_MS);

        // Drifted from the anchor (knocked away / wandered) -> walk back home.
        if (Math.abs(pos.x - s.base.x) + Math.abs(pos.y - s.base.y) > RETURN_DIST) {
            GCMovement.nudgeTo(bot, s.base.x, s.base.y);
            s.fidgetMoveActive = true;
            return;
        }

        switch (pickAction(ThreadLocalRandom.current())) {
            case TURN -> GCMovement.turnAround(bot);
            case DUCK -> GCMovement.duck(bot, ThreadLocalRandom.current().nextInt(DUCK_MIN_MS, DUCK_MAX_MS));
            case HOP -> GCMovement.jumpInPlace(bot);
            case WANDER -> {
                int dx = ThreadLocalRandom.current().nextInt(-WANDER_PX, WANDER_PX + 1);
                Point to = wanderTarget(bot.getMap(), s.base, dx);
                if (to == null) {
                    GCMovement.turnAround(bot); // the nudge would step off the anchor's ledge: look around instead
                } else {
                    GCMovement.nudgeTo(bot, to.x, to.y); // small wander near the anchor
                    s.fidgetMoveActive = true;
                }
            }
        }
    }
}
