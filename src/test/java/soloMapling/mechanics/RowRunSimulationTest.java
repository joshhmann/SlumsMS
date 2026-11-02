package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: a whole row played from entry to clear, through the real {@link Orchestrator}, the real solvers and a fake
 * world - not one solver against one state.
 *
 * <p>Every other mechanics test asks "does this solver emit the right step for this snapshot". That cannot see the failure
 * the owner actually reported ("they just stand there"): a row that advances, hands a member to the wrong stage, loses a
 * member between two maps, or spins on a `Wait` while an action was available. So this class drives the orchestrator in a
 * loop, applies each emitted step to a world that only changes in the ways our scripts change it, and asserts on the run.
 *
 * <p>The world is deliberately strict: no clear property is ever written except by the step that earns it. The twenty
 * clouds only reach the altar through {@code PlaceItem}, the levers only through {@code HitReactorNamed}, the stage only
 * clears through {@code TalkNpc}, and a stage's own script rule (the split of three, the empty storage floor, the six
 * scars) is checked before anything is written. A solver that returns a no-op passes nothing here.
 *
 * <p>What it does not prove: that the steps are executable by a real {@code Character} (that is {@code StepExecutor}'s job
 * and its own tests), that the maps hold the reactors and NPCs the row names, or that the SQL drop tables behind the
 * pieces exist. It proves the orchestration, and nothing else.
 */
class RowRunSimulationTest {

    private static final long TICK_MS = 100;

    /**
     * A wait streak longer than this while the party had something to do is a stall, not patience. The longest real one
     * on Orbis PQ is the pair of bots waiting out the leader's six drops and one talk.
     */
    /**
     * The longest a member may be idle in a row: four seconds of ticks. The point is a stall, not a turn - a full
     * five-member Orbis party shares uneven work (one member carries a piece to a scar while the others hold, and the
     * sealed room's surplus members stand clear of the platforms on purpose), and the engine's own abandon grace is
     * 30 s, so anything under that is a party taking turns. Measured with five members: 24 ticks.
     */
    private static final int MAX_WAIT_STREAK = 40;

    // ---- what the scripts give the tower, so the fake can be as strict as they are ----------------------

    private static final int CLOUD = 4001063;
    private static final int ALTAR = 2006000;
    private static final int CLOUD_REACTOR = 2002001;
    private static final int WALKWAY_PIECE = 4001050;
    private static final int LOUNGE_PIECE = 4001052;
    private static final int ROOT_OF_LIFE = 4001055;
    private static final int EAK = 2013001;
    private static final int LOBBY_DAY = 3; // Wednesday: the pass is 4001056 + 3
    private static final int[] SCARS = {4001046, 4001049, 4001047, 4001044, 4001048, 4001045}; // scar1..scar6

    // ---- Zakum and Horntail, as their scripts spawn them ------------------------------------------------

    private static final int ZAKUM_FAKE_BODY = 8800000;
    private static final int[] ZAKUM_ARMS = {8800003, 8800004, 8800005, 8800006, 8800007, 8800008, 8800009, 8800010};
    private static final int HORNTAIL_HEAD_1 = 8810000;
    private static final int HORNTAIL_HEAD_2 = 8810001;
    private static final int[] HORNTAIL_PARTS = {8810002, 8810003, 8810004, 8810005, 8810006, 8810007, 8810008,
            8810009};
    private static final int HORNTAIL_BODY = 8810018;

    // ---- the loop --------------------------------------------------------------------------------------

    /** What one simulated run left behind, so a test can assert on it without a dozen locals. */
    private record Sim(RunState run, World world, List<Integer> stageOrder, int ticks) {
        String ended() {
            return run.endedBecause();
        }

        /** Every step the run emitted, in order, as "stage:member:step". */
        List<String> steps() {
            return world.applied;
        }
    }

