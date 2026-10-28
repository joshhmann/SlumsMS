package scripting;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.menus.MenuWindow;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1: scripts/npc/9990000.js (Slums-chan) loads in Cosmic's Graal setup and walks her menu. */
class SlumsChanScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @Test
    void herScriptWelcomesThenOpensTheGuideAndCloses() throws Exception {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1003);
        ScriptEngine engine = new Loader().load("npc/9990000.js");
        assertNotNull(engine, "9990000.js evaluates");
        BotMenuScriptTest.FakeCm cm = new BotMenuScriptTest.FakeCm(player);
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        script.invokeFunction("start");
        assertTrue(cm.sent.contains("Slums-chan") && cm.sent.contains("#L0#"), cm.sent);
        script.invokeFunction("action", (byte) 1, (byte) 4, 0);
        assertTrue(cm.sent.contains("Welcome to SlumsMS"), "the guide's first page: " + cm.sent);
        assertFalse(cm.disposed);
        MenuWindow.closed(player);
        script.invokeFunction("action", (byte) 0, (byte) 4, 0);
        assertTrue(cm.disposed, "closing the window disposes");
    }
}
