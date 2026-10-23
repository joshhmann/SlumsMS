package scripting;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.menus.MenuWindow;
import soloMapling.menus.MenuWindowTestAccess;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0: slumsmenu.js runs in Cosmic's Graal setup, redraws on a click that keeps the menu, and closes on null. */
class SlumsMenuScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @Test
    void theScriptShowsTheMenuRedrawsAndCloses() throws Exception {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1002);
        MenuWindowTestAccess.fakeWindow();
        try {
            MenuWindow.Menu second = p -> new MenuWindow.Page("second", List.of(new MenuWindow.Option("bye", x -> null)));
            MenuWindow.open(player, 9200000, p -> new MenuWindow.Page("first",
                    List.of(new MenuWindow.Option("go", x -> second))));

            ScriptEngine engine = new Loader().load("npc/" + MenuWindow.SCRIPT + ".js");
            assertNotNull(engine, "slumsmenu.js evaluates");
            BotMenuScriptTest.FakeCm cm = new BotMenuScriptTest.FakeCm(player);
            engine.put("cm", cm);
            Invocable script = (Invocable) engine;

            script.invokeFunction("start");
            assertTrue(cm.sent.startsWith("first") && cm.sent.contains("#L0#go#l"), cm.sent);
            script.invokeFunction("action", (byte) 1, (byte) 4, 0);
            assertTrue(cm.sent.startsWith("second"), cm.sent);
            assertFalse(cm.disposed, "still open");
            script.invokeFunction("action", (byte) 1, (byte) 4, 0);
            assertTrue(cm.disposed, "a null choice closes it");
        } finally {
            MenuWindowTestAccess.realWindow();
        }
    }
}
