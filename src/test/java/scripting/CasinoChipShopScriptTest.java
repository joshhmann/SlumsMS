package scripting;

import org.junit.jupiter.api.Test;
import soloMapling.Casino.CasinoChipConfig;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Ticket 164: NPC 9000055 in Henesys Game Park had no script, so NPC talk fell through script -> hasShop() and logged
 * "NPC 9000055 (9000055) is not coded". The port also dropped the upstream script (it named the stale NPC 9201066);
 * this runs the restored file in the server's own Graal setup against a fake {@code cm}.
 */
class CasinoChipShopScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of NPCConversationManager the chip exchange script uses. */
    public static final class ChipCm {
        public Integer openedShop;
        public boolean disposed;

        public void openShopNPC(int id) {
            openedShop = id;
        }

        public void dispose() {
            disposed = true;
        }
    }

    @Test
    void opensTheChipShopCasinoChipConfigNames() {
        ScriptEngine engine = new Loader().load("npc/9000055.js");
        assertNotNull(engine, "scripts/npc/9000055.js exists and evaluates");
        ChipCm cm = new ChipCm();
        engine.put("cm", cm);
        try {
            ((Invocable) engine).invokeFunction("start");
        } catch (Exception e) {
            fail(e);
        }
        assertEquals(CasinoChipConfig.CASINO_SHOP_ID, cm.openedShop, "the script asks for shop 9999001, not a stale id");
        assertTrue(cm.disposed, "the dialogue closes after opening the shop");
    }
}
