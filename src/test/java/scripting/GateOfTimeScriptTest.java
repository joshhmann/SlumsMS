package scripting;

import client.Character;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 b040: the player notes say lower Grandis is reached by "the Gate of Time in the Temple of Time". The gate
 * (scripts/portal/270000000.10.js) used to go to the Future only; it now opens a menu (scripts/npc/gateOfTime.js) with
 * the Future and lower Grandis (Pantheon), both behind server.maps.AreaGates' level-120 gate.
 */
class GateOfTimeScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of PortalPlayerInteraction the portal uses. */
    public static final class FakePi {
        public int npc = -1;
        public String script;

        public void openNpc(int npcId, String name) {
            npc = npcId;
            script = name;
        }
    }

    /** The parts of NPCConversationManager the menu uses. */
    public static final class FakeCm {
        final Character player;
        public String menu;
        public String ok;
        public int warpedTo = -1;
        public boolean disposed;

        FakeCm(Character player) {
            this.player = player;
        }

        public Character getPlayer() {
            return player;
        }

        public void sendSimple(String text) {
            menu = text;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void warp(int map, int portal) {
            warpedTo = map;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Character player(int level) {
        Character chr = mock(Character.class);
        when(chr.getLevel()).thenReturn(level);
        when(chr.gmLevel()).thenReturn(0);
        return chr;
    }

    private static FakeCm pick(int level, int selection) throws Exception {
        ScriptEngine engine = new Loader().load("npc/gateOfTime.js");
        assertNotNull(engine, "gateOfTime.js evaluates");
        FakeCm cm = new FakeCm(player(level));
        engine.put("cm", cm);
        Invocable gate = (Invocable) engine;
        gate.invokeFunction("start");
        assertNotNull(cm.menu, "the gate asks where to go");
        gate.invokeFunction("action", 1, 4, selection);
        return cm;
    }

    @Test
    void theGatePortalOpensTheMenu() throws Exception {
        ScriptEngine engine = new Loader().load("portal/270000000.10.js");
        assertNotNull(engine);
        FakePi pi = new FakePi();
        Object moved = ((Invocable) engine).invokeFunction("enter", pi);
        assertEquals("gateOfTime", pi.script);
        assertEquals(2140000, pi.npc);
        assertEquals(Boolean.FALSE, moved, "the menu does the warp");
    }

    @Test
    void theMenuOffersTheFutureAndLowerGrandis() throws Exception {
        FakeCm cm = pick(120, 0);
        assertTrue(cm.menu.contains("Future") && cm.menu.contains("Grandis"), cm.menu);
        assertEquals(271000000, cm.warpedTo);

        FakeCm grandis = pick(120, 1);
        assertEquals(400000000, grandis.warpedTo, "level 120 goes to Pantheon");
        assertTrue(server.maps.AreaGates.isLowerGrandis(grandis.warpedTo));
        assertNull(grandis.ok);
        assertTrue(grandis.disposed);
    }

    @Test
    void level119IsTurnedAwayFromBoth() throws Exception {
        for (int selection = 0; selection <= 1; selection++) {
            FakeCm cm = pick(119, selection);
            assertEquals(-1, cm.warpedTo, "no warp for option " + selection);
            assertTrue(cm.ok != null && cm.ok.contains("level 120"), cm.ok);
            assertTrue(cm.disposed);
        }
    }
}
