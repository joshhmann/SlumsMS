package soloMapling.mechanics;

import client.Character;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Carries a solver's {@link SolverStep} out for one bot, one beat at a time (ticket 130 phase 1), with the bot's
 * existing systems only: walking through {@link GCMovement} (BotNav), swings through
 * {@link BotAttackDriver#botAttack} (the attacks FollowerFight already uses in party quests: no new skill, see the
 * client remote-attack check), loot through {@link DropCommands}, items handed over through {@link BotHoldings}, NPC
 * talk through our own NPC script ({@link BotNpcTalk}). Never touches a real player.
 */
final class StepExecutor {

    /** How close (px) a bot stands to an NPC before it clicks: a player clicks from about a screen-width step away. */
    static final int NPC_REACH_X = 110;
    static final int NPC_REACH_Y = 90;
    /** How close a bot walks to a member it drops something for, and to a drop it picks up. */
    static final int HANDOVER_REACH_X = 50;
    static final int LOOT_REACH_X = 45;
    static final int LOOT_SEARCH_PX = 600;
    /** A believable pause between two clicks on the NPC, and after dropping something. */
    static final long TALK_GAP_MS = 2_500;
    static final long DROP_GAP_MS = 1_500;

    /**
     * The gap between two drops on the same item reactor: the server's own 5000 ms activation delay
     * ({@code MapleMap.activateItemReactors}, {@code MapleMap.java:2665-2680}) plus a margin for the tick, so a
     * solver never reads a drop that has simply not fired yet as a refusal.
     */
    static final long ITEM_REACTOR_SETTLE_MS = 5_000 + 1_500;

    /** How many drops one bot gives a single item reactor before it says the bed will not take it. */
    static final int PLACE_ON_TRIES = 3;

    /** How often one bot may try a use-item on a monster (Ellin PQ's marble): the rule is HP-gated, so a spray is noise. */
    static final long USE_ON_MOB_GAP_MS = 1_000;

    /** How often one bot may touch a reactor. */
    static final long TOUCH_GAP_MS = 1_000;

    private final Map<Integer, Beat> beats = new ConcurrentHashMap<>();

    /**
     * 1.6.2 (KPQ pace): a mob another member of the run already goes for counts as this much more walk (ms, about
     * 400 px), so a party spreads over the platforms instead of all chasing one Ligator and hopping on together when
     * it dies.
     */
    static final int TAKEN_PENALTY_MS = 400 * 8;

    /**
     * How the executor puts an item on a bot: the gear system's own move ({@link
     * soloMapling.ArtificialPlayer.BotGearSystem.BotGear#wear}). A seam so a test can see what the step asked for without
     * a bot that has item data; production always runs the default.
     */
    static volatile java.util.function.BiFunction<Character, Integer, soloMapling.ArtificialPlayer.BotGearSystem.BotGear.WearResult>
            wearer = soloMapling.ArtificialPlayer.BotGearSystem.BotGear::wear;

    /** One wear attempt per bot per this long: a refusal is not retried every tick. */
    static final long EQUIP_GAP_MS = 3_000;

    /** Per-bot pacing: when it last clicked, dropped, or was sent somewhere. */
    /** How many warps in a row may fail before the run is ended and the bot's real map is reported. */
    static final int WARP_TRIES = 3;

    /** One warp that did not land: the beat's counter steps up; true once the run should stop retrying. */
    static boolean warpGaveUp(Beat beat) {
        beat.warpTries++;
        return beat.warpTries >= WARP_TRIES;
    }

    /** A step that names a map other than the run's current room is a solver bug, and is never carried out. */
    static boolean wrongRoom(SolverStep.WarpTo w, int stageMapId) {
        return w.mapId() != stageMapId;
    }

    static final class Beat {
        volatile long lastTalkMs;
        volatile boolean talking;
        long lastDropMs;
        long lastPortalMs;
        long lastSayMs;
        boolean toldLeader;
        int scriptFailures;
        Point moveTarget;
        long moveIssuedMs;
        int farmHave = -1;
        long farmSinceMs;
        /** The puzzle position this bot is walking to, and when it last got closer to it. */
        final OccupyWatch occupy = new OccupyWatch();
        /** The wait message this bot has been sitting on, since when, and when it last said it. */
        String waitMsg;
        long waitSinceMs;
        long waitToldMs;
        /** When this member was last put into another room, so a failed warp is retried rather than spammed. */
        long lastWarpMs;
        /** How many times in a row a warp was issued and did not take, and the last refusal it was told about. */
        int warpTries;
        String warpTold;
        /** When this bot last tried to put an item on, and the refusal it was last heard saying. */
        long lastEquipMs = Long.MIN_VALUE / 2;
        String equipToldWhy;
        /** When this bot last hit the room's reactor, so the click is not spammed. */
        long lastReactorMs;
        /** When this bot last put a stack on a reactor, and when it last said it is still gathering it. */
        long lastPlaceMs;
        /** When this bot last dropped onto an item reactor, and how often it has dropped on each reactor id. */
        long lastPlaceOnMs;
        long lastUseOnMobMs;
        long lastTouchMs;
        final java.util.Map<Integer, Integer> placeOnTries = new java.util.HashMap<>();
        boolean stallTold;
        int stallGain;
        final Chase chase = new Chase();
    }

    void forget(int botId) {
        beats.remove(botId);
    }

    void execute(RunState run, Orchestrator orchestrator, LiveStageView view, Character bot, SolverStep step, long now,
                 Consumer<String> log) {
        Beat beat = beats.computeIfAbsent(bot.getId(), id -> new Beat());
        switch (step) {
            case SolverStep.MoveTo m -> moveNear(bot, beat, m.x(), m.y(), 30, now);
            case SolverStep.Fight f -> fight(view, bot, beat, f, now, log);
            case SolverStep.Loot l -> loot(view, bot, beat, l.itemId(), now);
            case SolverStep.TakeBack t -> takeBack(view, bot, beat, t, now, log);
            case SolverStep.TalkNpc t -> talk(run, orchestrator, view, bot, beat, t, now, log);
            case SolverStep.TalkNpcAt t -> talkAt(run, orchestrator, view, bot, beat, t, now, log);
            case SolverStep.DropFor d -> dropFor(view, bot, beat, d, now, log);
            case SolverStep.Say s -> say(view, bot, beat, s.line(), now);
            case SolverStep.HitReactor h -> hitReactor(bot, beat, h, now, log);
            case SolverStep.UseItemOnMob u -> useItemOnMob(bot, beat, u, now, log);
            case SolverStep.TouchReactor t -> touchReactor(bot, beat, t.reactorId(), null, now, log);
            case SolverStep.TouchReactorNamed tn -> touchReactor(bot, beat, 0, tn.reactorName(), now, log);
            case SolverStep.HitReactorNamed h -> {
                MapleMap named = bot.getMap();
                server.maps.Reactor r = named == null || h.reactorName() == null
                        ? null : named.getReactorByName(h.reactorName());
                if (r == null) {
                    if (now - beat.lastReactorMs > 10_000) {
                        beat.lastReactorMs = now;
                        log.accept(bot.getName() + ": no reactor \"" + h.reactorName() + "\" on map " + bot.getMapId());
                    }
                } else {
                    hitReactorAt(bot, beat, r, h.reactorName(), now, log);
                }
            }
            case SolverStep.HitReactorByOid h -> hitReactorByOid(bot, beat, h, now, log);
            case SolverStep.EquipItem e -> equipItem(bot, beat, e, now, log);
            case SolverStep.UnequipItem u -> unequipItem(bot, beat, u, now, log);
            case SolverStep.PlaceItem p -> placeItem(bot, beat, p, now, log);
            case SolverStep.PlaceItemOn p -> placeItemOn(bot, beat, p, now, log);
            case SolverStep.Occupy o -> occupy(bot, beat, o, now, log);
            case SolverStep.CheckAnswer c -> check(run, orchestrator, bot, beat, c, now, log);
            case SolverStep.Converse c -> converse(run, orchestrator, bot, beat, c, now, log);
            case SolverStep.EnterPortal e -> enterPortal(bot, beat, e, now, log);
            case SolverStep.EnterPortalTo to -> enterPortalTo(bot, beat, to, now, log);
            case SolverStep.ScriptedPortal p -> scriptedPortal(bot, beat, p, now, log);
            case SolverStep.WarpTo w -> warp(view, bot, beat, w, now, log);
            case SolverStep.Wait w -> waitNote(bot, beat, w, now, log);
            case SolverStep.Done d -> {
            }
            case SolverStep.Abandon a -> {
                // the orchestrator ends the run on this reason before any executor sees it
            }
        }
    }

