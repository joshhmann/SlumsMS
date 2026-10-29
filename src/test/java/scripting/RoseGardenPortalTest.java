package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner 2026-10-29: the Roof of the Fifth Tower's portal next00 (211061001) runs pt_rosegarden, and the Gardener's
 * Spot's west00 (211080000) runs pt_rosegardenout, but neither script existed, so nobody could walk into the Rose
 * Garden. Both now exist and warp each way, with no gate (open lv 128-134 training).
 */
class RoseGardenPortalTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of PortalPlayerInteraction the two portals use. */
    public static final class FakePi {
        public int map = -1;
        public String portal;

        public void playPortalSound() {
        }

        public void warp(int mapId, String portalName) {
            map = mapId;
            portal = portalName;
        }
    }

    private static FakePi enter(String script) throws Exception {
        assertTrue(Files.exists(Path.of("scripts/portal/" + script + ".js")), script + ".js exists");
        ScriptEngine engine = new Loader().load("portal/" + script + ".js");
        assertNotNull(engine, script + ".js evaluates");
        FakePi pi = new FakePi();
        assertEquals(Boolean.TRUE, ((Invocable) engine).invokeFunction("enter", pi));
        return pi;
    }

    @Test
    void theFifthTowerRoofWalksIntoTheRoseGarden() throws Exception {
        FakePi in = enter("pt_rosegarden");
        assertEquals(211080000, in.map);
        assertEquals("west00", in.portal);
    }

    @Test
    void theGardenWalksBackToTheRoof() throws Exception {
        FakePi out = enter("pt_rosegardenout");
        assertEquals(211061001, out.map);
        assertEquals("next00", out.portal);
    }
}
