package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/**
 * Guild Quest's first two stages and the protection rule every stage starts with: the lobby ({@link GqLobbySolver}),
 * the outskirts where each member earns his own Protector Rock ({@link GqEarringSolver}) and {@link GqProtection}.
 */
class GqEarlyStagesTest {

    private static final int EARRING = GqProtection.EARRING;
    private static final MechanicsModel.Content ROW = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR)).content().stream()
            .filter(c -> c.id().equals("guildquest")).findFirst().orElseThrow();

    private static final class View implements StageView {
        final int stageIndex;
        final List<RunMember> members = new ArrayList<>();
        final Map<Integer, Integer> at = new HashMap<>();
        final Map<Integer, Map<Integer, Integer>> bags = new HashMap<>();
        final Map<Integer, Boolean> worn = new HashMap<>();
        final Map<Integer, String> cannotWear = new HashMap<>();
        final Map<String, String> props = new HashMap<>();
        final Map<Integer, List<ReactorPlacement>> reactors = new HashMap<>();
        final Map<Integer, Integer> floor = new HashMap<>(); // map -> earrings on the floor
        final Map<String, Object> memory = new HashMap<>();
        long now = 1_000_000;
        boolean cleared;

        View(int stageIndex) {
            this.stageIndex = stageIndex;
        }

        View member(int id, int map, boolean isWorn) {
            members.add(new RunMember(id, id == 1, true, MechanicsPersona.PERFECT, 0, 20));
            at.put(id, map);
            worn.put(id, isWorn);
            return this;
        }

        View carries(int id, int item, int n) {
            bags.computeIfAbsent(id, k -> new HashMap<>()).put(item, n);
            return this;
        }

        RunMember m(int id) {
            return members.stream().filter(x -> x.characterId() == id).findFirst().orElseThrow();
        }

        @Override
        public MechanicsModel.Stage stage() {
            return ROW.stages().get(stageIndex - 1);
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return "all";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return bags.getOrDefault(characterId, Map.of()).getOrDefault(itemId, 0);
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
            return at.get(characterId);
        }

        @Override
        public boolean wearing(int characterId, int itemId) {
            return itemId == EARRING && worn.getOrDefault(characterId, false);
        }

        @Override
        public Optional<String> cannotWear(int characterId, int itemId) {
            return Optional.ofNullable(cannotWear.get(characterId));
        }

        @Override
        public List<ReactorPlacement> reactorsOn(int mapId) {
            return reactors.getOrDefault(mapId, List.of());
        }

        @Override
        public int floorItemsOn(int mapId, int itemId, int forCharacterId) {
            return itemId == EARRING ? floor.getOrDefault(mapId, 0) : 0;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public boolean cleared() {
            return cleared;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- lobby

    @Test
    void theLobbyWaitsOutTheStrategyTimeThenTakesTheDoorsOwnScript() {
        GqLobbySolver solver = new GqLobbySolver();
        View v = new View(1).member(1, 990000000, false);

        SolverStep none = solver.next(v, v.m(1));
        assertTrue(assertInstanceOf(SolverStep.Wait.class, none).why().contains("entryTimestamp"));

        v.props.put("entryTimestamp", String.valueOf(v.now + 120_000));
        SolverStep.Wait waiting = assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.m(1)));
        assertTrue(waiting.why().contains("120 s"), waiting.why());

        v.props.put("entryTimestamp", String.valueOf(v.now - 1));
        SolverStep.ScriptedPortal door = assertInstanceOf(SolverStep.ScriptedPortal.class, solver.next(v, v.m(1)));
        assertEquals("join00", door.portalName(), "the door whose script is guildwaitingenter");

        v.at.put(1, 990000100);
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.m(1)));
        v.cleared = true;
        assertInstanceOf(SolverStep.Done.class, solver.next(v, v.m(1)));
    }

    // ------------------------------------------------------------------------------------------------------ protection

    @Test
    void protectionIsNothingWhenWornAnEquipWhenCarriedAndAWaitNamingTheItemOtherwise() {
        View v = new View(2).member(1, 990000100, true).member(2, 990000100, false).member(3, 990000100, false);
        v.carries(2, EARRING, 1);

        assertNull(GqProtection.step(v, v.m(1)));
        SolverStep.EquipItem equip = assertInstanceOf(SolverStep.EquipItem.class, GqProtection.step(v, v.m(2)));
        assertEquals(EARRING, equip.itemId());
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, GqProtection.step(v, v.m(3)));
        assertTrue(wait.why().contains("1032033") && wait.why().contains("unverified"), wait.why());

        v.cannotWear.put(2, "level 5 is below the required 10");
        SolverStep.Abandon cannot = assertInstanceOf(SolverStep.Abandon.class, GqProtection.step(v, v.m(2)));
        assertTrue(cannot.why().contains("level 5"), cannot.why());
    }

    // ------------------------------------------------------------------------------------------------------- outskirts

    private static List<StageView.ReactorPlacement> rocks() {
        List<StageView.ReactorPlacement> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            out.add(new StageView.ReactorPlacement(500_000_200 + i, i % 2 == 0 ? 9202000 : 9202009, "", 0, 100 * i, 0));
        }
        return out;
    }

    @Test
    void eachMemberKeepsToOneRockAndNoTwoShareOne() {
        GqEarringSolver solver = new GqEarringSolver();
        View v = new View(2).member(1, 990000100, false).member(2, 990000100, false).member(3, 990000100, false);
        v.reactors.put(990000100, rocks());

        List<Integer> chosen = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (int id : new int[]{1, 2, 3}) {
                SolverStep.HitReactorByOid hit = assertInstanceOf(SolverStep.HitReactorByOid.class, solver.next(v, v.m(id)));
                if (round == 0) {
                    chosen.add(hit.objectId());
                } else {
                    assertEquals(chosen.get(id - 1), hit.objectId(), "member " + id + " stays on his rock");
                }
            }
        }
        assertEquals(3, chosen.stream().distinct().count(), "three members, three rocks: " + chosen);
    }

    @Test
    void aSpentRockIsDroppedAndAnotherTaken() {
        GqEarringSolver solver = new GqEarringSolver();
        View v = new View(2).member(1, 990000100, false);
        List<StageView.ReactorPlacement> list = rocks();
        v.reactors.put(990000100, list);
        int first = assertInstanceOf(SolverStep.HitReactorByOid.class, solver.next(v, v.m(1))).objectId();

        v.reactors.put(990000100, list.stream().map(r -> r.objectId() == first
                ? new StageView.ReactorPlacement(r.objectId(), r.reactorId(), r.name(), 5, r.x(), r.y()) : r).toList());

        int second = assertInstanceOf(SolverStep.HitReactorByOid.class, solver.next(v, v.m(1))).objectId();
        assertNotEquals(first, second, "the rock is at its last state: it is spent, hit another");
    }

    @Test
    void theDropIsLootedPutOnAndOnlyThenTheDoorIsTaken() {
        GqEarringSolver solver = new GqEarringSolver();
        View v = new View(2).member(1, 990000100, false);
        v.reactors.put(990000100, rocks());

        v.floor.put(990000100, 1);
        assertEquals(EARRING, assertInstanceOf(SolverStep.Loot.class, solver.next(v, v.m(1))).itemId());

        v.floor.clear();
        v.carries(1, EARRING, 1);
        assertEquals(EARRING, assertInstanceOf(SolverStep.EquipItem.class, solver.next(v, v.m(1))).itemId());

        v.worn.put(1, true);
        v.carries(1, EARRING, 0);
        assertEquals("next00", assertInstanceOf(SolverStep.EnterPortal.class, solver.next(v, v.m(1))).portalName());

        v.at.put(1, 990000300);
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.m(1)));
    }

    @Test
    void aMemberWhoWouldReachTheStatueRoomWithoutTheEarringEndsTheRunWithTheReason() {
        GqEarringSolver solver = new GqEarringSolver();
        View v = new View(2).member(1, 990000300, false);

        SolverStep.Abandon abandon = assertInstanceOf(SolverStep.Abandon.class, solver.next(v, v.m(1)));
        assertTrue(abandon.why().contains("30000"), abandon.why());
    }

    @Test
    void noRockLeftCostsAWaitThatNamesTheItemAndTheUnverifiedDropRow() {
        GqEarringSolver solver = new GqEarringSolver();
        View v = new View(2).member(1, 990000100, false);

        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.m(1)));

        assertTrue(wait.why().contains("1032033") && wait.why().contains("unverified"), wait.why());
    }

    @Test
    void theRowIsWiredAsPlanned() {
        assertEquals(7, ROW.stages().size());
        assertEquals(List.of("gq-lobby", "gq-earring", "gq-statues", "gq-spears", "gq-vassals", "gq-maze", "gq-boss"),
                ROW.stages().stream().map(MechanicsModel.Stage::solver).toList());
        for (MechanicsModel.Stage s : ROW.stages()) {
            assertNull(s.nextPortal(), "stage " + s.index() + ": the doors are script portals, no plain nextPortal");
            assertTrue(Orchestrator.allSolvers().containsKey(s.solver()), s.solver() + " is registered");
        }
        assertEquals("slums.mechanics.guildquest", ROW.flag());
        assertEquals(6, ROW.minPlayers());
        assertEquals(30, ROW.maxPlayers());
        assertEquals(1, ROW.composition().size());
        assertEquals(10, ROW.composition().get(0).minLevel());
        assertEquals(30, ROW.composition().get(0).maxLevel());
        assertEquals(List.of(0, 0), ROW.queue().register());
        assertEquals(List.of(1), ROW.queue().join());
        assertEquals(9040001, ROW.afterClear().rewardNpc());
        assertEquals(990001100, ROW.afterClear().rewardMap());
    }
}
