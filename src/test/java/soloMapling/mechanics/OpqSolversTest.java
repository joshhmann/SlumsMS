package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: the five Orbis PQ stages against a fake room, no server. Every expectation here is what
 * {@code scripts/npc/2013001.js} asks for on that map: the altar takes one drop of twenty cloud pieces
 * ({@code scripts/reactor/2006000.js}), the storage room clears when no monster and no item is left
 * ({@code countMonsters() == 0 && countItems() == 0}), the lounge takes forty pieces, and the five levers must end
 * matching the five characters the script wrote into {@code stage6_c}.
 */
class OpqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content opq() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("OrbisPQ");
        assertNotNull(content, "content/opq.yaml must be listed in content/index.txt");
        return content;
    }

    private static MechanicsModel.Stage stageAt(int mapId) {
        return opq().stages().stream().filter(s -> s.mapId() == mapId).findFirst().orElseThrow();
    }

    @Test
    void theRowIsTheFiveStagesOurScriptsCanClearAndItHasItsOwnSwitch() {
        MechanicsModel.Content content = opq();

        assertEquals(MechanicsModel.Kind.PQ, content.kind());
        assertEquals(Mechanics.OPQ_FLAG, content.flag(), "Orbis PQ must not answer to -Dslums.mechanics alone");
        assertEquals(List.of(920010000, 920010200, 920010300, 920010601, 920010700),
                content.stages().stream().map(MechanicsModel.Stage::mapId).toList(),
                "entrance, walkway, storage, a lounge room, the levers: script stages 0, 1, 2, 5, 6");
        assertEquals(List.of("statusStg0", "statusStg1", "statusStg2", "statusStg5", "statusStg6"),
                content.stages().stream().map(s -> s.clear().eventProperty()).toList());
        Map<String, StageSolver> solvers = Orchestrator.allSolvers();
        for (MechanicsModel.Stage stage : content.stages()) {
            assertTrue(solvers.containsKey(stage.solver()), "no solver for " + stage.solver() + " in Orchestrator");
        }
    }

    // ---- stage 0, the entrance: twenty cloud pieces on the altar, then Eak -------------------------------

    @Test
    void aBotWithNothingInItsBagHitsTheClouds() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        view.map.put(MEMBER, 920010000);

        SolverStep.HitReactor hit = assertInstanceOf(SolverStep.HitReactor.class,
                new OpqCloudsSolver().next(view, member(false)));
        assertEquals(2002001, hit.reactorId());
    }

    @Test
    void theLeaderPutsTheTwentyPiecesOnTheAltarAsOneDrop() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        view.items.put(LEADER + ":4001063", 20);

        SolverStep.PlaceItem place = assertInstanceOf(SolverStep.PlaceItem.class,
                new OpqCloudsSolver().next(view, member(true)));
        assertEquals(4001063, place.itemId());
        assertEquals(20, place.count(), "roles/reactor/2006000.js only fires for a drop of exactly twenty");
        assertEquals("2006000", place.reactor());
    }

    @Test
    void eakIsAskedOnlyOnceTheAltarHasBeenUsed() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        assertInstanceOf(SolverStep.HitReactor.class, new OpqCloudsSolver().next(view, member(true)),
                "before the altar is hit Eak does not stand on the map at all, so the leader farms clouds too");

        view.reactors.put("2006000", 1);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqCloudsSolver().next(view, member(true)));
        assertEquals(2013001, talk.npcId());
    }

    @Test
    void aHelperDropsItsPiecesForTheLeader() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        view.map.put(MEMBER, 920010000);
        view.items.put(MEMBER + ":4001063", 7);

        SolverStep.DropFor drop = assertInstanceOf(SolverStep.DropFor.class,
                new OpqCloudsSolver().next(view, member(false)));
        assertEquals(4001063, drop.itemId());
        assertEquals(7, drop.count());
        assertEquals(LEADER, drop.toCharacterId());
    }

    // ---- stage 2, the storage room: nothing left on the floor -------------------------------------------

    @Test
    void theStorageRoomIsSweptOfMonstersThenOfEveryDropThenEak() {
        OpqSweepSolver solver = new OpqSweepSolver();
        View view = new View(stageAt(920010300), members());
        view.map.put(LEADER, 920010300);

        view.mobs = 3;
        assertEquals(0, assertInstanceOf(SolverStep.Fight.class, solver.next(view, member(true))).itemId(),
                "the room is cleared by fighting, no item is being farmed");

        view.mobs = 0;
        view.floor.put("0:" + LEADER, 4); // mesos and drops both count for countItems()
        assertEquals(0, assertInstanceOf(SolverStep.Loot.class, solver.next(view, member(true))).itemId(),
                "Loot(0) is the whole floor: countItems() counts mesos too");

        view.floor.clear();
        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                solver.next(view, member(true))).npcId());
    }

    // ---- stage 5, the lounge room: forty pieces, Eak is in the lounge -----------------------------------

    @Test
    void theLeaderWalksBackToTheLoungeWithFortyPieces() {
        OpqSubRoomSolver solver = new OpqSubRoomSolver();
        View view = new View(stageAt(920010601), members());
        view.map.put(LEADER, 920010601);
        view.items.put(LEADER + ":4001052", 40);

        SolverStep.WarpTo warp = assertInstanceOf(SolverStep.WarpTo.class, solver.next(view, member(true)));
        assertEquals(OpqSubRoomSolver.EAK_MAP, warp.mapId(), "Eak stands in the lounge, not in a side room");

        view.map.put(LEADER, OpqSubRoomSolver.EAK_MAP);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class, solver.next(view, member(true)));
        assertEquals(40, talk.count());
        assertEquals(4001052, talk.itemId());
    }

    @Test
    void aHelperFarmsTheSideRoomAndNeverFollowsTheLeaderOut() {
        OpqSubRoomSolver solver = new OpqSubRoomSolver();
        View view = new View(stageAt(920010601), members());
        view.map.put(LEADER, OpqSubRoomSolver.EAK_MAP); // the leader has left with his forty
        view.map.put(MEMBER, 920010601);
        view.items.put(MEMBER + ":4001052", 12);
        view.mobs = 4;

        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member(false)),
                "with Eak elsewhere the pieces stay in the bag: nobody hands them over across maps");
        assertEquals(4001052, fight.itemId());

        view.items.put(MEMBER + ":4001052", 0);
        view.mobs = 0;
        view.floor.put("4001052:" + MEMBER, 5);
        assertEquals(4001052, assertInstanceOf(SolverStep.Loot.class, solver.next(view, member(false))).itemId());
    }

    // ---- stage 6, the levers: only the levers that differ from stage6_c --------------------------------

    @Test
    void theFirstTalkIsWhatPicksTheCombination() {
        View view = new View(stageAt(920010700), members());
        view.map.put(LEADER, 920010700);

        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqLeversSolver().next(view, member(true))).npcId());
        assertInstanceOf(SolverStep.Wait.class, new OpqLeversSolver().next(view, member(false)),
                "everyone else waits for Eak to write stage6_c");
    }

    @Test
    void onlyTheLeversThatDisagreeWithTheCombinationArePulled() {
        // one bot in the party owns all five levers, so the assertion names the lever and not the share-out
        View view = new View(stageAt(920010700), List.of(member(true)));
        view.map.put(LEADER, 920010700);
        view.props.put("stage6_c", "01010"); // levers 2 and 4 must be on
        for (int lever = 1; lever <= 5; lever++) {
            view.reactors.put(String.valueOf(lever), 0);
        }
        view.reactors.put("2", 1); // already right

        SolverStep.HitReactorNamed hit = assertInstanceOf(SolverStep.HitReactorNamed.class,
                new OpqLeversSolver().next(view, member(true)));
        assertEquals("4", hit.reactorName(), "lever 4 is wanted but off; lever 2 is already on and must not be toggled");

        view.reactors.put("4", 1);
        view.reactors.put("1", 1); // somebody pushed a wrong one
        assertEquals("1", assertInstanceOf(SolverStep.HitReactorNamed.class,
                new OpqLeversSolver().next(view, member(true))).reactorName());
    }

    @Test
    void theFiveLeversAreSharedOutOverTheBotsSoNoTwoToggleTheSameOne() {
        View view = new View(stageAt(920010700), List.of(
                new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT)));

        assertEquals(List.of(1, 3, 5), OpqLeversSolver.mine(view, LEADER));
        assertEquals(List.of(2, 4), OpqLeversSolver.mine(view, MEMBER));
    }

    @Test
    void onceTheCombinationMatchesTheLeaderAsksEakForTheClear() {
        View view = new View(stageAt(920010700), members());
        view.map.put(LEADER, 920010700);
        view.props.put("stage6_c", "10010");
        view.reactors.put("1", 1);
        view.reactors.put("4", 1);

        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqLeversSolver().next(view, member(true))).npcId());
        assertInstanceOf(SolverStep.Wait.class, new OpqLeversSolver().next(view, member(false)));
    }

    @Test
    void aClearedStageStopsTheSolver() {
        View view = new View(stageAt(920010700), members());
        view.props.put("statusStg6", "1");
        assertInstanceOf(SolverStep.Done.class, new OpqLeversSolver().next(view, member(true)));
    }

    // ---- the fake room ---------------------------------------------------------------------------------

    private static List<RunMember> members() {
        return List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT));
    }

    private static RunMember member(boolean leader) {
        return leader ? new RunMember(LEADER, true, true, MechanicsPersona.PERFECT)
                : new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT);
    }

    private static final class View implements StageView {

        private final MechanicsModel.Stage stage;
        private final List<RunMember> members;
        private final Map<String, String> props = new HashMap<>();
        private final Map<String, Integer> items = new HashMap<>();
        private final Map<Integer, Integer> map = new HashMap<>();
        private final Map<String, Integer> floor = new HashMap<>();
        private final Map<String, Integer> reactors = new HashMap<>();
        private final Map<String, Object> memory = new HashMap<>();
        private int mobs;
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
            return characterId == LEADER ? "leader" : "sweeper";
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
            return new int[]{0, 0};
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
        public int reactorState(String name) {
            return reactors.getOrDefault(name, -1);
        }
    }

    /** Kept so a future stage data change that drops a stage is caught by name, not by index. */
    static List<Integer> stageMaps() {
        List<Integer> maps = new ArrayList<>();
        for (MechanicsModel.Stage stage : opq().stages()) {
            maps.add(stage.mapId());
        }
        return maps;
    }

    @Test
    void everyStageHasARoleForEveryMemberAndAClearPropertyOfItsOwn() {
        for (MechanicsModel.Stage stage : opq().stages()) {
            assertFalse(stage.roles().isEmpty(), "stage " + stage.index() + " has no roles");
            assertNotNull(stage.clear(), "stage " + stage.index() + " has no clear");
            assertEquals("1", stage.clear().equals(), "every Orbis clear is statusStgN == 1");
        }
    }
}
