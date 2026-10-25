package scripting;

import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

/** 1.5.2: MapleTV's scroll generator had its scroll tables cut to [[ [4001006]]] (an ETC item) and failed to load. */
class ScrollGeneratorTablesTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @Test
    void theTablesAreScrollFamiliesForEveryJobStyle() throws Exception {
        ScriptEngine engine = new Loader().load("npc/scroll_generator.js");
        assertNotNull(engine, "scroll_generator.js evaluates");
        assertEquals(6, ((Number) engine.eval("jobWeaponRestricted.length")).intValue(),
                "beginner, warrior, magician, bowman, thief, pirate");
        assertTrue(((Number) engine.eval("tier2Scrolls.length")).intValue() > 0);
        assertTrue(((Number) engine.eval("tier3Scrolls.length")).intValue() > 0);
        String flat = (String) engine.eval("[jobWeaponRestricted, aranWeaponRestricted, tier2Scrolls, tier3Scrolls].toString()");
        for (String id : flat.split(",")) {
            int v = Integer.parseInt(id.trim());
            assertTrue(v / 10000 == 204 && v % 100 == 0, v + " is a scroll family base (204xx00)");
        }
    }
}
