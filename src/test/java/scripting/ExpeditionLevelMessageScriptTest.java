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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 (owner 2026-10-26): a player under an expedition's level got "You do not meet the criteria to battle ..."; the
 * recruiters now say the level needed. Aldol (9270047, Spooky World) also survives a stale menu once the registration
 * timer has dropped the expedition.
 */
class ExpeditionLevelMessageScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakePlayer {
        private final int level;

        FakePlayer(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public String getName() {
            return "Tester";
        }
    }

    public static final class FakeEm {
        public boolean started;

        public void setProperty(String key, Object value) {
        }

        public String getProperty(String key) {
            return "";
        }

        public boolean startInstance(Object expedition) {
            started = true;
            return true;
        }
    }

    /** The parts of NPCConversationManager a recruiter's first page and a stale menu use. */
    public static final class RecruiterCm {
        final FakePlayer player;
        final FakeEm em = new FakeEm();
        public String ok;
        public boolean disposed;

        RecruiterCm(int level) {
            player = new FakePlayer(level);
        }

        public FakePlayer getPlayer() {
            return player;
        }

        public Object getExpedition(Object type) {
            return null; // none registering (or the timer ran out)
        }

        public FakeEm getEventManager(String name) {
            return em;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void sendSimple(String text) {
        }

        public boolean isQuestStarted(int quest) {
            return true; // Von Leon's and Magnus's recruiters ask for their quests first
        }

        public boolean isQuestCompleted(int quest) {
            return true;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable load(String path, RecruiterCm cm) {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " evaluates");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    @Test
    void aldolSaysTheLevelSpookyWorldNeeds() throws Exception {
        RecruiterCm cm = new RecruiterCm(50);
        load("npc/9270047.js", cm).invokeFunction("start");
        assertEquals("You need to be at least level 100 to battle Scarlion and Targa.", cm.ok);
        assertTrue(cm.disposed);
    }

    @Test
    void everyRecruiterSaysTheLevelItNeeds() throws Exception {
        Pattern type = Pattern.compile("ExpeditionType\\.([A-Z_0-9]+);");
        List<String> checked = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("scripts/npc"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".js")).sorted().toList()) {
                String src = Files.readString(f);
                assertFalse(src.contains("You do not meet the criteria to battle"), f + " still has the stock line");
                Matcher m = type.matcher(src);
                if (!src.contains("You need to be at least level ") || !m.find()) {
                    continue;
                }
                int min = server.expeditions.ExpeditionType.valueOf(m.group(1)).getMinLevel();
                RecruiterCm cm = new RecruiterCm(1);
                load("npc/" + f.getFileName(), cm).invokeFunction("start");
                assertNotNull(cm.ok, f.toString());
                assertTrue(cm.ok.startsWith("You need to be at least level " + min + " to "), f + ": " + cm.ok);
                checked.add(f.getFileName().toString());
            }
        }
        for (String stock : List.of("9270047.js", "2030013.js", "2083004.js", "1061014.js", "9120201.js",
                "2041024.js", "9201113.js")) {
            assertTrue(checked.contains(stock), stock + " checked in " + checked);
        }
    }

    @Test
    void aldolsStaleMenuDoesNotThrowOnceTheExpeditionIsGone() throws Exception {
        for (int[] at : new int[][]{{2, 2}, {2, 3}, {4, 0}}) {
            RecruiterCm cm = new RecruiterCm(120);
            ScriptEngine engine = new Loader().load("npc/9270047.js");
            assertNotNull(engine);
            engine.put("cm", cm);
            engine.put("status", at[0]); // the leader's menu (2) or the start notice (4) still open
            ((Invocable) engine).invokeFunction("action", 1, 0, at[1]);
            assertEquals("The expedition is no longer registering. Talk to me again to start a new one.", cm.ok,
                    "status " + at[0] + " selection " + at[1]);
            assertTrue(cm.disposed);
            assertFalse(cm.em.started, "no instance started for a gone expedition");
        }
    }
}