    // ---- steps ----

    /**
     * The window over which a farming bot's pace is judged (1.6.2, b153: on TEST most lobby answerers never handed
     * anything in and the log could not say why). 1.6.2 KPQ pace: was "no change in 3 minutes"; a bot that crept up
     * one coupon now and then never said anything.
     */
    static final long STALL_MS = 90_000L;
    /** Fewer coupons than this in one {@link #STALL_MS} window is a stall: under 2 a minute. */
    static final int STALL_MIN_GAIN = 3;

    /**
     * How long a stander may be on its way to a puzzle position before it says where it is stuck, and how often it
     * says it again. mx-2 on TEST (run 4d3de9837) spent 1164 s between a wrong check and the right one with no log
     * line at all: one member never reached its position and nothing else is written while it walks.
     */
    static final long OCCUPY_NOTE_MS = 30_000L;

    /**
     * How much closer (px) a stander must get to its position for the clock to start again. The rope rectangles are
     * 4 px wide and 150-222 px tall ({@code content/kpq.yaml:144-147}), so a climber crossing one covers 30+ px
     * between two notes at 30 s apart: anything under this is standing still, which is what the note is about.
     */
    static final int OCCUPY_PROGRESS_PX = 30;

    /**
     * A gap this long between two ticks of the same walk means the bot was doing something else in between (standing
     * on the position, so the solver answered with a Wait; or sent off it), so the next tick is a new walk.
     */
    static final long OCCUPY_GAP_MS = 10_000L;

    /** Whether a stander that has made no progress since {@code sinceMs} should say so now (once per window). */
    static boolean occupyDueForNote(long sinceMs, long toldMs, long now) {
        return now - sinceMs >= OCCUPY_NOTE_MS && now - toldMs >= OCCUPY_NOTE_MS;
    }

    /**
     * One stander's walk to one puzzle position. The old note fired on "has not arrived in 30 s", and on TEST that
     * was 58% false (of 1,513 OCCUPY lines, 873 were followed within 30 s by {@code WAIT <same bot> ... on <same
     * position>}, median 5 s later - 1,157 counting later ones too - and 478 of those came under 30 s after the bot
     * had already said it stood on the very position: the clock reset only on a change of position id, so a second
     * walk to the same rope re-used the first walk's clock).
     *
     * <p>Two things fix that. A walk is contiguous: {@link #sinceStepMs} is the last tick this watch was asked about,
     * so a gap (the bot stood on the position and the solver answered with a Wait, or was sent elsewhere) starts a new
     * walk. And the clock is progress, as {@link Chase} uses for fights: it restarts whenever the bot is
     * {@link #OCCUPY_PROGRESS_PX} px nearer, so a climbing bot is never reported. The note fires only when neither
     * has happened for {@link #OCCUPY_NOTE_MS}.
     */
    static final class OccupyWatch {
        String positionId;
        /** Distance to the aim point at the last progress, and when that was. */
        int bestPx = -1;
        long sinceMs;
        long toldMs;
        /** When this walk began (for the line) and when the watch was last asked about it (for continuity). */
        long startedMs;
        long sinceStepMs;

        /**
         * No progress for {@link #OCCUPY_NOTE_MS} toward {@code o}: true once per window. A gap of more than
         * {@link #OCCUPY_GAP_MS} between two calls means the bot was not walking here in between, so this is a new
         * walk.
         */
        boolean due(SolverStep.Occupy o, Point at, long now) {
            int px = distanceTo(o, at);
            boolean contiguous = now - sinceStepMs <= OCCUPY_GAP_MS && sinceStepMs <= now;
            if (!o.positionId().equals(positionId) || !contiguous) {
                start(o, px, now);
                return false;
            }
            sinceStepMs = now;
            if (px <= bestPx - OCCUPY_PROGRESS_PX || px >= bestPx + OCCUPY_PROGRESS_PX) {
                // Clearly closer (climbing) or clearly further (walked away, then sent back): a new walk either way.
                start(o, px, now);
                return false;
            }
            if (!occupyDueForNote(sinceMs, toldMs, now)) {
                return false;
            }
            toldMs = now;
            return true;
        }

        private void start(SolverStep.Occupy o, int px, long now) {
            positionId = o.positionId();
            bestPx = px;
            startedMs = now;
            sinceMs = now;
            sinceStepMs = now;
            toldMs = 0;
        }

        /** How long this walk has gone on, for the line. */
        long walkMs(long now) {
            return now - startedMs;
        }

        /** Arrived: the next walk to this position starts from zero. */
        void arrived() {
            positionId = null;
            bestPx = -1;
            startedMs = 0;
            sinceStepMs = 0;
            toldMs = 0;
        }

        private static int distanceTo(SolverStep.Occupy o, Point at) {
            if (at == null) {
                return Integer.MAX_VALUE / 2;
            }
            return (int) Math.hypot(at.x - o.x(), at.y - o.y());
        }
    }

    /** Whether a member sitting on the same wait since {@code sinceMs} should say it now (once a minute after 30 s). */
    static boolean waitDueForNote(long sinceMs, long toldMs, long now) {
        return now - sinceMs >= OCCUPY_NOTE_MS && now - toldMs >= WAIT_AGAIN_MS;
    }

    static final long WAIT_AGAIN_MS = 60_000L;

    /**
     * mx-2 (TEST 10-31): a waiting member wrote nothing, so 1164 s of a stage that never became exact left no trace.
     * A bot that sits on one wait message for 30 s now says what it waits for and where it stands, and repeats at most
     * once a minute while the message stays the same; a new step starts it over.
     */
    private static void waitNote(Character bot, Beat beat, SolverStep.Wait w, long now, Consumer<String> log) {
        String msg = w.why();
        if (msg == null || msg.isBlank()) {
            beat.waitMsg = null;
            beat.waitSinceMs = 0;
            beat.waitToldMs = 0;
            return;
        }
        if (!msg.equals(beat.waitMsg)) {
            beat.waitMsg = msg;
            beat.waitSinceMs = now;
            beat.waitToldMs = 0;
            return;
        }
        if (!waitDueForNote(beat.waitSinceMs, beat.waitToldMs, now)) {
            return;
        }
        beat.waitToldMs = now;
        log.accept(waitNoteLine(bot.getName(), msg, bot.getPosition(), now - beat.waitSinceMs));
    }

    static String waitNoteLine(String name, String msg, Point at, long forMs) {
        return "WAIT " + name + " " + forMs / 1000 + " s: " + msg + (at == null ? "" : " (at " + at.x + "," + at.y + ")");
    }

    /**
     * Whether a farmer got fewer than {@link #STALL_MIN_GAIN} in the {@link #STALL_MS} window that ends now; true
     * once per run of slow windows (a window at pace lets it tell again). A count that went down (a hand-in, extras
     * dropped) starts the window again. {@code beat.farmHave} keeps the count the window started with.
     */
    static boolean stalled(Beat beat, int have, long now) {
        if (beat.farmHave < 0 || have < beat.farmHave) {
            beat.farmHave = have;
            beat.farmSinceMs = now;
            return false;
        }
        if (now - beat.farmSinceMs < STALL_MS) {
            return false;
        }
        boolean slow = have - beat.farmHave < STALL_MIN_GAIN;
        boolean tell = slow && !beat.stallTold;
        beat.stallTold = slow;
        beat.stallGain = have - beat.farmHave;
        beat.farmHave = have;
        beat.farmSinceMs = now;
        return tell;
    }

    /**
     * The target a Fight step is counted against, as a line should print it. A collector on KPQ stage 5 gets
     * {@code Fight(0, 4001008, Integer.MAX_VALUE)} ({@link KillCollectHandInSolver}), meaning "fight while the park
     * has monsters", and printing the sentinel made 180 of 186 TEST STALL lines read {@code on 0/2147483647 x
     * 4001008} (2026-11-01 00:31 -> 11-02 03:16; 181 of 186 counting any held count) - a requirement no one can
     * read as anything but broken. The count
     * the collector is really held to is the leader's {@code HAND_IN count 10}
     * ({@code content/kpq.yaml:311-316}), which is his own step, not this one's.
     */
    static String fightTargetLabel(SolverStep.Fight f) {
        if (f.itemId() <= 0) {
            return "";
        }
        return f.untilItemCount() == Integer.MAX_VALUE ? "all" : Integer.toString(f.untilItemCount());
    }

