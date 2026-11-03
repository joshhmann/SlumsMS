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
 * Ticket 164: the Magatia PQ row and the three stage rules it needed, plus the honest hold that keeps a half-modelled
 * party quest from reporting a clear it never got.
 *
 * <p>Every claim is one of our scripts: the books (`2112013.js`), the empty room (`MagatiaPQ_A.js:418-426`), the beaker
 * (`2618000.js:27-41`) and Juliet's two files (`2112005.js:72-87`).
 */
class MagatiaPqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content row() {
        MechanicsModel.Content c = MechanicsRuns.contentFor("MagatiaPQ_A");
        assertNotNull(c, "content/magatiapq.yaml must be listed in content/index.txt");
        return c;
    }

    private static RunMember member(boolean leader) {
        return new RunMember(leader ? LEADER : MEMBER, leader, true, MechanicsPersona.PERFECT);
    }

    @Test
    void theRowIsSixStagesAndAllOfThemAreDriven() {
        MechanicsModel.Content c = row();
        assertEquals("MagatiaPQ_A", c.eventScript());
        assertEquals(4, c.minPlayers(), "MagatiaPQ_A.js:27: exactly four");
        assertEquals(4, c.maxPlayers());
        assertEquals(71, c.minLevel());
        assertEquals(45, c.timeLimitMinutes());
        assertEquals(2112003, c.entryNpc(), "scripts/npc/2112003.js is Juliet");
        assertEquals(261000021, c.recruitMap());

        List<MechanicsModel.Stage> stages = c.stages();
        assertEquals(6, stages.size(), "six of the seven script stages are driven; stage 7 is a walk-out");
        assertEquals(List.of("magatia-books", "fight-room", "reactor-count", "magatia-files",
                        MagatiaClimbSolver.ID, SummonedBossSolver.ID),
                stages.stream().map(MechanicsModel.Stage::solver).toList());
        assertEquals(List.of(926110001, 926110001, 926110200, 926110200, 926110300, 926110401),
                stages.stream().map(MechanicsModel.Stage::mapId).toList());
        assertEquals(List.of("statusStg1", "statusStg2", "statusStg3", "statusStg4", "statusStg6"),
                stages.subList(0, 5).stream().map(s -> s.clear().eventProperty()).toList());
        assertTrue(stages.get(5).clear().onInstance(), "the boss's death calls clearPQ, so the flag is the clear");
        assertEquals(List.of(926110301, 926110302, 926110303, 926110304), stages.get(4).maps(),
                "the four rooms the climb is fought in");
        assertEquals("3", stages.get(2).clear().equals(), "the beaker counts to three (2618000.js:34-41)");
        assertEquals(List.of(926110201, 926110202), stages.get(3).maps(),
                "the two files come from the two side rooms");
    }

    @Test
    void theClimbTakesTheColumnTheEventPublished() {
        MechanicsModel.Stage climb = row().stages().get(4);
        assertEquals("1", climb.clear().equals(), "jnr5_rp.js:52-57 sets statusStg6 = 1 on the last row");
        View v = new View(climb, 926110301);
        v.props.put(MagatiaClimbSolver.combKey(926110301), "3021130210");
        v.at.replace(LEADER, 926110301);
        MagatiaClimbSolver solver = new MagatiaClimbSolver();

        // row 0's digit is 3, row 3's is 1, row 9's is 0 - the portal name IS the answer
        for (int row : new int[]{0, 3, 9}) {
            v.y = MagatiaClimbSolver.ROW_Y[row];
            SolverStep.ScriptedPortal step = assertInstanceOf(SolverStep.ScriptedPortal.class,
                    solver.next(v, member(true)), "row " + row);
            assertEquals("pt" + row + "3021130210".charAt(row), step.portalName(), "row " + row);
        }
        // a member at the fail portal's height is back on row 0, not stuck
        v.y = 120;
        assertEquals("pt03", assertInstanceOf(SolverStep.ScriptedPortal.class,
                solver.next(v, member(true))).portalName());
        // from the hub the first step is the room door, which is what sets bit 0
        v.at.replace(LEADER, 926110300);
        assertEquals("pt00", assertInstanceOf(SolverStep.ScriptedPortal.class,
                solver.next(v, member(true))).portalName());
        // and with the combination not published yet, row 0 is the safe take that rolls it
        v.at.replace(LEADER, 926110302);
        v.props.remove(MagatiaClimbSolver.combKey(926110302));
        assertEquals("pt00", assertInstanceOf(SolverStep.ScriptedPortal.class,
                solver.next(v, member(true))).portalName());
    }

    @Test
    void theBossRoomIsATalkThenAFightAndTheScriptsOwnClear() {
        MechanicsModel.Stage sixth = row().stages().get(5);
        assertTrue(sixth.clear().onInstance(),
                "MagatiaPQ_A.js:435-450: either Frankenroid's death shows the clear effect and calls clearPQ");
        assertEquals(2112010, sixth.npcId(), "Yulete is the summoner");
        assertEquals(List.of(9300151, 9300152), sixth.mobs(), "both forms are the same fight");
        View v = new View(sixth, 926110401);
        v.npc = true;
        SummonedBossSolver solver = new SummonedBossSolver();

        // an empty room: the leader talks to Yulete, everyone else waits for the boss to arrive
        assertEquals(new SolverStep.TalkNpc(2112010, 0, 0),
                solver.next(v, member(true)), "the talk is what summons");
        SolverStep other = solver.next(v, member(false));
        assertInstanceOf(SolverStep.Wait.class, other, "a non-leader never summons");
        assertTrue(((SolverStep.Wait) other).why().contains("summon"), "and says what it is waiting for");

        // with the boss on the floor everyone fights, and the fight does not care which form it is
        v.mobs = 1;
        assertEquals(new SolverStep.Fight(0, 0, 0), solver.next(v, member(true)));
        assertEquals(new SolverStep.Fight(0, 0, 0), solver.next(v, member(false)));

        // off the room's map, back in; and once the script clears it, done
        v.at.replace(LEADER, 926110400);
        assertInstanceOf(SolverStep.WarpTo.class, solver.next(v, member(true)));
        v.at.replace(LEADER, 926110401);
        v.cleared = true;
        assertInstanceOf(SolverStep.Done.class, solver.next(v, member(true)));
    }

    @Test
    void theShelfRoomIsFoughtUntilTheScriptFindsItEmpty() {
        MechanicsModel.Stage stage = row().stages().get(1);
        View v = new View(stage, 926110001);
        FightTheRoomSolver solver = new FightTheRoomSolver();
        v.mobs = 3;
        assertInstanceOf(SolverStep.Fight.class, solver.next(v, member(true)));
        v.mobs = 0;
        SolverStep.Wait w = assertInstanceOf(SolverStep.Wait.class, solver.next(v, member(true)));
        assertTrue(w.why().contains("Empty the shelf's room"), "the wait names the stage it is waiting on: " + w.why());
        v.cleared = true;
        assertInstanceOf(SolverStep.Done.class, solver.next(v, member(true)));
    }

    @Test
    void theBeakerIsHitUntilTheCountTheRowNamesIsReached() {
        MechanicsModel.Stage stage = row().stages().get(2);
        View v = new View(stage, 926110200);
        ReactorCountSolver solver = new ReactorCountSolver();

        assertEquals(3, ReactorCountSolver.target(stage.clear()));
        assertEquals(-1, ReactorCountSolver.counter(v), "the event has not written statusStg3 at all yet");
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, member(true)),
                "with no counter there is nothing to compare, so the solver says so rather than hitting blindly");

        v.props.put("statusStg3", "1");
        assertEquals(2618000, assertInstanceOf(SolverStep.HitReactor.class,
                solver.next(v, member(true))).reactorId(), "one hit per tick while the count is short");
        v.props.put("statusStg3", "3");
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, member(true)),
                "at three the door is the script's job (2618000.js:37-40), not another hit");
        v.cleared = true;
        assertInstanceOf(SolverStep.Done.class, solver.next(v, member(true)));
    }

    @Test
    void bothFilesGoToJulietFromTheLeaderAndNobodyElse() {
        MechanicsModel.Stage stage = row().stages().get(3);
        View v = new View(stage, 926110200);
        MagatiaFilesSolver solver = new MagatiaFilesSolver();
        v.npc = true;

        // the leader holding both talks, with no item in the step: the NPC's own haveItem pair is the gate
        v.held.put(LEADER, new int[]{1, 1});
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class, solver.next(v, member(true)));
        assertEquals(2112005, talk.npcId());
        assertEquals(0, talk.itemId(), "an item id here would make the leader hand one file over on its own");

        // a member holding both pools them on the leader instead
        v.held.put(MEMBER, new int[]{1, 1});
        assertInstanceOf(SolverStep.DropFor.class, solver.next(v, member(false)));

        // and a member short of a file is sent into a side room
        v.held.put(MEMBER, new int[]{0, 0});
        v.at.replace(MEMBER, 926110201);
        v.mobs = 3;
        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(v, member(false)));
        assertEquals(4001134, fight.itemId(), "the first file he is short of");
        v.mobs = 0;
        SolverStep.Wait w = assertInstanceOf(SolverStep.Wait.class, solver.next(v, member(false)));
        assertTrue(w.why().contains("4001134"), w.why());
    }

    private static final class View implements StageView {
        private final MechanicsModel.Stage stage;
        private final Map<Integer, Integer> at = new HashMap<>();
        private final Map<Integer, int[]> held = new HashMap<>();
        private final Map<String, String> props = new HashMap<>();
        private final Map<String, Object> memory = new HashMap<>();
        private int mobs;
        private int y;
        private boolean cleared;
        private boolean npc;

        View(MechanicsModel.Stage stage, int map) {
            this.stage = stage;
            at.put(LEADER, map);
            at.put(MEMBER, map);
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return List.of(member(true), member(false));
        }

        @Override
        public Optional<RunMember> leader() {
            return Optional.of(member(true));
        }

        @Override
        public String roleOf(int characterId) {
            return characterId == LEADER ? "leader" : "carrier";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            int[] h = held.get(characterId);
            return h == null ? 0 : h[itemId == 4001134 ? 0 : 1];
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{at.getOrDefault(characterId, stage.mapId()), y};
        }

        @Override
        public int mapOf(int characterId) {
            return at.getOrDefault(characterId, stage.mapId());
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
        public boolean cleared() {
            return cleared;
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return npc && npcId == stage.npcId() ? Optional.of(new int[]{0, 0}) : Optional.empty();
        }

        @Override
        public int floorItems(int itemId, int forCharacterId) {
            return 0;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }
    }
}
