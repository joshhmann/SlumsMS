package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: Treasure PQ, the shortest row on the branch. What it proves is that the row is the script's own shape -
 * one map, the chest {@code 6741001} as the only source of a monster ({@code scripts/reactor/6741001.js}), and
 * {@code eim.clearPQ()} as the only clear ({@code scripts/event/TreasurePQ.js:232-237}) - and that a row without the
 * chest step can never clear, because nothing on the map exists to kill.
 */
class TreasurePqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content row() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("TreasurePQ");
        assertNotNull(content, "content/treasurepq.yaml must be listed in content/index.txt");
        return content;
    }

    private static RunMember member(boolean leader) {
        return new RunMember(leader ? LEADER : MEMBER, leader, true, MechanicsPersona.PERFECT);
    }

    private static TreasureChestSolver solver() {
        return new TreasureChestSolver();
    }

    @Test
    void theRowIsTheScriptsGatesAndOneMapThatClearsOnTheInstancesFlag() {
        MechanicsModel.Content c = row();
        assertEquals("TreasurePQ", c.eventScript());
        assertEquals(4, c.minPlayers(), "TreasurePQ.js:27");
        assertEquals(6, c.maxPlayers());
        assertEquals(140, c.minLevel(), "TreasurePQ.js:28: the highest band of any row on this branch");
        assertEquals(255, c.maxLevel());
        assertEquals(9220018, c.entryNpc(), "scripts/npc/9220018.js is MV's Lair's front door");
        assertEquals(674030100, c.recruitMap(), "TreasurePQ.js:31");
        assertEquals(674030100, c.exitMap(), "TreasurePQ.js:30");
        assertEquals(Mechanics.TREASURE_FLAG, c.flag(), "one switch: -Dslums.mechanics.treasurepq");

        MechanicsModel.Stage stage = c.stages().get(0);
        assertEquals(674030000, stage.mapId(), "one map, the entry map itself (TreasurePQ.js:29)");
        assertEquals("treasure-chest", stage.solver());
        assertTrue(stage.clear().onInstance(), "clearPQ (:232-237) writes no property");
        assertEquals(1, c.stages().size(), "this event has one stage; the bonus map is a warp, not a stage");
    }

    @Test
    void theChestIsHitBeforeAnythingExistsToFightAndItIsTheOnlyThingHit() {
        View v = new View(row(), 674030000);
        // no monster, chest present: the summon
        assertEquals(TreasureChestSolver.CHEST, assertInstanceOf(SolverStep.HitReactor.class,
                solver().next(v, member(true))).reactorId(),
                "scripts/reactor/6741001.js act() spawns 9400589 and nothing else does");
        // a monster out: it is fought, and the chest is left alone
        v.mobs = 1;
        assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(true)));
        // cleared: the run is done even though the map still holds a chest
        v.cleared = true;
        assertInstanceOf(SolverStep.Done.class, solver().next(v, member(true)));
    }

    @Test
    void aRowWithoutTheChestNeverClearsBecauseTheMapHasNoOtherMonster() {
        View v = new View(row(), 674030000);
        v.chestPresent = false; // the same row with only "kill what is out": nothing is ever out
        SolverStep step = solver().next(v, member(true));
        assertInstanceOf(SolverStep.Wait.class, step, "the honest state of a map with no chest: " + step);
        assertTrue(((SolverStep.Wait) step).why().contains("treasure chest"), ((SolverStep.Wait) step).why());
        v.mobs = 0;
        for (int tick = 0; tick < 50; tick++) {
            assertInstanceOf(SolverStep.Wait.class, solver().next(v, member(true)),
                    "and it never becomes a clear, however long it waits");
        }
    }

    @Test
    void everyMemberPlaysTheSameMapAndAForeignOneIsSentBack() {
        assertEquals(TreasureChestSolver.MAP, assertInstanceOf(SolverStep.WarpTo.class,
                solver().next(new View(row(), 674030300), member(false))).mapId(),
                "674030300 is the bonus map: a member still there when a run starts is brought back");
        View v = new View(row(), 674030000);
        v.mobs = 1;
        assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(true)));
        assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(false)),
                "no roles to split: this event has no hand-in and no NPC inside the map");
    }

    private static final class View implements StageView {
        private final MechanicsModel.Content row;
        private int map;
        private int mobs;
        private boolean cleared;
        private boolean chestPresent = true;

        View(MechanicsModel.Content row, int map) {
            this.row = row;
            this.map = map;
        }

        @Override
        public MechanicsModel.Stage stage() {
            return row.stages().get(0);
        }

        @Override
        public List<RunMember> members() {
            return List.of(member(true), member(false));
        }

        @Override
        public String roleOf(int characterId) {
            return characterId == LEADER ? "leader" : "raider";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public int mapOf(int characterId) {
            return map;
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        @Override
        public boolean eventCleared() {
            return cleared;
        }

        @Override
        public int reactorState(int reactorId) {
            return reactorId == TreasureChestSolver.CHEST && chestPresent ? 0 : -1;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return new HashMap<>();
        }
    }
}
