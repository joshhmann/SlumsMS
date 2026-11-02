package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: Ludi PQ's nine stages against a fake room, no server. Every expectation is what
 * {@code scripts/npc/2040036.js-2040044.js} ask for on that map: 25, 15, 32, 6, 24, -, 3, five of nine boxes and one
 * key, each with its own {@code Nstageclear == "true"} - and stage 6, whose only writer is the door's own script.
 */
class LpqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;
    private static final int PASS = 4001022;
    private static final int KEY = 4001023;

    private static MechanicsModel.Content lpq() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("LudiPQ");
        assertNotNull(content, "content/lpq.yaml must be listed in content/index.txt");
        return content;
    }

    private static MechanicsModel.Stage stageAt(int mapId) {
        return lpq().stages().stream().filter(s -> s.mapId() == mapId).findFirst().orElseThrow();
    }

    @Test
    void theRowIsTheScriptsNineStagesAndItHasItsOwnSwitch() {
        MechanicsModel.Content content = lpq();

        assertEquals(MechanicsModel.Kind.PQ, content.kind());
        assertEquals(Mechanics.LPQ_FLAG, content.flag(), "Ludi PQ must not answer to -Dslums.mechanics alone");
        assertEquals(2040034, content.entryNpc(), "the Red Sign on 221024500 is the front door");
        assertEquals(221024500, content.recruitMap());
        assertEquals(5, content.minPlayers(), "LudiPQ.js:26-27");
        assertEquals(6, content.maxPlayers());
        assertEquals(35, content.minLevel(), "LudiPQ.js:28-29 - upstream's 50 is a foreign constant");
        assertEquals(255, content.maxLevel());
        assertEquals(45, content.timeLimitMinutes(), "LudiPQ.js:35");
        assertEquals(List.of(922010100, 922010200, 922010300, 922010400, 922010500, 922010600, 922010700,
                        922010800, 922010900),
                content.stages().stream().map(MechanicsModel.Stage::mapId).toList(), "the tower, stage 1 to 9");
        assertEquals(List.of("1stageclear", "2stageclear", "3stageclear", "4stageclear", "5stageclear", "6stageclear",
                        "7stageclear", "8stageclear", "9stageclear"),
                content.stages().stream().map(s -> s.clear().eventProperty()).toList());
        for (MechanicsModel.Stage stage : content.stages()) {
            assertEquals("true", stage.clear().equals(), "stage " + stage.index() + " clears on <N>stageclear == true");
        }
        Map<String, StageSolver> solvers = Orchestrator.allSolvers();
        for (MechanicsModel.Stage stage : content.stages()) {
            assertTrue(solvers.containsKey(stage.solver()), "no solver for " + stage.solver() + " in Orchestrator");
        }
    }

    // ---- the pass stages: 25, 15, 32, 6, 24, 3 ------------------------------------------------------

    @Test
    void theLeaderHandsThePassesInOnlyWhenItHoldsThemAll() {
        LpqFarmSolver solver = new LpqFarmSolver();
        View view = rooms(stageAt(922010100), two());
        view.mobs = 0;
        view.items.put(LEADER + ":" + PASS, 24);
        assertFalse(solver.next(view, member(true)) instanceof SolverStep.TalkNpc,
                "2040036 checks haveItem(4001022, 25): 24 clears nothing and the run must keep farming");

        view.items.put(LEADER + ":" + PASS, 25);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class, solver.next(view, member(true)));
        assertEquals(2040036, talk.npcId());
        assertEquals(PASS, talk.itemId());
        assertEquals(25, talk.count());
    }

    @Test
    void aStageWithNoMonstersIsClearedByItsBoxes() {
        // 922010200 has no monster at all: eleven boxes (2202003) and a trap hold the fifteen passes
        View view = new View(stageAt(922010200), two());
        view.mobs = 0;
        SolverStep.HitReactor hit = assertInstanceOf(SolverStep.HitReactor.class,
                new LpqFarmSolver().next(view, member(true)));
        assertEquals(2202003, hit.reactorId());
        assertEquals(2202003, LpqFarmSolver.boxReactor(stageAt(922010200)));

        view.items.put(LEADER + ":" + PASS, 15);
        assertEquals(2040037, assertInstanceOf(SolverStep.TalkNpc.class,
                new LpqFarmSolver().next(view, member(true))).npcId());
    }

    @Test
    void aStageWithMonstersAndBoxesFightsFirstAndBoxesAfter() {
        LpqFarmSolver solver = new LpqFarmSolver();
        View view = new View(stageAt(922010300), two());
        view.mobs = 4;
        assertEquals(PASS, assertInstanceOf(SolverStep.Fight.class, solver.next(view, member(true))).itemId());

        view.mobs = 0;
        assertEquals(2201001, assertInstanceOf(SolverStep.HitReactor.class,
                solver.next(view, member(true))).reactorId(), "eight monsters and eight boxes, and the boxes come after");
    }

    @Test
    void theThreeBossesStageAsksForThreePasses() {
        View view = new View(stageAt(922010700), two());
        view.mobs = 0;
        view.items.put(LEADER + ":" + PASS, 2);
        assertInstanceOf(SolverStep.HitReactor.class, new LpqFarmSolver().next(view, member(true)),
                "two passes is not three");
        view.items.put(LEADER + ":" + PASS, 3);
        assertEquals(3, assertInstanceOf(SolverStep.TalkNpc.class,
                new LpqFarmSolver().next(view, member(true))).count());
    }

    @Test
    void theKeyStageHandsInTheKeyNotAPass() {
        View view = new View(stageAt(922010900), two());
        view.mobs = 0;
        view.items.put(LEADER + ":" + PASS, 3);
        assertInstanceOf(SolverStep.HitReactor.class, new LpqFarmSolver().next(view, member(true)),
                "passes do not open 2040044: the key does");

        view.items.put(LEADER + ":" + KEY, 1);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class,
                new LpqFarmSolver().next(view, member(true)));
        assertEquals(2040044, talk.npcId());
        assertEquals(KEY, talk.itemId());
    }

    // ---- the room stages: 6 in five rooms, 24 in six ------------------------------------------------

    @Test
    void aRoomStageSendsEachMemberIntoItsOwnRoomAndBackOutWithThePasses() {
        LpqFarmSolver solver = new LpqFarmSolver();
        View view = new View(stageAt(922010400), two());
        view.items.put(LEADER + ":" + PASS, 6); // the leader is done; the helper still needs its share

        assertEquals(Set.of(922010401, 922010402, 922010403, 922010404, 922010405), solver.extraMaps(view),
                "922010400's own portals in01..in05 lead to the five rooms");
        assertEquals(922010401, LpqFarmSolver.roomFor(view, LEADER), "slot 0 takes the first room");
        assertEquals(922010402, LpqFarmSolver.roomFor(view, MEMBER), "slot 1 takes the second");

        SolverStep.WarpTo into = assertInstanceOf(SolverStep.WarpTo.class, solver.next(view, member(false)));
        assertEquals(922010402, into.mapId(), "a member short of passes walks into its room");

        view.map.put(MEMBER, 922010402);
        view.items.put(MEMBER + ":" + PASS, 2);
        SolverStep.WarpTo back = assertInstanceOf(SolverStep.WarpTo.class, solver.next(view, member(false)));
        assertEquals(922010400, back.mapId(), "with passes in hand it comes back to hand them over");
    }

    @Test
    void theSixRoomStageHasSixRooms() {
        View view = new View(stageAt(922010500), two());
        assertEquals(6, new LpqFarmSolver().extraMaps(view).size());
    }

    // ---- stage 6: a door, and its own script is the clear ------------------------------------------

    @Test
    void stageSixIsClearedByTakingItsDoorNotByWaiting() {
        View view = new View(stageAt(922010600), two());
        SolverStep.ScriptedPortal door = assertInstanceOf(SolverStep.ScriptedPortal.class,
                new LpqFarmSolver().next(view, member(true)));
        assertEquals("next00", door.portalName());
        assertTrue(stageAt(922010600).objectives().stream().noneMatch(o -> o.kind() == MechanicsModel.ObjectiveKind.HAND_IN),
                "no NPC sets 6stageclear: scripts/portal/lpq5.js:33-41 does, on the way through");
    }

    // ---- stage 8: five of nine boxes ----------------------------------------------------------------

    @Test
    void theNineBoxesHandOutOnlyTheFiveTheCombinationWants() {
        View view = boxes(stageAt(922010800), five());
        view.props.put("statusStg8", "0");
        view.props.put(LpqComboSolver.COMBO_PROPERTY, "101010000");

        assertEquals(List.of(), LpqComboSolver.want(view), "the NPC wants five boxes, not three");
        view.props.put(LpqComboSolver.COMBO_PROPERTY, "101011001");
        assertEquals(List.of(0, 2, 4, 5, 8), LpqComboSolver.want(view));
        view.props.put(LpqComboSolver.COMBO_PROPERTY, "1010100010");
        assertEquals(List.of(), LpqComboSolver.want(view), "a nine-character answer or nothing");

        view.props.put(LpqComboSolver.COMBO_PROPERTY, "111100000");
        assertEquals(List.of(), LpqComboSolver.want(view), "four ones is not the five the NPC wants");
    }

    @Test
    void theBoxesAreaIsWalkedToAndTheCheckOnlyComesWhenAllFiveStandRight() {
        View view = boxes(stageAt(922010800), five());
        view.props.put("statusStg8", "0");
        view.props.put("stage8combo", "101011001");

        SolverStep.MoveTo move = assertInstanceOf(SolverStep.MoveTo.class,
                new LpqComboSolver().next(view, member(true)));
        assertTrue(view.area(0).contains(move.x(), move.y()), "box 1 of the combination is the first area");

        // the leader stands right but the other four do not: no check yet
        view.where.put(LEADER, new int[]{comboX(0), comboY(0)});
        assertFalse(LpqComboSolver.standingRight(view));
        assertFalse(new LpqComboSolver().next(view, member(true)) instanceof SolverStep.TalkNpc);

        // now all five
        int[] ids = {LEADER, 2, 3, 4, 5};
        for (int i = 0; i < ids.length; i++) {
            view.where.put(ids[i], new int[]{comboX(0), comboY(0)});
        }
        assertTrue(LpqComboSolver.standingRight(view), "five standing on boxes the combination wants");
        assertEquals(2040043, assertInstanceOf(SolverStep.TalkNpc.class,
                new LpqComboSolver().next(view, member(true))).npcId());
    }

    @Test
    void aSixthMemberStandsClearBecauseTheNpcCountsExactlyFive() {
        View view = boxes(stageAt(922010800), six());
        view.props.put("statusStg8", "0");
        view.props.put(LpqComboSolver.COMBO_PROPERTY, "101011001");
        view.npc = new int[]{-500, -700};

        SolverStep.MoveTo move = assertInstanceOf(SolverStep.MoveTo.class,
                new LpqComboSolver().next(view, new RunMember(6, false, true, MechanicsPersona.PERFECT)));
        assertEquals(-500, move.x());
        assertEquals(-700, move.y(), "off the boxes, beside the NPC");
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private static int comboX(int area) {
        return (int) BOXES.get(area).getCenterX();
    }

    private static int comboY(int area) {
        return (int) BOXES.get(area).getCenterY();
    }

    /** Nine boxes in a row: the map's own area nodes, one per box. */
    private static final List<Rectangle> BOXES = buildBoxes();

    private static List<Rectangle> buildBoxes() {
        List<Rectangle> out = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            out.add(new Rectangle(-600 + i * 130, -760, 90, 80));
        }
        return out;
    }

    private static List<RunMember> two() {
        return List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT));
    }

    private static List<RunMember> five() {
        List<RunMember> out = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            out.add(new RunMember(i, i == 1, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    private static List<RunMember> six() {
        List<RunMember> out = new ArrayList<>(five());
        out.add(new RunMember(6, false, true, MechanicsPersona.PERFECT));
        return out;
    }

    private static RunMember member(boolean leader) {
        return leader ? new RunMember(LEADER, true, true, MechanicsPersona.PERFECT)
                : new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT);
    }

    private static View rooms(MechanicsModel.Stage stage, List<RunMember> members) {
        return new View(stage, members);
    }

    private static View boxes(MechanicsModel.Stage stage, List<RunMember> members) {
        View view = new View(stage, members);
        view.areas.addAll(BOXES);
        return view;
    }

    private static final class View implements StageView {

        private final MechanicsModel.Stage stage;
        private final List<RunMember> members;
        private final Map<String, String> props = new HashMap<>();
        private final Map<String, Integer> items = new HashMap<>();
        private final Map<Integer, Integer> map = new HashMap<>();
        private final Map<String, Integer> floor = new HashMap<>();
        private final List<Rectangle> areas = new ArrayList<>();
        private final Map<Integer, int[]> where = new HashMap<>();
        private final Map<String, Object> memory = new HashMap<>();
        private int mobs;
        private int[] npc;
        long now;

        View(MechanicsModel.Stage stage, List<RunMember> members) {
            this.stage = stage;
            this.members = members;
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
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
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return where.getOrDefault(characterId, new int[]{0, 0});
        }

        @Override
        public int mapOf(int characterId) {
            return map.getOrDefault(characterId, stage.mapId());
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        @Override
        public int floorItems(int itemId, int forCharacterId) {
            int sum = 0;
            for (Map.Entry<String, Integer> e : floor.entrySet()) {
                String[] k = e.getKey().split(":");
                int item = Integer.parseInt(k[0]);
                if (itemId > 0 && item != itemId) {
                    continue;
                }
                if (k[1].equals(String.valueOf(forCharacterId))) {
                    sum += e.getValue();
                }
            }
            return sum;
        }

        @Override
        public Rectangle area(int index) {
            return index >= 0 && index < areas.size() ? areas.get(index) : null;
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return npc == null ? Optional.empty() : Optional.of(npc);
        }
    }
}
