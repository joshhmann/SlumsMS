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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** LudiMaze PQ: the exit map, its NPC, and the item the maze's monsters drop (LudiMazePQ.js:73, 9103000.js:62). */
    private static final int MAZE_EXIT_MAP = 809050015;
    private static final int MAZE_EXIT_NPC = 9103000;
    private static final int MAZE_PASS = 4001106;
    private static final int MAZE_SUMMONER = 8098000;

    /** Pirate PQ: the ship's maps and the two NPCs that matter (scripts/portal/davy_next0..4.js, 2094002, 2094001). */
    private static final List<Integer> SHIP_MAPS = List.of(925100000, 925100100, 925100200, 925100300, 925100400,
            925100500);

    /**
     * The maze's passages, exactly as Map.wz/Map/Map8/8090500*.img.xml has them (each map's pt 2 portals and their
     * targets). Only 809050008's t001 reaches the exit.
     */
    private static final Map<Integer, Map<String, Integer>> MAZE = mazeGraph();

    private static Map<Integer, Map<String, Integer>> mazeGraph() {
        Map<Integer, Map<String, Integer>> maze = new java.util.HashMap<>();
        int[][] steps = {
                {809050004, 809050007, 809050012}, {809050005, 809050008, 809050013},
                {809050006, 809050009, 809050014}, {809050007, 809050010, 809050000},
                {809050008, 809050011, 809050001}, {809050009, 809050012, 809050002},
                {809050010, 809050013, 809050003}, {809050011, 809050014, 809050004},
                {809050012, 809050015, 809050005}, {809050013, 809050001, 809050006},
                {809050014, 809050002, 809050007}, {809050000, 809050003, 809050008},
                {809050001, 809050004, 809050009}, {809050002, 809050005, 809050010},
                {809050003, 809050006, 809050011}};
        String[][] names = {{"t001", "t002", "t003"}, {"t000", "t001", "t002"}, {"t000", "t001", "t002"},
                {"t000", "t001", "t002"}, {"t000", "t001", "t002"}, {"t000", "t001", "t002"},
                {"t001", "t002", "t003"}, {"t000", "t001", "t002"}, {"t000", "t001", "t002"},
                {"t000", "t001", "t002"}, {"t000", "t001", "t002"}, {"t000", "t001", "t002"},
                {"t001", "t002", "t003"}, {"t000", "t001", "t002"}, {"t000", "t001", "t002"}};
        for (int i = 0; i < 15; i++) {
            Map<String, Integer> exits = new java.util.LinkedHashMap<>();
            for (int k = 0; k < 3; k++) {
                exits.put(names[i][k], steps[i][k]);
            }
            maze.put(809050000 + i, exits);
        }
        maze.put(MAZE_EXIT_MAP, Map.of("t000", 809050008));
        return maze;
    }
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
    private static final int CHAOS_FAKE_BODY = 8800100;
    private static final int[] CHAOS_ARMS = {8800103, 8800104, 8800105, 8800106, 8800107, 8800108, 8800109, 8800110};
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

    // ---- the room-opened rows: every fight behind a reactor a bot clicks, or the script's own spawn ---------

    /** scripts/reactor/2201004.js:28 spawns 8500000 in 220080001; Mob.wz revive 8500000 -> 8500001 -> 8500002. */
    private static final int PAPULATUS_1 = 8500000;
    private static final int PAPULATUS_2 = 8500001;
    private static final int PAPULATUS_3 = 8500002;
    /** scripts/reactor/5411001.js:28 spawns 9420521 in 541020800; Mob.wz/9420521.img.xml revive/0 = 9420522. */
    private static final int KREXEL_1 = 9420521;
    private static final int KREXEL_2 = 9420522;
    /** scripts/reactor/8001000.js:23 spawns 9400112 in 801040100; revive 9400112 -> 9400113 -> 9400300. */
    private static final int SHOWA_1 = 9400112;
    private static final int SHOWA_2 = 9400113;
    private static final int SHOWA_3 = 9400300;
    /**
     * BalrogBattle.js:145-153 spawns the fake body 8830000, 8830002 and the claw 8830006; the claw's line is
     * Mob.wz/8830006.img.xml revive/0 = 8830001, and 8830000 is turned real by the script (:262-265), not by WZ.
     */
    private static final int BALROG_BODY = 8830000;
    private static final int BALROG_1 = 8830001;
    private static final int BALROG_2 = 8830002;
    /** scripts/reactor/5511000.js summons 9420542 and 5511001.js summons 9420547, 3.2 s later, in 551030200. */
    private static final int SCARGA_TARGA_1 = 9420542;
    private static final int SCARGA_TARGA_2 = 9420543;
    private static final int SCARGA_TARGA_3 = 9420544;
    private static final int SCARGA_SCARLION_1 = 9420547;
    private static final int SCARGA_SCARLION_2 = 9420548;
    private static final int SCARGA_SCARLION_3 = 9420549;
    /** ElementalBattle.js:230-233: the two monsters are the map's own life in 922020100. */
    private static final int ELEMENTAL_1 = 9300086;
    private static final int ELEMENTAL_2 = 9300100;
    /** PinkBeanBattle.js:306-313 spawns 8820002, whose kills walk 8820003..8820006 and then the real 8820001. */
    private static final int PINKBEAN_1 = 8820002;
    private static final int PINKBEAN_2 = 8820003;
    private static final int PINKBEAN_3 = 8820004;
    private static final int PINKBEAN_4 = 8820005;
    private static final int PINKBEAN_5 = 8820006;
    private static final int PINKBEAN = 8820001;

    // ---- the door rows and the survive row -------------------------------------------------------------

    /**
     * scripts/reactor/5411000.js:24 spawns 9420513 in 541010100, and scripts/portal/captinsg00.js:4 is the only place
     * that asks for 4000381 - the script never re-checks it inside the instance and never takes it.
     */
    private static final int LATANICA_DOOR_ITEM = 4000381;
    private static final int LATANICA = 9420513;
    // ---- the five 5 s spawns: the script's spawnMob1 is the only source, and its death is the clear ------

    /** MagnusBattle.js:91-92 and :120-125: {@code spawnMonster(8880002, 2592, -1355)} five seconds in. */
    private static final int MAGNUS = 8880002;
    /** NWillBattle.js:116-117 and :145-150: 9990301 at (-1161,215). */
    private static final int NWILL = 9990301;
    /** NLucidBattle.js:115-116 and :144-149: 9990150 at (1058,48). */
    private static final int NLUCID = 9990150;
    /** NVerusBattle.js:116-117 and :145-150: 9990415 at (16,266). */
    private static final int NVERUS = 9990415;
    /** NDarknellBattle.js:116-117 and :145-150: 9995009 at (57,29). */
    private static final int NDARKNELL = 9995009;

    // ---- GuardianNex: one script, six caves, one boss each ------------------------------------------------

    /**
     * GuardianNex.js:5-6: the caves are {@code eventMap + 10 * lobbyId} and each lobby's boss is the map's own life
     * (Map.wz/Map/Map2/240070010-240070060.img.xml, the only life entry, mobTime -1). The six rows share one event
     * script, so the run is picked by the map the instance was built on.
     */
    private static final Map<Integer, Integer> NEX_CAVES = Map.of(
            240070010, 7_120_100,
            240070020, 7_120_101,
            240070030, 7_120_102,
            240070040, 8_120_100,
            240070050, 8_120_101,
            240070060, 8_140_510);

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
        return play(MechanicsRuns.contentFor(eventScript), eventScript, members, maxTicks, prepare);
    }

    /** A script several rows share (GuardianNex's six caves): the row is the one built on {@code stageMap}. */
    private static Sim play(int stageMap, String eventScript, List<RunMember> members, int maxTicks) {
        return play(MechanicsRuns.contentFor(eventScript, List.of(stageMap)), eventScript, members, maxTicks,
                world -> {
                });
    }

    private static Sim play(MechanicsModel.Content content, String eventScript, List<RunMember> members,
                            int maxTicks, java.util.function.Consumer<World> prepare) {
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
            world.tickSpawn();
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
        assertTrue(sim.steps().stream().noneMatch(s -> s.contains("HitReactor[reactorId=2001016]")),
                "b365: the pot 2001016 takes the Royal's Transparent Item; it is never hit");
        assertTrue(sim.steps().stream().anyMatch(s -> s.contains("PlaceItem[itemId=" + OpqStatueSolver.SEED)),
                "the garden is grown from the Nependeaths' seeds");
        assertTrue(sim.steps().stream().anyMatch(s -> s.contains("PlaceItem[itemId=" + OpqStatueSolver.STRANGER_SEED)),
                "and the Grass of Life from Papa Pixie's seed in 2002003");
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

    /**
     * Pirate PQ, end to end: the deck, three sets of twenty medals through the guide, two rooms emptied, four keys on
     * four doors, and the Lord Pirate's room cleared by the rescued leader's talk. Every gate is the script's own, so a
     * run that finishes is a run whose six maps, five passages and four doors agree with `PiratePQ.js` and its portals.
     */
    @Test
    void aPiratePartyCrossesTheShipAndRescuesItsLeader() {
        Sim sim = play("PiratePQ", party(THREE), 20_000);

        assertTrue(sim.run().ended(), "the ship has to be finished inside the tick budget: " + sim.ticks()
                + " ticks, stage " + sim.run().currentStage() + ", last steps " + tail(sim.steps(), 24));
        assertEquals("cleared", sim.run().endedBecause());
        assertEquals(925100000, sim.run().stageData().mapId(), "one stage, entered on the deck");
        assertTrue(sim.world().instanceCleared, "PiratePQ.js:337-344 writes no property: the clear is the instance");
        assertEquals("3", sim.world().props.get("stage2"),
                "stage2 is the only property the ship writes (2094002.js:47-78), and no clear property was invented: "
                        + sim.world().props);
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("TalkNpc[")
                        && l.contains(String.valueOf(PirateQuestSolver.GUIDE))),
                "the medals go through the guide, twenty at a time");
        assertTrue(sim.world().visited.containsAll(List.of(925100000, 925100100, 925100200, 925100300, 925100400,
                925100500)), "the run crossed all six maps: " + sim.world().visited);
        for (int door : PirateQuestSolver.DOORS) {
            assertTrue(sim.world().state(String.valueOf(door)) > 0, "door " + door + " took its key");
        }
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("PlaceItemOn[")),
                "and the keys were dropped on the doors, not handed to an NPC");
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("TalkNpc[")
                        && l.contains(String.valueOf(PirateQuestSolver.RESCUED))),
                "the clear is the rescued leader's talk");
    }

    /**
     * Treasure PQ end to end: the chest is the only source of a monster, and the monster's death is the clear. Two
     * fail-befores live in `TreasurePqSolversTest`; this is the whole row through the real orchestrator.
     */
    @Test
    void aTreasurePartyOpensTheChestAndKillsWhatItHolds() {
        Sim sim = play("TreasurePQ", party(4), 2_000);

        assertEquals("cleared", sim.ended(), "TreasurePQ.js:244-252 clears on the monster the chest holds: "
                + sim.ended());
        assertEquals(List.of(1), sim.stageOrder(), "one map, one stage");
        assertTrue(sim.world().instanceCleared, "clearPQ (:232-237) sets the flag and nothing else");
        assertTrue(sim.world().props.isEmpty(), "so no property was invented for it: " + sim.world().props);
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("HitReactor[reactorId="
                        + TreasureChestSolver.CHEST + "]")),
                "the chest was hit: the map has no other way to produce a monster");
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("Fight[")),
                "and what the chest held was fought, not walked past");
    }

    /**
     * DelliBattle end to end: the run must be ended by the instance's flag at minute six, not by our budget, and the
     * party must never stand still with a monster in the room.
     */
    @Test
    void aDelliPartyHoldsTheRoomUntilTheScriptsClockClearsIt() {
        Sim sim = play("DelliBattle", party(2), 5_000);

        assertEquals("cleared", sim.ended(), "the clear is scheduledTimeout, not a count: " + sim.ended()
                + " after " + sim.ticks() + " ticks");
        assertTrue(sim.world().instanceCleared, "clearPQ (:208-211)");
        assertTrue(sim.world().props.isEmpty(), "no property was invented for it: " + sim.world().props);
        assertTrue(sim.world().now >= 360_000, "the run lasted the script's six minutes: ended at " + sim.world().now
                + " ms, " + sim.ticks() + " ticks");
        assertTrue(sim.world().longestWaitStreak <= 160,
                "an empty room may be waited in, but only until the 15 s respawn: "
                        + sim.world().longestWaitStreak);
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

    /**
     * LudiMaze PQ end to end: a party that starts split over three maps ({@code LudiMazePQ.js:144-150}) farms the
     * summoner's monsters, walks the client's passages to the exit and clears on the instance's flag once the whole team
     * stands there with thirty passes ({@code scripts/npc/9103000.js:57-73}).
     */
    @Test
    void aSplitLudiMazePartyFindsTheExitAndClearsOnTheInstanceFlag() {
        Sim sim = play("LudiMazePQ", party(THREE), 20_000, world -> {
            // playerEntry drops each member on a random map: three different ones, as a real run does
            world.at.put(2, 809050002);
            world.at.put(3, 809050011);
        });

        assertTrue(sim.run().ended(), "the run has to finish: " + sim.ticks() + " ticks, stage "
                + sim.run().currentStage());
        assertEquals("cleared", sim.run().endedBecause(), "9103000 calls clearPQ(): the instance's flag is the clear");
        assertTrue(sim.world().instanceCleared, "and nothing else wrote a clear");
        assertTrue(sim.world().props.isEmpty(), "no property was invented for the maze: " + sim.world().props);
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("EnterPortal[")),
                "a party that starts split has to walk the passages, not wait for the leader");
        assertTrue(sim.steps().stream().anyMatch(l -> l.contains("9103000")),
                "and the clear is the exit's NPC with the passes: " + sim.steps());
        assertTrue(sim.steps().stream().noneMatch(l -> l.contains("Wait[") && !l.contains("why=")),
                "every wait says why");
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

    /**
     * Chaos Zakum is the same altar one instance id over: its own row ({@code zakum-chaos}), its own row-ordered body
     * chain (8800100 -> 8800101 -> 8800102, which is exactly what Mob.wz's {@code revive} says) and its own clear.
     */
    @Test
    void chaosZakumWalksItsOwnChainAndTheInstanceClearsItself() {
        Sim sim = play("ChaosZakumBattle", party(6), 2_000);

        assertEquals("cleared", sim.ended(), "the chaos clear is the script's defeatedBoss=1: " + sim.ended());
        assertEquals(List.of(1), sim.stageOrder(), "one map, no stage transitions");
        assertEquals("1", sim.world().props.get("defeatedBoss"));
        assertTrue(sim.world().killed.containsAll(List.of(8800103, 8800110, 8800100, 8800101, 8800102)),
                "every chaos arm, then the chaos body's three forms: " + sim.world().killed);
        assertEquals(CHAOS_FAKE_BODY, sim.world().killed.get(8),
                "the first chaos body form is only offered once the eight chaos arms fell");
        assertTrue(sim.world().killed.stream().noneMatch(id -> id >= 8800000 && id <= 8800010),
                "a chaos room never touches a normal part: " + sim.world().killed);
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
        Sim sim = play("TD_Battle2", party(6), 2_000, w -> w.spawnAfter(45_000));

        assertEquals("cleared", sim.ended(), "the boss arrived, died, and the instance cleared: " + sim.ended());
        assertEquals(List.of(DUNAS), sim.world().killed);
        assertTrue(sim.world().waits > 0, "the room really was empty for a while: " + tail(sim.steps(), 5));
    }

    /**
     * Papulatus is opened by a reactor a bot clicks: 220080001 holds no mobs at all, only reactor 2201004
     * (scripts/reactor/2201004.js:22-29), which spawns 8500000. The chain 8500000 -> 8500001 -> 8500002 is WZ
     * {@code info/revive}, and 8500002 is what {@code isPapulatus} waits for (PapulatusBattle.js:229-232).
     */
    @Test
    void aBotPartyClicksPapulatusReactorAndWalksTheChainToTheClear() {
        Sim sim = play("PapulatusBattle", party(6), 2_000);

        assertOpenedRoomClear(sim, List.of(PAPULATUS_1, PAPULATUS_2, PAPULATUS_3), 2201004);
    }

    /** Krexel is the same shape: reactor 5411001 (scripts/reactor/5411001.js:22-29) spawns 9420521, revive 9420522. */
    @Test
    void aBotPartyClicksKrexelsReactorAndKillsBothForms() {
        Sim sim = play("KrexelBattle", party(6), 2_000);

        assertOpenedRoomClear(sim, List.of(KREXEL_1, KREXEL_2), 5411001);
    }

    /**
     * Showa's chain is 9400112 -> 9400113 -> 9400300 (Mob.wz revive), opened by reactor 8001000. The bonus map
     * 801040101 is deliberately not a second stage: clearPQ spawns the bonus mob in the same call that clears
     * (ShowaBattle.js:196-207), so there is nothing for a bot to walk to.
     */
    @Test
    void aBotPartyClicksShowasReactorAndWalksTheChainToTheClear() {
        Sim sim = play("ShowaBattle", party(6), 2_000);

        assertOpenedRoomClear(sim, List.of(SHOWA_1, SHOWA_2, SHOWA_3), 8001000);
        assertTrue(sim.steps().stream().noneMatch(st -> st.contains("WarpTo[mapId=801040101]")),
                "the bonus room is a drop, not a stage: " + tail(sim.steps(), 8));
    }

    /**
     * Balrog's body starts life as a fake (BalrogBattle.js:145-153) and the script makes it real (:262-265). The row
     * lists it last, and the fake is never a fight target: {@link BossTargetRules} skips unengaged fake parts, so the
     * two unsealed forms die first and the body is only hittable after {@code makeMonsterReal}.
     */
    @Test
    void aBotPartyNeverFightsBalrogsFakeBodyAndTheInstanceClearsItself() {
        Sim sim = play("BalrogBattle", party(6), 2_000);

        assertEquals("cleared", sim.ended(), "the script's second unsealed death is clearPQ: " + sim.ended()
                + "; last steps " + tail(sim.steps(), 10));
        assertEquals(BALROG_2, sim.world().killed.get(0), "the unsealed form in the room dies first");
        assertTrue(sim.world().killed.containsAll(List.of(BALROG_BODY, BALROG_1)),
                "and the body the script made real is fought as a body: " + sim.world().killed);
        assertFalse(sim.world().foughtFake,
                "8830000 is a fake body until the script makes it real: no Fight may ever name it while it is fake");
        assertTrue(sim.steps().stream().noneMatch(st -> st.contains(":Abandon[")), tail(sim.steps(), 10));
    }

    /**
     * Scarga is the two-reactor room: 5511000 brings Targa's half (9420542..44) and 5511001 brings Scarlion's
     * (9420547..9420549), and {@code isScarga} counts only the two last forms (ScargaBattle.js:192-195). Both reactors
     * must be clicked, in the row's order, before both halves can die.
     */
    @Test
    void aBotPartyClicksBothScargaReactorsAndKillsBothHalves() {
        Sim sim = play("ScargaBattle", party(6), 2_000);

        assertOpenedRoomClear(sim, null, 5511000, 5511001); // two chains: asserted one at a time below
        // the two halves are independent chains, so the kills interleave; what has to hold is each chain's own order
        assertEquals(List.of(SCARGA_TARGA_1, SCARGA_TARGA_2, SCARGA_TARGA_3), targaKills(sim),
                "Targa's half walks 9420542 -> 9420543 -> 9420544");
        assertEquals(List.of(SCARGA_SCARLION_1, SCARGA_SCARLION_2, SCARGA_SCARLION_3), scarlionKills(sim),
                "Scarlion's half walks 9420547 -> 9420548 -> 9420549");
        assertEquals(java.util.Set.of(SCARGA_TARGA_1, SCARGA_TARGA_2, SCARGA_TARGA_3, SCARGA_SCARLION_1,
                SCARGA_SCARLION_2, SCARGA_SCARLION_3), java.util.Set.copyOf(sim.world().killed),
                "both halves died, six deaths: " + sim.world().killed);
    }

    /**
     * Elemental Thanatos is two map-life monsters with no chain, and the script clears on the SECOND death
     * (ElementalBattle.js:235-248) - which is why the row reads the instance's flag and not the {@code boss} counter.
     */
    @Test
    void aBotPartyKillsBothElementalThanatosAndTheInstanceClearsItself() {
        Sim sim = play("ElementalBattle", party(2), 2_000);

        assertSingleRoomClear(sim, List.of(ELEMENTAL_1, ELEMENTAL_2));
    }

    /**
     * Pink Bean walks the script's own wave order: 8820002 through 8820006 (PinkBeanBattle.js:259-304), then the real
     * 8820001, whose death writes {@code defeatedBoss 1} in the same call that clears (:261). The statues and the fake
     * body are never targets: they are not in the row, and {@code 8820000} is not even a map life.
     */
    @Test
    void aBotPartyWalksPinkBeansWavesToTheDefeatedBossProperty() {
        Sim sim = play("PinkBeanBattle", party(6), 2_000);

        assertEquals("cleared", sim.ended(), "defeatedBoss == 1 is the clear: " + sim.ended()
                + "; last steps " + tail(sim.steps(), 10));
        assertEquals(List.of(PINKBEAN_1, PINKBEAN_2, PINKBEAN_3, PINKBEAN_4, PINKBEAN_5, PINKBEAN),
                sim.world().killed, "the five statue forms, then the real Pink Bean");
        assertEquals("1", sim.world().props.get("defeatedBoss"), "PinkBeanBattle.js:261 writes it with the clear");
        for (int statue = 8820019; statue <= 8820023; statue++) {
            int id = statue;
            assertFalse(sim.world().killed.contains(id), "a statue is never a target: " + id);
            assertTrue(sim.steps().stream().noneMatch(st -> st.contains(String.valueOf(id))),
                    "a statue is never even named by a step: " + id);
        }
        assertTrue(sim.steps().stream().noneMatch(st -> st.contains("Fight[mobIds=[8820000")),
                "8820000 is the script's fake and is not in the row: " + tail(sim.steps(), 8));
    }

    /**
     * Delli's room holds the script's friendly monster 9300162, which must never be attacked. The guarantee is that a
     * mob the row does not list gets no part at all, so a row that stays inside its own target list can never order a
     * friendly into a Fight. Delli's own row is not on this branch - the room's win is its script's timer, and that
     * belongs to {@code DelliHoldSolver} on {@code 164-opq}.
     */
    @Test
    void aFriendlyMobTheRowDoesNotListNeverBecomesATarget() {
        assertNull(LiveStageView.partRole(9300162, List.of()), "an unlisted monster is not a part at all");
        assertNull(LiveStageView.partRole(9300162, List.of(9600000, 9600001)),
                "and it stays out even when the room lists other monsters");
        assertEquals(BossTargetRules.Role.ADD, LiveStageView.partRole(9600000, List.of(9600000, 9600001)),
                "a listed monster is a target, which is why the row must not list the friendly one");
    }

    /**
     * Round 3, fail-before for primitive A: without the White Essence the run cannot open Latanica's door, and the row
     * says so instead of walking to the portal and being silently refused. scripts/portal/captinsg00.js:4 is the only
     * check; the instance never asks again (LatanicaBattle.js getEligibleParty), so the row's declaration is the only
     * thing that can make a dry run visible before a player is standing at the door.
     */
    @Test
    void aRunWithoutTheWhiteEssenceWaitsAtLatanicasDoorAndNeverEnters() {
        Sim sim = play("LatanicaBattle", party(6), 400);

        assertNull(sim.ended(), "a run that cannot be let in is not ended: " + sim.ended());
        assertTrue(sim.steps().stream().anyMatch(st -> st.contains(":Wait[") && st.contains("4000381")),
                "the wait names the missing item: " + tail(sim.steps(), 6));
        assertTrue(sim.steps().stream().noneMatch(st -> st.contains(":EnterPortal[")), "it does not walk to the door");
        assertTrue(sim.steps().stream().noneMatch(st -> st.contains(":HitReactor[")), "and it does not click past it");
        assertTrue(sim.steps().stream().noneMatch(st -> st.contains(":Fight[")), "and it does not fight");
        assertTrue(sim.steps().stream().noneMatch(st -> st.contains(":Abandon[")), "and it is not a dead room either");
        assertTrue(sim.world().killed.isEmpty(), "nothing dies in a run that never got in");
    }

    @Test
    void aRunHoldingTheWhiteEssenceOpensLatanicasRoomAndClearsIt() {
        Sim sim = play("LatanicaBattle", party(6), 2_000, w -> w.give(1, LATANICA_DOOR_ITEM, 1));

        assertOpenedRoomClear(sim, List.of(LATANICA), 5411000);
    }

    // ---- the five 5 s spawns: the script's spawnMob1, inside the solver's grace ---------------------------------

    @Test
    void aBotPartyKillsMagnusFiveSecondsAfterSetup() {
        assertRoomClear(play("MagnusBattle", party(6), 2_000), List.of(MAGNUS), true);
    }

    @Test
    void aBotPartyKillsWillFiveSecondsAfterSetup() {
        assertRoomClear(play("NWillBattle", party(6), 2_000), List.of(NWILL), true);
    }

    @Test
    void aBotPartyKillsLucidFiveSecondsAfterSetup() {
        assertRoomClear(play("NLucidBattle", party(6), 2_000), List.of(NLUCID), true);
    }

    @Test
    void aBotPartyKillsVerusFiveSecondsAfterSetup() {
        assertRoomClear(play("NVerusBattle", party(6), 2_000), List.of(NVERUS), true);
    }

    @Test
    void aBotPartyKillsDarknellFiveSecondsAfterSetup() {
        assertRoomClear(play("NDarknellBattle", party(6), 2_000), List.of(NDARKNELL), true);
    }

    /**
     * A row whose room is opened by a reactor: the same shape as {@link #assertSingleRoomClear}, except that the members
     * that do not click do idle for the tick the click is in flight - the room is genuinely empty until the click lands,
     * and the solver's idle step is a 500 ms Wait, not a stall. The rule the assertion keeps is the one that matters:
     * once the fight has started, nobody waits again.
     */
    private static void assertOpenedRoomClear(Sim sim, List<Integer> deaths, int... reactorIds) {
        assertRoomClear(sim, deaths, true);
        assertReactorsHit(sim, reactorIds);
    }

    /** The three things every one of these rows is: one stage, one map, the listed deaths in order, no wait, a clear. */
    /**
     * GuardianNex is one script and six caves, so the six rows are only distinguishable by the map the instance was
     * built on ({@code MechanicsRuns.contentFor(eventScript, instanceMapIds)}): each cave's row must be the one that
     * arms, and its boss the one that dies.
     */
    @Test
    void eachOfGuardianNexSixCavesArmsItsOwnRowAndFightsItsOwnBoss() {
        for (Map.Entry<Integer, Integer> cave : new java.util.TreeMap<>(NEX_CAVES).entrySet()) {
            Sim sim = play(cave.getKey(), "GuardianNex", party(1), 2_000);

            assertEquals(cave.getKey(), sim.run().stageData().mapId(),
                    "cave " + cave.getKey() + " must pick its own row, not the first row for the script");
            assertRoomClear(sim, List.of(cave.getValue()), false);
        }
    }

    private static void assertSingleRoomClear(Sim sim, List<Integer> deaths) {
        assertRoomClear(sim, deaths, false);
    }

    private static void assertRoomClear(Sim sim, List<Integer> deaths, boolean idleBeforeTheFightIsAllowed) {
        assertEquals("cleared", sim.ended(), "the run must end on the script's instance clear: " + sim.ended()
                + " after " + sim.ticks() + " ticks; last steps " + tail(sim.steps(), 10));
        assertTrue(sim.run().cleared(), "clear: {instance: true} is the script's setEventCleared()");
        assertEquals(List.of(1), sim.stageOrder(), "one map, no stage transitions");
        if (deaths != null) {
            assertEquals(deaths, sim.world().killed, "the fight's ids, in the order the chain wakes them");
        }
        if (!idleBeforeTheFightIsAllowed) {
            assertEquals(0, sim.world().waits, "a healthy fight never waits: " + tail(sim.steps(), 10));
        } else {
            int firstFight = -1;
            for (int i = 0; i < sim.steps().size(); i++) {
                if (sim.steps().get(i).contains("Fight[")) {
                    firstFight = i;
                    break;
                }
            }
            assertTrue(firstFight >= 0, "the room's fight does happen: " + tail(sim.steps(), 10));
            for (int i = firstFight + 1; i < sim.steps().size(); i++) {
                assertFalse(sim.steps().get(i).contains(":Wait["),
                        "nobody idles once the fight has started: " + sim.steps().get(i));
            }
        }
        assertTrue(sim.steps().stream().noneMatch(s -> s.contains(":Abandon[")), "no abandon: " + tail(sim.steps(), 10));
    }

    private static List<Integer> targaKills(Sim sim) {
        return sim.world().killed.stream()
                .filter(id -> id == SCARGA_TARGA_1 || id == SCARGA_TARGA_2 || id == SCARGA_TARGA_3).toList();
    }

    private static List<Integer> scarlionKills(Sim sim) {
        return sim.world().killed.stream()
                .filter(id -> id == SCARGA_SCARLION_1 || id == SCARGA_SCARLION_2 || id == SCARGA_SCARLION_3).toList();
    }

    /** The room's own reactors, clicked once each, in the row's order, and nothing else touched. */
    private static void assertReactorsHit(Sim sim, int... reactorIds) {
        List<String> hits = sim.steps().stream().filter(s -> s.contains("HitReactor[reactorId=")).toList();
        List<String> expected = java.util.Arrays.stream(reactorIds)
                .mapToObj(id -> "HitReactor[reactorId=" + id + "]").toList();
        assertEquals(reactorIds.length, hits.size(), "each reactor is clicked exactly once: " + hits);
        for (int i = 0; i < reactorIds.length; i++) {
            assertTrue(hits.get(i).endsWith(expected.get(i)),
                    "reactor " + i + " is " + reactorIds[i] + ", in the row's order: " + hits);
        }
    }

    // ---- the four Normal-tier fights: one room, one mob, spawned five seconds after setup --------------

    /**
     * Hard Will, Hard Lucid, Hard Darknell and Hard Verus Hilla are the same script shape: one empty room, one mob
     * spawned by `eim.schedule("spawnMob1", 5000)`, and a clear that is the script's own `setEventCleared`. What each
     * test below has to prove is not that the mob dies - that is the solver's ordinary work - but that the solver
     * <b>waits out the five seconds without abandoning the room</b>, which is the one thing a boss row can get wrong
     * when its target is not there on the first tick.
     */
    @Test
    void hardWillSpawnsFiveSecondsInAndTheRunWaitsForIt() {
        assertLateSpawn("NWillBattle", 9990301);
    }

    @Test
    void hardLucidSpawnsFiveSecondsInAndTheRunWaitsForIt() {
        assertLateSpawn("NLucidBattle", 9990150);
    }

    @Test
    void hardDarknellSpawnsFiveSecondsInAndTheRunWaitsForIt() {
        assertLateSpawn("NDarknellBattle", 9995009);
    }

    @Test
    void hardVerusSpawnsFiveSecondsInAndTheRunWaitsForIt() {
        assertLateSpawn("NVerusBattle", 9990415);
    }

    /**
     * The script's own delay is {@code spawnTimes = [5000]} and the row's grace is 15 s, so five seconds of an empty
     * room must be a wait and not an abandon: the run reaches {@code Done}, kills the one mob, and no step it took was an
     * {@code Abandon}.
     */
    private static void assertLateSpawn(String eventScript, int boss) {
        Sim sim = play(eventScript, party(6), 2_000, w -> w.spawnAfter(5_000));

        assertEquals("cleared", sim.ended(), eventScript + "'s clear is the script's own event flag: " + sim.ended());
        assertEquals(List.of(1), sim.stageOrder(), "one map, no stage transitions");
        assertTrue(sim.world().killed.contains(boss), "the mob the script spawns: " + sim.world().killed);
        assertTrue(sim.steps().stream().noneMatch(s -> s.contains("Abandon")),
                "an empty room for five seconds is a wait, not a fault: " + tail(sim.steps(), 12));
        assertTrue(sim.steps().stream().anyMatch(s -> s.startsWith("1:") && s.contains("Wait")),
                "and the wait is the step it took: " + tail(sim.steps(), 12));
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

        /** The maze's summoner reactor: hitting it is what brings monsters, and they drop the pass. */
        private boolean reactorSummons;

        @Override
        public Map<String, Integer> portalsOn(int mapId) {
            return MAZE.getOrDefault(mapId, Map.of());
        }

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

        /**
         * A boss the script brings in after setup ({@code eim.schedule("spawnMob1", 5000)} in the four Normal-tier
         * fights): the parts stand in {@link #pendingParts} until this many milliseconds have passed, so a test can prove
         * the solver waits the spawn out instead of abandoning the room.
         */
        private long spawnDelayMs;
        private long spawnedAt;
        private final List<BossTargetRules.Part> pendingParts = new ArrayList<>();
        private final List<String> applied = new ArrayList<>();

        /** Every map a member stood on, in the order they first did: a run that finishes crossed them all. */
        private final java.util.Set<Integer> visited = new java.util.LinkedHashSet<>();
        private final List<Integer> killed = new ArrayList<>();
        private final Map<Integer, Integer> streaks = new LinkedHashMap<>();
        private final Map<Integer, Integer> mapsAtStageStart = new LinkedHashMap<>();
        private List<Rectangle> areas = List.of();
        private int mobs;
        /** b365, OPQ's garden: 0 not there, 1 Nependeaths, 2 a Royal grown from a seed, 3 Papa Pixie. */
        private int garden;
        private final java.util.Set<Integer> plantedPots = new java.util.HashSet<>();
        private long now = 1_000;
        private boolean passGiven;
        private boolean altarRefuses;
        private boolean noPasses;
        private boolean bedsRefuse;
        private int altarDelayTicks;
        private int altarTicks;
        private int tick;
        private int waits;
        private boolean foughtFake;
        private int balrogDeaths;
        private int elementalDeaths;
        private boolean targaDown;
        private boolean scarlionDown;
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
                case 925100000, 925100100, 925100200, 925100300, 925100400, 925100500 -> {
                    // the ship: six maps, each a kill and a door, and the guides that gate them
                    mobs = 12;
                    npcs.put(PirateQuestSolver.GUIDE, new int[]{0, 0});
                    npcs.put(PirateQuestSolver.RESCUED, new int[]{0, 0});
                    for (int door : PirateQuestSolver.DOORS) {
                        reactors.put(String.valueOf(door), 0); // every door starts shut
                    }
                }
                case 925010300 -> {
                    // "Save Delli": the room re-spawns every 15 s (DelliBattle.js:109-112) and the event's own six-minute
                    // timer is what clears it (:119-124) - nothing the party does advances it (:213 is empty)
                    mobs = 10;
                }
                case 674030000 -> {
                    // the treasure room: no monsters of its own, the chest is the summon (scripts/reactor/6741001.js)
                    mobs = 0;
                    reactors.put(String.valueOf(TreasureChestSolver.CHEST), 0);
                }
                case 809050000 -> {
                    // the maze: no monsters of its own, the summoner reactor brings them (scripts/reactor/8098000.js)
                    mobs = 0;
                    reactorSummons = true;
                    npcs.put(MAZE_EXIT_NPC, new int[]{0, 0});
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
                case 280030001 -> {
                    // the chaos altar: 2111001.js:30-38 spawns the chaos fake body and the eight chaos arms
                    props.put("summoned", "true");
                    parts.add(new BossTargetRules.Part(CHAOS_FAKE_BODY, BossTargetRules.Role.BODY, true, false, false,
                            false, 0));
                    for (int arm : CHAOS_ARMS) {
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
                // the room-opened rows: nothing stands here until a reactor is clicked (or, for Balrog and Pink
                // Bean, until the script's own setup spawn lands). 220080001, 541020800, 801040100 and 551030200 hold
                // a reactor and no mobs at all in the dump.
                case 105100300 -> {
                    // BalrogBattle.js:145-153: the fake body plus one unsealed form; the claw 8830006 is not in the
                    // row, so the fight the row describes is the two unsealed forms and then the body made real.
                    parts.add(new BossTargetRules.Part(BALROG_BODY, BossTargetRules.Role.BODY, true, false, false,
                            false, 0));
                    parts.add(life(BALROG_2));
                }
                case 922020100 -> {
                    parts.add(life(ELEMENTAL_1)); // Map.wz/Map/Map9/922020100.img.xml, both mobTime -1
                    parts.add(life(ELEMENTAL_2));
                }
                case 270050100 -> parts.add(life(PINKBEAN_1)); // startWave one second after setup (:93-96, :306-313)
                case 240070010, 240070020, 240070030, 240070040, 240070050, 240070060 ->
                        parts.add(life(NEX_CAVES.get(stageMap))); // GuardianNex: the cave's own life, mobTime -1
                default -> {
                    // a room whose parts come from somewhere else keeps its own case: summoned by a click
                    // (summonProperty) or opened by a reactor the solver must hit first
                    if (run.content().kind() == MechanicsModel.Kind.BOSS && run.stageData().summonProperty() == null
                            && run.stageData().hitReactors().isEmpty()) {
                        // a one-part boss: the row names the mob the script spawns, and nothing else stands in the room
                        for (int mob : run.stageData().mobs()) {
                            parts.add(new BossTargetRules.Part(mob, BossTargetRules.Role.BODY, false, true, false, false, 0));
                        }
                    }
                }
            }
            if (spawnDelayMs > 0 && !parts.isEmpty()) {
                pendingParts.clear();
                pendingParts.addAll(parts);
                parts.clear();
                spawnedAt = now + spawnDelayMs;
            }
        }

        /** The script's own clock: a delayed spawn lands when it is due, and only then. */
        void tickSpawn() {
            if (!pendingParts.isEmpty() && now >= spawnedAt) {
                parts.addAll(pendingParts);
                pendingParts.clear();
            }
        }

        /**
         * Makes the boss arrive {@code ms} after the room was entered. The parts are built at entry, so the ones already
         * standing are moved back into the pending list: a world that only set a field would leave the boss in the room
         * from the first tick and the test would prove nothing.
         */
        void spawnAfter(long ms) {
            spawnDelayMs = ms;
            if (!parts.isEmpty()) {
                pendingParts.clear();
                pendingParts.addAll(parts);
                parts.clear();
                spawnedAt = now + ms;
            }
        }

        /** A mob the map instance starts with: a listed, hit-able target with no role but its id. */
        private static BossTargetRules.Part life(int mobId) {
            return new BossTargetRules.Part(mobId, BossTargetRules.Role.ADD, false, true, false, false, 0);
        }

        // ---- applying one step: the script rule each step triggers ---------------------------------------

        void apply(Map<Integer, SolverStep> steps) {
            tick++;
            for (int map : at.values()) {
                visited.add(map);
            }
            if (!noRespawn && tick % 150 == 0 && at.containsValue(925010300)) {
                mobs = Math.max(mobs, 10); // respawnStages: Delli's room re-spawns every 15 s (DelliBattle.js:109-112)
            }
            if (!instanceCleared && now >= 360_000 && run.stageData().mapId() == 925010300) {
                // scheduledTimeout: six minutes, kill the room, show the clear, clearPQ (DelliBattle.js:119-124)
                mobs = 0;
                instanceCleared = true;
            }
            if (!noRespawn && tick % 150 == 0 && (at.containsValue(925100100) || at.containsValue(925100400))) {
                // the ship refills a room the party is still working (MapleMap's own respawn); the two rooms whose gate
                // is a property or a set of doors, never "an empty map on the tick it was checked"
                mobs = Math.max(mobs, 12);
            }
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
                case SolverStep.EnterPortal p -> {
                    // a portal is the passage: in the maze it is that map's own target (Map.wz/Map8/8090500*), on the
                    // ship it is davy_nextN, whose gate the script itself checks
                    int here = at.getOrDefault(id, run.stageData().mapId());
                    Integer maze = MAZE.getOrDefault(here, Map.of()).get(p.portalName());
                    if (maze != null) {
                        at.put(id, maze);
                    } else if (SHIP_MAPS.contains(here)) {
                        int next = shipNext(here, p.portalName());
                        if (next > 0) {
                            at.put(id, next); // -1: the script's gate refused, and the member stays where it is
                        }
                    } else {
                        at.put(id, run.stageData().mapId()); // any other row: the passage leads back to the stage
                    }
                }
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
            if (reactorSummons && reactorId == MAZE_SUMMONER) {
                return 0; // the maze's summoner stands on every map but three (scripts/reactor/8098000.js:3)
            }
            return reactors.getOrDefault(String.valueOf(reactorId), -1);
        }

        private void hitReactor(int id, String key) {
            int stageMap = run.stageData().mapId();
            if (key.equals(String.valueOf(TreasureChestSolver.CHEST)) && run.stageData().mapId() == TreasureChestSolver.MAP) {
                mobs = Math.max(mobs, 1); // scripts/reactor/6741001.js: act() spawns 9400589, nothing else does
                return;
            }
            if (reactorSummons && key.equals(String.valueOf(MAZE_SUMMONER))) {
                mobs = Math.max(mobs, 5); // scripts/reactor/8098000.js: the monsters the passes come from
                return;
            }
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
                throw new AssertionError("b365: 2001016 takes an item (Reactor.wz type 100); a client never hits it");
            }
            // the room-opened rows: one click is the whole spawn (scripts/reactor/*.js act())
            switch (stageMap) {
                case 220080001 -> {
                    if (key.equals("2201004")) {
                        parts.add(life(PAPULATUS_1));
                    }
                    return;
                }
                case 541020800 -> {
                    if (key.equals("5411001")) {
                        parts.add(life(KREXEL_1));
                    }
                    return;
                }
                case 801040100 -> {
                    if (key.equals("8001000")) {
                        parts.add(life(SHOWA_1));
                    }
                    return;
                }
                case 551030200 -> {
                    // 3.2 s of summonBossDelayed is instant here: the room only cares that each half arrives
                    if (key.equals("5511000")) {
                        parts.add(life(SCARGA_TARGA_1));
                    }
                    if (key.equals("5511001")) {
                        parts.add(life(SCARGA_SCARLION_1));
                    }
                    return;
                }
                case 541010100 -> {
                    if (key.equals("5411000")) {
                        parts.add(life(LATANICA));
                    }
                    return;
                }
                default -> {
                }
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
                case "" -> {
                    // b365: the garden's pots share a name, so they are placed by position
                    if (p.itemId() == OpqStatueSolver.SEED) {
                        assertTrue(p.x() == 430 || p.x() == 200, "a seed goes in a seed pot, never 2002003: " + p);
                        assertTrue(plantedPots.add(p.x()), "a planted pot takes no second seed");
                        take(id, p.itemId(), p.count());
                        garden = 2; // scripts/reactor/2001000.js: this one grows a Royal
                        mobs = 1;
                    } else if (p.itemId() == OpqStatueSolver.STRANGER_SEED) {
                        assertTrue(p.x() == -755, "Papa Pixie's seed goes in 2002003: " + p);
                        take(id, p.itemId(), p.count());
                        drop(0, ROOT_OF_LIFE, 1); // scripts/reactor/2002003.js drops the Grass of Life
                        garden = 0;
                    } else {
                        throw new AssertionError("nothing in the garden takes " + p.itemId());
                    }
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
            if (p.reactorId() >= 2519000 && p.reactorId() <= 2519003) {
                // the ship's four doors: one Old Metal Key each, and the activation opens the door
                if (p.itemId() == PirateQuestSolver.KEY && p.count() == 1) {
                    pending.add(new Pending(p.reactorId(), p.itemId(), now + 5_000));
                } else {
                    drop(0, p.itemId(), p.count());
                }
                return;
            }
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
            if (PirateQuestSolver.DOORS.contains(p.reactorId())) {
                return; // scripts/reactor/2519000.js only shows the clear effect: the door itself is open
            }
            int stage = Integer.parseInt(props.getOrDefault("stage", "0")) + 1;
            props.put("stage", String.valueOf(stage)); // scripts/reactor/9108000.js:4-9
        }

        /**
         * The ship's five passages, each with the gate its own script applies: an empty map for the deck and the two
         * middle rooms ({@code scripts/portal/davy_next0.js}, {@code _next2.js}, {@code _next3.js}), {@code stage2 === "3"}
         * for the medals ({@code _next1.js}), and all four doors open on an empty map for the boss ({@code _next4.js},
         * which also spawns him). Returns the map to move to, or -1 when the gate refuses.
         */
        private int shipNext(int from, String portalName) {
            boolean empty = mobs == 0;
            return switch (from) {
                case 925100000 -> empty && "davy_next0".equals(portalName) ? stock(925100100) : -1;
                case 925100100 -> "3".equals(props.get("stage2")) && "davy_next1".equals(portalName) ? stock(925100200) : -1;
                case 925100200 -> empty && "davy_next2".equals(portalName) ? stock(925100300) : -1;
                case 925100300 -> empty && "davy_next3".equals(portalName) ? stock(925100400) : -1;
                case 925100400 -> {
                    if (empty && "davy_next4".equals(portalName)
                            && PirateQuestSolver.doorsOpen(this) == PirateQuestSolver.DOORS.size()) {
                        mobs = 1; // the Lord Pirate is spawned on the way in (scripts/portal/davy_next4.js:40-70)
                        yield 925100500;
                    }
                    yield -1;
                }
                default -> -1;
            };
        }

        /** A map the party walks onto for the first time gets its own monsters; one stage, six maps, one stock each. */
        private int stock(int map) {
            if (!visited.contains(map)) {
                mobs = map == 925100500 ? 1 : 12;
            }
            return map;
        }

        private void talk(int id, SolverStep.TalkNpc t) {
            if (t.npcId() == PirateQuestSolver.GUIDE) {
                // scripts/npc/2094002.js:47-78: twenty of the medal it asks for right now, then stage2 goes up by one
                int wanted = PirateQuestSolver.medalWanted(this);
                if (wanted > 0 && heldOf(id, wanted) >= PirateQuestSolver.MEDAL_COUNT) {
                    take(id, wanted, PirateQuestSolver.MEDAL_COUNT);
                    int stage = Integer.parseInt(props.getOrDefault("stage2", "0")) + 1;
                    props.put("stage2", String.valueOf(stage));
                }
                return;
            }
            if (t.npcId() == PirateQuestSolver.RESCUED) {
                // scripts/npc/2094001.js:45-58: the Lord Pirate down, then clearPQ() on a plain talk
                if (mobs == 0) {
                    instanceCleared = true;
                }
                return;
            }
            if (t.npcId() == MAZE_EXIT_NPC) {
                // scripts/npc/9103000.js:57-73 - the whole team here and thirty passes in the leader's hand
                if (missingFrom(at.getOrDefault(id, MAZE_EXIT_MAP)).isEmpty()
                        && heldOf(id, MAZE_PASS) >= LudiMazeSolver.NEED) {
                    take(id, MAZE_PASS, LudiMazeSolver.NEED);
                    instanceCleared = true;
                }
                return;
            }
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
                        if (garden == 0) {
                            garden = 1; // b365: nine Nependeaths (9300048) stand in the garden
                            mobs = 9;
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
            if (garden > 0 && mobs > 0) {
                mobs--;
                switch (garden) {
                    case 1 -> give(id, OpqStatueSolver.SEED, 1); // a Nependeath's seed
                    case 2 -> {
                        // the Royal dies at its pot: its Transparent Item lands in 2001016, which brings Papa Pixie
                        garden = 3;
                        mobs = 1;
                    }
                    case 3 -> give(id, OpqStatueSolver.STRANGER_SEED, 1); // Papa Pixie's Even Stranger Seed
                    default -> {
                    }
                }
                return;
            }
            if (mobs > 0) {
                mobs--;
                if (noPasses) {
                    return; // the drop tables give nothing: the stage can never clear
                }
                if (run.stageData().mapId() == TreasureChestSolver.MAP && mobs == 0) {
                    // TreasurePQ.js:244-252 watches 9400589 alone and calls clearPQ() on its death
                    instanceCleared = true;
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
                        if (part.fake()) {
                            foughtFake = true; // the solver must never name an unkillable fake body
                        }
                        killed.add(mobId);
                        parts.remove(part);
                        wake(part);
                        // the script's own rule for a one-boss room: monsterKilled sees the room's last form die and
                        // calls clearPQ(), which is setEventCleared - the instance flag, not a property. The row lists a
                        // revive chain in order, so the last id it names is the clear (Papulatus 8500002, Krexel 9420522)
                        List<Integer> chain = run.stageData().mobs();
                        if (!chain.isEmpty() && mobId == chain.get(chain.size() - 1)
                                && run.stageData().clear().onInstance()) {
                            instanceCleared = true;
                        }
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
            if (stageMap == 280030001) {
                if (!parts.stream().anyMatch(p -> p.role() == BossTargetRules.Role.ARM)) {
                    replaceFakeBody(CHAOS_FAKE_BODY, CHAOS_FAKE_BODY);
                }
                if (part.mobId() == 8800100) {
                    parts.add(new BossTargetRules.Part(8800101, BossTargetRules.Role.BODY, false, true, false, false, 0));
                }
                if (part.mobId() == 8800101) {
                    parts.add(new BossTargetRules.Part(8800102, BossTargetRules.Role.BODY, false, true, false, false, 0));
                }
                if (part.mobId() == 8800102) {
                    props.put("defeatedBoss", "1"); // ChaosZakumBattle.js:200-215
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
                case PAPULATUS_1 -> parts.add(life(PAPULATUS_2));
                case PAPULATUS_2 -> parts.add(life(PAPULATUS_3));
                case PAPULATUS_3 -> instanceCleared = true;
                case KREXEL_1 -> parts.add(life(KREXEL_2));
                case KREXEL_2 -> instanceCleared = true;
                case SHOWA_1 -> parts.add(life(SHOWA_2));
                case SHOWA_2 -> parts.add(life(SHOWA_3));
                case SHOWA_3 -> instanceCleared = true;
                case BALROG_2, BALROG_1, BALROG_BODY -> {
                    // BalrogBattle.js:250-274: the first unsealed death makes the fake body real, the second clears
                    balrogDeaths++;
                    if (balrogDeaths == 1) {
                        replaceFakeBody(BALROG_BODY, BALROG_BODY);
                        parts.add(life(BALROG_1));
                    }
                    if (balrogDeaths >= 2) {
                        instanceCleared = true;
                    }
                }
                case SCARGA_TARGA_1 -> parts.add(life(SCARGA_TARGA_2));
                case SCARGA_TARGA_2 -> parts.add(life(SCARGA_TARGA_3));
                case SCARGA_SCARLION_1 -> parts.add(life(SCARGA_SCARLION_2));
                case SCARGA_SCARLION_2 -> parts.add(life(SCARGA_SCARLION_3));
                case SCARGA_TARGA_3 -> targaDown = true;
                case SCARGA_SCARLION_3 -> scarlionDown = true;
                case PINKBEAN_1 -> parts.add(life(PINKBEAN_2));
                case PINKBEAN_2 -> parts.add(life(PINKBEAN_3));
                case PINKBEAN_3 -> parts.add(life(PINKBEAN_4));
                case PINKBEAN_4 -> parts.add(life(PINKBEAN_5));
                case PINKBEAN_5 -> parts.add(life(PINKBEAN)); // the fifteenth jr death spawns the real Pink Bean
                case PINKBEAN -> props.put("defeatedBoss", "1"); // PinkBeanBattle.js:261, in the same call as clearPQ
                case LATANICA -> instanceCleared = true; // LatanicaBattle.js:233-241 -> clearPQ, no property
                case MAGNUS -> instanceCleared = true; // MagnusBattle.js:217-220, the 5 s spawn is the clear target
                case NWILL -> instanceCleared = true; // NWillBattle.js:241-244
                case NLUCID -> instanceCleared = true; // NLucidBattle.js:248-251
                case NVERUS -> instanceCleared = true; // NVerusBattle.js:241-244
                case NDARKNELL -> instanceCleared = true; // NDarknellBattle.js:255-258
                default -> {
                }
            }
            if (targaDown && scarlionDown) {
                instanceCleared = true; // ScargaBattle.js:197-210: both last forms, then the clear
            }
            if (part.mobId() == ELEMENTAL_1 || part.mobId() == ELEMENTAL_2) {
                elementalDeaths++;
                if (elementalDeaths >= 2) {
                    instanceCleared = true; // ElementalBattle.js:235-248: the second death is the clear
                }
            }
            if (NEX_CAVES.containsValue(part.mobId())) {
                instanceCleared = true; // GuardianNex.js:94-103: this cave's boss dying is the instance's clear
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
            List<Integer> shared = new ArrayList<>(data.mapSet());
            if (solver != null) {
                shared.addAll(solver.extraMaps(this));
            }
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
        public List<StageView.ReactorPlacement> reactorsOn(int mapId) {
            if (mapId != 920010800) {
                return List.of();
            }
            return List.of(new StageView.ReactorPlacement(1, 2001000, "pot", plantedPots.contains(430) ? 1 : 0, 430, 20),
                    new StageView.ReactorPlacement(2, 2001001, "pot", plantedPots.contains(200) ? 1 : 0, 200, 20),
                    new StageView.ReactorPlacement(3, 2001016, "", 0, 199, 29),
                    new StageView.ReactorPlacement(4, 2002003, "pot", 0, -755, 19));
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
