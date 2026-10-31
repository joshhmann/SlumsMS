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

    private final Map<Integer, Beat> beats = new ConcurrentHashMap<>();

    /**
     * 1.6.2 (KPQ pace): a mob another member of the run already goes for counts as this much more walk (ms, about
     * 400 px), so a party spreads over the platforms instead of all chasing one Ligator and hopping on together when
     * it dies.
     */
    static final int TAKEN_PENALTY_MS = 400 * 8;

    /** Per-bot pacing: when it last clicked, dropped, or was sent somewhere. */
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
            case SolverStep.DropFor d -> dropFor(view, bot, beat, d, now, log);
            case SolverStep.Say s -> say(view, bot, beat, s.line(), now);
            case SolverStep.HitReactor h -> log.accept(bot.getName() + ": reactor steps come in phase 2");
            case SolverStep.Occupy o -> occupy(bot, beat, o, now);
            case SolverStep.CheckAnswer c -> check(run, orchestrator, bot, beat, c, now, log);
            case SolverStep.Converse c -> converse(run, orchestrator, bot, beat, c, now, log);
            case SolverStep.EnterPortal e -> enterPortal(bot, beat, e, now, log);
            case SolverStep.Wait w -> {
            }
            case SolverStep.Done d -> {
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
            log.accept("STALL " + bot.getName() + " on " + beat.farmHave + "/" + f.untilItemCount() + " x " + f.itemId()
                    + ": +" + beat.stallGain + " in " + STALL_MS / 1000 + " s (under 2 a minute): "
                    + stallWhy(view, bot, f.itemId()));
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
            target = BotAttackDriver.pickTarget(bot, f.mobId(), m -> beat.chase.skipped(m.getObjectId(), now)
                    || !f.onLeash(m.getPosition().x, m.getPosition().y), m -> takenByAnother(bot.getId(), m));
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
        int mobId = f.mobId();
        return m.isAlive() && (mobId <= 0 || m.getId() == mobId) && !beat.chase.skipped(m.getObjectId(), now)
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
            orchestrator.abort(run, "NPC " + npcId + "'s script failed " + MAX_SCRIPT_FAILURES + " times in a row for "
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
