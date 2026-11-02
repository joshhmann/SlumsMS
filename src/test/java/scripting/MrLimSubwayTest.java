package scripting;

import client.Client;
import client.Character;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: Mr. Lim's Kerberos menu (scripts/npc/1052115.js) offered the Dusty Platform pyramid run and the Train 999
 * bonus stage through cm.start_PyramidSubway and cm.bonus_PyramidSubway. Neither exists in this server - there is no
 * Kerberos subway party quest, only the Netts pyramid on 92601* - so both options ended in a script error. The train
 * option is worse than a dead end: its success path spends the Boarding Pass (4001321), so the menu has to stay shut
 * rather than hand a run that cannot start the player's item.
 *
 * <p>The script runs against the real {@link NPCConversationManager}: a method the server does not have is the same
 * error it was in production.
 */
class MrLimSubwayTest {
    private static final int A_MAP_WITHOUT_A_SECTION = 100000000;
    private static final int BOARDING_PASS = 4001321;

    private static final class MrLimCm extends NPCConversationManager {
        final Character player = mock(Character.class);
        final Map<Integer, Integer> items = new LinkedHashMap<>();
        final StringBuilder dialog = new StringBuilder();
        boolean disposed;

        MrLimCm() {
            super(mock(Client.class), 1052115, "1052115");
            when(player.getLevel()).thenReturn(27);
        }

        @Override
        public Character getPlayer() {
            return player;
        }

        @Override
        public int getMapId() {
            return A_MAP_WITHOUT_A_SECTION;
        }

        @Override
        public boolean isLeader() {
            return true;
        }

        @Override
        public boolean haveItem(int itemid, int quantity) {
            return items.getOrDefault(itemid, 0) >= quantity;
        }

        @Override
        public boolean canHold(int itemid) {
            return true;
        }

        @Override
        public void gainItem(int id, short quantity) {
            int now = items.getOrDefault(id, 0) + quantity;
            if (now <= 0) {
                items.remove(id);
            } else {
                items.put(id, now);
            }
        }

        @Override
        public void sendOk(String text) {
            dialog.append(text).append('\n');
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable load(MrLimCm cm) {
        ScriptEngine engine = new Loader().load("npc/1052115.js");
        assertNotNull(engine, "scripts/npc/1052115.js did not evaluate");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    /** Open the menu's first dialog, then pick {@code selection}. */
    private static void pick(MrLimCm cm, int selection) throws Exception {
        Invocable script = load(cm);
        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, selection);
    }

    @Test
    void thePyramidOptionSaysItIsClosedInsteadOfThrowing() throws Exception {
        MrLimCm cm = new MrLimCm();
        pick(cm, 1);

        assertTrue(cm.dialog.toString().contains("closed for repairs"), cm.dialog.toString());
        assertTrue(cm.items.isEmpty(), "nothing may be taken for a run that cannot start");
    }

    @Test
    void theTrainOptionKeepsTheBoardingPass() throws Exception {
        MrLimCm cm = new MrLimCm();
        cm.items.put(BOARDING_PASS, 1);
        pick(cm, 2);

        assertEquals(1, cm.items.getOrDefault(BOARDING_PASS, 0), "the pass is not spent on a train that never leaves");
        assertTrue(cm.dialog.toString().contains("still in your inventory"), cm.dialog.toString());
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