    /** One STALL line: how many the bot holds against what its step is counted against, then the reason. */
    static String stallLine(String name, int held, SolverStep.Fight f, String why) {
        return "STALL " + name + " on " + held + "/" + fightTargetLabel(f) + " x " + f.itemId() + ": " + why;
    }

    /** What a stalled farmer's bag and floor look like, for the STALL line. */
    private static String stallWhy(LiveStageView view, Character bot, int itemId) {
        try {
            client.inventory.Inventory etc = bot.getInventory(client.inventory.InventoryType.ETC);
            int free = etc.getNumFreeSlot();
            boolean fits = BotHoldings.canHold(bot, new client.inventory.Item(itemId, (short) 0, (short) 1));
            MapleMap map = bot.getMap();
            int onFloor = 0;
            int takeable = 0;
            if (map != null) {
                for (server.maps.MapObject o : map.getMapObjectsInRange(bot.getPosition(),
                        (double) LOOT_SEARCH_PX * LOOT_SEARCH_PX, List.of(server.maps.MapObjectType.ITEM))) {
                    if (o instanceof MapItem mi && mi.getItemId() == itemId && mi.getDropper() instanceof Monster) {
                        onFloor++;
                        if (BotHoldings.canCreditDrop(bot, mi)) {
                            takeable++;
                        }
                    }
                }
            }
            Point at = bot.getPosition();
            return "etc " + free + " free of " + etc.getSlotLimit() + (fits ? "" : ", CANNOT HOLD " + itemId) + ", "
                    + onFloor + " on the floor within " + LOOT_SEARCH_PX + " px (" + takeable + " it may take), at "
                    + (at == null ? "?" : at.x + "," + at.y) + ", level " + bot.getLevel();
        } catch (RuntimeException e) {
            return "(could not look: " + e + ")";
        }
    }

