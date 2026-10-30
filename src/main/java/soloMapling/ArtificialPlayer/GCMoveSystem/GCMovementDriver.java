package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import constants.game.CharacterStance;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Rope;
import soloMapling.ArtificialPlayer.BotMovementSystem.BotGround;
import soloMapling.server.BotShutdown;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Adapted from GreenCatMS - GCMoveSystem tick driver, now also ticks contact damage. Credit: NutNNut.
/*
 * The 50 ms (20 Hz) tick driver for GCMoveSystem dynamic movement. Replaces GreenCat's
 * per-bot TimerManager task + BotManager.stepMovementCore funnel with a
 * self-contained scheduled driver.
 *
 * Threading: a shared scheduled pool; each bot is driven by one repeating fixed-rate task.
 * java.util.concurrent.ScheduledThreadPoolExecutor never runs the SAME periodic task
 * concurrently with itself, so per-bot single-thread (which GreenCat's non-volatile physics
 * fields assume) is preserved. A scale-out LOD scheduler can replace this later.
 */
final class GCMovementDriver {
    private GCMovementDriver() {
    }

    private static final int AI_TICK_MS = 100;            // heavy decisions every other tick
    // LOD scheduling cadence for an unobserved bot (no real player on or adjacent to its map): the
    // driver self-reschedules its tick at this interval instead of TICK_MS, so it stops consuming
    // 20 Hz wakeups. The analytic CoarseExecutor is pure wall-clock, so the slower cadence yields
    // identical positions.
    private static final int UNOBSERVED_TICK_MS = 250;
    // Ours (Fable Phase 3): an unobserved bot with no movement job at all doesn't need
    // the 250ms coarse cadence either. A job started while idling begins up to ~1s
    // late, which is invisible on a map nobody can see; on promotion the next tick
    // returns to TICK_MS. At ~1200 mostly-idle background bots this cuts the constant
    // movement wakeups roughly 4x.
    private static final int UNOBSERVED_IDLE_TICK_MS = 1000;
    // Coarse "arrived" box: within this of the goal, hold (and fire arrival) instead of replanning.
    private static final int COARSE_ARRIVE_PX = 12;
    private static final boolean ENABLE_UNSTUCK = true;
    private static final int AIR_STUCK_RECOVER_TICKS = 30;
    // Ours: live fall-off-map catch. The airborne integrator has no VR-bottom clamp, so a bot that slips
    // through a foothold gap (or off the side) free-falls forever — the frozen-air watchdog only fires once
    // the position STOPS changing, which a live plummet never does. Trigger a snap-back once the bot is this
    // far outside the map's VR bounds (footholds live inside the VR, so a legit deep drop never reaches here).
    private static final int FALL_RECOVER_SLACK_PX = 400;
    // Organic map-entry: appear standing at the spawn portal, wait a "client load" beat, then drop.
    // Mirrors WarpCommands.botEnterPortalDropDown (the recorded engine's ~1.5s lag before the drop).
    private static final long PORTAL_DROP_DELAY_MS = 1500;
    private static final int PORTAL_DROP_DELAY_JITTER_MS = 600; // + 0..600ms so arrivals aren't uniform
    // Abandon a move target the bot can't get closer to for this long (unreachable / blocked / bug),
    // so a bot never tries to reach a point forever. The clock resets on any real progress.
    private static final long MOVE_NO_PROGRESS_MS = 8_000;
    private static final int MOVE_PROGRESS_EPS_PX = 16;
    // Recompute a bot's movement profile on this cadence so runtime changes (chiefly party Haste —
    // joining/leaving a party with a high-level thief) take effect without needing a map change.
    // refreshMovementProfile no-ops when the bucket is unchanged, so this is ~free for non-party bots.
    private static final long PROFILE_REFRESH_INTERVAL_MS = 20_000;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "gcmove-tick-" + THREAD_SEQ.getAndIncrement());
                t.setDaemon(true);
                return t;
            });

    static void start(BotMovementState entry) {
        stop(entry);
        entry.tickStopped = false;
        scheduleNext(entry, 0);
    }

    static void stop(BotMovementState entry) {
        entry.tickStopped = true; // gate the self-reschedule; an in-flight tick may finish once more
        if (entry.task != null) {
            entry.task.cancel(false);
            entry.task = null;
        }
    }

    /*
     * Self-rescheduling tick: instead of a fixed 20 Hz task per bot, each tick schedules the next at a
     * cadence that matches the bot's tier — TICK_MS (50 ms) when its map is observed,
     * UNOBSERVED_TICK_MS (250 ms) when not. So an unobserved bot stops consuming 20 Hz wakeups
     * (the analytic CoarseExecutor is pure wall-clock, so a slower cadence gives identical positions).
     * Successive ticks never overlap (the next is scheduled only after this one returns) and the
     * executor establishes happens-before between them, so per-bot single-thread semantics hold even
     * though the physical pool thread may differ.
     */
    private static void scheduleNext(BotMovementState entry, long delayMs) {
        if (entry.tickStopped || BotShutdown.isQuiesced()) {
            return; // Ticket 09: no new movement tick once the gate has closed
        }
        entry.task = POOL.schedule(() -> {
            safeTick(entry);
            scheduleNext(entry, nextDelayMs(entry));
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static long nextDelayMs(BotMovementState entry) {
        Character bot = entry.bot;
        boolean active = bot != null && bot.getMap() != null
                && ObserverTracker.isActiveMap(bot.getMapId());
        if (active) {
            return BotPhysicsEngine.cfg.TICK_MS;
        }
        if (bot != null
                && !GCMovement.isMoving(bot) && !GCMovement.isTraveling(bot) && !GCMovement.isFollowing(bot)) {
            return UNOBSERVED_IDLE_TICK_MS; // jobless + unseen: idle heartbeat only
        }
        return UNOBSERVED_TICK_MS;
    }

    // Throttled profile recompute. Self-thief speed/jump is static (job/level), but party Haste is dynamic
    // (roster changes at runtime), and the profile is otherwise only built once at enable(). Every
    // PROFILE_REFRESH_INTERVAL_MS we recompute; refreshMovementProfile returns fast and re-warms only when
    // the speed/jump bucket actually changed, so a non-party bot pays just one fromCharacter() per interval.
    private static void maybeRefreshProfile(BotMovementState entry) {
        long now = System.currentTimeMillis();
        if (now - entry.lastProfileRefreshMs < PROFILE_REFRESH_INTERVAL_MS) {
            return;
        }
        entry.lastProfileRefreshMs = now;
        BotMovementManager.refreshMovementProfile(entry);
    }

    private static void safeTick(BotMovementState entry) {
        if (BotShutdown.isQuiesced()) {
            stop(entry); // Ticket 09: end the chain for this bot instead of stepping physics on a dead map
            return;
        }
        try {
            soloMapling.server.BotPerfStats.MOVEMENT_TICKS.increment();
            long startedAt = System.nanoTime();
            try {
                tick(entry);
            } finally {
                BotPerformanceMonitor.recordMovementTick(System.nanoTime() - startedAt);
            }
        } catch (Throwable t) {
            // A thrown exception must not break the self-reschedule chain — swallow so the bot keeps ticking.
        }
    }

    private static void tick(BotMovementState entry) {
        Character bot = entry.bot;
        if (bot == null || bot.getMap() == null) {
            stop(entry);
            return;
        }
        // Pick up runtime profile changes (party Haste) before the map-change branch, so onMapChange warms
        // the new map's graph with the up-to-date profile. Throttled + no-op when unchanged (see helper).
        maybeRefreshProfile(entry);
        if (entry.lastMapId != bot.getMapId()) {
            onMapChange(entry, bot);
            return;
        }

        // Sitting in a chair (e.g. a grinding TrainingBot on a rest break): hold the sit and skip the
        // tick. botSitChair set the SIT stance and broadcast showChair; if the driver kept idling it
        // would reset the stance to standing (idleOnGround -> syncCharacterState) and broadcast that
        // every tick, snapping the bot upright the instant it sits. A sitting bot isn't moving, so
        // nothing is lost by holding; botCancelChair clears the chair and the driver resumes normally.
        // Mirrors the old engine's getChair() > 0 guards in MovementCommands.
        if (bot.getChair() > 0) {
            return;
        }

        // Rope rest hold (grind break): the bot is deliberately hanging idle on a rope. Freeze the hang and
        // broadcast — never run nav / steering / player-reaction / contact-knockback below, any of which
        // could dislodge it. Set on arrival by GrindBreakRoutine, cleared on break end (and leaveGrind).
        if (entry.resting) {
            if (entry.climbing) {
                BotPhysicsEngine.holdClimb(entry, bot);
            } else {
                BotPhysicsEngine.idleOnGround(entry, bot); // shouldn't happen (rest is a rope hang), but hold anyway
            }
            broadcastIfObserved(entry);
            return;
        }

        // Commitment hold (ticket 05, BotCommitmentHold): a player-facing commitment in flight owns the
        // bot's position - an open trade window. Freeze the bot where the commitment found it: no nav,
        // no steering, no player reaction, no move intent consumed, no contact knockback. The hold is
        // released by the commitment's owner at its single exit (and self-heals here if that owner never
        // runs), after which the driver resumes the surviving move intent from this exact position.
        if (BotCommitmentHold.stillHolding(entry)) {
            boolean airborne = entry.inAir;
            if (entry.climbing) {
                BotPhysicsEngine.holdClimb(entry, bot);
            } else {
                BotPhysicsEngine.idleOnGround(entry, bot);
                if (airborne) {
                    entry.inAir = true; // show the JUMP stance while hovering, like the portal drop
                }
            }
            broadcastIfObserved(entry);
            return;
        }

        // Contact/fall damage: render the bot taking hits from mobs. Runs before the movement branches
        // so even an idle/standing bot recoils when a mob touches it. Self-gates on LOD (does ~nothing
        // when no real player shares the bot's map) and on the i-frame window.
        BotContactDamage.tickMobDamage(entry, bot);

        // Idle safety (1.4.2): a bot about to stand idle - or idling - with live mobs in reach, or hit while idle,
        // walks to a safe spot first. Observed maps only (no contact damage elsewhere); sets a move target that the
        // branches below walk like any other.
        if (!bot.isOwnedAlt() && GCMovement.isMapObserved(bot.getMapId())) { // 1.6.0.4: an alt stays by its owner
            IdleSafety.tick(entry, bot);
        }

        boolean active = ObserverTracker.isActiveMap(bot.getMapId());

        // LOD promotion (M2): the bot was moving analytically (coarse) and a player just arrived —
        // rebuild the physics shadow at its current interpolated position so physics resumes cleanly
        // instead of snapping from stale shadow coords. Minimal reconstruction; full hysteresis is M3.
        if (active && entry.coarseActive) {
            reconstructPhysicsFromCoarse(entry, bot);
        }

        boolean runAiTick = consumeAiTick(entry);

        // Player reaction (ported pathAware): on an observed map, occasionally emote/chat at or stop-and-
        // turn toward a nearby real player while roaming. Grounded bots only; no-op when unobserved.
        if (active && runAiTick && !entry.inAir && !entry.climbing
                && !soloMapling.ArtificialPlayer.BotTownSystem.AfkCampers.isCamping(bot.getId())) { // 1.6.0: AFK = no reaction
            BotPlayerReaction.maybeReact(entry, bot);
        }
        // While a stop-reaction is in progress, hold position (don't walk off mid-greeting) and keep the
        // stall timer fresh so the pause isn't mistaken for being stuck. Resumes automatically after.
        if (entry.reactingUntilMs > System.currentTimeMillis()) {
            entry.moveProgressAtMs = System.currentTimeMillis();
            BotPhysicsEngine.idleOnGround(entry, bot);
            broadcastIfObserved(entry);
            return;
        }

        // Ticket 33: a hesitation before a jump - stand still for the beat, like a stop-reaction.
        if (entry.flaws.holdUntilMs > System.currentTimeMillis() && !entry.inAir && !entry.climbing) {
            entry.moveProgressAtMs = System.currentTimeMillis();
            BotPhysicsEngine.idleOnGround(entry, bot);
            broadcastIfObserved(entry);
            return;
        }

        // Pending organic portal/teleport drop: hold standing at the spawn portal (the bot appears
        // up at the portal, above the floor), then release the natural fall once the load beat passes.
        if (entry.portalDropAtMs > 0L) {
            if (System.currentTimeMillis() < entry.portalDropAtMs) {
                BotPhysicsEngine.idleOnGround(entry, bot); // float at the spawn point
                entry.inAir = true; // show the JUMP stance while floating (the release fall already does)
                broadcastIfObserved(entry);
                return;
            }
            entry.portalDropAtMs = 0L;
            BotPhysicsEngine.beginPortalDrop(entry, bot, bot.getPosition()); // release the fall
            broadcastIfObserved(entry);
            return;
        }

        // Abandon a move target the bot can't make progress toward, so it never tries forever.
        if (giveUpStalledMove(entry)) {
            BotPhysicsEngine.idleOnGround(entry, bot);
            broadcastIfObserved(entry);
            return;
        }

        Point target = resolveTarget(entry, bot);
        boolean hasGoal = target != null || entry.inAir || entry.climbing || entry.navEdge != null;
        if (!hasGoal) {
            landAbandonedCoarseTrip(entry, bot);
            if (entry.duckUntilMs > System.currentTimeMillis()) {
                BotPhysicsEngine.proneOnGround(entry, bot); // idle fidget: hold a crouch/duck pose
            } else {
                BotPhysicsEngine.idleOnGround(entry, bot);
            }
            broadcastIfObserved(entry); // dedup suppresses idle spam; skipped entirely when unobserved
            return;
        }

        // LOD coarse (M2): an unobserved bot with a plannable in-map point goal moves by analytic ETA
        // over the baked edge times — no physics, no broadcast — so it costs ~nothing. Falls through to
        // throttled physics when mid-air/climbing or the map has no baked graph (never bake just to move
        // an unwatched bot). `active` is false here only when nobody can see the bot.
        if (!active && tryCoarseAdvance(entry, bot, target)) {
            NavTrace.tick(entry, false, target);
            return;
        }
        stepMovementCore(entry, target != null ? target : bot.getPosition(), runAiTick);
        NavTrace.tick(entry, active, target); // ticket 157: one read when off
    }

    /*
     * Move an unobserved bot toward target analytically. Builds a
     * MovementPlan from the cached graph once, then advances it by wall-clock time and writes
     * the interpolated position. Returns false (caller falls back to throttled physics) when
     * the bot is airborne/climbing or the map has no cached graph.
     */
    private static boolean tryCoarseAdvance(BotMovementState entry, Character bot, Point target) {
        long startedAt = System.nanoTime();
        try {
            return tryCoarseAdvance0(entry, bot, target);
        } finally {
            BotPerformanceMonitor.recordOffscreenAccrual(System.nanoTime() - startedAt);
        }
    }

    private static boolean tryCoarseAdvance0(BotMovementState entry, Character bot, Point target) {
        if (target == null || entry.inAir || entry.climbing) {
            return false;
        }
        // Already at the goal: arrive (fires the callback, holds an anchor) without replanning each tick.
        if (entry.coarsePlan == null
                && Math.abs(bot.getPosition().x - target.x) <= COARSE_ARRIVE_PX
                && Math.abs(bot.getPosition().y - target.y) <= COARSE_ARRIVE_PX) {
            arriveCoarse(entry, bot, target);
            return true;
        }
        boolean swim = bot.getMap().isSwim() && SwimNavigator.enabled;
        BotNavigationGraph graph = swim ? null : BotNavigationGraphProvider.peekBestGraph(bot.getMap(), entry.movementProfile);
        if (graph == null && !swim) {
            return false; // no cached graph -> let the M1 throttle cover it (don't trigger a bake)
        }
        long now = System.currentTimeMillis();
        boolean needPlan = entry.coarsePlan == null
                || entry.coarsePlanMapId != bot.getMapId()
                || !target.equals(entry.coarsePlanTarget);
        if (needPlan) {
            // Ticket 34: a swim map plans over its water graph (null while it builds: physics covers it meanwhile).
            MovementPlan plan;
            if (swim) {
                if (SwimNavGraph.peekOrWarm(bot.getMap()) == null) {
                    return false;
                }
                plan = MovementPlan.inMap(bot.getMapId(), SwimNavigator.coarseRoute(bot.getMap(), bot.getPosition(), target));
            } else {
                plan = MovementPlan.inMap(graph, bot.getMap(), bot.getPosition(), target);
            }
            if (plan == null) {
                arriveCoarse(entry, bot, target); // already in the target region / unplannable
                return true;
            }
            entry.coarsePlan = plan;
            entry.coarsePlanStartMs = now;
            entry.coarsePlanTarget = new Point(target);
            entry.coarsePlanMapId = bot.getMapId();
        }
        if (!entry.coarseActive) {
            BotPerformanceMonitor.recordCoarseDemotion();
        }
        entry.coarseActive = true;
        CoarseExecutor.Step step = CoarseExecutor.advance(entry.coarsePlan, entry.coarsePlanStartMs, now);
        if (step.position() != null) {
            bot.setPosition(step.position());
        }
        if (step.complete()) {
            arriveCoarse(entry, bot, entry.coarsePlanTarget);
        }
        return true;
    }

    /*
     * 1.6.0 foothold regression (TEST health sweep, 2026-10-25): where an unobserved bot ends a coarse trip. The trip
     * used to end exactly on the requested point, but a move target is only an intent: the idle fidget wanders to
     * (anchor x +-45, anchor y), which past a ledge's end is in the air; an unplannable goal (plan == null) can be
     * anywhere. A physics walk to such a point walks off the ledge and falls, or stops on the floor; the coarse
     * arrival teleported the bot onto it and left it standing in the air or inside the ground (Henesys' terraces are
     * 60 px apart, hence the many dy = +-60). Now it lands where a walk there would: BotGround.landing (the floor at
     * or just under the target - the one a step off the ledge drops onto - or, with none below, the floor the target
     * sank into), else the ground under where the bot is, else the fall-off recovery ground. Swim maps keep the
     * point: there is no floor to stand on.
     */
    static Point coarseArrivalPoint(MapleMap map, Point target, Point current) {
        if (map == null || map.isSwim() || map.getFootholds() == null) {
            return new Point(target);
        }
        Point ground = BotGround.landing(map, target);
        if (ground == null && current != null) {
            ground = BotGround.settle(map, current);
        }
        if (ground == null) {
            ground = recoveryGround(map, current, target);
        }
        return ground != null ? ground : new Point(target);
    }

    /*
     * 1.5.0 rc5: a coarse trip whose goal went away part-way (clearMoveIntent, a new activity, a stop) left the bot
     * at its last interpolated point - half-way up a rope climb, on a jump arc, inside a platform - and the idle
     * branch then held it there as "standing" until its next goal. Put it on the foothold it belongs on (BotGround.settle,
     * else the recovery ground) and drop the stale plan. A no-op when no trip is in flight.
     */
    static void landAbandonedCoarseTrip(BotMovementState entry, Character bot) {
        if (entry.coarsePlan == null) {
            return;
        }
        entry.coarsePlan = null;
        entry.coarsePlanTarget = null;
        entry.coarsePlanStartMs = 0L;
        entry.coarsePlanMapId = -1;
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        Point ground = BotGround.settle(map, pos); // lifts a point a lerp left inside a platform back onto it
        if (ground == null) {
            ground = recoveryGround(map, pos, null);
        }
        if (ground != null && !ground.equals(pos)) {
            BotPhysicsEngine.teleportTo(entry, bot, ground);
        }
    }

    /* Finish a coarse trip: snap to the goal's ground, clear the plan + move target, fire the arrival callback. */
    static void arriveCoarse(BotMovementState entry, Character bot, Point target) {
        if (target != null) {
            bot.setPosition(coarseArrivalPoint(bot.getMap(), target, bot.getPosition()));
        }
        entry.coarsePlan = null;
        entry.coarsePlanTarget = null;
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        entry.moveBestDist = Integer.MAX_VALUE;
        BotMovementManager.clearNavigationState(entry);
        GCMovement.fireArrival(entry);
    }

    /*
     * Promotion (COARSE -> FULL/HALO): rebuild the physics shadow at the bot's current interpolated
     * position so the resumed physics tick doesn't snap from stale shadow coordinates, and rebuild every
     * other mode-scoped field with it (see BotTransitionBoundary for the durable/reconstructible split).
     * The durable half — identity, progression, commitments, lifecycle — is deliberately not touched.
     *
     * Package-visible because this is the promotion seam: BotTransitionBoundaryTest drives this exact
     * entry point rather than the boundary class, so the ordering (reset, then snap, then re-seed the swept
     * trace) stays under test and the promotion cost stays on BotPerformanceMonitor.recordPromotionReconstruct.
     */
    static void reconstructPhysicsFromCoarse(BotMovementState entry, Character bot) {
        long startedAt = System.nanoTime();
        try {
            reconstructPhysicsFromCoarse0(entry, bot);
        } finally {
            BotPerformanceMonitor.recordPromotionReconstruct(System.nanoTime() - startedAt);
        }
    }

    private static void reconstructPhysicsFromCoarse0(BotMovementState entry, Character bot) {
        BotTransitionBoundary.reconstructForObserved(entry);
        Point pos = bot.getPosition();
        Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(pos.x, pos.y - 1));
        BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : pos);
        BotTransitionBoundary.reseedObservedTrace(entry, bot);
    }

    /*
     * No-progress give-up: if the bot can't get meaningfully closer to its moveTarget for
     * MOVE_NO_PROGRESS_MS, abandon the move (clear target + nav, drop the callback) so an
     * unreachable / blocked / buggy goal can't loop forever. Any real progress resets the clock, so
     * long legitimate walks are never cut off. Follow/farm without a moveTarget are unaffected.
     */
    private static boolean giveUpStalledMove(BotMovementState entry) {
        if (entry.moveTarget == null || entry.inAir || entry.climbing) {
            return false;
        }
        Point bp = entry.bot.getPosition();
        int dist = Math.abs(bp.x - entry.moveTarget.x) + Math.abs(bp.y - entry.moveTarget.y);
        long now = System.currentTimeMillis();
        if (entry.moveProgressAtMs == 0L) {
            entry.moveProgressAtMs = now;
        }
        if (dist < entry.moveBestDist - MOVE_PROGRESS_EPS_PX) {
            entry.moveBestDist = dist;
            entry.moveProgressAtMs = now;
            return false;
        }
        if (now - entry.moveProgressAtMs <= MOVE_NO_PROGRESS_MS) {
            return false;
        }
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        entry.moveBestDist = Integer.MAX_VALUE;
        BotMovementManager.clearNavigationState(entry);
        GCMovement.abandonMove(entry);
        return true;
    }

    private static Point resolveTarget(BotMovementState entry, Character bot) {
        if (entry.moveTarget != null) {
            return entry.moveTarget;
        }
        if (entry.following && entry.owner != null && entry.owner.getMap() == bot.getMap()) {
            return formationPlace(entry.owner.getPosition(), GCMovement.followOffset(bot.getId()), bot.getMap());
        }
        if (entry.farmAnchor != null) {
            return entry.farmAnchor;
        }
        return null;
    }

    /*
     * 1.6.2 ticket 99: a follower's formation place - {@code dx} px beside the one it follows, kept inside the map's
     * area so a "right 120" line at a wall bunches up instead of steering into it. dx 0 = the followed spot itself.
     */
    static Point formationPlace(Point followed, int dx, server.maps.MapleMap map) {
        if (dx == 0 || followed == null) {
            return followed;
        }
        int x = followed.x + dx;
        java.awt.Rectangle area = map == null ? null : map.getMapArea();
        if (area != null && area.width > 0) {
            x = Math.max(area.x + 10, Math.min(area.x + area.width - 10, x));
        }
        return new Point(x, followed.y);
    }

    /* Faithful port of BotManager.stepMovementCore (minus fidget). */
    private static void stepMovementCore(BotMovementState entry, Point target, boolean runAiTick) {
        BotNavigationManager.NavigationDirective nav =
                BotNavigationManager.resolveTarget(entry, target, runAiTick);
        if (nav.consumedTick) {
            return;
        }
        Point steering = nav.targetPos;
        if (entry.moveTargetPrecise && entry.navEdge == null) {
            entry.navPreciseTarget = true;
        }
        // Ticket 33: running hops and wrong-way twitches while walking; an overrun past a jump's launch spot.
        if (runAiTick && NavMovementFlaws.walking(entry, steering, AI_TICK_MS)) {
            return;
        }
        steering = NavMovementFlaws.steer(entry, steering);
        tickMovementPhase(entry, steering, runAiTick);
        if (runAiTick && !entry.inAir && !entry.climbing) {
            BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(entry, target);
        }
        tickStuckDetection(entry);
        clearReachedMoveTarget(entry);
    }

    private static void tickMovementPhase(BotMovementState entry, Point target, boolean runAiTick) {
        if (entry.climbing) {
            BotMovementManager.tickClimbing(entry, target, runAiTick);
        } else if (isSwimMap(entry) && entry.inAir) {
            BotMovementManager.tickSwimming(entry, target);
        } else if (entry.inAir) {
            BotMovementManager.tickAirborne(entry, target);
        } else {
            BotMovementManager.tickGrounded(entry, target);
        }
    }

    private static boolean isSwimMap(BotMovementState entry) {
        return entry.bot != null && entry.bot.getMap() != null && entry.bot.getMap().isSwim();
    }

    private static void clearReachedMoveTarget(BotMovementState entry) {
        if (entry.moveTarget == null) {
            return;
        }
        Point botPos = entry.bot.getPosition();
        int arrivalDist = entry.moveTargetPrecise ? 8 : BotMovementManager.cfg.STOP_DIST;
        if (Math.abs(botPos.x - entry.moveTarget.x) <= arrivalDist
                && Math.abs(botPos.y - entry.moveTarget.y) <= arrivalDist
                && !hangingShortOfGroundTarget(entry, entry.moveTarget)) {
            entry.moveTarget = null;
            entry.moveTargetPrecise = false;
            GCMovement.fireArrival(entry);
        }
    }

    /*
     * 1.4.1: a bot on a rope is not "there" yet when its target is the ground beside the rope's head - within
     * STOP_DIST of the head counted as arrived, the move ended, and the bot was left hanging at the top of the ladder.
     * It still has to step off. A target on the rope itself (near the rope's x, inside its span, not on a foothold)
     * arrives as before.
     */
    static boolean hangingShortOfGroundTarget(BotMovementState entry, Point target) {
        Rope rope = entry.climbRope;
        if (!entry.climbing || rope == null || target == null || entry.resting) {
            return false;
        }
        boolean onRope = Math.abs(target.x - rope.x()) <= 12 && target.y > rope.topY() && target.y <= rope.bottomY();
        if (!onRope) {
            return true;
        }
        // A step-off foothold can sit a few px below the rope head, inside the rope's span: still ground.
        MapleMap map = entry.bot.getMap();
        Point ground = map == null ? null : BotPhysicsEngine.findGroundPoint(map, new Point(target.x, target.y - 4));
        return ground != null && Math.abs(ground.y - target.y) <= 4;
    }

    private static boolean consumeAiTick(BotMovementState entry) {
        entry.aiTickAccumulatorMs += BotPhysicsEngine.cfg.TICK_MS;
        if (entry.aiTickAccumulatorMs < AI_TICK_MS) {
            return false;
        }
        entry.aiTickAccumulatorMs -= AI_TICK_MS;
        return true;
    }

    // Organic portal entry: the bot appears at the portal lifted at least this far above the floor
    // (a natural portal sits higher → keep its height), floats the "client load" beat, then drops.
    // Guarantees a visible spawn→float→drop even when the arrival portal sits on the ground. Mirrors
    // the recorded engine's portalenterdrop feel; tune to taste.
    private static final int PORTAL_FLOAT_HEIGHT_PX = 60;

    static void onMapChange(BotMovementState entry, Character bot) {
        entry.lastMapId = bot.getMapId();
        // New map: drop any in-progress reaction pause. The per-player react cooldown is intentionally
        // NOT reset here - it's tied to the player so map-hopping can't re-trigger greetings at them.
        entry.reactingUntilMs = 0L;
        // Drop any coarse plan from the previous map; onMapChange re-seeds the physics shadow below.
        entry.coarsePlan = null;
        entry.coarsePlanTarget = null;
        entry.coarseActive = false;
        MapleMap map = bot.getMap();
        // If a real player is already on this map, mark it observed NOW so the visible spawn (float →
        // drop, jump stance) and FULL combat start this tick instead of waiting up to one observer poll.
        if (ObserverTracker.hasRealPlayerNow(map)) {
            ObserverTracker.markObservedNow(bot.getMapId());
            // Ours: the movement above is already instant - also wake the arriving bot's macro brain so
            // it acts promptly instead of on its slow 2-6s/10s wheel. See BotMapEntryResponder (B).
            soloMapling.ArtificialPlayer.BotMapEntryResponder.onBotArrivedObserved(bot);
        }
        entry.fhIndex = BotMovementManager.buildFhIndex(map);
        Point spawn = bot.getPosition();
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(spawn.x, spawn.y - 1));
        // Clear stale nav from the old map first (does not touch the physics state set below).
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        // Only play the visible spawn→float→drop where a player can actually see it; on unobserved
        // maps just land on the floor (nobody's watching, and it keeps coarse travel from stalling
        // ~1.5s per hop). The hold + release runs in tick(); beginPortalDrop hands off to the
        // airborne integrator.
        if (ground != null && ObserverTracker.isActiveMap(bot.getMapId())) {
            // Lift to at least PORTAL_FLOAT_HEIGHT_PX above the floor (keep a higher natural portal),
            // so the bot floats at/above the portal a beat, then drops — every time, not just for
            // portals that happen to sit high.
            int floatY = Math.min(spawn.y, ground.y - PORTAL_FLOAT_HEIGHT_PX);
            BotPhysicsEngine.teleportTo(entry, bot, new Point(spawn.x, floatY));
            entry.portalDropAtMs = System.currentTimeMillis() + PORTAL_DROP_DELAY_MS
                    + ThreadLocalRandom.current().nextInt(PORTAL_DROP_DELAY_JITTER_MS + 1);
        } else {
            // Unobserved (or no floor below): just stand where we landed — no float.
            BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : spawn);
            entry.portalDropAtMs = 0L;
        }
        BotNavigationGraphProvider.warmGraphAsync(map, entry.movementProfile);
        broadcastIfObserved(entry);
    }

    /*
     * Emit a movement packet only when the bot's map is observed (a real player is on it or an
     * adjacent map). For an unobserved bot nobody receives the packet, so this skips the snapshot +
     * map-iterate entirely. Gates only the driver-owned broadcasts (idle / drop / map-change /
     * recovery); the moving-bot broadcasts live inside the untouchable core and are reduced instead by
     * the unobserved tick throttle.
     */
    private static void broadcastIfObserved(BotMovementState entry) {
        if (entry.bot != null && ObserverTracker.isActiveMap(entry.bot.getMapId())) {
            BotMovementManager.broadcastMovement(entry);
        }
    }

    // ── Stuck detection (faithful port; recovery teleport inlined) ──
    private static void tickStuckDetection(BotMovementState entry) {
        entry.unstuckCooldownMs = BotMovementManager.tickDown(entry.unstuckCooldownMs);
        tickFrozenAirborneWatchdog(entry);
        tickRopeStallWatchdog(entry);
        tickFallOffMapRecovery(entry); // ours: catch a live plummet the frozen-air watchdog above misses
        if (entry.inAir || entry.climbing || entry.graphWarmupFallback
                || (entry.navEdge == null && entry.moveTarget == null)) {
            entry.stuckMs = 0;
            entry.stuckCheckX = Integer.MIN_VALUE;
            return;
        }
        Point botPos = entry.bot.getPosition();
        if (entry.stuckCheckX == Integer.MIN_VALUE) {
            entry.stuckCheckX = botPos.x;
            entry.stuckCheckY = botPos.y;
            return;
        }
        boolean moved = Math.abs(botPos.x - entry.stuckCheckX) > 8
                || Math.abs(botPos.y - entry.stuckCheckY) > 8;
        if (moved) {
            entry.stuckMs = 0;
            entry.stuckCheckX = botPos.x;
            entry.stuckCheckY = botPos.y;
        } else {
            entry.stuckMs += BotPhysicsEngine.cfg.TICK_MS;
        }
        if (ENABLE_UNSTUCK && entry.stuckMs >= 500 && entry.unstuckCooldownMs == 0) {
            entry.stuckMs = 0;
            entry.stuckCheckX = Integer.MIN_VALUE;
            BotMovementManager.tickUnstuck(entry);
        }
    }

    /** 1.4.1: a bot going somewhere that sits on a rope this long without climbing is hung, whatever the cause. */
    static final int ROPE_STALL_MS = 4_000;

    /*
     * 1.4.1 (bots stuck on top of ladders): the grounded stuck detector above skips climbing bots, and grind's
     * ClimbRecovery leaves a bot alone while it has a nav edge, so a bot parked on a rope with somewhere to go (a nav
     * edge or a move target) could hang there for good. After ROPE_STALL_MS with no vertical progress: at the head of
     * a rope with a step-off above, climb off onto the platform; anywhere else, jump off toward the goal. Then re-plan.
     * Rest hangs, commitment holds and a follower hanging beside its climbing owner are deliberate and left alone. The
     * top-of-ladder pause flaw (NavMovementFlaws.pausingAtRopeTop) is far shorter than the stall time.
     */
    /* !qa ld-1: a rope stall this long is logged even if the watchdog later finds nothing to do. */
    static final int QA_STALL_NOTE_MS = 2000;

    static void tickRopeStallWatchdog(BotMovementState entry) {
        if (!entry.climbing || entry.climbRope == null) {
            entry.ropeStallMs = 0;
            entry.ropeStallY = Integer.MIN_VALUE;
            entry.flaws.topPauseEdge = null; // a new climb rolls its own top pause
            return;
        }
        Point pos = entry.bot.getPosition();
        boolean goingSomewhere = entry.navEdge != null || entry.moveTarget != null;
        boolean deliberateHang = entry.resting || entry.heldForCommitment
                || (entry.following && entry.owner != null && CharacterStance.isClimbing(entry.owner.getStance()));
        if (!goingSomewhere || deliberateHang || pos == null) {
            entry.ropeStallMs = 0;
            entry.ropeStallY = Integer.MIN_VALUE;
            return;
        }
        if (entry.ropeStallY == Integer.MIN_VALUE || Math.abs(pos.y - entry.ropeStallY) > 4) {
            entry.ropeStallMs = 0;
            entry.ropeStallY = pos.y;
            return;
        }
        int before = entry.ropeStallMs;
        entry.ropeStallMs += BotPhysicsEngine.cfg.TICK_MS;
        if (before < QA_STALL_NOTE_MS && entry.ropeStallMs >= QA_STALL_NOTE_MS
                && soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.ROPE)) { // !qa ld-1
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.ROPE, entry.bot.getMapId(), entry.bot.getName()
                    + " still on rope for " + entry.ropeStallMs + " ms at " + pos.x + "," + pos.y + " (rope top "
                    + entry.climbRope.topY() + ")");
        }
        if (entry.ropeStallMs < ROPE_STALL_MS) {
            return;
        }
        entry.ropeStallMs = 0;
        entry.ropeStallY = Integer.MIN_VALUE;
        unhangFromRope(entry);
    }

    private static void qaUnhang(Character bot, Point pos, String how) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.ROPE)) { // !qa ld-1
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.ROPE, bot.getMapId(), "WATCHDOG " + bot.getName()
                    + " hung " + ROPE_STALL_MS + " ms at " + pos.x + "," + pos.y + ": " + how);
        }
    }

    static void unhangFromRope(BotMovementState entry) {
        Character bot = entry.bot;
        MapleMap map = bot.getMap();
        Rope rope = entry.climbRope;
        Point pos = bot.getPosition();
        NavStuckLog.record(NavStuckLog.Kind.ROPE_HUNG, map, pos, entry.navGraph, entry.navEdge,
                NavTrace.capture(entry));
        Point goal = entry.moveTarget != null ? entry.moveTarget : entry.navTargetPos;
        boolean atHead = pos.y <= rope.topY() + 2 * BotPhysicsEngine.climbStepPerTick() + 2;
        boolean goalAbove = goal == null || goal.y <= pos.y + BotMovementManager.cfg.STOP_DIST;
        BotMovementManager.clearNavigationState(entry);
        if (atHead && goalAbove && BotPhysicsEngine.findTopExitLanding(map, rope) != null) {
            entry.climbVerticalDir = -1;
            for (int i = 0; i < 4 && entry.climbing; i++) {
                BotPhysicsEngine.advanceClimb(entry, bot); // crosses the head: physics lands it on the platform
            }
            entry.climbVerticalDir = 0;
            if (!entry.climbing) {
                BotMovementManager.broadcastMovement(entry);
                qaUnhang(bot, pos, "climbed off at the rope head");
                return;
            }
        }
        qaUnhang(bot, pos, "jumped off toward " + (goal == null ? "no goal" : goal.x + "," + goal.y));
        int dx = goal != null ? Integer.compare(goal.x, pos.x) : 0;
        if (dx == 0) {
            dx = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        }
        BotMovementManager.jumpOffRope(entry, bot, dx);
    }

    private static void tickFrozenAirborneWatchdog(BotMovementState entry) {
        if (!entry.inAir || entry.climbing) {
            entry.airStuckTicks = 0;
            entry.airStuckX = Integer.MIN_VALUE;
            return;
        }
        Point pos = entry.bot.getPosition();
        if (pos.x != entry.airStuckX || pos.y != entry.airStuckY) {
            entry.airStuckTicks = 0;
            entry.airStuckX = pos.x;
            entry.airStuckY = pos.y;
            return;
        }
        if (++entry.airStuckTicks < AIR_STUCK_RECOVER_TICKS) {
            return;
        }
        entry.airStuckTicks = 0;
        entry.airStuckX = Integer.MIN_VALUE;
        // Recovery: snap to ground beneath the goal (or current position).
        MapleMap map = entry.bot.getMap();
        NavStuckLog.record(NavStuckLog.Kind.AIR_FROZEN, map, pos, entry.navGraph, entry.navEdge,
                NavTrace.capture(entry));
        Point goal = entry.moveTarget != null ? entry.moveTarget : entry.navTargetPos;
        // 1.6.0 WatchGate: in front of a player, drop straight down onto the ground below instead of snapping
        // across the map to the ground under the goal; the bot then walks to its goal like anyone would.
        if (goal != null && !WatchGate.mayReposition(entry.bot, "air-frozen-recover")) {
            goal = null;
        }
        Point ground = recoveryGround(map, pos, goal);
        BotPhysicsEngine.teleportTo(entry, entry.bot, ground != null ? ground : pos);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        broadcastIfObserved(entry);
    }

    // Live fall-off-map recovery: if the bot has left the map's VR bounds by more than a slack margin (fell
    // below the floor or off the side), snap it back to solid ground under its current goal. Mirrors the
    // frozen-air watchdog's recovery, but keys off "outside the map" instead of "frozen in the air", so it
    // catches an active free-fall. Ours (Fable fluid-combat pass).
    private static void tickFallOffMapRecovery(BotMovementState entry) {
        Character bot = entry.bot;
        MapleMap map = (bot != null) ? bot.getMap() : null;
        if (map == null) {
            return;
        }
        Rectangle vr = map.getMapArea();
        if (vr == null || vr.width <= 0 || vr.height <= 0) {
            return; // no usable VR bounds — can't tell inside from outside
        }
        Point pos = bot.getPosition();
        if (pos == null) {
            return;
        }
        boolean belowFloor = pos.y > vr.y + vr.height + FALL_RECOVER_SLACK_PX;
        boolean offSides = pos.x < vr.x - FALL_RECOVER_SLACK_PX
                || pos.x > vr.x + vr.width + FALL_RECOVER_SLACK_PX;
        if (!belowFloor && !offSides) {
            return;
        }
        NavStuckLog.record(NavStuckLog.Kind.FELL_OFF_MAP, map, pos, entry.navGraph, entry.navEdge,
                NavTrace.capture(entry));
        boolean watched = WatchGate.enabled() && WatchGate.watched(bot);
        if (watched) {
            WatchGate.noteSnappedWhileWatched(bot, "fell-off-map"); // out of bounds: there is nothing to walk on
        }
        Point goal = fallRecoveryGoal(entry.moveTarget, entry.navTargetPos, watched);
        Point ground = recoveryGround(map, pos, goal);
        BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground
                : new Point(Math.max(vr.x, Math.min(vr.x + vr.width, pos.x)), vr.y));
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        // De-thrash: resetEntryStateAfterTeleport only clears NAV state, leaving moveTarget — so the bot
        // would re-aim at the same too-far-below goal and re-fire the same doomed descent (teleport loop =
        // the "glitchy" fall on tall maps). Drop the goal too so the brain re-decides a safe target next tick.
        entry.moveTarget = null;
        broadcastIfObserved(entry);
    }

    /**
     * 1.6.1 QA: the ground a fallen bot is put back on. Out of the map's bounds the snap cannot be a walk, but in
     * front of a player it must not also be a long jump: the old goal-first pick set the bot down under its
     * destination, possibly across the map. A watched bot is put back under where it fell instead (null goal makes
     * {@link #recoveryGround} use its own x), so it reappears right where the player last saw it.
     */
    static Point fallRecoveryGoal(Point moveTarget, Point navTarget, boolean watched) {
        if (watched) {
            return null;
        }
        return moveTarget != null ? moveTarget : navTarget;
    }

    /*
     * 1.5.0 rc5: where a fallen or frozen bot is put back - always onto a foothold when the map has one. In order:
     * the ground under its goal; the ground under where it is; the top floor at its x pulled into the footholds' x
     * range; the ground under the map's nearest spawn point. Null only for a map with no footholds and no spawn.
     *
     * The fall-off-map recovery used to clamp x only to the VR rectangle and, when no foothold lay under that x,
     * teleport the bot to (x, VR top) - into the sky. On El Nath 211030000 the VR reaches 58 px left of the first
     * foothold, so grinders that fell off the left edge were parked at (-908, -1195), fell off again, and looped
     * (the rc5 health sweep's two NO_GROUND bots; FELL_OFF_MAP at (-908, 737) and NO_REGION at (-908, -1195) in the
     * stuck log).
     */
    private static final int MAP_TOP_Y = -1_000_000; // above every map: findGroundPoint from here finds the top floor

    static Point recoveryGround(MapleMap map, Point pos, Point goal) {
        if (map == null) {
            return null;
        }
        Point ground = null;
        if (goal != null) {
            ground = BotPhysicsEngine.findGroundPoint(map, new Point(goal.x, goal.y - 1));
        }
        if (ground == null && pos != null) {
            ground = BotPhysicsEngine.findGroundPoint(map, new Point(pos.x, pos.y - 1));
        }
        FootholdTree footholds = map.getFootholds();
        if (ground == null && pos != null && footholds != null && footholds.getMinDropX() < footholds.getMaxDropX()) {
            int x = Math.max(footholds.getMinDropX(), Math.min(footholds.getMaxDropX(), pos.x));
            ground = BotPhysicsEngine.findGroundPoint(map, new Point(x, MAP_TOP_Y));
        }
        if (ground == null) {
            Portal spawn = nearestSpawnPortal(map, pos);
            if (spawn != null) {
                Point sp = spawn.getPosition();
                ground = BotPhysicsEngine.findGroundPoint(map, new Point(sp.x, sp.y - 1));
                if (ground == null) {
                    ground = new Point(sp);
                }
            }
        }
        return ground;
    }

    private static Portal nearestSpawnPortal(MapleMap map, Point pos) {
        Portal best = null;
        for (Portal p : map.getPortals()) {
            if (p.getType() != Portal.MAP_PORTAL && p.getType() != 0) {
                continue;
            }
            if (best == null || (pos != null && Math.abs(p.getPosition().x - pos.x) < Math.abs(best.getPosition().x - pos.x))) {
                best = p;
            }
        }
        return best;
    }
}
