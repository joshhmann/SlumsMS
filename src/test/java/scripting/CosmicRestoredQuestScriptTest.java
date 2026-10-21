package scripting;

import client.Job;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.2 Cosmic parity (docs/release/cosmic-diff-1.4.1.md): quest end scripts the MapleRoot repack commented
 * out while Check.img still flags them as script-ended, so the quests could never be completed. Each runs in
 * the server's own Graal setup against a fake {@code qm}.
 */
class CosmicRestoredQuestScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private static Invocable load(String path, Object qm) {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " exists and evaluates");
        engine.put("qm", qm);
        return (Invocable) engine;
    }

    public static final class FakeInventory {
        public int getNumFreeSlot() {
            return 10;
        }
    }

    public static final class FakePlayer {
        private final Job style;

        FakePlayer(Job style) {
            this.style = style;
        }

        public Job getJobStyle() {
            return style;
        }

        public FakeInventory getInventory(InventoryType type) {
            return new FakeInventory();
        }

        public void dropMessage(int type, String message) {
        }
    }

    /** The parts of QuestActionManager the reward scripts use. */
    public static final class FakeQm {
        public final FakePlayer player;
        public final Map<Integer, Integer> gained = new HashMap<>();
        public String simple;
        public int exp;
        public int meso;
        public int fame;
        public boolean completed;
        public boolean disposed;

        FakeQm(Job style) {
            player = new FakePlayer(style);
        }

        public FakePlayer getPlayer() {
            return player;
        }

        public void sendNext(String text) {
        }

        public void sendSimple(String text) {
            simple = text;
        }

        public void gainItem(int itemId, int quantity) {
            gained.merge(itemId, quantity, Integer::sum);
        }

        public void gainExp(int gain) {
            exp += gain;
        }

        public void gainMeso(int gain) {
            meso += gain;
        }

        public void gainFame(int delta) {
            fame += delta;
        }

        public void completeQuest() {
            completed = true;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static void runEnd(Invocable quest, int selection) throws Exception {
        quest.invokeFunction("end", 1, 0, 0);
        quest.invokeFunction("end", 1, 0, 0);
        quest.invokeFunction("end", 1, 0, selection);
    }

    @Test
    void perionHouseQuestCompletesWithTheRepacksTrimmedScrollChoice() throws Exception {
        FakeQm qm = new FakeQm(Job.WARRIOR);
        Invocable quest = load("quest/2001.js", qm);
        runEnd(quest, 0);

        assertFalse(qm.simple.contains("#L1#"), "one scroll per class, the repack's trimmed list: " + qm.simple);
        assertEquals(1, qm.gained.get(2044302), "the warrior's 10% scroll");
        assertEquals(-100, qm.gained.get(4000022), "the house materials are taken");
        assertEquals(-1, qm.gained.get(4001004), "the deed is taken");
        assertEquals(20000, qm.exp);
        assertEquals(15000, qm.meso);
        assertEquals(2, qm.fame);
        assertTrue(qm.completed, "quest 2001 can be completed again");
        assertTrue(qm.disposed);
    }

    @Test
    void perionHouseQuestGivesMagiciansTheirOwnScroll() throws Exception {
        FakeQm qm = new FakeQm(Job.MAGICIAN);
        runEnd(load("quest/2001.js", qm), 0);

        assertEquals(1, qm.gained.get(2043802));
        assertTrue(qm.completed);
    }

    @Test
    void omegaSampleQuestCompletesWithTheChosenScroll() throws Exception {
        FakeQm qm = new FakeQm(Job.THIEF);
        Invocable quest = load("quest/3414.js", qm);
        runEnd(quest, 1);

        assertEquals(1, qm.gained.get(2044702), "second thief choice");
        for (int sample = 4031103; sample <= 4031106; sample++) {
            assertEquals(-1, qm.gained.get(sample), "sample " + sample + " is handed in");
        }
        assertEquals(12000, qm.exp);
        assertTrue(qm.completed, "quest 3414 can be completed again");
    }

    @Test
    void omegaFreeRateVariantEvaluatesAgain() throws Exception {
        FakeQm qm = new FakeQm(Job.BOWMAN);
        Invocable quest = load("quest/3414_free10rate.js", qm);
        quest.invokeFunction("end", 1, 0, 0);
        quest.invokeFunction("end", 1, 0, 0);

        assertTrue(qm.simple.contains("#t2044502#"), qm.simple);
    }
}
