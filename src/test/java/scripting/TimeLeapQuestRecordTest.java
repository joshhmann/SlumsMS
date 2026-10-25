package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quest 6363 "A New Revelation" (Buccaneer, gives Time Leap): the server's Quest.wz only lets it be handed in once
 * record 6364 holds "2" (infoNumber/infoex, not in stock Cosmic's data), and no script set that record, so no
 * Buccaneer could get Time Leap. Papulatus in the past (NPC 2043000) sets it when he sends the player back.
 */
class TimeLeapQuestRecordTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of NPCConversationManager the Papulatus script uses. */
    public static final class FakeCm {
        private final boolean questStarted;
        public final Map<String, String> progress = new HashMap<>();
        public int warpedTo;

        FakeCm(boolean questStarted) {
            this.questStarted = questStarted;
        }

        public boolean isQuestStarted(int quest) {
            return quest == 6363 && questStarted;
        }

        public void setQuestProgress(int quest, int infoNumber, String value) {
            progress.put(quest + "/" + infoNumber, value);
        }

        public void sendNext(String text) {
        }

        public void warp(int map) {
            warpedTo = map;
        }

        public void dispose() {
        }
    }

    private static FakeCm talk(boolean questStarted) throws Exception {
        ScriptEngine engine = new Loader().load("npc/2043000.js");
        assertNotNull(engine);
        FakeCm cm = new FakeCm(questStarted);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);
        return cm;
    }

    @Test
    void returningToThePresentSetsTheRecordQuest6363Needs() throws Exception {
        FakeCm cm = talk(true);

        assertEquals("2", cm.progress.get("6363/6364"), "record 6364 = 2, the hand-in condition");
        assertEquals(220080000, cm.warpedTo);
    }

    @Test
    void withoutTheQuestPapulatusOnlySendsThePlayerBack() throws Exception {
        FakeCm cm = talk(false);

        assertTrue(cm.progress.isEmpty());
        assertEquals(220080000, cm.warpedTo);
    }
}
