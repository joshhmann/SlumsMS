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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    private static final int HPQ_CAKE = 4001101;

    /** Cafe PQ: the one coupon, and how many a kill drops (CafePQ_1.js:251-259, five for a level 40+ area). */
    private static final int CAFE_COUPON = 4001007;
    private static final int CAFE_DROP = 5;
    private static final int COMPUTER = 1052013;
    private static final int LPQ_PASS = 4001022;
    private static final int LPQ_KEY = 4001023;
    private static final List<Integer> LPQ_TALK = List.of(2040036, 2040037, 2040038, 2040039, 2040040, 2040041,
            2040042, 2040043, 2040044);
    private static final Map<Integer, Integer> LPQ_STAGE_MOBS = Map.of(922010100, 25, 922010300, 8, 922010500, 24,
            922010700, 3);
    private static final Map<Integer, Integer> DOOR_TARGET = Map.of(922010100, 922010200, 922010200, 922010300,
            922010300, 922010400, 922010400, 922010500, 922010500, 922010600, 922010600, 922010700, 922010700,
            922010800, 922010800, 922010900);
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

    // ---- the seven single-room rows: map life, or (Castellan) the script's own setup spawn ------------------

    /** TD_Battle2.js:37 in 240070303 ({@code Map.wz/Map/Map2/240070303.img.xml}, life at -119,254). */
    private static final int DUNAS = 8220010;
    /** TD_Battle3.js:37 in 240070403. */
    private static final int AUFHEBEN = 8220011;
    /** TD_Battle4.js:37 in 240070503. */
    private static final int OBERON = 8220012;
    /** TD_Battle1.js:37 is 7220005; 7220003 is the map's life and the other two are its {@code info/revive}. */
    private static final int BERGAMOT_1 = 7220003;
    private static final int BERGAMOT_2 = 7220004;
    private static final int BERGAMOT_3 = 7220005;
    /** TD_Battle5.js:37 is 8220015; 8220013 is the map's life and 8220014 its first revive. */
    private static final int NIBELUNG_1 = 8220013;
    private static final int NIBELUNG_2 = 8220014;
    private static final int NIBELUNG_3 = 8220015;
    /** MahaBattle.js:139-142 clears on this id in 914020000. */
    private static final int MAHA = 9001014;
    /** CastellanBattle.js:131-134 spawns 9400408 at setup; 9400409 is its {@code info/revive} and the clear (:227-240). */
    private static final int CASTELLAN_1 = 9400408;
    private static final int CASTELLAN_2 = 9400409;

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
        return play(eventScript, members, maxTicks, world -> {
        });
    }

    private static Sim play(String eventScript, List<RunMember> members, int maxTicks,
                            java.util.function.Consumer<World> prepare) {
        MechanicsModel.Content content = MechanicsRuns.contentFor(eventScript);
        assertEquals(eventScript, content == null ? null : content.eventScript(),
                "the row must be listed in content/index.txt");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(20_261_102L), 0, 0);
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, members);
        assertFalse(run.ended(), "the party started: " + run.endedBecause());
        World world = new World(run);
        world.enterStage();
        prepare.accept(world);
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


    /** Henesys PQ's own gate is three to six (HenesysPQ.js:27-28), so the smallest legal party plays the hill. */
    private static final int THREE = 3;

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

    // ---- Ludi PQ: nine stages, one door, five boxes out of nine ---------------------------------------

    @Test
    void aLudiPartyPlaysAllNineStagesToTheKey() {
        Sim sim = play("LudiPQ", party(FIVE), 4_000);

        assertTrue(sim.run().ended(), "the run has to finish inside the tick budget: " + sim.ticks());
        assertEquals("cleared", sim.run().endedBecause());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), sim.stageOrder(), "all nine stage maps, in order");
        assertEquals(9, sim.run().lastStage());
        assertEquals("true", sim.world().props.get("9stageclear"), "2040044 takes the key and clears the PQ");

        // every stage was earned by a step, and the door of stage 6 is taken by running its script
        for (int stage = 1; stage <= 9; stage++) {
            int s = stage;
            assertTrue(sim.world().applied.stream().anyMatch(l -> l.startsWith(s + ":")),
                    "stage " + stage + " saw a step");
            assertTrue(sim.world().applied.stream().anyMatch(l -> l.startsWith(s + ":") && !l.contains(":Wait[")),
                    "stage " + stage + " was worked by somebody, not waited out");
        }
        assertTrue(sim.steps().stream().anyMatch(l -> l.startsWith("6:") && l.contains("ScriptedPortal[portalName=next00]")),
                "stage 6's only clear is the door's own script (scripts/portal/lpq5.js:33-41)");
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("HitReactor[reactorId=2202003]")),
                "stage 2 has no monsters at all: its fifteen passes come out of the boxes");
        assertTrue(sim.world().waits * 2 < sim.ticks() * FIVE, "waits " + sim.world().waits + " of "
                + (sim.ticks() * FIVE) + " member-ticks");
    }

    // ---- Henesys PQ: six beds, one bunny, ten rice cakes ------------------------------------------------

    @Test
    void aHenesysPartyPlantsTheSixBedsAndFeedsGrowlie() {
        Sim sim = play("HenesysPQ", party(THREE), 4_000);

        assertTrue(sim.run().ended(), "the run has to finish inside the tick budget: " + sim.ticks()
                + " ticks, stage " + sim.run().currentStage() + ", props " + sim.world().props);
        assertEquals("cleared", sim.run().endedBecause());
        assertEquals(List.of(1, 2), sim.stageOrder(), "the beds, then the cakes: both on the hill");
        assertEquals("6", sim.world().props.get("stage"),
                "six activations, one per bed: a seventh would skip \"6\" and the hill could never clear");
        assertEquals("true", sim.world().props.get("1stageclear"), "1012114 takes ten cakes and clears the quest");

        for (int bed = 9108000; bed <= 9108005; bed++) {
            assertEquals(1, sim.world().reactors.get(String.valueOf(bed)), "bed " + bed + " blooms exactly once");
        }
        assertTrue(sim.steps().stream().anyMatch(l -> l.startsWith("1:") && l.contains("PlaceItemOn[")),
                "stage 1 is earned by dropping seeds on the beds, not by waiting");
        assertTrue(sim.steps().stream().anyMatch(l -> l.startsWith("2:") && l.contains("TalkNpc[")),
                "stage 2 is Growlie's hand-in");
        // the hill's waits are the server's: after every drop the activation lands 5000 ms later, so a member may sit
        // up to that long before the bed blooms. What must not happen is an idle member while a bed waits for a seed.
        assertTrue(sim.world().longestWaitStreak <= 60, "longest wait " + sim.world().longestWaitStreak
                + " ticks (the 5000 ms activation, plus a margin)");
        assertTrue(sim.steps().stream().noneMatch(l -> l.contains("Wait[") && !l.contains("why=")),
                "every wait says why");
    }

    @Test
    void everyOneOfTheSixCafeAreasPlaysToTheComputersClear() {
        int[][] maps = {{190000000, 190000001, 190000002}, {191000000, 191000001}, {192000000, 192000001},
                {195000000, 195010000, 195020000, 195030000}, {196000000, 196010000}, {197000000, 197010000}};
        int[] needs = {400, 350, 350, 450, 500, 300};
        for (int n = 1; n <= 6; n++) {
            Sim sim = play("CafePQ_" + n, party(THREE), 12_000);

            assertTrue(sim.run().ended(), "area " + n + " has to finish inside the tick budget: " + sim.ticks()
                    + " ticks, stage " + sim.run().currentStage());
            assertEquals("cleared", sim.run().endedBecause(), "area " + n);
            assertEquals(maps[n - 1][0], sim.run().stageData().mapId(), "area " + n + " is one stage on its entry map");
            assertTrue(sim.world().instanceCleared, "area " + n + " clears on the instance's own flag: nothing in"
                    + " CafePQ_" + n + ".js writes a property");
            assertTrue(sim.world().props.isEmpty(), "and no property was invented to stand in for it: "
                    + sim.world().props);
            assertTrue(sim.steps().stream().anyMatch(l -> l.startsWith("1:") && l.contains("TalkNpc[")
                            && l.contains(String.valueOf(COMPUTER))),
                    "the clear is the Computer's talk, and the script's own haveItem gate is what allows it");
            // the script PAYS the party its coupons (1052013.js:59 is a positive gainItem), so the party must never
            // hand them over: a hand-in here would ask the bots for the item the NPC is giving them
            String handedIn = "with " + needs[n - 1] + " x " + CAFE_COUPON;
            assertTrue(sim.steps().stream().noneMatch(l -> l.contains("TalkNpc[") && l.contains(handedIn)),
                    "area " + n + " must not hand anything in: " + sim.steps());
            assertTrue(sim.steps().stream().noneMatch(l -> l.contains("Wait[") && !l.contains("why=")),
                    "every wait says why");
        }
    }

    @Test
    void aCafeAreaWithoutItsCouponsNeverAdvancesOnTheComputersTalk() {
        // the fail-before for the instance clear: the leader reaches the Computer with 399 of 400 coupons, which the
        // script's own haveItem gate refuses, so the instance never clears and the row cannot advance
        Sim sim = play("CafePQ_1", party(THREE), 12_000, world -> {
            world.mobs = 0;         // nothing alive, nothing on the floor, and nothing coming back
            world.floor.clear();
            world.noRespawn = true;
        });

        assertFalse(sim.world().instanceCleared, "a short party is not a clear");
        assertNotEquals("cleared", sim.run().endedBecause(), "and the row must not claim one");
    }

    @Test
    void theMoonBunnyIsNeverHitOnTheHill() {
        Sim sim = play("HenesysPQ", party(THREE), 4_000);

        assertEquals("cleared", sim.run().endedBecause());
        assertFalse(sim.world().killed.contains(9300061),
                "killing the bunny ends the run (HenesysPQ.js:251-255): no step may ever name it");
        assertTrue(sim.steps().stream().noneMatch(l -> l.contains("mobId=9300061") || l.contains("9300061]")),
                "not in a fight target either");
    }

    @Test
    void aBedTheServerNeverTakesIsDroppedOnThreeTimesAndNeverForty() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("HenesysPQ");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(7L), 0, 0);
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, party(THREE));
        World world = new World(run);
        world.bedsRefuse = true; // the drops land and nothing activates: the beds never bloom
        world.enterStage();
        List<SolverStep.PlaceItemOn> drops = new ArrayList<>();

        for (int i = 0; i < 1_200 && !run.ended(); i++) {
            Map<Integer, SolverStep> steps = orchestrator.tick(run, world, world.now);
            for (SolverStep step : steps.values()) {
                if (step instanceof SolverStep.PlaceItemOn p) {
                    drops.add(p);
                }
            }
            world.apply(steps);
            world.now += TICK_MS;
        }

        assertFalse(run.ended(), "a hill that never blooms is not a failure: " + run.endedBecause());
        assertEquals(1, run.currentStage(), "and the run never advances past stage 1");
        assertTrue(world.applied.stream().noneMatch(l -> l.contains("Abandon")), "nobody gives up");
        assertTrue(drops.size() > 0, "the beds keep being tried");
        assertTrue(drops.size() <= 3 * THREE * 6, "three drops per bed per member (" + StepExecutor.PLACE_ON_TRIES
                + "), never an unbounded stream: " + drops.size());
    }

    @Test
    void aLudiStageWhosePassCountIsNeverReachedIsWorkedAndNeverAbandoned() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("LudiPQ");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(11L), 0, 0);
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, party(FIVE));
        World world = new World(run);
        world.noPasses = true; // the monsters never drop one: stage 1's rule cannot be met
        world.enterStage();

        for (int i = 0; i < 900 && !run.ended(); i++) {
            Map<Integer, SolverStep> steps = orchestrator.tick(run, world, world.now);
            world.apply(steps);
            world.now += TICK_MS;
        }

        assertFalse(run.ended(), "a stage that cannot clear yet is not a failure: " + run.endedBecause());
        assertEquals(1, run.currentStage(), "and it never advances past the real stage");
        assertTrue(world.applied.stream().noneMatch(l -> l.contains("Abandon")), "nobody gives up");
        assertTrue(world.waits > 0, "and it waits with a reason (a pass count it cannot reach), never silently");
        assertTrue(world.applied.stream().anyMatch(l -> l.startsWith("1:")), "the stage keeps being worked");
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

    // ---- the seven single-room rows: entry, one map, the boss's death, the instance's clear -------------

    /**
     * Dunas ({@code scripts/event/TD_Battle2.js}:37 {@code tdBossId = 8220010}) is the map's life in 240070303, the
     * fight is one kill, and the clear is the script's {@code clearPQ()} with no property behind it.
     */
    @Test
    void aBotPartyKillsDunasAndTheInstanceClearsItself() {
        Sim sim = play("TD_Battle2", party(6), 2_000);

        assertSingleRoomClear(sim, List.of(DUNAS));
    }

    @Test
    void aBotPartyKillsAufhebenAndTheInstanceClearsItself() {
        Sim sim = play("TD_Battle3", party(6), 2_000);

        assertSingleRoomClear(sim, List.of(AUFHEBEN));
    }

    @Test
    void aBotPartyKillsOberonAndTheInstanceClearsItself() {
        Sim sim = play("TD_Battle4", party(6), 2_000);

        assertSingleRoomClear(sim, List.of(OBERON));
    }

    /**
     * Bergamot is a WZ revive chain ({@code Mob.wz/07220003.img.xml} {@code info/revive}): 7220003 wakes 7220004 and
     * 7220004 wakes 7220005, which is the script's {@code tdBossId} (TD_Battle1.js:37) and the only death that clears.
     * The row lists all three in that order and the run walks them without waiting in between.
     */
    @Test
    void aBotPartyWalksBergamotsReviveChainAndTheInstanceClearsItself() {
        Sim sim = play("TD_Battle1", party(6), 2_000);

        assertSingleRoomClear(sim, List.of(BERGAMOT_1, BERGAMOT_2, BERGAMOT_3));
    }

    /** Nibelung's chain is 8220013 -> 8220014 -> 8220015 (TD_Battle5.js:37 is the last form). */
    @Test
    void aBotPartyWalksNibelungsReviveChainAndTheInstanceClearsItself() {
        Sim sim = play("TD_Battle5", party(6), 2_000);

        assertSingleRoomClear(sim, List.of(NIBELUNG_1, NIBELUNG_2, NIBELUNG_3));
    }

    /** Maha is one character's instance (scripts/quest/21401.js:23-24), one map, one map-life boss. */
    @Test
    void oneBotKillsMahaAndTheInstanceClearsItself() {
        Sim sim = play("MahaBattle", party(1), 2_000);

        assertSingleRoomClear(sim, List.of(MAHA));
    }

    /**
     * Castellan is the script's own spawn: {@code CastellanBattle.js:131-134} puts 9400408 on the ground at setup, and
     * that death revives 9400409, the only id whose death reaches {@code clearPQ()} (:227-240).
     */
    @Test
    void aBotPartyWalksCastellansTwoFormsAndTheInstanceClearsItself() {
        Sim sim = play("CastellanBattle", party(6), 2_000);

        assertSingleRoomClear(sim, List.of(CASTELLAN_1, CASTELLAN_2));
    }

    /**
     * The reason the row declares {@code spawnWaitSeconds}: the room can stand empty while the instance's own life is
     * still coming. Here it stays empty for 45 s - past the 30 s grace - and the run must be waiting, not abandoning
     * an instance whose boss is on its way.
     */
    @Test
    void aDunasRoomThatStandsEmptyForFortyFiveSecondsIsNotAbandonedAtThirty() {
        Sim sim = play("TD_Battle2", party(6), 2_000, w -> w.spawnAfterMs(DUNAS, 45_000));

        assertEquals("cleared", sim.ended(), "the boss arrived, died, and the instance cleared: " + sim.ended());
        assertEquals(List.of(DUNAS), sim.world().killed);
        assertTrue(sim.world().waits > 0, "the room really was empty for a while: " + tail(sim.steps(), 5));
    }

    /** The three things every one of these rows is: one stage, one map, the listed deaths in order, no wait, a clear. */
    private static void assertSingleRoomClear(Sim sim, List<Integer> deaths) {
        assertEquals("cleared", sim.ended(), "the run must end on the script's instance clear: " + sim.ended()
                + " after " + sim.ticks() + " ticks; last steps " + tail(sim.steps(), 10));
        assertTrue(sim.run().cleared(), "clear: {instance: true} is the script's setEventCleared()");
        assertEquals(List.of(1), sim.stageOrder(), "one map, no stage transitions");
        assertEquals(deaths, sim.world().killed, "the fight's ids, in the order the chain wakes them");
        assertEquals(0, sim.world().waits, "a healthy fight never waits: " + tail(sim.steps(), 10));
        assertTrue(sim.steps().stream().noneMatch(s -> s.contains(":Abandon[")), "no abandon: " + tail(sim.steps(), 10));
    }

    // ---- the fake world ---------------------------------------------------------------------------------

    /**
     * The world a row runs against. It holds only what a {@link StageView} exposes, and everything in it changes only
     * because a step was applied - the properties the scripts write are written by the step that triggers the script.
     */
    private static final class World implements StageView {

        /** {@code eim.setEventCleared()}: Cafe PQ's six areas write no property at all, only this. */
        private boolean instanceCleared;

        /** Takes the 15 s respawn away, so a test can leave an area with nothing left to kill or to pick up. */
        private boolean noRespawn;

        @Override
        public boolean eventCleared() {
            return instanceCleared;
        }

        private final RunState run;
        private final Map<Integer, Integer> at = new LinkedHashMap<>();
        private final Map<Integer, int[]> spot = new LinkedHashMap<>();
        private final Map<String, Integer> held = new LinkedHashMap<>();
        private final Map<String, String> props = new LinkedHashMap<>();
        private final Map<String, Integer> reactors = new LinkedHashMap<>();
        private final Map<String, Integer> eventStates = new LinkedHashMap<>();
        private final Map<Integer, int[]> npcs = new LinkedHashMap<>();
        private final List<Drop> floor = new ArrayList<>();
        private final List<Pending> pending = new ArrayList<>();
        private final List<BossTargetRules.Part> parts = new ArrayList<>();
        /** Boss ids the map's life has not stood up yet, and the {@code now} they appear at. */
        private final Map<Integer, Long> pendingSpawns = new LinkedHashMap<>();
        private final List<String> applied = new ArrayList<>();
        private final List<Integer> killed = new ArrayList<>();
        private final Map<Integer, Integer> streaks = new LinkedHashMap<>();
        private final Map<Integer, Integer> mapsAtStageStart = new LinkedHashMap<>();
        private List<Rectangle> areas = List.of();
        private int mobs;
        private long now = 1_000;
        private boolean passGiven;
        private boolean altarRefuses;
        private boolean noPasses;
        private boolean bedsRefuse;
        private int altarDelayTicks;
        private int altarTicks;
        private int tick;
        private int waits;
        private int longestWaitStreak;

        private record Drop(int ownerId, int itemId, int count) {
        }

        /** A drop on an item reactor, waiting out the server's 5000 ms activation delay. */
        private record Pending(int reactorId, int itemId, long firesAt) {
        }

        World(RunState run) {
            this.run = run;
            for (RunMember m : run.members()) {
                at.put(m.characterId(), run.stageData().mapId());
                spot.put(m.characterId(), new int[]{0, 0});
            }
        }

        /** "This id arrives after N ms": the room stands without it until then, as a script's setup spawn does. */
        void spawnAfterMs(int mobId, long afterMs) {
            parts.removeIf(p -> p.mobId() == mobId);
            pendingSpawns.put(mobId, now + afterMs);
        }

        // ---- the stage's own starting state, exactly what setup() leaves ---------------------------------

        void enterStage() {
            int stageMap = run.stageData().mapId();
            mobs = 0;
            parts.clear();
            pendingSpawns.clear();
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
                case 922010100, 922010300, 922010500, 922010700 -> {
                    // the passes are held by the map's own monsters and boxes; the count is what the NPC asks for
                    mobs = LPQ_STAGE_MOBS.getOrDefault(stageMap, 0);
                    npcs.put(run.stageData().npcId(), new int[]{0, 0});
                }
                case 922010200 -> npcs.put(2040037, new int[]{0, 0}); // boxes only: no monster lives here
                case 922010400 -> {
                    mobs = 6; // one pass per room; the World counts fights, not rooms
                    npcs.put(2040039, new int[]{0, 0});
                }
                case 922010600 -> npcs.put(2040041, new int[]{0, 0});
                case 910010000 -> {
                    // the primrose hill: six seed beds (Map.wz/Map/Map9/910010000.img.xml moonflower1..6) and Growlie
                    for (int bed = 9108000; bed <= 9108005; bed++) {
                        reactors.put(String.valueOf(bed), run.currentStage() == 1 ? 0 : 1);
                    }
                    mobs = 30; // respawnStages re-spawns the hill every 15 s (HenesysPQ.js:131-138)
                    npcs.put(1012114, new int[]{429, 83});
                    if (run.currentStage() == 2) {
                        props.put("stage", "6"); // six beds planted and the Moon Bunny out on the hill
                    }
                }
                case 190000000, 191000000, 192000000, 195000000, 196000000, 197000000 -> {
                    // a Cafe PQ area: its monsters drop the coupon by hand (CafePQ_1.js:261-277) and the Computer
                    // waits on the area's entry map
                    mobs = 40;
                    npcs.put(COMPUTER, new int[]{0, 0});
                }
                case 922010800 -> {
                    // scripts/npc/2040043.js: nine area nodes (one per box) and the leader's combination
                    List<Rectangle> boxes = new ArrayList<>();
                    for (int i = 0; i < 9; i++) {
                        boxes.add(new Rectangle(-600 + i * 130, -760, 90, 80));
                    }
                    areas = boxes;
                    npcs.put(2040043, new int[]{0, 300});
                }
                case 922010900 -> {
                    mobs = 1; // the Alishar fight: one key at the end of it
                    npcs.put(2040044, new int[]{0, 0});
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
                case 240070303 -> parts.add(life(DUNAS)); // Map.wz/Map/Map2/240070303.img.xml, life mobTime -1
                case 240070403 -> parts.add(life(AUFHEBEN));
                case 240070503 -> parts.add(life(OBERON));
                case 240070203 -> parts.add(life(BERGAMOT_1)); // its two revives come from its death, not the map
                case 240070603 -> parts.add(life(NIBELUNG_1));
                case 914020000 -> parts.add(life(MAHA));
                case 800040410 -> parts.add(life(CASTELLAN_1)); // the script's spawn, same effect on the room
                default -> {
                }
            }
        }

        /** A mob the map instance starts with: a listed, hit-able target with no role but its id. */
        private static BossTargetRules.Part life(int mobId) {
            return new BossTargetRules.Part(mobId, BossTargetRules.Role.ADD, false, true, false, false, 0);
        }

        // ---- applying one step: the script rule each step triggers ---------------------------------------

        void apply(Map<Integer, SolverStep> steps) {
            tick++;
            if (!noRespawn && run.stageData().mapId() == 910010000 && tick % 150 == 0) {
                mobs = Math.max(mobs, 6); // respawnStages: the hill re-spawns every 15 s (HenesysPQ.js:131-138)
            }
            if (!noRespawn && CafeAreas.mapsOf(run.stageData().mapId()).contains(run.stageData().mapId())
                    && tick % 150 == 0) {
                // CafePQ_*.js:131-138: every eventMap is re-spawned every 15 s, and the coupons come from kills
                mobs = Math.max(mobs, 40);
            }
            for (Pending p : List.copyOf(pending)) {
                if (p.firesAt() <= now) {
                    pending.remove(p);
                    activate(p); // MapleMap.activateItemReactors fires 5000 ms after the drop landed
                }
            }
            for (Map.Entry<Integer, Long> spawn : List.copyOf(pendingSpawns.entrySet())) {
                if (spawn.getValue() <= now) {
                    pendingSpawns.remove(spawn.getKey());
                    parts.add(life(spawn.getKey())); // the map's life stands up
                }
            }
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
                case SolverStep.ScriptedPortal p -> {
                    // Ludi PQ's doors (scripts/portal/lpq0..lpq7.js): the stage's own clear, then the next map
                    if (run.stageData().mapId() == 922010600) {
                        props.put("6stageclear", "true"); // scripts/portal/lpq5.js:33-41
                    }
                    at.put(id, DOOR_TARGET.getOrDefault(run.stageData().mapId(), run.stageData().mapId()));
                }
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
                case SolverStep.PlaceItemOn p -> placeOn(id, p);
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

        @Override
        public int reactorState(int reactorId) {
            return reactors.getOrDefault(String.valueOf(reactorId), -1);
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
            switch (stageMap) {
                case 922010200 -> {
                    if (key.equals("2202003")) {
                        if (!noPasses) {
                            give(id, LPQ_PASS, 1);
                        } // eleven boxes hold the fifteen passes (and 2200002 is a trap)
                    }
                    return;
                }
                case 922010300 -> {
                    if (key.equals("2201001")) {
                        give(id, LPQ_PASS, 1);
                    }
                    return;
                }
                case 922010700 -> {
                    if (key.equals("2201002")) {
                        give(id, LPQ_PASS, 1);
                    }
                    return;
                }
                case 922010900 -> {
                    if (key.equals("2201003")) {
                        give(id, LPQ_KEY, 1);
                    }
                    return;
                }
                default -> {
                }
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

        /** Henesys PQ's seed beds: the reactor's own WZ slot decides whether the drop is taken at all. */
        private void placeOn(int id, SolverStep.PlaceItemOn p) {
            assertTrue(heldOf(id, p.itemId()) >= p.count(), "dropping needs the seed in hand: " + id + " holds "
                    + heldOf(id, p.itemId()) + " of " + p.itemId());
            take(id, p.itemId(), p.count());
            if (p.itemId() != HpqBedsSolver.seedFor(p.reactorId()) || p.count() != 1) {
                drop(0, p.itemId(), p.count()); // the wrong seed, or the wrong quantity: it lands on the floor
                return;
            }
            if (!bedsRefuse) {
                pending.add(new Pending(p.reactorId(), p.itemId(), now + 5_000));
            }
        }

        private void activate(Pending p) {
            reactors.put(String.valueOf(p.reactorId()), 1);
            int stage = Integer.parseInt(props.getOrDefault("stage", "0")) + 1;
            props.put("stage", String.valueOf(stage)); // scripts/reactor/9108000.js:4-9
        }

        private void talk(int id, SolverStep.TalkNpc t) {
            if (LPQ_TALK.contains(t.npcId())) {
                lpqTalk(id, t);
                return;
            }
            switch (run.stageData().mapId()) {
                case 190000000, 191000000, 192000000, 195000000, 196000000, 197000000 -> {
                    int need = CafeCouponsSolver.need(this);
                    if (heldOf(id, CAFE_COUPON) >= need) {
                        // scripts/npc/1052013.js:56-63: gainItem(4001007, +couponsNeeded) and then clearPQ() - the
                        // NPC PAYS the party, so the coupons stay in hand and the clear rides the instance's flag
                        give(id, CAFE_COUPON, need);
                        instanceCleared = true;
                    }
                }
                case 910010000 -> {
                    if (heldOf(id, HPQ_CAKE) >= 10) {
                        take(id, HPQ_CAKE, 10);
                        props.put("1stageclear", "true"); // scripts/npc/1012114.js:66-76, then clearPQ
                    }
                }
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

        /** The nine Ludi PQ talks, straight out of scripts/npc/2040036.js-2040044.js. */
        private void lpqTalk(int id, SolverStep.TalkNpc t) {
            int stage = LPQ_TALK.indexOf(t.npcId()) + 1;
            if (t.npcId() == 2040043) {
                // stage 8: the first talk writes the combination, the second counts the boxes
                if (props.get("stage8combo") == null) {
                    props.put("stage8combo", "101011001");
                    props.put("statusStg8", "0");
                    return;
                }
                if (onTheBoxes()) {
                    props.put("8stageclear", "true");
                }
                return;
            }
            if (t.itemId() <= 0 || t.count() <= 0) {
                return;
            }
            if (heldOf(id, t.itemId()) >= t.count()) {
                take(id, t.itemId(), t.count());
                props.put(stage + "stageclear", "true"); // clearStage(stage) in 2040036..2040044
            }
        }

        /** Exactly five members standing on the boxes the combination wants, as 2040043.js:120-150 counts them. */
        private boolean onTheBoxes() {
            String combo = props.get("stage8combo");
            if (combo == null || combo.length() != 9) {
                return false;
            }
            int onCombo = 0;
            for (RunMember m : run.members()) {
                int[] p = spot.get(m.characterId());
                for (int a = 0; a < 9; a++) {
                    if (areas.get(a).contains(p[0], p[1])) {
                        if (combo.charAt(a) == '1') {
                            onCombo++;
                        }
                        break;
                    }
                }
            }
            return onCombo == 5;
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
                if (noPasses) {
                    return; // the drop tables give nothing: the stage can never clear
                }
                if (f.itemId() > 0) {
                    // one monster drops a few of its piece; Papa Pixie drops the one Root of Life
                    give(id, f.itemId(), f.itemId() == WALKWAY_PIECE ? 10 : f.itemId() == LOUNGE_PIECE ? 12
                            : f.itemId() == CAFE_COUPON ? CAFE_DROP : 1);
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
                return;
            }
            // The seven single-room rows: each death is either the clear the script writes, or the next form of a WZ
            // revive chain (Monster.killBy copies getRevives() onto the map, server/life/Monster.java:903-963).
            switch (part.mobId()) {
                case BERGAMOT_1 -> parts.add(life(BERGAMOT_2));
                case BERGAMOT_2 -> parts.add(life(BERGAMOT_3));
                case BERGAMOT_3 -> instanceCleared = true;
                case NIBELUNG_1 -> parts.add(life(NIBELUNG_2));
                case NIBELUNG_2 -> parts.add(life(NIBELUNG_3));
                case NIBELUNG_3 -> instanceCleared = true;
                case CASTELLAN_1 -> parts.add(life(CASTELLAN_2));
                default -> {
                }
            }
            if (part.mobId() == CASTELLAN_2 || part.mobId() == MAHA || part.mobId() == DUNAS
                    || part.mobId() == AUFHEBEN || part.mobId() == OBERON) {
                instanceCleared = true; // clearPQ(): the script sets no property, so the instance is the whole clear
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
