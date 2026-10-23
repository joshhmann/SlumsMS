package scripting;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6, owner QA 2026-10-23 (t-bg-1: "dont see an option to view guild rankings"): Heracle (2010007) in the Guild HQ
 * opens the stock guild ranking window, the Honorable Rock's, which lists bot and player guilds by GP.
 */
class HeracleGuildRankScriptTest {

    /** The parts of NPCConversationManager the script uses. */
    public static final class FakeCm {
        private final Character player;
        public String sent;
        public boolean ranks;
        public boolean disposed;

        FakeCm(Character player) {
            this.player = player;
        }

        public Character getPlayer() {
            return player;
        }

        public void sendSimple(String text) {
            sent = text;
        }

        public void sendOk(String text) {
            sent = text;
        }

        public void sendYesNo(String text) {
            sent = text;
        }

        public void displayGuildRanks() {
            ranks = true;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @Test
    void heracleOffersTheGuildRankingAndOpensIt() throws Exception {
        ScriptEngine engine = new Loader().load("npc/2010007.js");
        assertNotNull(engine, "2010007.js evaluates");
        FakeCm cm = new FakeCm(Character.getDefault(new BotClient(0, 0)));
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        script.invokeFunction("start");
        assertTrue(cm.sent.contains("#L3#See the guild rankings#l"), cm.sent);
        assertTrue(cm.sent.contains("#L0#Create a Guild#l"), "the stock options stay");
        script.invokeFunction("action", (byte) 1, (byte) 4, 3);
        assertTrue(cm.ranks, "the stock ranking window opens");
        assertTrue(cm.disposed);
    }
}
