package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 (owner 2026-10-29): the roof's next00 portal did nothing in a real client, so Ifia (2161001, on the same roof)
 * also walks you into the Rose Garden, with the same warp as pt_rosegarden.js; during the Von Leon quests she still
 * sends you to the Audience Room corridor.
 */
class IfiaRoseGardenTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeCm {
        final boolean onQuest;
        public String menu;
        public int map = -1;
        public String portal;
        public boolean disposed;

        FakeCm(boolean onQuest) {
            this.onQuest = onQuest;
        }

        public boolean isQuestStarted(int quest) {
            return onQuest && quest == 3174;
        }

        public void sendSimple(String text) {
            menu = text;
        }

        public void sendOk(String text) {
            menu = text;
        }

        public void warp(int mapId, String portalName) {
            map = mapId;
            portal = portalName;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable ifia(FakeCm cm) {
        ScriptEngine engine = new Loader().load("npc/2161001.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    @Test
    void sheWalksYouIntoTheRoseGarden() throws Exception {
        FakeCm cm = new FakeCm(false);
        Invocable npc = ifia(cm);
        npc.invokeFunction("start");
        assertTrue(cm.menu.contains("Rose Garden"), cm.menu);
        npc.invokeFunction("action", 1, 4, 0);
        assertEquals(211080000, cm.map);
        assertEquals("west00", cm.portal);
        assertTrue(cm.disposed);
    }

    @Test
    void duringTheVonLeonQuestsSheStillSendsYouToTheCorridor() throws Exception {
        FakeCm cm = new FakeCm(true);
        ifia(cm).invokeFunction("start");
        assertEquals(211070000, cm.map);
    }
}
