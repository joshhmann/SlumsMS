package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import soloMapling.server.BotShutdown;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/*
 * Cross-map follow: keep a bot tailing a target across portals.
 *
 * Same-map following is driven by GCMovementDriver (its resolveTarget returns the
 * target's live position). This coarse ~400 ms poller adds the cross-map half: when the target is on
 * a different map (they stepped through a portal), it travels the bot to that map via GCTravel
 * and resumes same-map follow on arrival — chasing further if the target keeps moving. The bot routes
 * to the target's map, so for an adjacent map that's the same portal the target took.
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCFollow {
    private GCFollow() {
    }

    private static final long POLL_MS = 400;

    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "gcfollow-poll");
        t.setDaemon(true);
        return t;
    });

    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();

    static final class Session {
        final Character bot;
        final Character target;
        ScheduledFuture<?> task;
        int travelDest = -1; // map the current follow-travel is heading to (-1 = not traveling)
        volatile int blockedDest = -1; // 1.6.0.4 owned alt: the owner's map it cannot walk to (not retried)
        volatile boolean toldWaiting;  // 1.6.0.4 owned alt: the owner has heard why it waits (once until it catches up)
        volatile Object leftInstance;  // 1.6.2 S3: the instance it last went out of with its leader (tried once each)

        Session(Character bot, Character target) {
            this.bot = bot;
            this.target = target;
        }
    }

    static void start(Character bot, Character target) {
        if (bot == null || target == null) {
            return;
        }
        cancel(bot);
        GCMovement.enable(bot);
        Session s = new Session(bot, target);
        s.task = POOL.scheduleAtFixedRate(
                () -> {
                    try {
                        tick(s);
                    } catch (Throwable ignored) {
                        // a thrown poll would cancel the periodic task
                    }
                }, 0, POLL_MS, TimeUnit.MILLISECONDS);
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

    static boolean isFollowing(Character bot) {
        return bot != null && SESSIONS.containsKey(bot.getId());
    }

    static Character target(Character bot) {
        Session s = bot == null ? null : SESSIONS.get(bot.getId());
        return s == null ? null : s.target;
    }

    /*
     * 1.6.0.4 (P0): where an owned alt may not follow its owner. An alt goes only where it is itself a member: never
     * into an event instance (PQ, boss, event map) it was not entered into with the party, and never onto a map the
     * owner reached through one. Same map id is not enough there - an instance map shares its id with the public map.
     * Null = free to follow.
     * 1.6.2 b168: the same rule for a population FollowerBot (one implementation for alts and bots): before, only an
     * alt was gated, so a follower whose leader was in an instance (a Zakum expedition, a PQ stage) walked to the
     * PUBLIC map with that id and then counted it as "same map", standing where its player could not see it. A
     * follower the event registered with the party (same instance) still follows inside it.
     */
    static String followBlockReason(Character bot, Character leader) {
        var botEim = bot.getEventInstance();
        var leaderEim = leader.getEventInstance();
        var leaderMap = leader.getMap();
        var mapEim = leaderMap == null ? null : leaderMap.getEventInstance();
        // 1.6.2 S3: a follower inside an instance its leader left goes out with it (leavesWithLeader) - judged from
        // outside, where it is going: it waits only if the leader went somewhere it could not enter from there.
        boolean leaving = leavesWithLeader(bot, leader);
        if (leaving) {
            botEim = null;
        }
        boolean blocked = leaderEim != botEim || (mapEim != null && mapEim != botEim)
                || (!leaving && bot.getMapId() == leader.getMapId() && bot.getMap() != leaderMap);
        if (!blocked) {
            return null;
        }
        return bot.isOwnedAlt()
                ? bot.getName() + " waits outside: alts can't follow you into an instance they did not enter with you."
                : bot.getName() + " waits outside: it can't follow you into an instance it did not enter with you.";
    }

    /*
     * 1.6.2 S3: the follower is inside an event instance (the event registered it with the party) and its leader is
     * no longer in that instance - it went out through the exit, a scroll, a warp, or into another event. A member
     * does not stay behind: it leaves too, the way a player leaves (BotEventExit: the script's playerExit, which
     * unregisters it and sends it to the event's exit map), and follows from there. Before, the instance check read
     * this as "the leader is in an instance the bot did not enter" and the bot stood inside saying it waits outside.
     */
    static boolean leavesWithLeader(Character bot, Character leader) {
        var botEim = bot.getEventInstance();
        return botEim != null && leader.getEventInstance() != botEim;
    }

    /* Out of the instance through the event's own exit, once per instance; the next poll follows from the exit map. */
    private static void leaveWithLeader(Session s, Character bot) {
        var eim = bot.getEventInstance();
        if (eim == null || s.leftInstance == eim) {
            return; // already on its way out (or the script kept it: not retried every poll)
        }
        s.leftInstance = eim;
        if (GCMovement.isTraveling(bot)) {
            GCTravel.cancel(bot);
        }
        s.travelDest = -1;
        GCMovement.pauseFollowForTravel(bot);
        soloMapling.ArtificialPlayer.BotEventExit.leave(bot);
    }

    /* The alt / follower stands where it is (no travel, no same-map tailing) and its player hears why, once per map. */
    private static void waitHere(Session s, Character bot, Character target, String why) {
        if (GCMovement.isTraveling(bot)) {
            GCTravel.cancel(bot);
        }
        s.travelDest = -1;
        GCMovement.pauseFollowForTravel(bot);
        int dest = target.getMapId();
        if (s.blockedDest != dest) {
            s.blockedDest = dest;
            tellOwnerOnce(s, target, why);
        }
    }

    private static void tellOwnerOnce(Session s, Character owner, String message) {
        if (s.toldWaiting) {
            return;
        }
        s.toldWaiting = true;
        try {
            owner.yellowMessage(message);
        } catch (RuntimeException ignored) {
            // the owner is mid logout; the alt is about to be saved and removed anyway
        }
    }

    static void tick(Session s) {
        if (BotShutdown.isQuiesced()) {
            cancel(s.bot); // Ticket 09: a follower must not travel after the gate has closed
            return;
        }
        Character bot = s.bot;
        Character target = s.target;
        if (bot == null || bot.getMap() == null || target == null || target.getMap() == null) {
            GCMovement.endFollowState(bot); // target gone / offline — end follow
            cancel(bot);
            return;
        }

        if (leavesWithLeader(bot, target)) { // 1.6.2 S3: its leader left the instance; alts and bots alike
            leaveWithLeader(s, bot);
            return;
        }
        String blocked = followBlockReason(bot, target); // alts and population followers alike (b168)
        if (blocked != null) {
            waitHere(s, bot, target, blocked);
            return;
        }

        // Past the gate a shared map id is the same map object (same channel, same instance or none).
        if (soloMapling.ArtificialPlayer.BotHelpers.sameMap(bot, target)) {
            // Same map: cancel any in-flight travel and let the 50 ms driver tail the target.
            if (GCMovement.isTraveling(bot)) {
                GCTravel.cancel(bot);
            }
            s.travelDest = -1;
            s.blockedDest = -1;
            s.toldWaiting = false;
            GCMovement.armSameMapFollow(bot, target);
            return;
        }

        // Different map: pause same-map follow and travel to the target's map.
        GCMovement.pauseFollowForTravel(bot);
        int destMap = target.getMapId();
        if (bot.isOwnedAlt() && s.blockedDest == destMap) {
            return; // 1.6.0.4: told once already; wait here until the owner moves somewhere it can walk to
        }
        if (!GCMovement.isTraveling(bot)) {
            s.travelDest = destMap;
            GCTravel.travel(bot, destMap, altGaveUp(s, bot, target, destMap));
        } else if (s.travelDest != destMap) {
            // Target moved to a different map mid-travel — redirect the chase.
            GCTravel.cancel(bot);
            s.travelDest = destMap;
            GCTravel.travel(bot, destMap, altGaveUp(s, bot, target, destMap));
        }
    }

    /* 1.6.0.4: an owned alt's trip that gave up (no walking route) marks that map so it waits instead of retrying. */
    static java.util.function.Consumer<Boolean> altGaveUp(Session s, Character bot, Character target, int destMap) {
        if (!bot.isOwnedAlt()) {
            return null; // population followers unchanged
        }
        return ok -> {
            if (!ok && target.getMapId() == destMap && s.blockedDest != destMap) {
                s.blockedDest = destMap;
                // 1.6.1 ticket 13: OwnedAltCompanion catches it up a few seconds later (boats, dead ends)
                tellOwnerOnce(s, target, bot.getName() + " can't walk to where you are and catches up with you"
                        + " in a few seconds.");
            }
        };
    }
}
