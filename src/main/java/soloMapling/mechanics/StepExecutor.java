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

    private static final int MIN_REACH_PX = 60;
    private static final double REACH_FRAC = 0.8;

    private final Map<Integer, Beat> beats = new ConcurrentHashMap<>();

    /** Per-bot pacing: when it last clicked, dropped, or was sent somewhere. */
    private static final class Beat {
        volatile long lastTalkMs;
        volatile boolean talking;
        long lastDropMs;
        long lastPortalMs;
        long lastSayMs;
        boolean toldLeader;
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
            case SolverStep.Say s -> say(view, bot, beat, s.line(), now);
            case SolverStep.HitReactor h -> log.accept(bot.getName() + ": reactor steps come in phase 2");
            case SolverStep.Occupy o -> occupy(bot, beat, o, now);
            case SolverStep.CheckAnswer c -> check(run, orchestrator, bot, beat, c, now, log);
            case SolverStep.Converse c -> converse(bot, beat, c, now, log);
            case SolverStep.EnterPortal e -> enterPortal(bot, beat, e, now, log);
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
        if (f.itemId() > 0 && bot.getItemQuantity(f.itemId(), false) < f.untilItemCount()) {
            MapItem drop = nearestMobDrop(view, map, bot, f.itemId(), at);
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
        if (paid > 0 && to != null && !MechanicsRuns.drivable(to) && !beat.toldLeader) {
            // a real leader may be anywhere on the map: say where the NPC who takes the passes stands (TEST 10-30:
            // the tester waited by the spawn on the far left, Cloto is on the far right)
            beat.toldLeader = true;
            int[] npc = npcOn(bot, view.stage().npcId());
            beat.lastSayMs = now;
            SocialCommands.BotSpeak(bot, passLine(to.getName(), npc == null ? null : npc[0], to.getPosition().x));
        }
    }

    /** What a bot says once to a real leader when it drops its pass at his feet. */
    static String passLine(String leader, Integer npcX, int leaderX) {
        String where = npcX == null || Math.abs(npcX - leaderX) < 300 ? "" : npcX > leaderX ? " on the right" : " on the left";
        return leader + " dropped my pass by u, take them to Cloto" + where;
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
    private static void occupy(Character bot, Beat beat, SolverStep.Occupy o, long now) {
        Point at = bot.getPosition();
        if (at != null && o.inside(at.x, at.y)) {
            return; // there: hold still (the formation is checked by the leader)
        }
        Point target = new Point(o.x(), o.y());
        if (beat.moveTarget == null || !beat.moveTarget.equals(target) || now - beat.moveIssuedMs > 4_000) {
            GCMovement.move(bot, o.x(), o.y());
            beat.moveTarget = target;
            beat.moveIssuedMs = now;
        }
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
        click(bot, beat, c.npcId(), List.of(), "check " + c.tryValue(), now, log);
    }

    private void converse(Character bot, Beat beat, SolverStep.Converse c, long now, Consumer<String> log) {
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
        click(bot, beat, c.npcId(), c.answers(), c.why(), now, log);
    }

    /** One NPC conversation through its own script, off the tick thread (a few hundred ms in interpreted mode). */
    private static void click(Character bot, Beat beat, int npcId, List<Integer> answers, String why, long now,
                              Consumer<String> log) {
        beat.lastTalkMs = now;
        beat.talking = true;
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                BotNpcTalk.Result r = BotNpcTalk.INSTANCE.converse(bot, npcId, answers);
                String said = r.lastLine().replaceAll("#[a-zA-Z]", "").replace("\r\n", " ");
                if (said.length() > 90) {
                    said = said.substring(0, 90) + "...";
                }
                log.accept("NPC " + bot.getName() + " -> " + npcId + " (" + why + ")"
                        + (r.ran() ? ": \"" + said + "\"" : " FAILED: " + r.error()));
            } finally {
                beat.lastTalkMs = System.currentTimeMillis();
                beat.talking = false;
            }
        });
    }

    /** Walk into a portal on the bot's map; the portal's script gate (PqGates) still decides whether it opens. */
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
