package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0 (ticket 102): the Pink Bean exit NPC (2141002) threw without an event instance and paid its extras loosely. */
class PinkBeanExitNpcTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class Chr {
        public int getId() {
            return 42;
        }
    }

    public static final class Eim {
        public boolean cleared;
        public boolean eventRewardWorks = true;
        public int eventRewardCalls;
        public final Map<String, String> props = new HashMap<>();

        public boolean isEventCleared() {
            return cleared;
        }

        public boolean giveEventReward(Object chr, int level) {
            eventRewardCalls++;
            return eventRewardWorks;
        }

        public String getProperty(String k) {
            return props.get(k);
        }

        public void setProperty(String k, Object v) {
            props.put(k, String.valueOf(v));
        }
    }

    public static final class Cm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public Eim eim;

        public Eim getEventInstance() {
            return eim;
        }

        public Chr getPlayer() {
            return new Chr();
        }

        public void warp(int m) {
            warpedTo = m;
        }
    }

    private static Cm talk(Cm cm, int steps) throws Exception {
        ScriptEngine engine = new Loader().load("npc/2141002.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        for (int i = 1; i < steps; i++) {
            npc.invokeFunction("action", 1, 0, 0);
        }
        return cm;
    }

    @Test
    void withNoInstanceTheWayOutStillWorksAndPaysNothing() throws Exception {
        Cm cm = talk(new Cm(), 2);
        assertEquals(270050000, cm.warpedTo);
        assertTrue(cm.disposed);
        assertTrue(cm.inventory.isEmpty());
    }

    @Test
    void leavingBeforeTheClearPaysNothing() throws Exception {
        Cm cm = new Cm();
        cm.eim = new Eim();
        talk(cm, 2);
        assertEquals(270050000, cm.warpedTo);
        assertTrue(cm.inventory.isEmpty());
        assertEquals(0, cm.eim.eventRewardCalls);
    }

    @Test
    void aClearPaysOnceAndOnlyWithRoom() throws Exception {
        Cm cm = new Cm();
        cm.eim = new Eim();
        cm.eim.cleared = true;
        cm.roomForEverything = false;
        talk(cm, 2);
        assertTrue(cm.inventory.isEmpty(), "no room, no extras");
        assertEquals(0, cm.eim.eventRewardCalls, "and the event reward is not spent either");
        assertNull(cm.warpedTo);

        cm = new Cm();
        cm.eim = new Eim();
        cm.eim.cleared = true;
        talk(cm, 2);
        assertEquals(1000, cm.count(2000005));
        assertEquals(270050000, cm.warpedTo);

        Cm again = new Cm();
        again.eim = cm.eim;
        talk(again, 2);
        assertTrue(again.inventory.isEmpty(), "the same player cannot claim twice in one run");
        assertEquals(1, cm.eim.eventRewardCalls);
    }

    @Test
    void aFailedEventRewardGivesNoExtras() throws Exception {
        Cm cm = new Cm();
        cm.eim = new Eim();
        cm.eim.cleared = true;
        cm.eim.eventRewardWorks = false;
        talk(cm, 2);
        assertTrue(cm.inventory.isEmpty());
    }
}
