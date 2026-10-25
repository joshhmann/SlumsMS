package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

/** 1.5.2: Tana (3004006), the way out of five boss rooms, threw on a null instance and never closed after a clear. */
class BossExitTanaTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMap {
        public int getReturnMapId() {
            return 100000000;
        }
    }

    public static final class FakeChar {
        public int getWorld() {
            return 1;
        }

        public FakeMap getMap() {
            return new FakeMap();
        }
    }

    public static final class ExitCm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public PapulatusScriptsTest.FakeEim eim;
        public int map = 280030000;   // Zakum's altar

        public PapulatusScriptsTest.FakeEim getEventInstance() {
            return eim;
        }

        public int getMapId() {
            return map;
        }

        public FakeChar getPlayer() {
            return new FakeChar();
        }

        public void warp(int m) {
            warpedTo = m;
        }
    }

    private static ExitCm leave(ExitCm cm) throws Exception {
        ScriptEngine engine = new Loader().load("npc/3004006.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);
        return cm;
    }

    @Test
    void withNoInstanceTanaLetsYouOut() throws Exception {
        ExitCm cm = leave(new ExitCm());
        assertEquals(211042400, cm.warpedTo);
        assertTrue(cm.disposed);
    }

    @Test
    void anUnknownRoomFallsBackToItsReturnMap() throws Exception {
        ExitCm cm = new ExitCm();
        cm.map = 999;
        leave(cm);
        assertEquals(100000000, cm.warpedTo, "not map 0");
    }

    @Test
    void afterAClearTheTalkIsClosed() throws Exception {
        ExitCm cm = new ExitCm();
        cm.eim = new PapulatusScriptsTest.FakeEim("Zakum1");
        cm.eim.cleared = true;
        cm.eim.setProperty("rewarded", "true");
        leave(cm);
        assertEquals(211042400, cm.warpedTo);
        assertTrue(cm.disposed);
    }
}
