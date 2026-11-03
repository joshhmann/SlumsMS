package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: the Magatia bookshelf. Twenty-six standings of one NPC, and the script keys each on
 * {@code cm.getNpcObjectId()} (`scripts/npc/2112013.js:29-32`), so the failure this test pins is the interesting one:
 *
 * <p><b>Fail-before.</b> {@code noNumberOfTriesToTheFirstBookEverClearsThisShelf} shows that a solver which can only
 * address "the NPC" - the pre-seam behaviour, where {@code TalkNpc} resolves through {@code MapleMap.getNPCById} and
 * always lands on the first standing - reads the first book for ever and the stage never clears. The view used here
 * models the script honestly: a book can be read once, and only the one at index 6 is the pass.
 */
class MagatiaBooksSolversTest {

    private static final int NPC = 2112013;
    private static final int MAP = 926110000;
    private static final int PASS_AT = 6; // the seventh book, as the owner's fail-before asks
    private static final int BOOKS = 26;

    @Test
    void theShelfIsReadOneBookAtATimeAndOnlyTheSeventhClearsIt() {
        View v = new View();
        MagatiaBooksSolver solver = new MagatiaBooksSolver();

        List<Integer> read = new ArrayList<>();
        for (int i = 0; i < BOOKS && !v.cleared(); i++) {
            SolverStep step = solver.next(v, member());
            SolverStep.TalkNpcAt talk = assertInstanceOf(SolverStep.TalkNpcAt.class, step,
                    "every tick that is not a clear reads one more book: " + step);
            assertEquals(NPC, talk.npcId());
            read.add(talk.objectId());
            v.read(talk.objectId());
        }

        assertEquals(1000 + PASS_AT, read.get(PASS_AT), "the seventh read is the seventh book");
        assertTrue(v.cleared(), "the seventh book is the shelf's pass (2112013.js:56-61)");
        assertEquals(PASS_AT + 1, read.size(), "and the shelf stops there: seven books, not twenty-six");
        assertEquals(read.size(), read.stream().distinct().count(), "no book is read twice: " + read);
    }

    @Test
    void noNumberOfTriesToTheFirstBookEverClearsThisShelf() {
        View v = new View();
        MagatiaBooksSolver solver = new MagatiaBooksSolver();

        // the pre-seam behaviour: every talk resolves through getNPCById, i.e. placement index 0 for ever
        for (int i = 0; i < 200; i++) {
            v.read(0);
        }
        assertTrue(!v.cleared(), "reading the first book two hundred times is not a clear");

        // and with the seam in place the same shelf clears, because the solver walks the standings instead
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < BOOKS && !v.cleared(); i++) {
            SolverStep.TalkNpcAt talk = assertInstanceOf(SolverStep.TalkNpcAt.class,
                    solver.next(v, member()));
            order.add(talk.objectId());
            v.read(talk.objectId());
        }
        assertTrue(v.cleared(), "the same shelf clears once the books are addressed one by one: " + order);
    }

    @Test
    void twoMembersNeverReadTheSameBook() {
        View v = new View();
        MagatiaBooksSolver solver = new MagatiaBooksSolver();

        SolverStep.TalkNpcAt a = assertInstanceOf(SolverStep.TalkNpcAt.class, solver.next(v, member()));
        SolverStep.TalkNpcAt b = assertInstanceOf(SolverStep.TalkNpcAt.class, solver.next(v, member()));
        assertTrue(a.objectId() != b.objectId(), "a claimed book is claimed for the instance, not per member: "
                + a.objectId() + " vs " + b.objectId());
        // and once one of them clears the shelf, everybody is done
        v.read(1000 + PASS_AT);
        assertInstanceOf(SolverStep.Done.class, solver.next(v, member()));
    }

    @Test
    void aShelfWithNoBooksLeftSaysSoInsteadOfLooping() {
        View v = new View();
        MagatiaBooksSolver solver = new MagatiaBooksSolver();
        for (int i = 0; i < BOOKS; i++) {
            solver.next(v, member());
            v.read(1000 + i == 1000 + PASS_AT ? 0 : 1000 + i); // every book read, and the pass one is not the pass
        }
        v.cleared = false;
        SolverStep step = solver.next(v, member());
        SolverStep.Abandon giveUp = assertInstanceOf(SolverStep.Abandon.class, step,
                "twenty-six books is a bound the script guarantees, so the run can say it is out: " + step);
        assertTrue(giveUp.why().contains("26"), giveUp.why());
    }

    private static RunMember member() {
        return new RunMember(1, true, true, MechanicsPersona.PERFECT);
    }

    /** A Magatia shelf: {@code BOOKS} standings of one NPC, a book readable once, and one of them the pass. */
    private static final class View implements StageView {
        private final List<NpcPlacement> books = new ArrayList<>();
        private final Map<String, Object> memory = new HashMap<>();
        private final Map<Integer, Integer> reads = new LinkedHashMap<>();
        private boolean cleared;

        View() {
            for (int i = 0; i < BOOKS; i++) {
                books.add(new NpcPlacement(1000 + i, -100 * i, 90));
            }
        }

        @Override
        public List<NpcPlacement> npcPlacements(int npcId) {
            return npcId == NPC ? books : List.of();
        }

        void read(int objectId) {
            reads.merge(objectId, 1, Integer::sum);
            if (objectId == 1000 + PASS_AT) {
                cleared = true; // 2112013.js:56-61
            }
        }

        @Override
        public MechanicsModel.Stage stage() {
            return new MechanicsModel.Stage(1, MAP, "the Alcadno bookshelf", NPC, MagatiaBooksSolver.ID, List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), new MechanicsModel.Clear("statusStg1", "1"),
                    null, List.of(), List.of(), null, null, 0, 45, 0, List.of(), 0L, 0);
        }

        @Override
        public List<RunMember> members() {
            return List.of(member());
        }

        @Override
        public String roleOf(int characterId) {
            return "leader";
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
            return "statusStg1".equals(key) && cleared ? Optional.of("1") : Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public int mapOf(int characterId) {
            return MAP;
        }

        @Override
        public boolean eventCleared() {
            return cleared;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }

        @Override
        public boolean cleared() {
            return cleared;
        }
    }
}
