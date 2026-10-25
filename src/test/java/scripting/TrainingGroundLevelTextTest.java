package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Victoria Island training-ground NPCs: the repack raised their level limit from 20 to 200 (anyone below 200 may
 * enter) but left the refusal saying "under level 20", so a level-200 player was told a limit that is not the real one.
 */
class TrainingGroundLevelTextTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of NPCConversationManager the training-ground scripts use. */
    public static final class FakeCm {
        private final int level;
        public String ok;
        public String simple;

        FakeCm(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public boolean isQuestActive(int quest) {
            return false;
        }

        public int getPlayerCount(int map) {
            return 0;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void sendSimple(String text) {
            simple = text;
        }

        public void dispose() {
        }
    }

    private static FakeCm talk(String npc, int level) throws Exception {
        ScriptEngine engine = new Loader().load("npc/" + npc + ".js");
        assertNotNull(engine, npc + " evaluates");
        FakeCm cm = new FakeCm(level);
        engine.put("cm", cm);
        ((Invocable) engine).invokeFunction("start");
        return cm;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1012118", "1012119", "1022104", "1022105", "1032113", "1032114", "1052113", "1052114",
            "1095001", "1095002"})
    void refusalStatesTheRealLimit(String npc) throws Exception {
        FakeCm capped = talk(npc, 200);
        assertNotNull(capped.ok, npc + " refuses a level-200 player");
        assertTrue(capped.ok.contains("under level 200"), npc + ": " + capped.ok);

        FakeCm below = talk(npc, 199);
        assertNull(below.ok, npc + " lets level 199 in");
        assertNotNull(below.simple, npc + " shows the training center list");
    }
}
