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
 * Ticket 164: DelliBattle, the one row whose clear is a clock. The point of these tests is that nothing the party does
 * can advance the event - {@code monsterKilled} is empty ({@code DelliBattle.js:213}) and only {@code scheduledTimeout}
 * ({@code :119-124}) sets the flag - so the solver's job is to keep the room worked and never to hit the friendly.
 */
class DelliBattleSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content row() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("DelliBattle");
        assertNotNull(content, "content/dellibattle.yaml must be listed in content/index.txt");
        return content;
    }

    private static RunMember member(boolean leader) {
        return new RunMember(leader ? LEADER : MEMBER, leader, true, MechanicsPersona.PERFECT);
    }

    private static DelliHoldSolver solver() {
        return new DelliHoldSolver();
    }

    @Test
    void theRowIsTheScriptsGatesAndABudgetLongerThanTheScriptsClock() {
        MechanicsModel.Content c = row();
        assertEquals("DelliBattle", c.eventScript());
        assertEquals(1, c.minPlayers(), "DelliBattle.js:27: one to two players");
        assertEquals(2, c.maxPlayers());
        assertEquals(120, c.minLevel(), "DelliBattle.js:28");
        assertEquals(255, c.maxLevel());
        assertEquals(2095000, c.entryNpc(), "scripts/npc/2095000.js:48-70 is Delli's front door");
        assertEquals(925010200, c.recruitMap(), "DelliBattle.js:31");
        assertEquals(925010200, c.exitMap(), "DelliBattle.js:30");
        assertTrue(c.timeLimitMinutes() > 6,
                "the script's own timer is six minutes and ITS timeout is the clear (DelliBattle.js:34,:119-124), so our"
                        + " budget must outlast it or the run ends on the budget before the flag exists: "
                        + c.timeLimitMinutes());
        assertEquals(Mechanics.DELLI_FLAG, c.flag());

        MechanicsModel.Stage stage = c.stages().get(0);
        assertEquals(925010300, stage.mapId(), "one room: minMapId == maxMapId (DelliBattle.js:33-34)");
        assertEquals("delli-hold", stage.solver());
        assertTrue(stage.clear().onInstance(), "clearPQ (:208-211) sets the flag and nothing else");
    }

    @Test
    void theRoomIsWorkedAndTheEventIsNeverAdvancedByKilling() {
        View v = new View(row(), 925010300);
        v.mobs = 3;
        assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(true)),
                "the room's monsters are fought: it re-spawns every 15 s (DelliBattle.js:109-112)");
        v.mobs = 0;
        SolverStep step = solver().next(v, member(true));
        assertInstanceOf(SolverStep.Wait.class, step, "and with the room empty there is nothing to do but wait: " + step);
        assertTrue(((SolverStep.Wait) step).why().contains("clock"), ((SolverStep.Wait) step).why());
        // no count, no hand-in: nothing this solver emits could reach a clear
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(solver().next(v, member(true)) instanceof SolverStep.Wait,
                    "the row cannot clear itself: the flag comes from the server's timer");
        }
    }

    @Test
    void theClerAndTheFriendlyAreNeverATarget() {
        View v = new View(row(), 925010300);
        v.mobs = 1;
        SolverStep.Fight f = assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(true)));
        assertTrue(f.mobIds().isEmpty(), "Fight(0) is 'any monster', and the driver refuses WZ-friendly mobs: "
                + f.mobIds());
        assertEquals(0, f.itemId(), "and it asks for no item: this event's monsterKilled is empty");
    }

    @Test
    void theRunEndsWhenTheInstanceSaysSoNotWhenTheRoomIsEmpty() {
        View v = new View(row(), 925010300);
        v.mobs = 0;
        assertInstanceOf(SolverStep.Wait.class, solver().next(v, member(true)));
        v.cleared = true; // scheduledTimeout ran at six minutes
        assertInstanceOf(SolverStep.Done.class, solver().next(v, member(true)));
    }

    @Test
    void aMemberOutsideTheRoomIsWalkedBackIn() {
        View v = new View(row(), 925010200);
        assertEquals(DelliHoldSolver.ROOM, assertInstanceOf(SolverStep.WarpTo.class,
                solver().next(v, member(false))).mapId());
    }

    private static final class View implements StageView {
        private final MechanicsModel.Content row;
        private final int map;
        private int mobs;
        private boolean cleared;

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
            return characterId == LEADER ? "leader" : "escort";
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
        public Map<String, Object> stageMemory() {
            return new HashMap<>();
        }
    }
}
