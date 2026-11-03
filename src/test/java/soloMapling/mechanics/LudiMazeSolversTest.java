package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: LudiMaze PQ. The maze is the client's own portal graph ({@code Map.wz/Map/Map8/8090500*.img.xml}), the
 * party starts split by design ({@code LudiMazePQ.js:144-150}) and the exit's NPC wants the whole team there with thirty
 * passes ({@code scripts/npc/9103000.js:57-73}).
 */
class LudiMazeSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;
    private static final int EXIT = 9103000;
    private static final int PASS = 4001106;
    private static final int GOAL = 809050015;

    /**
     * The maze exactly as the client data has it, one line per map, read from
     * {@code Map.wz/Map/Map8/8090500*.img.xml} (each map's {@code pt 2} portals and their {@code tm}). It is not a
     * clean formula: the passages are fixed per map, and only {@code 809050008}'s {@code t001} reaches the exit.
     */
    private static final Map<Integer, Map<String, Integer>> CLIENT_MAZE = clientMaze();

    private static Map<String, Integer> exits(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    private static Map<Integer, Map<String, Integer>> clientMaze() {
        Map<Integer, Map<String, Integer>> maze = new LinkedHashMap<>();
        maze.put(809050000, exits("t001", 809050004, "t002", 809050007, "t003", 809050012));
        maze.put(809050001, exits("t000", 809050005, "t001", 809050008, "t002", 809050013));
        maze.put(809050002, exits("t000", 809050006, "t001", 809050009, "t002", 809050014));
        maze.put(809050003, exits("t000", 809050007, "t001", 809050010, "t002", 809050000));
        maze.put(809050004, exits("t000", 809050008, "t001", 809050011, "t002", 809050001));
        maze.put(809050005, exits("t000", 809050009, "t001", 809050012, "t002", 809050002));
        maze.put(809050006, exits("t001", 809050010, "t002", 809050013, "t003", 809050003));
        maze.put(809050007, exits("t000", 809050011, "t001", 809050014, "t002", 809050004));
        maze.put(809050008, exits("t000", 809050012, "t001", 809050015, "t002", 809050005));
        maze.put(809050009, exits("t000", 809050013, "t001", 809050001, "t002", 809050006));
        maze.put(809050010, exits("t000", 809050014, "t001", 809050002, "t002", 809050007));
        maze.put(809050011, exits("t000", 809050000, "t001", 809050003, "t002", 809050008));
        maze.put(809050012, exits("t001", 809050001, "t002", 809050004, "t003", 809050009));
        maze.put(809050013, exits("t000", 809050002, "t001", 809050005, "t002", 809050010));
        maze.put(809050014, exits("t000", 809050003, "t001", 809050006, "t002", 809050011));
        maze.put(GOAL, exits("t000", 809050008));
        return maze;
    }

    private static MechanicsModel.Content row() {
        MechanicsModel.Content c = MechanicsRuns.contentFor("LudiMazePQ");
        assertNotNull(c, "content/ludimazepq.yaml must be listed in content/index.txt");
        return c;
    }

    private static MechanicsModel.Stage mazeStage() {
        return row().stages().get(0);
    }

    private static List<RunMember> two() {
        return List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT));
    }

    private static RunMember member(boolean leader) {
        return leader ? two().get(0) : two().get(1);
    }

    // ---- the row ------------------------------------------------------------------------------------

    @Test
    void theRowIsOneStageOverSixteenMapsOffItsOwnSwitch() {
        MechanicsModel.Content c = row();
        assertEquals("ludimazepq", c.id());
        assertEquals("LudiMazePQ", c.eventScript());
        assertEquals(MechanicsModel.Kind.PQ, c.kind());
        assertEquals(9103001, c.entryNpc(), "Rolly on 220000000 starts it (scripts/npc/9103001.js:25-28,55,77)");
        assertEquals(220000000, c.recruitMap());
        assertEquals(809050017, c.exitMap());
        assertEquals(3, c.minPlayers(), "LudiMazePQ.js:27");
        assertEquals(6, c.maxPlayers());
        assertEquals(51, c.minLevel(), "LudiMazePQ.js:28");
        assertEquals(255, c.maxLevel());
        assertEquals(15, c.timeLimitMinutes(), "LudiMazePQ.js:37");
        assertEquals(Mechanics.LUDIMAZE_FLAG, c.flag());
        assertEquals(1, c.stages().size());

        MechanicsModel.Stage s = mazeStage();
        assertEquals(809050000, s.mapId());
        assertEquals(15, s.maps().size(), "fifteen maps playerEntry can drop a member on (:144-150)");
        assertEquals(16, s.mapSet().size(), "plus the stage's own map: the whole maze");
        for (int map = 809050000; map <= GOAL; map++) {
            assertTrue(s.mapSet().contains(map), map + " is the maze's own ground, not a stray map");
        }
        assertTrue(s.clear().onInstance(), "9103000 calls clearPQ() and writes no property (:66-73)");
        assertNull(s.clear().eventProperty());
        assertEquals(EXIT, s.npcId());
        assertEquals(30, LudiMazeSolver.NEED, "9103000.js:62");
        assertEquals(PASS, s.objectives().stream().filter(o -> o.kind() == MechanicsModel.ObjectiveKind.HAND_IN)
                .findFirst().orElseThrow().itemId());
    }

    // ---- the graph ----------------------------------------------------------------------------------

    @Test
    void theMazeIsTheClientsGraphAndAShortestRouteReachesTheExit() {
        for (int from = 809050000; from < GOAL; from++) {
            String hop = LudiMazeSolver.firstHopToward(CLIENT_MAZE, from, GOAL);
            assertNotNull(hop, from + " must reach the exit over the client's own portals");
            assertTrue(CLIENT_MAZE.get(from).containsKey(hop), from + ": the hop is one of its own portals");
            assertTrue(reaches(CLIENT_MAZE, from, GOAL, 6), from + " reaches the exit over the client's own portals");
        }
        assertEquals("t001", LudiMazeSolver.firstHopToward(CLIENT_MAZE, 809050008, GOAL),
                "809050008's t001 is the one passage that reaches the exit");
        assertNull(LudiMazeSolver.firstHopToward(CLIENT_MAZE, GOAL, GOAL), "already there: no hop");
    }

    private static boolean reaches(Map<Integer, Map<String, Integer>> g, int from, int goal, int hops) {
        if (from == goal) {
            return true;
        }
        if (hops == 0) {
            return false;
        }
        Map<String, Integer> exits = g.get(from);
        if (exits == null) {
            return false;
        }
        for (int next : exits.values()) {
            if (reaches(g, next, goal, hops - 1)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anUnmappedMazeIsExploredOnePortalAtATimeAndLearned() {
        View view = new View(List.of(LEADER, MEMBER), 809050000);
        view.portals.putAll(CLIENT_MAZE);
        List<RunMember> party = two();

        SolverStep first = new LudiMazeSolver().next(view, party.get(0));
        SolverStep.EnterPortal hop = assertInstanceOf(SolverStep.EnterPortal.class, first,
                "a member on an unmapped maze map walks a passage, it does not wait for the leader");
        assertEquals("t001", hop.portalName(), "the first portal that leads somewhere unmapped: t001 -> 809050004");
        assertFalse(LudiMazeSolver.graph(view).isEmpty(), "the sighting is kept in the stage's own memory");
        assertTrue(LudiMazeSolver.graph(view).get(809050000).containsValue(809050007));
    }

    @Test
    void aPartyThatCannotReachTheExitSaysWhatItMappedAndAbandonsInsideTheBudget() {
        View view = new View(List.of(LEADER, MEMBER), 809050000);
        // a maze with no way out: every map leads back into the same two maps
        view.portals.put(809050000, Map.of("t000", 809050001));
        view.portals.put(809050001, Map.of("t000", 809050000));
        List<RunMember> party = two();

        new LudiMazeSolver().next(view, party.get(0)); // learns the first map, walks off
        view.at.put(LEADER, 809050001);
        // the one untried exit out of 809050001 is taken once - that is how a dead end gets ruled out - and then there
        // is nothing left: the solver has to say so rather than fake progress
        assertInstanceOf(SolverStep.EnterPortal.class, new LudiMazeSolver().next(view, party.get(0)));
        SolverStep waiting = new LudiMazeSolver().next(view, party.get(0));
        assertInstanceOf(SolverStep.Wait.class, waiting, "nothing left to try: say why, do not fake progress");
        assertTrue(((SolverStep.Wait) waiting).why().contains("no route"), String.valueOf(waiting));

        view.now = LudiMazeSolver.GIVE_UP_MS + 1;
        SolverStep.Abandon abandon = assertInstanceOf(SolverStep.Abandon.class,
                new LudiMazeSolver().next(view, party.get(0)));
        assertTrue(abandon.why().contains(String.valueOf(GOAL)), abandon.why());
        assertTrue(abandon.why().contains("maps mapped"), abandon.why());
    }

    // ---- the exit -----------------------------------------------------------------------------------

    @Test
    void theLeaderWaitsForThePartyBeforeItTalksAndTheWaitNamesWhoIsMissing() {
        View view = new View(List.of(LEADER, MEMBER), GOAL);
        view.at.put(MEMBER, 809050003);
        view.items.put(LEADER + ":" + PASS, 30);

        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class,
                new LudiMazeSolver().next(view, member(true)));
        assertTrue(wait.why().contains("Member2"), "the wait must name who is missing: " + wait.why());

        view.at.put(MEMBER, GOAL);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class,
                new LudiMazeSolver().next(view, member(true)));
        assertEquals(EXIT, talk.npcId());
        assertEquals(PASS, talk.itemId());
        assertEquals(30, talk.count());
    }

    @Test
    void aLeaderShortOfPassesWaitsAtTheExitAndAMemberWithEnoughHandsThemOver() {
        View view = new View(List.of(LEADER, MEMBER), GOAL);
        view.items.put(LEADER + ":" + PASS, 29);
        view.items.put(MEMBER + ":" + PASS, 30);

        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class,
                new LudiMazeSolver().next(view, member(true)));
        assertTrue(wait.why().contains("29 of 30"), wait.why());

        SolverStep.DropFor drop = assertInstanceOf(SolverStep.DropFor.class,
                new LudiMazeSolver().next(view, member(false)),
                "a member holding the whole count puts it in the leader's hands at the exit");
        assertEquals(LEADER, drop.toCharacterId());
        assertEquals(PASS, drop.itemId());
    }

    @Test
    void aMemberThatNeverReachedTheExitWalksTowardsItRatherThanTowardTheLeader() {
        View view = new View(List.of(LEADER, MEMBER), GOAL);
        view.at.put(MEMBER, 809050008);
        view.portals.putAll(CLIENT_MAZE);
        view.items.put(MEMBER + ":" + PASS, 30);

        SolverStep.EnterPortal hop = assertInstanceOf(SolverStep.EnterPortal.class,
                new LudiMazeSolver().next(view, member(false)),
                "holding the passes, the way to the exit is the maze, not the leader's feet");
        assertEquals("t001", hop.portalName(), "809050008 -> the exit on t001");
    }

    @Test
    void aMemberOutsideTheMazeIsSentInAndAClearedInstanceIsDone() {
        View view = new View(List.of(LEADER, MEMBER), 809050000);
        view.at.put(MEMBER, 220000000);
        assertEquals(809050000, assertInstanceOf(SolverStep.WarpTo.class,
                new LudiMazeSolver().next(view, member(false))).mapId());

        view.cleared = true;
        assertInstanceOf(SolverStep.Done.class, new LudiMazeSolver().next(view, member(false)));
    }

    // ---- condition 1: a declared map is not a stray ------------------------------------------------

    /**
     * Fail-before: before {@code maps:} existed, the orchestrator's wrong-map branch owned a single map plus whatever the
     * solver hedged, so a member standing on {@code 809050007} was told to walk a portal or wait - fourteen members
     * dragged to the leader's map before the maze began. The row now declares its fifteen maps.
     */
    @Test
    void aMemberOnAMazeMapFarmsTheSummonersMonstersBeforeItWalksAnywhere() {
        View view = new View(List.of(LEADER, MEMBER), 809050000);
        view.summoner = true;
        view.portals.putAll(CLIENT_MAZE);

        assertEquals(LudiMazeSolver.SUM_REACTOR, assertInstanceOf(SolverStep.HitReactor.class,
                new LudiMazeSolver().next(view, member(true))).reactorId(),
                "scripts/reactor/8098000.js is where the maze's monsters come from, and they are the passes");

        view.mobs = 2;
        assertEquals(PASS, assertInstanceOf(SolverStep.Fight.class,
                new LudiMazeSolver().next(view, member(true))).itemId(),
                "a live monster first: it drops the pass");
    }

    @Test
    void aMemberOnADeclaredMapIsDrivenByTheSolverAndNotTreatedAsAStray(@TempDir Path dir) throws Exception {
        MechanicsModel.Content content = declaredMapsRow(dir);
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new java.util.Random(7L), 0, 0);
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, two());
        View view = new View(List.of(LEADER, MEMBER), 100000000);
        view.stage = run.stageData();
        view.at.put(MEMBER, 200000001); // a map the row declares as part of the stage
        view.mobs = 4;

        Map<Integer, SolverStep> steps = orchestrator.tick(run, view, 0);

        SolverStep step = steps.get(MEMBER);
        assertNotNull(step, "the member is driven");
        assertFalse(step instanceof SolverStep.EnterPortal, "a declared map is not behind: " + step);
        assertFalse(step instanceof SolverStep.WarpTo, "and it is not dragged anywhere: " + step);
        assertFalse(step instanceof SolverStep.Wait && String.valueOf(((SolverStep.Wait) step).why()).contains(
                "not on stage"), "and it is not reported as a stray: " + step);
        assertInstanceOf(SolverStep.Fight.class, step, "the row's solver drives it where it stands");
    }

    /** A one-stage PQ row whose stage declares a second map, so the strays branch sees it. */
    private static MechanicsModel.Content declaredMapsRow(Path dir) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "declared.yaml\n");
        Files.writeString(dir.resolve("declared.yaml"), """
                id: declared
                name: Declared maps mini row
                kind: PQ
                eventScript: DeclaredPQ
                entryNpc: 9000000
                recruitMap: 100000000
                exitMap: 100000000
                minPlayers: 2
                maxPlayers: 6
                minLevel: 10
                maxLevel: 255
                timeLimitMinutes: 10
                flag: slums.mechanics.declared
                sources:
                  - "not a shipped row: this test builds it to pin the declared-map rule"
                stages:
                  - stage: 1
                    map: 100000000
                    maps: [200000001]
                    name: The stage that spans two maps
                    solver: kill-collect-hand-in
                    roles:
                      - id: leader
                        who: LEADER
                        min: 1
                        max: 1
                      - id: collector
                        who: NON_LEADER
                        min: 0
                        max: party-1
                    items:
                      - id: 4000000
                        cleanup: true
                    objectives:
                      - id: hunt
                        kind: KILL_FOR_ITEM
                        role: all
                        mob: 100100
                        item: 4000000
                        count: 1
                      - id: hand
                        kind: HAND_IN
                        role: leader
                        npc: 9000000
                        item: 4000000
                        count: 1
                    clear:
                      eventProperty: 1stageclear
                      equals: "true"
                """);
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        return result.content().get(0);
    }

    // ---- the fake view ------------------------------------------------------------------------------

    private static final class View implements StageView {
        private final List<RunMember> members;
        private MechanicsModel.Stage stage = mazeStage();
        private final Map<Integer, Integer> at = new HashMap<>();
        private final Map<String, Integer> items = new HashMap<>();
        private final Map<Integer, Map<String, Integer>> portals = new HashMap<>();
        private long now;
        private boolean cleared;
        private int mobs;

        View(List<Integer> ids, int map) {
            List<RunMember> list = new ArrayList<>();
            for (int id : ids) {
                list.add(new RunMember(id, id == LEADER, true, MechanicsPersona.PERFECT));
            }
            this.members = list;
            for (int id : ids) {
                at.put(id, map);
            }
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return characterId == LEADER ? "leader" : "collector";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return items.getOrDefault(characterId + ":" + itemId, 0);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{characterId * 10, 0};
        }

        @Override
        public int mapOf(int characterId) {
            return at.getOrDefault(characterId, stage.mapId());
        }

        @Override
        public Map<String, Integer> portalsOn(int mapId) {
            return portals.getOrDefault(mapId, Map.of());
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        /** Whether the map carries the summoner reactor; a test that wants the maze walked keeps it off. */
        private boolean summoner;

        @Override
        public int reactorState(int reactorId) {
            return reactorId == LudiMazeSolver.SUM_REACTOR && summoner ? 0 : -1;
        }

        @Override
        public Optional<String> nameOf(int characterId) {
            return Optional.of("Member" + characterId);
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }

        private final Map<String, Object> memory = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public boolean eventCleared() {
            return cleared;
        }
    }
}
