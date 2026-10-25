package soloMapling.qa;

import client.Job;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * vb-6 (1.5.2 auto-QA): the clicks GameQaWorld answers Dances with Balrog with must reach the 2nd job advancement.
 * The old 3, 0, 1 asked for the Fighter explanation and ended back at the menu, so "VB JOB advance 100 -> 110"
 * never showed.
 */
class JobAdvanceClicksTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakePlayer {
        public boolean gotPartyQuestItem(String key) {
            return false;
        }
    }

    /** The parts of NPCConversationManager 1022000.js uses for a level 30 Warrior holding the proof (4031012). */
    public static final class JobCm {
        int job = 100;
        boolean proof = true;
        final List<Integer> jobChanges = new ArrayList<>();
        boolean disposed;

        public int getJobId() { return job; }
        public Job getJob() { return Job.getById(job); }
        public int getLevel() { return 30; }
        public boolean canSpawnPlayerNpc(int map) { return false; }
        public boolean haveItem(int item) { return item == 4031012 && proof; }
        public boolean canHold(int item) { return true; }
        public void gainItem(int item, int qty) {
            if (item == 4031012 && qty < 0) {
                proof = false;
            }
        }
        public boolean isQuestStarted(int quest) { return false; }
        public void startQuest(int quest) { }
        public void completeQuest(int quest) { }
        public void changeJobById(int id) {
            jobChanges.add(id);
            job = id;
        }
        public FakePlayer getPlayer() { return new FakePlayer(); }
        public String getFirstJobStatRequirement(int type) { return ""; }
        public String numberWithCommas(int n) { return Integer.toString(n); }
        public void sendNext(String text) { }
        public void sendNextPrev(String text) { }
        public void sendSimple(String text) { }
        public void sendYesNo(String text) { }
        public void sendOk(String text) { }
        public void dispose() { disposed = true; }
    }

    private static JobCm click(int... clicks) throws Exception {
        ScriptEngine engine = new Loader().load("npc/1022000.js");
        assertNotNull(engine, "1022000.js evaluates");
        JobCm cm = new JobCm();
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        for (int selection : clicks) {
            npc.invokeFunction("action", 1, 0, selection); // as GameQaWorld.npcScript: mode 1, type 0
        }
        return cm;
    }

    @Test
    void theVb6ClicksMakeAFighter() throws Exception {
        JobCm cm = click(GameQaWorld.JOB_ADVANCE_CLICKS);
        assertEquals(List.of(110), cm.jobChanges);
    }

    @Test
    void theOldClicksNeverAdvanced() throws Exception {
        assertEquals(List.of(), click(3, 0, 1).jobChanges, "the bug this guards against");
    }
}
