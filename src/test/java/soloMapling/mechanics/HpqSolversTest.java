package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: Henesys PQ's two stages against a fake hill, no server. Every expectation is what the scripts ask for:
 * six beds planted one seed each ({@code scripts/reactor/9108000..9108005.js}), the stage clears at exactly
 * {@code stage == "6"}, and stage 2 is Growlie taking ten rice cakes ({@code scripts/npc/1012114.js:66-76}).
 */
class HpqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;
    private static final int BUNNY = 9300061;
    private static final int CAKE = 4001101;

    private static MechanicsModel.Content hpq() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("HenesysPQ");
        assertNotNull(content, "content/hpq.yaml must be listed in content/index.txt");
        return content;
    }

    private static MechanicsModel.Stage stageAt(int index) {
        return hpq().stages().stream().filter(s -> s.index() == index).findFirst().orElseThrow();
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
    void theRowIsTheScriptsTwoStagesAndItHasItsOwnSwitch() {
        MechanicsModel.Content content = hpq();

        assertEquals(MechanicsModel.Kind.PQ, content.kind());
        assertEquals(Mechanics.HPQ_FLAG, content.flag(), "Henesys PQ must not answer to -Dslums.mechanics alone");
        assertEquals(1012112, content.entryNpc(), "Tory on 100000200 is the front door (scripts/npc/1012112.js)");
        assertEquals(100000200, content.recruitMap());
        assertEquals(910010300, content.exitMap());
        assertEquals(3, content.minPlayers(), "HenesysPQ.js:27 minPlayers = 3");
        assertEquals(6, content.maxPlayers());
        assertEquals(10, content.minLevel(), "HenesysPQ.js:29 minLevel = 10");
        assertEquals(255, content.maxLevel(), "HenesysPQ.js:30 maxLevel = 255, not upstream's cap");
        assertEquals(10, content.timeLimitMinutes(), "HenesysPQ.js:35 eventTime = 10");
        assertEquals(List.of(910010000, 910010000), content.stages().stream().map(MechanicsModel.Stage::mapId).toList(),
                "both stages are on the hill: the bunny spawns where the beds are");
        assertEquals(List.of("stage", "1stageclear"),
                content.stages().stream().map(s -> s.clear().eventProperty()).toList());
        assertEquals("6", content.stages().get(0).clear().equals(), "the beds raise stage, and six is the clear");
        assertNull(content.stages().get(1).clear().equals(),
                "1stageclear is a presence test (HenesysPQ.js:110) - safe because setup never initialises it");
        Map<String, StageSolver> solvers = Orchestrator.allSolvers();
        for (MechanicsModel.Stage stage : content.stages()) {
            assertTrue(solvers.containsKey(stage.solver()), "no solver for " + stage.solver() + " in Orchestrator");
        }
        for (MechanicsModel.Stage stage : content.stages()) {
            assertFalse(stage.mobs().contains(BUNNY), "the Moon Bunny is friendly: naming it ends the run");
        }
    }

    @Test
    void theSixBedsAreTheReactorsOwnWzPairingAndRowDataIsTheSameSix() {
        assertEquals(Map.of(9108000, 4001095, 9108001, 4001096, 9108002, 4001097, 9108003, 4001098,
                        9108004, 4001099, 9108005, 4001100), HpqBedsSolver.BEDS,
                "Map.wz/Reactor.wz/9108000..9108005: event/0 type 100 state 1, item 4001095..4001100, quantity 1");
        assertEquals(HpqBedsSolver.BEDS, HpqBedsSolver.fromRow(new View(stageAt(1), two())),
                "the row must carry the same six pairs the WZ does, and it is what the solver plants");
        for (Map.Entry<Integer, Integer> bed : HpqBedsSolver.BEDS.entrySet()) {
            assertEquals(bed.getValue(), HpqBedsSolver.seedFor(bed.getKey()));
        }
        assertEquals(0, HpqBedsSolver.seedFor(9101000), "the fullmoon reactor takes nothing");
    }

    // ---- stage 1: six beds --------------------------------------------------------------------------

    @Test
    void aSeedIsPlantedOnItsOwnBedAndNowhereElse() {
        View view = new View(stageAt(1), two());
        view.props.put("stage", "0");
        view.mobs = 3;

        view.items.put(MEMBER + ":" + 4001097, 1);
        SolverStep.PlaceItemOn place = assertInstanceOf(SolverStep.PlaceItemOn.class,
                new HpqBedsSolver().next(view, member(false)));
        assertEquals(9108002, place.reactorId(), "4001097 belongs to moonflower3 (9108002) and no other bed");
        assertEquals(4001097, place.itemId());
        assertEquals(1, place.count(), "the reactor wants exactly one seed inside its area");

        view.items.put(LEADER + ":" + 4001100, 1);
        assertEquals(9108005, assertInstanceOf(SolverStep.PlaceItemOn.class,
                new HpqBedsSolver().next(view, member(true))).reactorId());
    }

    @Test
    void aBedAlreadyPlantedIsNeverFedAgain() {
        View view = new View(stageAt(1), two());
        view.props.put("stage", "1");
        view.beds.put(9108002, 1); // moonflower3 blooms already
        view.mobs = 2;
        view.items.put(MEMBER + ":" + 4001097, 1); // a duplicate seed for the bed that is done

        assertEquals(9108000, HpqBedsSolver.nextEmptyBed(view), "the first empty bed is the next one to fill");
        SolverStep step = new HpqBedsSolver().next(view, member(false));
        assertFalse(step instanceof SolverStep.PlaceItemOn,
                "a second activation would raise stage past six and the run could never clear");

        view.items.put(MEMBER + ":" + 4001095, 0);
        view.floor.put(4001095 + ":" + MEMBER, 1);
        assertEquals(4001095, assertInstanceOf(SolverStep.Loot.class, step = new HpqBedsSolver().next(view, member(false)))
                .itemId(), "a seed of the next empty bed on the floor is taken");

        view.floor.clear();
        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class,
                new HpqBedsSolver().next(view, member(false)));
        assertEquals(List.of(), fight.mobIds(), "any of the hill's leaves may drop it");
        assertEquals(4001095, fight.itemId());
        assertEquals(1, fight.untilItemCount());
    }

    @Test
    void sixPlantedBedsAreTheClearAndTheSolverStopsDropping() {
        View view = new View(stageAt(1), two());
        view.props.put("stage", "6");
        for (int bed : HpqBedsSolver.BEDS.keySet()) {
            view.beds.put(bed, 1);
        }
        assertEquals(6, HpqBedsSolver.planted(view));
        assertEquals(0, HpqBedsSolver.nextEmptyBed(view));
        assertTrue(view.cleared(), "clear: stage == \"6\"");
        assertInstanceOf(SolverStep.Done.class, new HpqBedsSolver().next(view, member(true)));
    }

    @Test
    void aMemberOffTheHillWalksBackBeforeAnythingElse() {
        View view = new View(stageAt(1), two());
        view.map.put(MEMBER, 100000200);
        SolverStep.WarpTo back = assertInstanceOf(SolverStep.WarpTo.class, new HpqBedsSolver().next(view, member(false)));
        assertEquals(910010000, back.mapId());
    }

    @Test
    void noStepOfTheBedsStageEverNamesTheMoonBunny() {
        View view = new View(stageAt(1), two());
        view.mobs = 5;
        for (int i = 0; i < 40; i++) {
            view.props.put("stage", String.valueOf(Math.min(6, i / 7)));
            view.beds.clear();
            for (int bed = 9108000; bed < 9108000 + Math.min(6, i / 7); bed++) {
                view.beds.put(bed, 1);
            }
            view.items.put(LEADER + ":" + 4001095, i % 2);
            SolverStep step = new HpqBedsSolver().next(view, member(true));
            if (step instanceof SolverStep.Fight fight) {
                assertTrue(fight.mobId() != BUNNY, "killing the bunny ends the run (HenesysPQ.js:251-255)");
            }
            if (step instanceof SolverStep.TalkNpc talk) {
                assertTrue(talk.npcId() != BUNNY);
            }
        }
    }

    @Test
    void twoMembersHoldingTheSameSeedCannotBothDropOnTheSameBed() {
        View view = new View(stageAt(1), two());
        view.props.put("stage", "0");
        view.items.put(LEADER + ":" + 4001095, 1);
        view.items.put(MEMBER + ":" + 4001095, 1); // both hold the same seed: the bed is one

        assertEquals(9108000, assertInstanceOf(SolverStep.PlaceItemOn.class,
                new HpqBedsSolver().next(view, member(true))).reactorId(), "the first member takes the bed");
        assertFalse(new HpqBedsSolver().next(view, member(false)) instanceof SolverStep.PlaceItemOn,
                "a second drop inside the 5000 ms activation window would activate twice and skip stage == \"6\"");

        view.now = HpqBedsSolver.CLAIM_MS + 1; // the claim runs out: a bed nobody planted is free again
        assertEquals(9108000, assertInstanceOf(SolverStep.PlaceItemOn.class,
                new HpqBedsSolver().next(view, member(false))).reactorId());
    }

    @Test
    void nobodyFarmsMoreOfASeedThePartyAlreadyHolds() {
        View view = new View(stageAt(1), two());
        view.props.put("stage", "0");
        view.mobs = 5;
        view.items.put(LEADER + ":" + 4001095, 1); // the leader has it and is walking to the bed
        view.items.put(MEMBER + ":" + 4001095, 1); // and the bed is claimed by a bot that already dropped

        SolverStep step = new HpqBedsSolver().next(view, member(false));
        assertTrue(step instanceof SolverStep.Wait || step instanceof SolverStep.PlaceItemOn,
                "not a fight: killing the hill's leaves for a seed the party holds wastes the map's monsters");
    }

    // ---- stage 2: ten rice cakes ----------------------------------------------------------------

    @Test
    void tenCakesAreHandedToGrowlieAndNineAreNot() {
        View view = new View(stageAt(2), two());
        view.props.put("stage", "6");
        view.mobs = 0;
        view.items.put(LEADER + ":" + CAKE, 9);
        SolverStep nine = new KillCollectHandInSolver().next(view, member(true));
        assertFalse(nine instanceof SolverStep.TalkNpc, "1012114 checks haveItem(4001101, 10): nine is not ten");

        view.items.put(LEADER + ":" + CAKE, 10);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class,
                new KillCollectHandInSolver().next(view, member(true)));
        assertEquals(1012114, talk.npcId(), "Growlie stands on the hill");
        assertEquals(CAKE, talk.itemId());
        assertEquals(10, talk.count());
    }

    @Test
    void theCakesStageClearsOnThePresenceOfThePropertyNotItsValue() {
        View view = new View(stageAt(2), two());
        view.props.put("stage", "6");
        assertFalse(view.cleared(), "1stageclear is missing: stage 2 is still running");
        view.props.put("1stageclear", "true");
        assertTrue(view.cleared());
    }

    // ---- a fake hill ------------------------------------------------------------------------------

    private static final class View implements StageView {

        private final MechanicsModel.Stage stage;
        private final List<RunMember> members;
        private final Map<String, String> props = new HashMap<>();
        private final Map<String, Integer> items = new HashMap<>();
        private final Map<Integer, Integer> map = new HashMap<>();
        private final Map<String, Integer> floor = new HashMap<>();
        private final Map<Integer, Integer> beds = new LinkedHashMap<>();
        private final Map<String, Object> memory = new HashMap<>();
        private int mobs;
        private int[] npc;
        private long now;

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
                if (itemId > 0 && Integer.parseInt(k[0]) != itemId) {
                    continue;
                }
                if (k[1].equals(String.valueOf(forCharacterId))) {
                    sum += e.getValue();
                }
            }
            return sum;
        }

        @Override
        public int reactorState(int reactorId) {
            return beds.getOrDefault(reactorId, 0);
        }

        @Override
        public Rectangle area(int index) {
            return null;
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return npc == null ? Optional.empty() : Optional.of(npc);
        }
    }
}
