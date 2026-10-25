package scripting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2 quest sweep: quests whose Check.img on our server says "script-ended" (endscript) where stock Cosmic's data
 * checks quest progress (infoex) instead. Without a script the hand-in fell back to medalQuest.js (no reward, a
 * "&lt;&gt; is not coded" message), or, for 3360, failed outright. Each script must keep Cosmic's progress check
 * and give the Act.img reward. Runs in the server's own Graal setup against a fake {@code qm}.
 */
class QuestScriptHandInTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of QuestActionManager the hand-in scripts use. */
    public static final class FakeQm {
        public final Map<String, String> progress = new HashMap<>();
        public int exp;
        public int fame;
        public boolean completed;
        public boolean disposed;
        public String lastText;
        public final StringBuilder allText = new StringBuilder();
        public final Map<Integer, Integer> gained = new HashMap<>();

        public String getQuestProgress(int quest) {
            return getQuestProgress(quest, 0);
        }

        public String getQuestProgress(int quest, int infoNumber) {
            return progress.getOrDefault(quest + "/" + infoNumber, "");
        }

        public void sendOk(String text) {
            lastText = text;
            allText.append(text).append('\n');
        }

        public void sendNext(String text) {
            lastText = text;
            allText.append(text).append('\n');
        }

        /** Items the fake player is missing (default: holds everything asked for). */
        public final java.util.Set<Integer> lacks = new java.util.HashSet<>();

        public boolean haveItem(int itemId, int quantity) {
            return !lacks.contains(itemId);
        }

        public boolean canHold(int itemId, int quantity) {
            return true;
        }

        public void gainItem(int itemId, int quantity) {
            gained.merge(itemId, quantity, Integer::sum);
        }

        public void gainExp(int gain) {
            exp += gain;
        }

        public void gainFame(int delta) {
            fame += delta;
        }

        public boolean forceCompleteQuest() {
            completed = true;
            return true;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable load(int quest, FakeQm qm) {
        ScriptEngine engine = new Loader().load("quest/" + quest + ".js");
        assertNotNull(engine, "quest/" + quest + ".js exists and evaluates");
        engine.put("qm", qm);
        return (Invocable) engine;
    }

    private static void handIn(Invocable script) throws Exception {
        script.invokeFunction("end", 1, 0, 0);
        script.invokeFunction("end", 1, 0, 0);
    }

    /** quest, progress record (infoNumber), the progress Cosmic's infoex asks for, EXP and fame from Act.img. */
    static Stream<Arguments> handIns() {
        return Stream.of(
                Arguments.of(2236, 0, "111111", 60000, 0),  // How to Shoo Away the Evil
                Arguments.of(3114, 0, "42", 0, 20),         // Eliza's Eternal Sleep
                Arguments.of(3311, 0, "5", 60000, 0),       // Clue
                Arguments.of(3345, 0, "4", 2200, 0),        // The Black Magician's Magic Pentagram
                Arguments.of(3360, 0, "1", 0, 0),           // Verifying the Password
                Arguments.of(3926, 0, "3333", 6500, 0),     // Screwing the Red Scorpions
                Arguments.of(3927, 0, "1", 0, 0),           // The Existence of the Secret Organization
                Arguments.of(3929, 0, "3333", 2000, 0),     // Sejan's Test
                Arguments.of(21728, 21761, "0", 200, 0));   // The Puppeteer's Cave
    }

    @ParameterizedTest(name = "quest {0} hands in once its progress is {2}")
    @MethodSource("handIns")
    void completesWithTheActRewardOnceTheStepsAreDone(int quest, int info, String done, int exp, int fame) throws Exception {
        FakeQm qm = new FakeQm();
        qm.progress.put(quest + "/" + info, done);
        handIn(load(quest, qm));

        assertTrue(qm.completed, "quest " + quest + " completes");
        assertEquals(exp, qm.exp, "EXP from Act.img");
        assertEquals(fame, qm.fame, "fame from Act.img");
        assertTrue(qm.disposed);
    }

    @ParameterizedTest(name = "quest {0} refuses before the steps are done")
    @MethodSource("handIns")
    void refusesBeforeTheStepsAreDone(int quest, int info, String done, int exp, int fame) throws Exception {
        FakeQm qm = new FakeQm();
        handIn(load(quest, qm));

        assertFalse(qm.completed, "quest " + quest + " must not complete with no progress");
        assertEquals(0, qm.exp);
        assertEquals(0, qm.fame);
        assertTrue(qm.disposed);
    }

    @Test
    void halfDoneProgressIsNotEnough() throws Exception {
        FakeQm qm = new FakeQm();
        qm.progress.put("2236/0", "111011");   // five of the six seals placed
        handIn(load(2236, qm));

        assertFalse(qm.completed);
    }

    /** 3714: owner decision 1.5.2, the Horntail egg quest gives stock Cosmic's Dragon Stone (2041200) again. */
    @Test
    void horntailEggQuestGivesTheDragonStoneAgain() throws Exception {
        FakeQm qm = new FakeQm();
        qm.lacks.add(2041200);
        Invocable quest = load(3714, qm);
        quest.invokeFunction("start", 1, 0, 0);
        quest.invokeFunction("start", 1, 0, 0);

        assertTrue(qm.allText.toString().contains("#t2041200#"), "the text promises the Dragon Stone: " + qm.allText);
        assertEquals(1, qm.gained.get(2041200), "and the quest gives it");
        assertEquals(-1, qm.gained.get(4001094), "the egg goes back to the nest");
        assertEquals(42000, qm.exp);
        assertTrue(qm.completed);
    }

    /** Cosmic's guard: a player still carrying a Dragon Stone gets no second one (3714 is repeatable). */
    @Test
    void horntailEggQuestRefusesWhileAStoneIsCarried() throws Exception {
        FakeQm qm = new FakeQm();
        Invocable quest = load(3714, qm);
        quest.invokeFunction("start", 1, 0, 0);

        assertEquals(null, qm.gained.get(2041200));
        assertFalse(qm.completed);
        assertTrue(qm.disposed);
    }
}
