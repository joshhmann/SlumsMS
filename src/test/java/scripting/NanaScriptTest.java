package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nana the Love fairy (scripts/npc/9201001.js) was pasted twice into one file; it is once again, and still works. */
class NanaScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMap {
        public int getId() {
            return 100000000; // Henesys, Nana's first post
        }
    }

    public static final class FakePlayer {
        public FakeMap getMap() {
            return new FakeMap();
        }

        public int getExpRate() {
            return 1;
        }
    }

    /** The parts of NPCConversationManager Nana uses. */
    public static final class NanaCm {
        public final List<Integer> started = new ArrayList<>();
        public String ok;
        public String acceptDecline;
        public boolean disposed;

        public boolean isQuestStarted(int quest) {
            return quest == 100400 || started.contains(quest);
        }

        public boolean isQuestCompleted(int quest) {
            return false;
        }

        public boolean haveItem(int item) {
            return false;
        }

        public boolean haveItem(int item, int qty) {
            return false;
        }

        public FakePlayer getPlayer() {
            return new FakePlayer();
        }

        public void startQuest(int quest) {
            started.add(quest);
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void sendAcceptDecline(String text) {
            acceptDecline = text;
        }

        public void dispose() {
            disposed = true;
        }
    }

    @Test
    void theScriptIsInTheFileOnce() throws Exception {
        String source = Files.readString(Path.of("scripts/npc/9201001.js"));
        Matcher starts = Pattern.compile("function start\\(").matcher(source);
        int count = 0;
        while (starts.find()) {
            count++;
        }
        assertEquals(1, count, "one start()");
    }

    @Test
    void nanaInHenesysHandsOutHerFavour() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201001.js");
        assertNotNull(engine, "9201001.js evaluates");
        NanaCm cm = new NanaCm();
        engine.put("cm", cm);
        Invocable nana = (Invocable) engine;

        nana.invokeFunction("start");
        assertNotNull(cm.acceptDecline, "asks for the favour");
        nana.invokeFunction("action", 1, 1, 0);

        assertEquals(List.of(100401), cm.started, "the Henesys Nana quest starts");
        assertTrue(cm.ok.contains("50"), cm.ok);
        assertTrue(cm.disposed);
    }
}