    private static Sim play(String eventScript, List<RunMember> members, int maxTicks) {
        MechanicsModel.Content content = MechanicsRuns.contentFor(eventScript);
        assertEquals(eventScript, content == null ? null : content.eventScript(),
                "the row must be listed in content/index.txt");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(20_261_102L), 0, 0);
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, members);
        assertFalse(run.ended(), "the party started: " + run.endedBecause());
        World world = new World(run);
        world.enterStage();
        List<Integer> order = new ArrayList<>();
        order.add(run.currentStage());
        int stage = run.currentStage();
        int ticks = 0;
        while (!run.ended() && ticks < maxTicks) {
            Map<Integer, SolverStep> steps = orchestrator.tick(run, world, world.now);
            world.apply(steps);
            if (run.currentStage() != stage) {
                assertEquals(stage + 1, run.currentStage(), "a run advances one stage at a time, never skips one");
                order.add(run.currentStage());
                stage = run.currentStage();
                world.enterStage();
            }
            world.assertMapsHold(stage);
            ticks++;
            world.now += TICK_MS;
        }
        return new Sim(run, world, order, ticks);
    }

    /** Orbis PQ's own gate is five to six (OrbisPQ.js:27), so the simulated party is a full one. */
    private static final int FIVE = 5;

    private static List<RunMember> party(int size) {
        List<RunMember> members = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            members.add(new RunMember(i, i == 1, true, MechanicsPersona.PERFECT));
        }
        return members;
    }

    // ---- the row a party quest is: Orbis PQ, eight stages, entry to clearPQ -----------------------------

    @Test
    void aBotPartyPlaysOrbisPqFromTheEntranceToClearPq() {
        Sim sim = play("OrbisPQ", party(FIVE), 4_000);

        assertEquals("cleared", sim.ended(), "the run must end on its own clear: " + sim.ended() + " after stage "
                + sim.run().currentStage() + "; last steps " + tail(sim.steps(), 30));
        assertTrue(sim.run().cleared(), "Orbis PQ has no afterClear: the last stage's clear ends the run");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), sim.stageOrder(),
                "entrance, walkway, storage, lobby, sealed room, lounge, levers, statue - in that order");
        assertEquals(8, sim.run().lastStage(), "all eight stages have a solver");
        assertEquals("1", sim.world().props.get("statusStg8"), "scripts/reactor/2006001.js ends the quest there");
        assertTrue(sim.ticks() < 2_000, "and it does so in " + sim.ticks() + " ticks, not by burning the budget");
    }

    @Test
    void everyOrbisStageIsEarnedByAStepAndNotByThePassageOfTime() {
        Sim sim = play("OrbisPQ", party(FIVE), 4_000);

        // the steps only the party can produce: the altar, the levers, the music player, the scars, the base
        assertTrue(sim.steps().stream().anyMatch(s -> s.contains("PlaceItem[itemId=4001063, count=20, reactor=2006000")),
                "the altar takes the twenty in one stack: " + tail(sim.steps(), 40));
        assertTrue(sim.steps().stream().anyMatch(s -> s.matches(".*HitReactorNamed\\[reactorName=[1-5]].*")),
                "the tower's five levers are reactors named 1..5: a named reactor is what writes their state; last"
                        + " steps " + tail(sim.steps(), 30));
        assertTrue(sim.steps().stream().anyMatch(s -> s.contains("reactor=music")),
                "the day's pass is dropped on the reactor named music; last steps " + tail(sim.steps(), 30));
        assertTrue(sim.steps().stream().anyMatch(s -> s.contains("reactor=scar4")),
                "and the piece goes on the scar its own WZ trigger names");
        assertTrue(sim.steps().stream().anyMatch(s -> s.contains("reactor=minerva")),
                "the Root of Life ends the quest on the statue's base");
        assertTrue(sim.steps().stream().anyMatch(s -> s.contains("HitReactor[reactorId=2001016]")),
                "Papa Pixie is spawned by the pot, not by waiting");
    }

    @Test
    void noMemberIdlesWhileThePartyHasSomethingToDo() {
        Sim sim = play("OrbisPQ", party(FIVE), 4_000);

        assertTrue(sim.world().longestWaitStreak <= MAX_WAIT_STREAK, "longest run of Wait steps for one member: "
                + sim.world().longestWaitStreak + " ticks (limit " + MAX_WAIT_STREAK + ")");
        for (int stage = 1; stage <= 8; stage++) {
            int s = stage;
            assertTrue(sim.steps().stream().anyMatch(l -> l.startsWith(s + ":") && !l.contains(":Wait[")),
                    "stage " + stage + " was worked by somebody, not waited out");
        }
        // waits counts per member per tick, so the honest share is under half of every member-tick the run had
        assertTrue(sim.world().waits * 2 < sim.ticks() * FIVE, "waits " + sim.world().waits + " of "
                + (sim.ticks() * FIVE) + " member-ticks: the party is working, not holding");
    }

    // ---- the negative: hold a rule false and the run must work the stage, not leave it -------------------

    @Test
    void aStageWhoseRuleIsNeverSatisfiedIsWorkedAndNeverAbandoned() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("OrbisPQ");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(4L), 0, 0);
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, party(FIVE));
        World world = new World(run);
        world.altarRefuses = true; // the altar never takes the twenty: the stage 1 rule cannot be met
        world.enterStage();

        for (int i = 0; i < 900 && !run.ended(); i++) {
            world.apply(orchestrator.tick(run, world, world.now));
            world.now += TICK_MS;
        }

        assertFalse(run.ended(), "a stage that cannot clear is not an abandon: " + run.endedBecause());
        assertEquals(1, run.currentStage(), "and it never advances");
        assertTrue(world.actionsInLast(60) > 0,
                "the party must keep trying (clouds, altar drops, hand-overs), not idle: " + tail(world.applied, 12));
        assertTrue(world.applied.stream().noneMatch(s -> s.contains("Abandon")), "and never give up on it");
        assertTrue(world.applied.stream().anyMatch(s -> s.contains("PlaceItem[itemId=4001063")),
                "the leader keeps offering the stack the altar refuses");
    }

    @Test
    void aStageWhoseRuleIsSatisfiedLateStillAdvances() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("OrbisPQ");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(5L), 0, 0);
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, party(FIVE));
        World world = new World(run);
        world.altarDelayTicks = 400; // the altar only takes the stack after forty seconds of trying
        world.enterStage();

        for (int i = 0; i < 4_000 && !run.ended(); i++) {
            world.apply(orchestrator.tick(run, world, world.now));
            if (run.currentStage() != 1) {
                break;
            }
            world.now += TICK_MS;
        }

        assertTrue(run.currentStage() > 1, "once the rule is met the stage clears and the run moves on");
        assertFalse(run.ended() && !run.cleared(), "no abort: " + run.endedBecause());
    }

    // ---- the boss rows: the same loop, a different world ------------------------------------------------

    @Test
    void zakumArmsFallOneAtATimeAndTheBodyWalksItsChainToTheClear() {
        Sim sim = play("ZakumBattle", party(6), 2_000);

        assertEquals("cleared", sim.ended(), "Zakum's clear is the script's defeatedBoss=1: " + sim.ended());
        assertEquals(List.of(1), sim.stageOrder(), "one map, no stage transitions");
        assertEquals("1", sim.world().props.get("defeatedBoss"));
        assertTrue(sim.world().killed.containsAll(List.of(8800003, 8800010, 8800000, 8800001, 8800002)),
                "every arm, then the body's three forms in order: " + sim.world().killed);
        assertEquals(8800000, sim.world().killed.get(8), "the first body form is only offered once the eight arms fell");
        assertTrue(sim.world().applied.stream().noneMatch(s -> s.contains("HitReactor[reactorId=2111001]")),
                "the altar is the human leader's click, so the bots never touch it");
    }

    @Test
    void horntailWalksItsThreeRoomsAndOpensTheBodyRoomItself() {
        Sim sim = play("HorntailBattle", party(6), 2_000);

        assertEquals("cleared", sim.ended(), "Horntail's clear is defeatedBoss=1: " + sim.ended());
        assertEquals(List.of(1, 2, 3), sim.stageOrder(), "the three rooms, in the row's order");
        assertEquals("2", sim.world().props.get("defeatedHead"), "HorntailBattle.js:237-238 counts the heads");
        assertEquals("1", sim.world().props.get("defeatedBoss"));
        assertTrue(sim.world().applied.stream().anyMatch(s -> s.contains("HitReactor[reactorId=2401000]")),
                "the body room is empty until a bot hits its reactor: " + tail(sim.world().applied, 20));
        assertTrue(sim.world().killed.containsAll(List.of(HORNTAIL_HEAD_1, HORNTAIL_HEAD_2, HORNTAIL_BODY)));
        assertTrue(sim.world().killed.containsAll(java.util.Arrays.stream(HORNTAIL_PARTS).boxed().toList()),
                "and the eight parts before the body");
    }

    // ---- the fake world ---------------------------------------------------------------------------------

    /**
     * The world a row runs against. It holds only what a {@link StageView} exposes, and everything in it changes only
     * because a step was applied - the properties the scripts write are written by the step that triggers the script.
     */
    private static final class World implements StageView {

        private final RunState run;
        private final Map<Integer, Integer> at = new LinkedHashMap<>();
        private final Map<Integer, int[]> spot = new LinkedHashMap<>();
        private final Map<String, Integer> held = new LinkedHashMap<>();
        private final Map<String, String> props = new LinkedHashMap<>();
        private final Map<String, Integer> reactors = new LinkedHashMap<>();
        private final Map<String, Integer> eventStates = new LinkedHashMap<>();
        private final Map<Integer, int[]> npcs = new LinkedHashMap<>();
        private final List<Drop> floor = new ArrayList<>();
        private final List<BossTargetRules.Part> parts = new ArrayList<>();
        private final List<String> applied = new ArrayList<>();
        private final List<Integer> killed = new ArrayList<>();
        private final Map<Integer, Integer> streaks = new LinkedHashMap<>();
        private final Map<Integer, Integer> mapsAtStageStart = new LinkedHashMap<>();
        private List<Rectangle> areas = List.of();
        private int mobs;
        private long now = 1_000;
        private boolean passGiven;
        private boolean altarRefuses;
        private int altarDelayTicks;
        private int altarTicks;
        private int tick;
        private int waits;
        private int longestWaitStreak;

        private record Drop(int ownerId, int itemId, int count) {
        }

        World(RunState run) {
            this.run = run;
            for (RunMember m : run.members()) {
                at.put(m.characterId(), run.stageData().mapId());
                spot.put(m.characterId(), new int[]{0, 0});
            }
        }

        // ---- the stage's own starting state, exactly what setup() leaves ---------------------------------

        void enterStage() {
            int stageMap = run.stageData().mapId();
            mobs = 0;
            parts.clear();
            areas = List.of();
            npcs.clear();
            passGiven = false;
            for (RunMember m : run.members()) {
                mapsAtStageStart.put(m.characterId(), at.get(m.characterId()));
            }
            switch (stageMap) {
                case 920010200, 920010300 -> mobs = 6; // the walkway monsters, the storage room
                case 920010601 -> mobs = 6; // the lounge side room
                case 920010400 -> {
                    eventStates.put("music", LOBBY_DAY); // scripts/event/OrbisPQ.js:162-163
                    npcs.put(EAK, new int[]{0, 0});
                }
                case 920010500 -> {
                    areas = List.of(new Rectangle(-205, -850, 86, 83), new Rectangle(-71, -963, 61, 87),
                            new Rectangle(40, -850, 76, 74)); // Map.wz/Map9/920010500.img.xml imgdirs 1-3
                    npcs.put(EAK, new int[]{-251, -900});
                }
                case 920010700 -> {
                    for (int lever = 1; lever <= 5; lever++) {
                        reactors.put(String.valueOf(lever), 0); // every lever starts pushed off
                    }
                    npcs.put(EAK, new int[]{0, 0});
                }
                case 920010100 -> npcs.put(EAK, new int[]{-201, -956});
                case 920010800 -> npcs.put(EAK, new int[]{137, 397});
                case 280030000 -> {
                    // the human leader clicks the Eye of Fire altar before the run's first tick
                    // (scripts/reactor/2111001.js:28-31): a fake body and the eight arms
                    props.put("summoned", "true");
                    parts.add(new BossTargetRules.Part(ZAKUM_FAKE_BODY, BossTargetRules.Role.BODY, true, false, false,
                            false, 0));
                    for (int arm : ZAKUM_ARMS) {
                        parts.add(new BossTargetRules.Part(arm, BossTargetRules.Role.ARM, false, true, false, false, 0));
                    }
                }
                case 240060000, 240060100 -> {
                    // scripts/reactor/2408003.js: the fake body plus the room's head
                    props.put("summoned", "true");
                    parts.add(new BossTargetRules.Part(ZAKUM_FAKE_BODY, BossTargetRules.Role.BODY, true, false, false,
                            false, 0));
                    parts.add(new BossTargetRules.Part(stageMap == 240060000 ? HORNTAIL_HEAD_1 : HORNTAIL_HEAD_2,
                            BossTargetRules.Role.BODY, false, true, false, false, 0));
                }
                default -> {
                }
            }
        }

        // ---- applying one step: the script rule each step triggers ---------------------------------------

        void apply(Map<Integer, SolverStep> steps) {
            tick++;
            for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
                SolverStep step = e.getValue();
                applied.add(run.currentStage() + ":" + e.getKey() + ":" + shortName(step));
                applyOne(e.getKey(), step);
                if (step instanceof SolverStep.Wait) {
                    longestWaitStreak = Math.max(longestWaitStreak, streaks.merge(e.getKey(), 1, Integer::sum));
                    waits++;
                } else {
                    streaks.put(e.getKey(), 0);
                }
            }
        }

        private void applyOne(int id, SolverStep step) {
            switch (step) {
                case SolverStep.MoveTo m -> spot.put(id, new int[]{m.x(), m.y()});
                case SolverStep.WarpTo w -> at.put(id, w.mapId());
                case SolverStep.EnterPortal p -> at.put(id, run.stageData().mapId());
                case SolverStep.Fight f -> fight(id, f);
                case SolverStep.Loot l -> loot(id, l.itemId());
                case SolverStep.DropFor d -> {
                    take(id, d.itemId(), d.count());
                    floor.add(new Drop(d.toCharacterId(), d.itemId(), d.count()));
                }
                case SolverStep.TakeBack t -> loot(id, t.itemId());
                case SolverStep.HitReactor h -> hitReactor(id, String.valueOf(h.reactorId()));
                case SolverStep.HitReactorNamed h -> hitReactor(id, h.reactorName());
                case SolverStep.PlaceItem p -> place(id, p);
                case SolverStep.TalkNpc t -> talk(id, t);
                case SolverStep.Occupy o -> spot.put(id, new int[]{o.x() + o.w() / 2, o.y() + o.h() / 2});
                default -> {
                }
            }
        }

        private void give(int id, int itemId, int count) {
            held.merge(id + ":" + itemId, count, Integer::sum);
        }

        private int heldOf(int id, int itemId) {
            return held.getOrDefault(id + ":" + itemId, 0);
        }

        private void take(int id, int itemId, int count) {
            int have = heldOf(id, itemId);
            assertTrue(have >= count, "a step cannot spend what the member does not hold: " + id + " has " + have
                    + " of " + itemId + ", asked for " + count);
            held.put(id + ":" + itemId, have - count);
        }

        private void loot(int id, int itemId) {
            for (Drop d : List.copyOf(floor)) {
                if ((itemId > 0 && d.itemId() != itemId) || (d.ownerId() != 0 && d.ownerId() != id)) {
                    continue;
                }
                floor.remove(d);
                give(id, d.itemId(), d.count());
                return;
            }
        }

        private void drop(int ownerId, int itemId, int count) {
            floor.add(new Drop(ownerId, itemId, count));
        }

        private int state(String name) {
            return reactors.getOrDefault(name, -1);
        }

        private void hitReactor(int id, String key) {
            int stageMap = run.stageData().mapId();
            if (key.equals(String.valueOf(CLOUD_REACTOR)) && stageMap == 920010000) {
                give(id, CLOUD, 1); // the cloud piece falls out of the reactor
                return;
            }
            if (stageMap == 920010400 && key.matches("200(200[4-9]|2010)")) {
                // the seven lp boxes (scripts/reactor/2002004-2010.js: rm.dropItems())
                if (!passGiven) {
                    passGiven = true;
                    drop(0, 4001056 + LOBBY_DAY, 1);
                }
                return;
            }
            if (stageMap == 920010700 && key.matches("[1-5]")) {
                reactors.put(key, state(key) > 0 ? 0 : 1); // a lever toggles
                return;
            }
            // the three boxes a clear opens: the bot that fetches one is on the ROOM's map while the stage being
            // played is the next one (the statue's stage fetches the levers' box), so this is keyed on the name only
            if (key.equals("stone3") && state("stone3") > 0) {
                drop(0, 4001046, 1); // the lobby's clear opened the third-piece box
                return;
            }
            if (key.equals("stone4") && state("stone4") > 0) {
                drop(0, 4001047, 1); // the sealed room's clear opened the fourth-piece box
                return;
            }
            if (key.equals("stone6") && state("stone6") > 0) {
                drop(0, 4001049, 1); // the levers' clear opened the sixth-piece box
                return;
            }
            if (key.equals("2001016")) {
                // scripts/reactor/2001016.js: clear the garden, spawn Papa Pixie (the pot is on the garden's map,
                // which is not the stage's map: the statue's stage drives both)
                mobs = 2;
                return;
            }
            if (stageMap == 240060200 && key.equals("2401000")) {
                // scripts/reactor/2401000.js: the intro is not a target, the eight parts and the body are
                parts.clear();
                for (int part : HORNTAIL_PARTS) {
                    parts.add(new BossTargetRules.Part(part, BossTargetRules.Role.ARM, false, true, false, false,
                            part % 2 == 0 ? 0 : 300));
                }
                parts.add(new BossTargetRules.Part(HORNTAIL_BODY, BossTargetRules.Role.BODY, false, true, false, false,
                        0));
                return;
            }
        }

        private void place(int id, SolverStep.PlaceItem p) {
            assertTrue(heldOf(id, p.itemId()) >= p.count(),
                    "placing needs the items in hand: " + id + " holds " + heldOf(id, p.itemId()) + " of " + p.itemId());
            switch (p.reactor()) {
                case "2006000" -> {
                    if (altarRefuses || altarTicks++ < altarDelayTicks) {
                        return; // the altar does not take it: nothing is consumed, the stack stays in hand
                    }
                    assertTrue(p.count() == 20, "the altar only reacts to twenty at once, so nothing else is offered");
                    take(id, p.itemId(), p.count());
                    reactors.put("2006000", 1); // scripts/reactor/2006000.js spawns Eak
                }
                case "music" -> {
                    assertTrue(p.itemId() == 4001056 + eventStates.getOrDefault("music", 0),
                            "only the day's pass matches the armed slot: " + p.itemId());
                    assertTrue(p.count() == 1, "and its quantity is one");
                    take(id, p.itemId(), p.count());
                    props.put("statusStg3", "0"); // scripts/reactor/2008006.js
                }
                case "scar1", "scar2", "scar3", "scar4", "scar5", "scar6" -> {
                    int index = Integer.parseInt(p.reactor().substring(4)) - 1;
                    assertTrue(p.itemId() == SCARS[index],
                            p.reactor() + " takes exactly one piece, and not the one that carries its number");
                    assertTrue(p.count() == 1, "one piece per scar");
                    take(id, p.itemId(), p.count());
                    reactors.put(p.reactor(), 1);
                }
                case "minerva" -> {
                    assertTrue(p.itemId() == ROOT_OF_LIFE, "the base takes the Root of Life");
                    take(id, p.itemId(), p.count());
                    props.put("statusStg8", "1"); // scripts/reactor/2006001.js, then clearPQ
                }
                default -> throw new AssertionError("no reactor named " + p.reactor() + " in this row");
            }
        }

        private void talk(int id, SolverStep.TalkNpc t) {
            switch (run.stageData().mapId()) {
                case 920010000 -> {
                    if (state("2006000") > 0) {
                        props.put("statusStg0", "1"); // clearStage(0)
                    }
                }
                case 920010200 -> {
                    if (heldOf(id, WALKWAY_PIECE) >= 30) {
                        take(id, WALKWAY_PIECE, 30);
                        give(id, 4001044, 1); // "Here, the 1st statue piece."
                        props.put("statusStg1", "1");
                    }
                }
                case 920010300 -> {
                    if (mobs == 0 && floor.isEmpty()) {
                        give(id, 4001045, 1); // "Find the 2nd Piece" - Eak hands it over
                        props.put("statusStg2", "1");
                    }
                }
                case 920010400 -> {
                    if ("0".equals(props.get("statusStg3"))) {
                        props.put("statusStg3", "2"); // clearStage(3) then setProperty("statusStg3", "2")
                        reactors.put("stone3", 1); // getReactorByName("stone3").forceHitReactor(1)
                    }
                }
                case 920010500 -> {
                    if (props.get("statusStg4") == null) {
                        props.put("stage4_0", "2");
                        props.put("stage4_1", "1");
                        props.put("stage4_2", "0");
                        props.put("statusStg4", "0");
                    } else if (onThePlatforms()) {
                        props.put("statusStg4", "1");
                        reactors.put("stone4", 1);
                    }
                }
                case 920010601, 920010600 -> {
                    if (heldOf(id, LOUNGE_PIECE) >= 40) {
                        take(id, LOUNGE_PIECE, 40);
                        give(id, 4001048, 1); // the fifth statue piece
                        props.put("statusStg5", "1");
                    }
                }
                case 920010700 -> {
                    if (props.get("stage6_c") == null) {
                        props.put("stage6_c", "01010");
                        props.put("statusStg6", "0");
                    } else if (leversMatch()) {
                        props.put("statusStg6", "1");
                        reactors.put("stone6", 1);
                    }
                }
                case 920010100 -> {
                    if (allScars()) {
                        for (RunMember m : run.members()) {
                            at.put(m.characterId(), 920010800); // warpEventTeam(920010800)
                        }
                    }
                }
                default -> {
                }
            }
        }

        private boolean onThePlatforms() {
            for (int i = 0; i < 3; i++) {
                Rectangle r = areas.get(i);
                int want = Integer.parseInt(props.get("stage4_" + i));
                int seen = 0;
                for (RunMember m : run.members()) {
                    int[] p = spot.get(m.characterId());
                    if (r.contains(p[0], p[1])) {
                        seen++;
                    }
                }
                if (seen != want) {
                    return false;
                }
            }
            return true;
        }

        private boolean leversMatch() {
            String combination = props.get("stage6_c");
            for (int lever = 1; lever <= 5; lever++) {
                boolean on = state(String.valueOf(lever)) > 0;
                if (on != (combination.charAt(lever - 1) == '1')) {
                    return false;
                }
            }
            return true;
        }

        private boolean allScars() {
            for (int scar = 1; scar <= 6; scar++) {
                if (state("scar" + scar) < 1) {
                    return false;
                }
            }
            return true;
        }

        private void fight(int id, SolverStep.Fight f) {
            if (run.content().kind() == MechanicsModel.Kind.BOSS) {
                bossFight(f);
                return;
            }
            if (mobs > 0) {
                mobs--;
                if (f.itemId() > 0) {
                    // one monster drops a few of its piece; Papa Pixie drops the one Root of Life
                    give(id, f.itemId(), f.itemId() == WALKWAY_PIECE ? 10 : f.itemId() == LOUNGE_PIECE ? 12 : 1);
                }
                return;
            }
        }

        private void bossFight(SolverStep.Fight f) {
            for (int mobId : f.mobIds()) {
                for (BossTargetRules.Part part : parts) {
                    if (part.mobId() == mobId) {
                        killed.add(mobId);
                        parts.remove(part);
                        wake(part);
                        return;
                    }
                }
            }
        }

        /** What a part's death does: an arm wakes the body, the body's forms chain, the last one is the clear. */
        private void wake(BossTargetRules.Part part) {
            int stageMap = run.stageData().mapId();
            if (stageMap == 280030000) {
                if (!parts.stream().anyMatch(p -> p.role() == BossTargetRules.Role.ARM)) {
                    replaceFakeBody(ZAKUM_FAKE_BODY, 8800000);
                }
                if (part.mobId() == 8800000) {
                    parts.add(new BossTargetRules.Part(8800001, BossTargetRules.Role.BODY, false, true, false, false, 0));
                }
                if (part.mobId() == 8800001) {
                    parts.add(new BossTargetRules.Part(8800002, BossTargetRules.Role.BODY, false, true, false, false, 0));
                }
                if (part.mobId() == 8800002) {
                    props.put("defeatedBoss", "1"); // ZakumBattle.js:199-205
                }
                return;
            }
            if (stageMap == 240060000) {
                props.put("defeatedHead", "1");
                return;
            }
            if (stageMap == 240060100) {
                props.put("defeatedHead", "2");
                return;
            }
            if (part.mobId() == HORNTAIL_BODY) {
                props.put("defeatedBoss", "1");
            }
        }

        private void replaceFakeBody(int fakeId, int realId) {
            for (int i = 0; i < parts.size(); i++) {
                BossTargetRules.Part p = parts.get(i);
                if (p.mobId() == fakeId && p.fake()) {
                    parts.set(i, new BossTargetRules.Part(realId, BossTargetRules.Role.BODY, false, true, false, false, 0));
                }
            }
        }

        /** How many of the last {@code n} applied steps were an action rather than a Wait. */
        int actionsInLast(int n) {
            int count = 0;
            for (int i = Math.max(0, applied.size() - n); i < applied.size(); i++) {
                if (!applied.get(i).contains(":Wait[")) {
                    count++;
                }
            }
            return count;
        }

        void assertMapsHold(int stage) {
            MechanicsModel.Stage data = run.stageData();
            StageSolver solver = Orchestrator.allSolvers().get(data.solver());
            List<Integer> shared = new ArrayList<>();
            shared.add(data.mapId());
            shared.addAll(solver.extraMaps(this));
            for (RunMember m : run.members()) {
                List<Integer> allowed = new ArrayList<>(shared);
                allowed.add(mapsAtStageStart.getOrDefault(m.characterId(), data.mapId()));
                int on = at.get(m.characterId());
                assertTrue(allowed.contains(on), "stage " + stage + ": member " + m.characterId() + " is on map " + on
                        + ", which the stage does not drive; allowed " + allowed);
            }
        }

        // ---- StageView: what the solvers may read ------------------------------------------------------

        @Override
        public MechanicsModel.Stage stage() {
            return run.stageData();
        }

        @Override
        public List<RunMember> members() {
            return run.members();
        }

        @Override
        public String roleOf(int characterId) {
            return run.roleOf(characterId);
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return heldOf(characterId, itemId);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return spot.getOrDefault(characterId, new int[]{0, 0});
        }

        @Override
        public int mapOf(int characterId) {
            return at.getOrDefault(characterId, run.stageData().mapId());
        }

        @Override
        public int floorItems(int itemId, int forCharacterId) {
            int sum = 0;
            for (Drop d : floor) {
                if ((itemId > 0 && d.itemId() != itemId) || (d.ownerId() != 0 && d.ownerId() != forCharacterId)) {
                    continue;
                }
                sum += d.count();
            }
            return sum;
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return Optional.ofNullable(npcs.get(npcId));
        }

        @Override
        public int reactorState(String name) {
            return state(name);
        }

        @Override
        public int reactorEventState(String name) {
            return eventStates.getOrDefault(name, -1);
        }

        @Override
        public Rectangle area(int index) {
            return index >= 0 && index < areas.size() ? areas.get(index) : null;
        }

        @Override
        public int liveMobs() {
            return run.content().kind() == MechanicsModel.Kind.BOSS ? parts.size() : mobs;
        }

        @Override
        public List<BossTargetRules.Part> bossParts() {
            return List.copyOf(parts);
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return run.stageMemory();
        }
    }

    private static String shortName(SolverStep step) {
        String text = step.toString();
        return text.length() > 90 ? text.substring(0, 90) + "..." : text;
    }

    private static String tail(List<String> lines, int n) {
        return lines.subList(Math.max(0, lines.size() - n), lines.size()).toString();
    }
}
