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

    private static final int MIN_REACH_PX = 60;
    private static final double REACH_FRAC = 0.8;

    private final Map<Integer, Beat> beats = new ConcurrentHashMap<>();

    /** Per-bot pacing: when it last clicked, dropped, or was sent somewhere. */
    private static final class Beat {
        volatile long lastTalkMs;
        volatile boolean talking;
        long lastDropMs;
        Point moveTarget;
        long moveIssuedMs;
    }

    void forget(int botId) {
        beats.remove(botId);
    }

    void execute(RunState run, Orchestrator orchestrator, LiveStageView view, Character bot, SolverStep step, long now,
                 Consumer<String> log) {
        Beat beat = beats.computeIfAbsent(bot.getId(), id -> new Beat());
        switch (step) {
            case SolverStep.MoveTo m -> moveNear(bot, beat, m.x(), m.y(), 30, now);
            case SolverStep.Fight f -> fight(view, bot, beat, f, now);
            case SolverStep.Loot l -> loot(view, bot, beat, l.itemId(), now);
            case SolverStep.TalkNpc t -> talk(run, orchestrator, view, bot, beat, t, now, log);
            case SolverStep.DropFor d -> dropFor(view, bot, beat, d, now, log);
            case SolverStep.Say s -> SocialCommands.BotSpeak(bot, s.line());
            case SolverStep.HitReactor h -> log.accept(bot.getName() + ": reactor steps come in phase 2");
            case SolverStep.Wait w -> {
            }
            case SolverStep.Done d -> {
            }
        }
    }

    // ---- steps ----

    private void fight(LiveStageView view, Character bot, Beat beat, SolverStep.Fight f, long now) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        Point at = bot.getPosition();
        if (bot.getItemQuantity(f.itemId(), false) < f.untilItemCount()) {
            MapItem drop = nearestMobDrop(map, bot, f.itemId(), at);
            if (drop != null) {
                pickUp(bot, beat, drop, now);
                return;
            }
        }
        Monster target = null;
        double best = Double.MAX_VALUE;
        for (Monster m : map.getAllMonsters()) {
            if (m == null || !m.isAlive() || m.getPosition() == null || (f.mobId() > 0 && m.getId() != f.mobId())
                    || !BotAttackDriver.botMayTarget(m)) {
                continue;
            }
            double d = at.distanceSq(m.getPosition());
            if (d < best) {
                best = d;
                target = m;
            }
        }
        if (target == null) {
            return; // the stage respawns its mobs every 15 s (KerningPQ.respawnStages): wait for them
        }
        Point mob = target.getPosition();
        int reach = Math.max(MIN_REACH_PX, (int) (BotAttackDriver.attackReachX(bot) * REACH_FRAC));
        int dx = mob.x - at.x;
        if (Math.abs(dx) > reach || Math.abs(mob.y - at.y) > Math.max(60, BotAttackDriver.attackReachY(bot))) {
            moveNear(bot, beat, mob.x - Integer.signum(dx) * (reach / 2), mob.y, reach / 3, now);
            return;
        }
        if (GCMovement.isFollowing(bot)) {
            GCMovement.stop(bot);
        }
        BotAttackDriver.botAttack(bot);
    }

    /** The nearest drop of {@code itemId} that a monster left and this bot may take (never a member's handover). */
    private static MapItem nearestMobDrop(MapleMap map, Character bot, int itemId, Point at) {
        MapItem best = null;
        double bestSq = (double) LOOT_SEARCH_PX * LOOT_SEARCH_PX;
        for (MapItem mi : LiveStageView.floorDrops(map, itemId, bot.getId())) {
            if (!(mi.getDropper() instanceof Monster) || !DropCommands.botCanLoot(bot, mi)
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
        for (MapItem mi : LiveStageView.floorDrops(map, itemId, bot.getId())) {
            if (!DropCommands.botCanLoot(bot, mi)) {
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
        DropCommands.botLootSingleDrop(bot, drop);
    }

    private void talk(RunState run, Orchestrator orchestrator, LiveStageView view, Character bot, Beat beat,
                      SolverStep.TalkNpc t, long now, Consumer<String> log) {
        int[] npc = view.npcPosition(t.npcId()).orElse(null);
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
                log.accept("NPC " + bot.getName() + " -> " + t.npcId()
                        + (t.itemId() > 0 ? " with " + t.count() + " x " + t.itemId() : "")
                        + (r.ran() ? ": \"" + said + "\"" : " FAILED: " + r.error()));
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
        if (d.toCharacterId() > 0) {
            if (to == null || to.getMap() != bot.getMap()) {
                return; // the leader is not on this map (yet): hold the pass
            }
            Point at = bot.getPosition();
            Point p = to.getPosition();
            if (Math.abs(p.x - at.x) > HANDOVER_REACH_X || Math.abs(p.y - at.y) > 60) {
                moveNear(bot, beat, p.x, p.y, HANDOVER_REACH_X / 2, now);
                return;
            }
        }
        beat.lastDropMs = now;
        int paid = BotHoldings.payOut(bot, d.itemId(), d.count(), to == null ? bot : to, bot.getPosition());
        log.accept("DROP " + bot.getName() + " drops " + paid + " x " + d.itemId()
                + (to == null ? " (extras)" : " for " + to.getName()));
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
