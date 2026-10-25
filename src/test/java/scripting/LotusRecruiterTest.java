package scripting;

import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionType;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

/** 1.5.2: Lotus (2491006) asked for the NormalLotusBattle event, which does not exist, and threw on every click. */
class LotusRecruiterTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeEm {
        public String getProperty(String k) {
            return "requirements";
        }
    }

    public static final class FakePlayer {
        public int getLevel() {
            return 200;
        }
    }

    public static final class LotusCm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public final FakePlayer player = new FakePlayer();
        public String eventAsked;
        public ExpeditionType created;
        public boolean hardExists = true;

        public FakePlayer getPlayer() {
            return player;
        }

        public Object getExpedition(ExpeditionType type) {
            return null;
        }

        public Object getEventManager(String name) {
            eventAsked = name;
            return hardExists && name.equals("HardLotusBattle") ? new FakeEm() : null;
        }

        public int createExpedition(ExpeditionType type) {
            created = type;
            return 0;
        }
    }

    private static Invocable load(LotusCm cm) {
        ScriptEngine engine = new Loader().load("npc/2491006.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    @Test
    void aLeaderCanCreateTheHardLotusExpedition() throws Exception {
        LotusCm cm = new LotusCm();
        Invocable npc = load(cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);
        assertEquals("HardLotusBattle", cm.eventAsked);
        assertTrue(cm.simple.contains("Hard Lotus"), cm.simple);
        npc.invokeFunction("action", 1, 0, 1);
        assertEquals(ExpeditionType.HARDLOTUS, cm.created);
        assertTrue(cm.ok.contains("has been created"), cm.ok);
    }

    @Test
    void aMissingEventIsReportedNotThrown() throws Exception {
        LotusCm cm = new LotusCm();
        cm.hardExists = false;
        Invocable npc = load(cm);
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);
        assertNotNull(cm.ok);
        assertTrue(cm.disposed);
    }
}