    private void fight(LiveStageView view, Character bot, Beat beat, SolverStep.Fight f, long now,
                       Consumer<String> log) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        Point at = bot.getPosition();
        if (f.itemId() > 0 && stalled(beat, bot.getItemQuantity(f.itemId(), false), now)) {
            log.accept(stallLine(bot.getName(), beat.farmHave, f, "+" + beat.stallGain + " in " + STALL_MS / 1000
                    + " s (under 2 a minute): " + stallWhy(view, bot, f.itemId())));
        }
        if (f.itemId() > 0 && bot.getItemQuantity(f.itemId(), false) < f.untilItemCount()) {
            MapItem drop = nearestMobDrop(view, map, bot, f.itemId(), at);
            if (drop != null) {
                pickUp(bot, beat, drop, now);
                return;
            }
        }
        Monster target = beat.chase.keep(map, now);
        if (target == null || !mayStillChase(bot, target, f, beat, now)) {
            target = pickCandidate(bot, f, beat, now);
            beat.chase.picked(target, now);
        }
        if (target == null) {
            return; // the stage respawns its mobs every 15 s (KerningPQ.respawnStages): wait for them
        }
        int[] reach = soloMapling.ArtificialPlayer.BotGrindSystem.Approach.reach(bot);
        if (!BotAttackDriver.canSwingFrom(map, at, target, reach[0], reach[1])) {
            if (beat.chase.walking(target.getObjectId(), BotAttackDriver.costTo(bot, target), now)) {
                log.accept("FIGHT " + bot.getName() + " gives up on " + target.getName() + " at "
                        + target.getPosition().x + "," + target.getPosition().y + " for " + Chase.SKIP_MS / 1000
                        + " s (no way closer in " + Chase.NO_PROGRESS_MS / 1000 + " s)");
                return;
            }
            int[] spot = soloMapling.ArtificialPlayer.BotGrindSystem.Approach.fightSpot(bot, target, reach,
                    soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning.human());
            moveNear(bot, beat, spot[0], spot[1], Math.max(10, reach[0] / 3), now);
            return;
        }
        beat.chase.inReach(target.getObjectId(), now);
        if (GCMovement.isFollowing(bot)) {
            GCMovement.stop(bot);
        }
        BotAttackDriver.AttackResult r = BotAttackDriver.botAttack(bot);
        if (BotAttackDriver.noTargetableMob(r)) {
            beat.chase.skip(target.getObjectId(), now); // the swing sees none it may hit from here: another one
        }
    }

    /**
     * The driver's pick for the step, trying its mob kinds in the order the step lists them (a boss: arms before the
     * body, the nearest arm first). The first kind with a pickable mob wins; none left is null.
     */
    private Monster pickCandidate(Character bot, SolverStep.Fight f, Beat beat, long now) {
        for (int mobId : f.mobIds()) {
            Monster m = BotAttackDriver.pickTarget(bot, mobId, m2 -> beat.chase.skipped(m2.getObjectId(), now)
                    || !f.onLeash(m2.getPosition().x, m2.getPosition().y), m2 -> takenByAnother(bot.getId(), m2));
            if (m != null) {
                return m;
            }
        }
        return f.mobIds().isEmpty() ? BotAttackDriver.pickTarget(bot, 0, m2 -> beat.chase.skipped(m2.getObjectId(), now)
                || !f.onLeash(m2.getPosition().x, m2.getPosition().y), m2 -> takenByAnother(bot.getId(), m2)) : null;
    }

    /** {@link #TAKEN_PENALTY_MS} when another bot this executor drives goes for {@code m}, else 0. */
    private int takenByAnother(int botId, Monster m) {
        for (Map.Entry<Integer, Beat> e : beats.entrySet()) {
            if (e.getKey() != botId && e.getValue().chase.target == m) {
                return TAKEN_PENALTY_MS;
            }
        }
        return 0;
    }

    /** The kept target is still one to chase: of the step's mob, not ruled out, and still the swing's to hit. */
    private static boolean mayStillChase(Character bot, Monster m, SolverStep.Fight f, Beat beat, long now) {
        return m.isAlive() && f.ofKind(m.getId()) && !beat.chase.skipped(m.getObjectId(), now)
                && f.onLeash(m.getPosition().x, m.getPosition().y)
                && now - beat.chase.pickedMs < Chase.REPICK_MS
                && BotAttackDriver.pickable(bot, m);
    }

    /**
     * 1.6.2 (KPQ pace, b153/b204): a fighting bot's hold on its target. While it walks to the mob, the walk's cost
     * (the nav planner's, {@link BotAttackDriver#costTo}) must drop by {@link #MIN_GAIN_MS} (about 20 px at walking
     * pace) within {@link #NO_PROGRESS_MS}, else the mob is skipped for {@link #SKIP_MS} and another picked; a swing
     * that sees no mob it may hit skips it too. The target is re-picked every {@link #REPICK_MS} (a cheaper one may
     * have spawned). Before, a bot kept walking at a mob it could not reach (TEST: hanging on the x=-437 rope, standing
     * under the y=-340 platform) for the whole run.
     */
    static final class Chase {
        static final long NO_PROGRESS_MS = 3_000;
        static final int MIN_GAIN_MS = 20 * 8;
        static final long SKIP_MS = 10_000;
        static final long REPICK_MS = 2_000;

        int oid = -1;
        volatile Monster target;
        long pickedMs;
        int bestCost = Integer.MAX_VALUE;
        long sinceMs;
        final Map<Integer, Long> skipUntil = new java.util.HashMap<>();

        boolean skipped(int oid, long now) {
            Long until = skipUntil.get(oid);
            return until != null && until > now;
        }

        void skip(int oid, long now) {
            skipUntil.values().removeIf(until -> until <= now);
            skipUntil.put(oid, now + SKIP_MS);
            if (this.oid == oid) {
                this.oid = -1;
                target = null;
            }
        }

        /** The target held, if it is still on this map. */
        Monster keep(MapleMap map, long now) {
            return target != null && target.getMap() == map ? target : null;
        }

        void picked(Monster m, long now) {
            int id = m == null ? -1 : m.getObjectId();
            if (id != oid) {
                oid = id;
                bestCost = Integer.MAX_VALUE;
                sinceMs = now;
            }
            target = m;
            pickedMs = now;
        }

        /** One walking beat toward {@code oid}, the walk costing {@code cost} ms; true when it gave up on the mob. */
        boolean walking(int oid, int cost, long now) {
            if (oid != this.oid) {
                this.oid = oid;
                bestCost = cost;
                sinceMs = now;
                return false;
            }
            if (bestCost == Integer.MAX_VALUE || cost <= bestCost - MIN_GAIN_MS) {
                bestCost = cost;
                sinceMs = now;
                return false;
            }
            if (now - sinceMs >= NO_PROGRESS_MS) {
                skip(oid, now);
                return true;
            }
            return false;
        }

        /** In reach of {@code oid}: the clock starts again if it has to walk once more. */
        void inReach(int oid, long now) {
            this.oid = oid;
            bestCost = Integer.MAX_VALUE;
            sinceMs = now;
        }
    }

    /**
     * The nearest drop of {@code itemId} that a monster left and this bot may take (never a member's handover):
     * its own kill, a party member's by the party loot rule ({@link QuestionCouponsSolver#mayTakeMobCoupon}), or
     * anyone's once the ownership window ran out. 1.6.2: was only its own or a free-for-all drop (the scan skipped
     * every coupon a fellow bot's kill dropped), so each bot farmed only its own kills.
     */
    private static MapItem nearestMobDrop(StageView view, MapleMap map, Character bot, int itemId, Point at) {
        MapItem best = null;
        double bestSq = (double) LOOT_SEARCH_PX * LOOT_SEARCH_PX;
        for (server.maps.MapObject o : map.getMapObjectsInRange(at, bestSq,
                List.of(server.maps.MapObjectType.ITEM))) {
            if (!(o instanceof MapItem mi) || mi.getMeso() != 0 || mi.getItemId() != itemId
                    || !(mi.getDropper() instanceof Monster)
                    || !soloMapling.ArtificialPlayer.BotGrindSystem.LootPicker.onTheFloor(mi)
                    || !(mi.getDropType() == 2 || QuestionCouponsSolver.mayTakeMobCoupon(view, bot.getId(),
                    mi.getOwnerId(), mi.hasExpiredOwnershipTime()))
                    || !BotHoldings.canCreditDrop(bot, mi)) {
                continue;
            }
            double d = at.distanceSq(mi.getPosition());
            if (d < bestSq) {
                bestSq = d;
                best = mi;
            }
        }
        return best;
    }

    private void loot(LiveStageView view, Character bot, Beat beat, int itemId, long now) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        MapItem best = null;
        double bestSq = Double.MAX_VALUE;
        for (MapItem mi : LiveStageView.floorDrops(view, map, itemId, bot.getId())) {
            // floorDrops already applies the stage's rule; botCanLoot still guards the shared drop commands' own
            // ownership window, so take whatever either of them allows (a party member's pass is the former's).
            if (!DropCommands.botCanLoot(bot, mi) && !LiveStageView.mayTake(view, bot.getId(), mi)) {
                continue;
            }
            if (!BotHoldings.canCreditDrop(bot, mi)) {
                continue;
            }
            double d = bot.getPosition().distanceSq(mi.getPosition());
            if (d < bestSq) {
                bestSq = d;
                best = mi;
            }
        }
        if (best != null) {
            pickUp(bot, beat, best, now);
        }
    }

    private void pickUp(Character bot, Beat beat, MapItem drop, long now) {
        Point at = bot.getPosition();
        Point p = drop.getPosition();
        if (Math.abs(p.x - at.x) > LOOT_REACH_X || Math.abs(p.y - at.y) > 60) {
            moveNear(bot, beat, p.x, p.y, LOOT_REACH_X / 2, now);
            return;
        }
        // 1.6.5 b331: wait out the drop's fly-out first, as grind loot does. A bot already standing at the chest
        // took the drop on the next tick, mid-animation on the players' clients, and an owner's client in ZPQ stage
        // 1 with its alt closed 4 of 4 times (TEST 2026-11-06, Client diag2).
        if (!soloMapling.ArtificialPlayer.BotGrindSystem.LootPicker.settled(drop,
                soloMapling.ArtificialPlayer.BotGrindSystem.LootPicker.SETTLE_MS, now)) {
            return;
        }
        DropCommands.botLootSingleDrop(bot, drop);
    }

    private void talk(RunState run, Orchestrator orchestrator, LiveStageView view, Character bot, Beat beat,
                      SolverStep.TalkNpc t, long now, Consumer<String> log) {
        int[] npc = npcOn(bot, t.npcId());
        if (npc == null) {
            log.accept(bot.getName() + ": NPC " + t.npcId() + " is not on map " + bot.getMapId());
            return;
        }
        Point at = bot.getPosition();
        if (Math.abs(npc[0] - at.x) > NPC_REACH_X || Math.abs(npc[1] - at.y) > NPC_REACH_Y) {
            int side = at.x < npc[0] ? -1 : 1; // stop on the side it comes from, as a player would
            moveNear(bot, beat, npc[0] + side * (NPC_REACH_X / 2), npc[1], NPC_REACH_X / 3, now);
            return;
        }
        if (beat.talking || now - beat.lastTalkMs < TALK_GAP_MS) {
            return;
        }
        beat.lastTalkMs = now;
        beat.talking = true;
        if (t.itemId() > 0) {
            orchestrator.noteHandIn(run, bot.getId(), t.itemId(), t.count(), now);
        }
        // the script runs in its own engine (a few hundred ms in interpreted mode): off the shared tick thread
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                BotNpcTalk.Result r = BotNpcTalk.INSTANCE.talk(bot, t.npcId());
                String said = r.lastLine().replaceAll("#[a-zA-Z]", "").replace("\r\n", " ");
                if (said.length() > 90) {
                    said = said.substring(0, 90) + "...";
                }
                String line = "NPC " + bot.getName() + " -> " + t.npcId()
                        + (t.itemId() > 0 ? " with " + t.count() + " x " + t.itemId() : "")
                        + (r.ran() ? ": \"" + said + "\"" : " FAILED: " + r.error());
                scriptResult(run, orchestrator, beat, bot, t.npcId(), r, line, log);
            } finally {
                beat.lastTalkMs = System.currentTimeMillis();
                beat.talking = false;
            }
        });
    }

    /**
     * One standing of an NPC, addressed by object id. The walk target is <b>that</b> placement's position, not the first
     * one {@code getNPCById} happens to return, and the script is handed the object id so {@code cm.getNpcObjectId()}
     * answers with the one the row meant.
     */
    private void talkAt(RunState run, Orchestrator orchestrator, LiveStageView view, Character bot, Beat beat,
                        SolverStep.TalkNpcAt t, long now, Consumer<String> log) {
        int[] npc = null;
        for (StageView.NpcPlacement p : view.npcPlacements(t.npcId())) {
            if (p.objectId() == t.objectId()) {
                npc = new int[]{p.x(), p.y()};
                break;
            }
        }
        if (npc == null) {
            if (now - beat.lastTalkMs > 10_000) {
                beat.lastTalkMs = now;
                log.accept(bot.getName() + ": no placement " + t.objectId() + " of NPC " + t.npcId()
                        + " on map " + bot.getMapId());
            }
            return;
        }
        Point at = bot.getPosition();
        if (Math.abs(npc[0] - at.x) > NPC_REACH_X || Math.abs(npc[1] - at.y) > NPC_REACH_Y) {
            int side = at.x < npc[0] ? -1 : 1;
            moveNear(bot, beat, npc[0] + side * (NPC_REACH_X / 2), npc[1], NPC_REACH_X / 3, now);
            return;
        }
        if (beat.talking || now - beat.lastTalkMs < TALK_GAP_MS) {
            return;
        }
        beat.lastTalkMs = now;
        beat.talking = true;
        int npcId = t.npcId();
        int objectId = t.objectId();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                BotNpcTalk.Result r = BotNpcTalk.INSTANCE.talkAt(bot, npcId, objectId);
                String said = r.lastLine().replaceAll("#[a-zA-Z]", "").replace("\r\n", " ");
                if (said.length() > 90) {
                    said = said.substring(0, 90) + "...";
                }
                String line = "NPC " + bot.getName() + " -> " + npcId + "@" + objectId
                        + (r.ran() ? ": \"" + said + "\"" : " FAILED: " + r.error());
                scriptResult(run, orchestrator, beat, bot, npcId, r, line, log);
            } finally {
                beat.lastTalkMs = System.currentTimeMillis();
                beat.talking = false;
            }
        });
    }

    private void dropFor(LiveStageView view, Character bot, Beat beat, SolverStep.DropFor d, long now,
                         Consumer<String> log) {
        if (now - beat.lastDropMs < DROP_GAP_MS) {
            return;
        }
        Character to = d.toCharacterId() > 0 ? view.character(d.toCharacterId()) : null;
        boolean realLeader = to != null && !MechanicsRuns.drivable(to);
        Point dropAt = bot.getPosition();
        if (d.toCharacterId() > 0) {
            if (to == null || to.getMap() != bot.getMap()) {
                return; // the leader is not on this map (yet): hold the pass
            }
            Point p = to.getPosition();
            // 1.6.2: within PassHandover.REACH_PX of him (the shared Approach reach test), else walk up first
            int[] reach = realLeader ? new int[]{PassHandover.REACH_PX, PassHandover.REACH_Y}
                    : new int[]{HANDOVER_REACH_X, 60};
            if (!soloMapling.ArtificialPlayer.BotGrindSystem.Approach.inReach(bot.getPosition(), p, reach)) {
                moveNear(bot, beat, p.x, p.y, HANDOVER_REACH_X / 2, now);
                return;
            }
            if (realLeader) {
                dropAt = new Point(p); // at his feet, not the bot's
            }
        }
        beat.lastDropMs = now;
        int paid = BotHoldings.payOut(bot, d.itemId(), d.count(), to == null ? bot : to, dropAt);
        log.accept("DROP " + bot.getName() + " drops " + paid + " x " + d.itemId()
                + (to == null ? " (extras)" : " for " + to.getName()));
        if (paid > 0 && realLeader && !beat.toldLeader) {
            // a real leader may be anywhere on the map: say where the NPC who takes the passes stands (TEST 10-30:
            // the tester waited by the spawn on the far left, Cloto is on the far right)
            beat.toldLeader = true;
            int[] npc = npcOn(bot, view.stage().npcId());
            beat.lastSayMs = now;
            SocialCommands.BotSpeak(bot, passLine(to.getName(), npc == null ? null : npc[0], to.getPosition().x));
        }
    }

    /**
     * 1.6.2: picks its own pass back up from a real leader's feet ({@link PassHandover#takeBack} decided he left it):
     * only the drop this bot dropped, walked to and picked up through the bot's own loot call, which keeps the game's
     * rule (by now the ownership window is over). The next step walks it to the leader again.
     */
    private void takeBack(LiveStageView view, Character bot, Beat beat, SolverStep.TakeBack t, long now,
                          Consumer<String> log) {
        MapItem mine = LiveStageView.ownDrop(bot.getMap(), t.itemId(), bot.getId());
        if (mine == null || !DropCommands.botCanLoot(bot, mine)) {
            return;
        }
        Point at = bot.getPosition();
        Point p = mine.getPosition();
        if (Math.abs(p.x - at.x) > LOOT_REACH_X || Math.abs(p.y - at.y) > 60) {
            moveNear(bot, beat, p.x, p.y, LOOT_REACH_X / 2, now);
            return;
        }
        int before = bot.getItemQuantity(t.itemId(), false);
        DropCommands.botLootSingleDrop(bot, mine);
        if (bot.getItemQuantity(t.itemId(), false) > before) {
            beat.toldLeader = false; // the next drop tells him again
            log.accept("TAKE BACK " + bot.getName() + " picks its " + t.itemId() + " back up (" + t.why() + ")");
        }
    }

    /** What a bot says once to a real leader when it drops its pass at his feet. */
    static String passLine(String leader, Integer npcX, int leaderX) {
        String where = npcX == null || Math.abs(npcX - leaderX) < 300 ? "" : npcX > leaderX ? " on the right" : " on the left";
        return leader + " dropped my pass by u, take them to Cloto" + where;
    }

    /** Failed NPC script runs in a row after which the run is abandoned (bot audit K10). */
    static final int MAX_SCRIPT_FAILURES = 3;

    /**
     * What one NPC talk's outcome means for the log and the run (bot audit K10: a broken stage script logged one
     * FAILED line per bot every 2.5 s, about 720 per bot per run). A success is logged and clears the count; a
     * failure is logged once in a row, and the {@link #MAX_SCRIPT_FAILURES}th in a row abandons the run.
     *
     * @return {log the line, abort the run}
     */
    static boolean[] scriptOutcome(Beat beat, boolean ran) {
        if (ran) {
            beat.scriptFailures = 0;
            return new boolean[]{true, false};
        }
        beat.scriptFailures++;
        return new boolean[]{beat.scriptFailures == 1, beat.scriptFailures >= MAX_SCRIPT_FAILURES};
    }

    private static void scriptResult(RunState run, Orchestrator orchestrator, Beat beat, Character bot, int npcId,
                                     BotNpcTalk.Result r, String line, Consumer<String> log) {
        boolean[] what = scriptOutcome(beat, r.ran());
        if (what[0]) {
            log.accept(line);
        }
        if (what[1]) {
            orchestrator.fail(run, "NPC " + npcId + "'s script failed " + MAX_SCRIPT_FAILURES + " times in a row for "
                    + bot.getName() + ": " + r.error());
        }
    }

    /** Where an NPC stands on the bot's own map (the bot talks where it is), or null. */
    static int[] npcOn(Character bot, int npcId) {
        MapleMap map = bot.getMap();
        server.life.NPC npc = map == null ? null : map.getNPCById(npcId);
        Point p = npc == null ? null : npc.getPosition();
        return p == null ? null : new int[]{p.x, p.y};
    }

    /** A party line; "@<id>" in it becomes that member's name. At most one line per bot every few seconds. */
    private void say(LiveStageView view, Character bot, Beat beat, String line, long now) {
        if (now - beat.lastSayMs < 4_000) {
            return;
        }
        beat.lastSayMs = now;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@(\\d+)").matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Character c = view.character(Integer.parseInt(m.group(1)));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(c == null ? "u" : c.getName()));
        }
        m.appendTail(out);
        SocialCommands.BotSpeak(bot, out.toString());
    }

    /** Stand on (or hang from) a puzzle position: walk/climb at it until the bot is inside its rectangle, then hold. */
    private static void occupy(Character bot, Beat beat, SolverStep.Occupy o, long now, Consumer<String> log) {
        Point at = bot.getPosition();
        if (at != null && o.inside(at.x, at.y)) {
            beat.occupy.arrived();
            return; // there: hold still (the formation is checked by the leader)
        }
        if (beat.occupy.due(o, at, now)) {
            log.accept(occupyStallLine(bot, o, at, beat.occupy.walkMs(now), now - beat.occupy.sinceMs));
        }
        Point target = new Point(o.x(), o.y());
        if (beat.moveTarget == null || !beat.moveTarget.equals(target) || now - beat.moveIssuedMs > 4_000) {
            GCMovement.move(bot, o.x(), o.y());
            beat.moveTarget = target;
            beat.moveIssuedMs = now;
        }
    }

    private static String occupyStallLine(Character bot, SolverStep.Occupy o, Point at, long walkMs, long stalledMs) {
        return occupyStallLine(bot.getName(), o, at, walkMs, stalledMs);
    }

    /**
     * {@code OCCUPY <bot> has not got closer to <pos> for <stalled> s (walking <walk> s): at x,y, wants x,y}. It only
     * fires when the bot is still not making progress at the deadline, and the two times are the pair a reader needs:
     * how long it has been stuck, and how long it has been trying at all.
     */
    static String occupyStallLine(String name, SolverStep.Occupy o, Point at, long walkMs, long stalledMs) {
        return "OCCUPY " + name + " has not got closer to " + o.positionId() + " for "
                + (stalledMs + 500) / 1_000 + " s (walking " + (walkMs + 500) / 1_000 + " s): at "
                + (at == null ? "nowhere" : at.x + "," + at.y) + ", wants " + o.x() + "," + o.y()
                + (o.rope() ? " (a rope)" : "");
    }

    /** The leader checks the formation with the NPC; from where he stands when he is within its reach. */
    private void check(RunState run, Orchestrator orchestrator, Character bot, Beat beat, SolverStep.CheckAnswer c,
                       long now, Consumer<String> log) {
        int[] npc = npcOn(bot, c.npcId());
        if (npc == null) {
            log.accept(bot.getName() + ": NPC " + c.npcId() + " is not on map " + bot.getMapId());
            return;
        }
        Point at = bot.getPosition();
        int reach = Math.max(NPC_REACH_X, c.reachPx());
        if (Math.abs(npc[0] - at.x) > reach || Math.abs(npc[1] - at.y) > Math.max(NPC_REACH_Y, reach / 2)) {
            int side = at.x < npc[0] ? -1 : 1;
            moveNear(bot, beat, npc[0] + side * (NPC_REACH_X / 2), npc[1], NPC_REACH_X / 3, now);
            return;
        }
        if (beat.talking || now - beat.lastTalkMs < TALK_GAP_MS) {
            return;
        }
        orchestrator.noteTry(run, bot.getId(), c.tryValue(), now);
        click(run, orchestrator, bot, beat, c.npcId(), List.of(), "check " + c.tryValue(), now, log);
    }

    private void converse(RunState run, Orchestrator orchestrator, Character bot, Beat beat, SolverStep.Converse c,
                          long now, Consumer<String> log) {
        int[] npc = npcOn(bot, c.npcId());
        if (npc == null) {
            log.accept(bot.getName() + ": NPC " + c.npcId() + " is not on map " + bot.getMapId());
            return;
        }
        Point at = bot.getPosition();
        if (Math.abs(npc[0] - at.x) > NPC_REACH_X || Math.abs(npc[1] - at.y) > NPC_REACH_Y) {
            int side = at.x < npc[0] ? -1 : 1;
            moveNear(bot, beat, npc[0] + side * (NPC_REACH_X / 2), npc[1], NPC_REACH_X / 3, now);
            return;
        }
        if (beat.talking || now - beat.lastTalkMs < TALK_GAP_MS) {
            return;
        }
        click(run, orchestrator, bot, beat, c.npcId(), c.answers(), c.why(), now, log);
    }

    /** One NPC conversation through its own script, off the tick thread (a few hundred ms in interpreted mode). */
    private void click(RunState run, Orchestrator orchestrator, Character bot, Beat beat, int npcId,
                       List<Integer> answers, String why, long now, Consumer<String> log) {
        beat.lastTalkMs = now;
        beat.talking = true;
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                BotNpcTalk.Result r = BotNpcTalk.INSTANCE.converse(bot, npcId, answers);
                String said = r.lastLine().replaceAll("#[a-zA-Z]", "").replace("\r\n", " ");
                if (said.length() > 90) {
                    said = said.substring(0, 90) + "...";
                }
                scriptResult(run, orchestrator, beat, bot, npcId, r, "NPC " + bot.getName() + " -> " + npcId + " (" + why
                        + ")" + (r.ran() ? ": \"" + said + "\"" : " FAILED: " + r.error()), log);
            } finally {
                beat.lastTalkMs = System.currentTimeMillis();
                beat.talking = false;
            }
        });
    }

    /** Walk into a portal on the bot's map; the portal's script gate (PqGates) still decides whether it opens. */
    /**
     * Put a member in the stage's map (a boss run's next room). The event's own instance is used, so the member stays in
     * the instance; one line per bot and room says when it went through.
     */
    private static void warp(LiveStageView view, Character bot, Beat beat, SolverStep.WarpTo w, long now,
                             Consumer<String> log) {
        if (bot.getMapId() == w.mapId()) {
            beat.warpTries = 0;
            return;
        }
        if (wrongRoom(w, view.stage().mapId())) {
            // the run is on one room at a time: a step that names another map is a solver bug, not a bot problem
            String told = "ask:" + w.mapId();
            if (!told.equals(beat.warpTold)) {
                beat.warpTold = told;
                log.accept(bot.getName() + ": asked for map " + w.mapId() + " while stage "
                        + view.stage().index() + " is map " + view.stage().mapId() + " (" + w.why() + ")");
            }
            return;
        }
        if (now - beat.lastWarpMs < 2_000) {
            return;
        }
        beat.lastWarpMs = now;
        MapleMap target = view.mapFor(w.mapId());
        if (target == null) {
            log.accept(bot.getName() + ": no map " + w.mapId() + " in this instance (" + w.why() + ")");
            return;
        }
        bot.changeMap(target, target.getPortal(0));
        if (bot.getMapId() == w.mapId()) {
            beat.warpTries = 0;
            log.accept(bot.getName() + ": through to " + w.mapId() + " (" + w.why() + ")");
            return;
        }
        // the warp did not take: retry on the latch a few times, then say where the bot really is and end the run
        // instead of retrying forever
        String line = bot.getName() + " could not be put in map " + w.mapId()
                + " (it is on " + bot.getMapId() + "): " + w.why();
        if (warpGaveUp(beat)) {
            log.accept(line + " - the run is ended");
            view.endRun(line);
            return;
        }
        log.accept(line + " - trying again");
    }

    /**
     * Hits the room's reactor with the bot's own client, the way {@code ReactorHitHandler} does for a player: the
     * script behind it decides what that means (Horntail's {@code 2401000} spawns the fight, and its own
     * "no {@code 8810026} yet" guard makes a second click harmless).
     */
    private static void hitReactor(Character bot, Beat beat, SolverStep.HitReactor h, long now, Consumer<String> log) {
        MapleMap map = bot.getMap();
        server.maps.Reactor reactor = map == null ? null : reactorFor(map, String.valueOf(h.reactorId()));
        if (reactor == null) {
            if (now - beat.lastReactorMs > 10_000) {
                beat.lastReactorMs = now;
                log.accept(bot.getName() + ": no reactor " + h.reactorId() + " on map " + bot.getMapId());
            }
            return;
        }
        hitReactorAt(bot, beat, reactor, String.valueOf(h.reactorId()), now, log);
    }

    /**
     * Puts the named item on ({@link SolverStep.EquipItem}) through the gear system's own move. One attempt per
     * {@link #EQUIP_GAP_MS}; a success is a line, a refusal or an item the bot does not carry is a line naming the item
     * and the reason - said once per distinct reason, so a bot that cannot wear it is heard once, not every tick.
     */
    private static void equipItem(Character bot, Beat beat, SolverStep.EquipItem e, long now, Consumer<String> log) {
        if (now - beat.lastEquipMs < EQUIP_GAP_MS) {
            return;
        }
        beat.lastEquipMs = now;
        soloMapling.ArtificialPlayer.BotGearSystem.BotGear.WearResult r = wearer.apply(bot, e.itemId());
        switch (r.outcome()) {
            case WORN -> {
                beat.equipToldWhy = null;
                log.accept("EQUIP " + bot.getName() + " puts on " + e.itemId() + " - " + e.why());
            }
            case ALREADY_WORN -> beat.equipToldWhy = null;
            case NOT_CARRIED, REFUSED -> {
                if (!r.why().equals(beat.equipToldWhy)) {
                    beat.equipToldWhy = r.why();
                    log.accept(bot.getName() + " cannot put on " + e.itemId() + ": " + r.why() + " (" + e.why() + ")");
                }
            }
        }
    }

    /** The seam {@link #wearer} has for taking an item off (tests); production runs the gear system's move. */
    static volatile java.util.function.BiFunction<Character, Integer, soloMapling.ArtificialPlayer.BotGearSystem.BotGear.Off>
            remover = soloMapling.ArtificialPlayer.BotGearSystem.BotGear::takeOff;

    /**
     * Takes the named worn item off into the bag ({@link SolverStep.UnequipItem}) through the gear system's own move; paced
     * like {@link #equipItem}, a line when it went and a line naming the reason when it could not.
     */
    private static void unequipItem(Character bot, Beat beat, SolverStep.UnequipItem u, long now, Consumer<String> log) {
        if (now - beat.lastEquipMs < EQUIP_GAP_MS) {
            return;
        }
        beat.lastEquipMs = now;
        soloMapling.ArtificialPlayer.BotGearSystem.BotGear.Off r = remover.apply(bot, u.itemId());
        switch (r) {
            case TAKEN_OFF -> log.accept("UNEQUIP " + bot.getName() + " takes off " + u.itemId() + " - " + u.why());
            case NOT_WORN -> { }
            case NO_ROOM -> {
                String why = "no free equip slot to put " + u.itemId() + " in";
                if (!why.equals(beat.equipToldWhy)) {
                    beat.equipToldWhy = why;
                    log.accept(bot.getName() + " cannot take off " + u.itemId() + ": " + why + " (" + u.why() + ")");
                }
            }
        }
    }

    /**
     * Hits the reactor with this map object id ({@link SolverStep.HitReactorByOid}): the one statue of twenty that the
     * puzzle's script names by {@code objectId % 1000}. A map without it is said once per ten seconds, as for the other
     * reactor steps, and nothing is hit.
     */
    private static void hitReactorByOid(Character bot, Beat beat, SolverStep.HitReactorByOid h, long now,
                                        Consumer<String> log) {
        MapleMap map = bot.getMap();
        server.maps.Reactor reactor = reactorByOid(map, h.objectId());
        if (reactor == null) {
            if (now - beat.lastReactorMs > 10_000) {
                beat.lastReactorMs = now;
                log.accept(bot.getName() + ": no reactor with object id " + h.objectId() + " on map " + bot.getMapId());
            }
            return;
        }
        hitReactorAt(bot, beat, reactor, "#" + h.objectId(), now, log);
    }

    /** The walk-and-click part, shared by {@link SolverStep.HitReactor} and {@link SolverStep.HitReactorNamed}. */
    private static void hitReactorAt(Character bot, Beat beat, server.maps.Reactor reactor, String label, long now,
                                     Consumer<String> log) {
        Point at = bot.getPosition();
        Point rp = reactor.getPosition();
        if (at != null && rp != null && (Math.abs(rp.x - at.x) > 45 || Math.abs(rp.y - at.y) > 60)) {
            moveNear(bot, beat, rp.x, rp.y, 30, now);
            return;
        }
        if (now - beat.lastReactorMs < 3_000) {
            return;
        }
        beat.lastReactorMs = now;
        reactor.hitReactor(client.BotClient.forScripts(bot)); // a population bot's script needs a player behind the client
        log.accept(bot.getName() + ": hit reactor " + label + " on " + bot.getMapId());
    }

    /**
     * Puts a stack on the ground at a reactor, the way a player drops it on the reactor: the server
     * ({@code MapleMap.activateItemReactors}) takes the drop only when the item and the <em>whole quantity</em> match
     * the reactor's react item and the drop lies inside the reactor's area, so the stack lands on the reactor's own
     * position ({@link SolverStep.PlaceItem}) and goes down as one drop. Nothing to place yet is not an error.
     */
    private static void placeItem(Character bot, Beat beat, SolverStep.PlaceItem p, long now, Consumer<String> log) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        if (bot.getItemQuantity(p.itemId(), false) < p.count()) {
            if (now - beat.lastPlaceMs > 10_000) {
                beat.lastPlaceMs = now;
                log.accept(bot.getName() + ": " + p.why() + " waits for " + p.count() + " x " + p.itemId());
            }
            return;
        }
        server.maps.Reactor reactor = reactorFor(map, p.reactor());
        Point at = reactor != null && reactor.getPosition() != null
                ? reactor.getPosition() : new Point(p.x(), p.y());
        Point mine = bot.getPosition();
        if (mine != null && (Math.abs(at.x - mine.x) > 45 || Math.abs(at.y - mine.y) > 60)) {
            moveNear(bot, beat, at.x, at.y, 30, now);
            return;
        }
        if (now - beat.lastPlaceMs < DROP_GAP_MS) {
            return;
        }
        beat.lastPlaceMs = now;
        int paid = BotHoldings.payOut(bot, p.itemId(), p.count(), bot, at, true);
        log.accept("PLACE " + bot.getName() + " puts " + paid + " x " + p.itemId() + " on "
                + (reactor != null ? p.reactor() : "(" + at.x + ", " + at.y + ")") + " - " + p.why());
    }

    /**
     * Puts an exact stack on the item reactor with this id ({@link SolverStep.PlaceItemOn}) - Henesys PQ's seed beds.
     * Three rules the plain {@link #placeItem} does not need:
     *
     * <ul>
     *   <li><b>Never twice.</b> A planted bed's script adds one to the instance's {@code stage}
     *       ({@code scripts/reactor/9108000.js:6-8}) and the stage clears at exactly {@code "6"}, so a second drop on
     *       the same bed would skip the clear for good. A bed whose state is above zero is left alone.</li>
     *   <li><b>The server is late on purpose.</b> {@code MapleMap.activateItemReactors} fires 5000 ms after the drop
     *       lands ({@code MapleMap.java:2665-2680}), so the next drop waits {@link #ITEM_REACTOR_SETTLE_MS}: a solver
     *       that re-checked at once would read a failure that is really a delay.</li>
     *   <li><b>Bounded retries.</b> At most {@link #PLACE_ON_TRIES} drops per bed per bot; after that it says so and
     *       stops, instead of feeding a bed the server keeps refusing.</li>
     *   <li><b>The drop lands in the box.</b> An item reactor's box is not a map {@code area} node: it is the
     *       {@code lt}/{@code rb} vectors of its own state ({@code Reactor.wz/9108000.img.xml:18-19}, i.e.
     *       {@code lt(-19,-42) rb(20,16)} for a bed), relative to the reactor's position - so dropping on the reactor
     *       itself is inside it, and no area lookup is involved.</li>
     * </ul>
     */
    /**
     * One attempt at using a use-item on a monster of a given kind - Ellin PQ's purifier marble on a Poison Flower.
     *
     * <p>The rule is not re-implemented here: {@code server.life.MonsterCatch.usePurifierMarble} is the same call the
     * client's {@code USE_CATCH_ITEM} handler makes, so a bot and a player catch the same monsters under the same
     * conditions. One attempt per {@link #USE_ON_MOB_GAP_MS} per bot, nearest-first, and the log says which monster and
     * whether it landed - a bot that kept spraying marbles at a healthy flower would look busy and change nothing.
     */
    /**
     * One touch of a reactor, rate-limited like a hit: the {@code touch} callback only fires from a client packet, so
     * this calls {@code ReactorScriptManager.touch(client, reactor)} directly - the same entry point the packet handler
     * uses - and leaves {@code act()} to {@link SolverStep.HitReactor}.
     */
    private static void touchReactor(Character bot, Beat beat, int reactorId, String reactorName, long now,
                                     Consumer<String> log) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        server.maps.Reactor reactor = reactorName != null ? map.getReactorByName(reactorName)
                : map.getReactorById(reactorId);
        if (reactor == null) {
            if (now - beat.lastReactorMs > 10_000) {
                beat.lastReactorMs = now;
                log.accept(bot.getName() + ": no reactor " + (reactorName != null ? "\"" + reactorName + "\""
                        : String.valueOf(reactorId)) + " on map " + map.getId());
            }
            return;
        }
        if (now - beat.lastTouchMs < TOUCH_GAP_MS) {
            return;
        }
        beat.lastTouchMs = now;
        scripting.reactor.ReactorScriptManager.getInstance().touch(client.BotClient.forScripts(bot), reactor);
        log.accept("TOUCH reactor " + (reactorName != null ? reactorName : String.valueOf(reactorId))
                + " (state " + reactor.getState() + ")");
    }

    private static void useItemOnMob(Character bot, Beat beat, SolverStep.UseItemOnMob u, long now, Consumer<String> log) {
        MapleMap map = bot.getMap();
        if (map == null || bot.getItemQuantity(u.itemId(), false) <= 0) {
            return;
        }
        if (now - beat.lastUseOnMobMs < USE_ON_MOB_GAP_MS) {
            return;
        }
        server.life.Monster prey = null;
        long best = Long.MAX_VALUE;
        for (server.maps.MapObject mo : map.getMonsters()) {
            if (mo instanceof server.life.Monster m && m.getId() == u.mobId() && m.getHp() > 0) {
                java.awt.Point mp = m.getPosition();
                long d = (long) (mp.x - bot.getPosition().x) * (mp.x - bot.getPosition().x)
                        + (long) (mp.y - bot.getPosition().y) * (mp.y - bot.getPosition().y);
                if (d < best) {
                    best = d;
                    prey = m;
                }
            }
        }
        if (prey == null) {
            return;
        }
        beat.lastUseOnMobMs = now;
        boolean caught = server.life.MonsterCatch.usePurifierMarble(bot.getClient(), bot, prey);
        log.accept((caught ? "USE-ITEM " : "USE-ITEM-MISS ") + u.itemId() + " on mob " + prey.getId()
                + " (hp " + prey.getHp() + "/" + prey.getMaxHp() + ")"
                + (caught ? ": caught, one marble gained" : ": too healthy, not caught"));
    }

    private static void placeItemOn(Character bot, Beat beat, SolverStep.PlaceItemOn p, long now, Consumer<String> log) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        server.maps.Reactor reactor = map.getReactorById(p.reactorId());
        if (reactor == null) {
            if (now - beat.lastPlaceOnMs > 10_000) {
                beat.lastPlaceOnMs = now;
                log.accept(bot.getName() + ": no reactor " + p.reactorId() + " on map " + bot.getMapId());
            }
            return;
        }
        if (reactor.getState() > 0) {
            if (now - beat.lastPlaceOnMs > 10_000) {
                beat.lastPlaceOnMs = now;
                log.accept(bot.getName() + ": bed " + p.reactorId() + " is already planted (state "
                        + reactor.getState() + ")");
            }
            return;
        }
        if (bot.getItemQuantity(p.itemId(), false) < p.count()) {
            if (now - beat.lastPlaceOnMs > 10_000) {
                beat.lastPlaceOnMs = now;
                log.accept(bot.getName() + ": " + p.why() + " waits for " + p.count() + " x " + p.itemId());
            }
            return;
        }
        if (now - beat.lastPlaceOnMs < ITEM_REACTOR_SETTLE_MS) {
            return; // the previous drop is still inside the server's 5 s activation window
        }
        Point at = reactor.getPosition();
        Point mine = bot.getPosition();
        if (at != null && mine != null && (Math.abs(at.x - mine.x) > 45 || Math.abs(at.y - mine.y) > 60)) {
            moveNear(bot, beat, at.x, at.y, 30, now);
            return;
        }
        int tries = beat.placeOnTries.merge(p.reactorId(), 1, Integer::sum);
        if (tries > PLACE_ON_TRIES) {
            if (now - beat.lastPlaceOnMs > 30_000) {
                beat.lastPlaceOnMs = now;
                log.accept(bot.getName() + ": giving up on bed " + p.reactorId() + " after " + PLACE_ON_TRIES
                        + " drops it did not take");
            }
            return;
        }
        beat.lastPlaceOnMs = now;
        int paid = BotHoldings.payOut(bot, p.itemId(), p.count(), bot, at == null ? mine : at, true);
        log.accept("PLACE-ON " + bot.getName() + " puts " + paid + " x " + p.itemId() + " on reactor "
                + p.reactorId() + " (try " + tries + ") - " + p.why());
    }

    /**
     * The reactor on this map whose <em>map object id</em> is {@code objectId}, or null. Unlike {@link #reactorFor} this
     * names exactly one reactor even when a map stands twenty of one WZ id in a row ({@link SolverStep.HitReactorByOid}).
     */
    static server.maps.Reactor reactorByOid(MapleMap map, int objectId) {
        return map == null ? null : map.getReactorByOid(objectId);
    }

    /**
     * The reactor a step names: by WZ name ({@code scar1}), or by id when the key is all digits ({@code 2006000}).
     * By id it prefers one still untouched ({@code state 0}): OPQ's twenty cloud reactors share one id, so a hit
     * means "the next cloud", not "the same cloud again"; once every one is used the first is returned so the hit
     * still reaches the map's own guard.
     */
    static server.maps.Reactor reactorFor(MapleMap map, String key) {
        if (map == null || key == null || key.isBlank()) {
            return null;
        }
        boolean numeric = key.chars().allMatch(c -> c >= '0' && c <= '9');
        if (!numeric) {
            return map.getReactorByName(key);
        }
        int id;
        try {
            id = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return null;
        }
        server.maps.Reactor first = map.getReactorById(id);
        if (first == null) {
            return null;
        }
        if (first.getState() == 0) {
            return first;
        }
        for (server.maps.MapObject o : map.getReactors()) {
            if (o instanceof server.maps.Reactor r && r.getId() == id && r.getState() == 0) {
                return r;
            }
        }
        return first;
    }

    /**
     * Walks to the door and runs its script, which decides whether it opens, where it sends the bot and what it
     * writes on the way (Ludi PQ's stage-6 door sets {@code 6stageclear}). The outcome is logged once per try so a
     * shut door is a visible note, not a silent stall.
     */
    private static void scriptedPortal(Character bot, Beat beat, SolverStep.ScriptedPortal p, long now,
                                       Consumer<String> log) {
        MapleMap map = bot.getMap();
        server.maps.Portal portal = map == null ? null : map.getPortal(p.portalName());
        if (portal == null) {
            if (now - beat.lastPortalMs > 10_000) {
                beat.lastPortalMs = now;
                log.accept(bot.getName() + ": no portal " + p.portalName() + " on map " + bot.getMapId());
            }
            return;
        }
        Point at = bot.getPosition();
        Point pos = portal.getPosition();
        if (Math.abs(pos.x - at.x) > 20 || Math.abs(pos.y - at.y) > 60) {
            moveNear(bot, beat, pos.x, pos.y, 10, now);
            return;
        }
        if (now - beat.lastPortalMs < 2_000) {
            return;
        }
        beat.lastPortalMs = now;
        scripting.portal.PortalScriptManager.Outcome outcome =
                scripting.portal.PortalScriptManager.getInstance().runPortalScript(portal, bot.getClient());
        log.accept(bot.getName() + ": portal " + p.portalName() + " " + outcome);
    }

    private static void enterPortal(Character bot, Beat beat, SolverStep.EnterPortal e, long now, Consumer<String> log) {
        MapleMap map = bot.getMap();
        server.maps.Portal portal = map == null ? null : map.getPortal(e.portalName());
        if (portal == null) {
            if (now - beat.lastPortalMs > 10_000) {
                beat.lastPortalMs = now;
                log.accept(bot.getName() + ": no portal " + e.portalName() + " on map " + bot.getMapId());
            }
            return;
        }
        Point at = bot.getPosition();
        Point p = portal.getPosition();
        if (Math.abs(p.x - at.x) > 20 || Math.abs(p.y - at.y) > 60) {
            moveNear(bot, beat, p.x, p.y, 10, now);
            return;
        }
        if (now - beat.lastPortalMs < 1_500) {
            return;
        }
        beat.lastPortalMs = now;
        int from = bot.getMapId();
        if (!GCMovement.enterPortal(bot, portal)) {
            log.accept(bot.getName() + ": portal " + e.portalName() + " on " + from + " is still shut");
        }
    }

    /**
     * Same walk as {@link #enterPortal}, but the portal is chosen by the map it leads to. A mine's doors are named by
     * direction and a hub's by number, and the row's next stage already names the map, so the name is not the solver's
     * business. Logs once per ten seconds when no portal on this map leads there, and once when the portal is shut.
     */
    private static void enterPortalTo(Character bot, Beat beat, SolverStep.EnterPortalTo step, long now,
                                      Consumer<String> log) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        server.maps.Portal portal = null;
        for (server.maps.Portal candidate : map.getPortals()) {
            if (candidate.getTargetMapId() == step.mapId()) {
                portal = candidate;
                break;
            }
        }
        if (portal == null) {
            if (now - beat.lastPortalMs > 10_000) {
                beat.lastPortalMs = now;
                log.accept(bot.getName() + ": no portal on " + bot.getMapId() + " leads to " + step.mapId());
            }
            return;
        }
        enterPortal(bot, beat, new SolverStep.EnterPortal(portal.getName()), now, log);
    }

    // ---- movement ----

    /** Walks toward (x, y) unless already within {@code tolerance} px; re-issues only when the target moved. */
    private static void moveNear(Character bot, Beat beat, int x, int y, int tolerance, long now) {
        Point at = bot.getPosition();
        if (at != null && Math.abs(at.x - x) <= tolerance && Math.abs(at.y - y) <= 60) {
            return;
        }
        Point target = new Point(x, y);
        boolean moved = beat.moveTarget == null || beat.moveTarget.distance(target) > 40;
        if (moved || now - beat.moveIssuedMs > 4_000) {
            GCMovement.move(bot, x, y);
            beat.moveTarget = target;
            beat.moveIssuedMs = now;
        }
    }
}
