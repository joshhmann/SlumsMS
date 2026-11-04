package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Holiday PQ 1-3 had no stage-3 exit portal script (08_xmas_st.js), and Elnath PQ's NPC 2020008 only started the
 * event behind quest 6192, which no script can start. Both run in the server's own Graal setup against fakes.
 */
class HolidayElnathPqScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakePi {
        public final int mapId;
        public int warpedTo = -1;

        FakePi(int mapId) {
            this.mapId = mapId;
        }

        public void playPortalSound() {
        }

        public int getMapId() {
            return mapId;
        }

        public void warp(int map, int portal) {
            warpedTo = map;
        }
    }

    @Test
    void holidayExitPortalWarpsEachDifficultyToItsClearMap() throws Exception {
        ScriptEngine engine = new Loader().load("portal/08_xmas_st.js");
        assertNotNull(engine, "portal/08_xmas_st.js exists and evaluates");
        for (int[] m : new int[][] {{889100001, 889100002}, {889100011, 889100012}, {889100021, 889100022}}) {
            FakePi pi = new FakePi(m[0]);
            engine.put("pi", pi);
            assertEquals(Boolean.TRUE, ((Invocable) engine).invokeFunction("enter", pi));
            assertEquals(m[1], pi.warpedTo);
        }
    }

    public static final class FakeMonster {
    }

    public static final class FakeMap {
        public final boolean grown;

        FakeMap(boolean grown) {
            this.grown = grown;
        }

        public FakeMonster getMonsterById(int id) {
            return grown && id == 9400326 ? new FakeMonster() : null;
        }
    }

    public static final class FakeEim {
        public Object getInstanceMap(int id) {
            return new Object();
        }

        public int getIntProperty(String k) {
            return k.equals("level") ? 1 : 0;
        }

        public boolean isEventCleared() {
            return false;
        }
    }

    public static final class SnowCm {
        private final FakeMap map;
        public String text = "";
        public boolean disposed;

        SnowCm(boolean grown) {
            map = new FakeMap(grown);
        }

        public int getMapId() {
            return 889100001;
        }

        public Object getEventInstance() {
            return new FakeEim();
        }

        public FakeMap getMap() {
            return map;
        }

        public void sendNext(String t) {
            text = t;
        }

        public void dispose() {
            disposed = true;
        }
    }

    @Test
    void holidayGateWaitsForTheGrownSnowmanThenLetsScroogeIn() throws Exception {
        for (boolean grown : new boolean[] {false, true}) {
            ScriptEngine engine = new Loader().load("npc/9105004.js");
            assertNotNull(engine);
            SnowCm cm = new SnowCm(grown);
            engine.put("cm", cm);
            ((Invocable) engine).invokeFunction("start");
            if (grown) {
                assertTrue(cm.text.startsWith("Awesome"), cm.text);
                assertFalse(cm.disposed);
            } else {
                assertTrue(cm.text.startsWith("Please, defeat"), cm.text);
                assertTrue(cm.disposed);
            }
        }
    }

    public static final class FakeEm {
        public int started;

        public Object getEligibleParty(Object party) {
            return new java.util.ArrayList<>(java.util.List.of("a"));
        }

        public boolean startInstance(Object party, Object map, int level) {
            started++;
            return true;
        }
    }

    public static final class ElnathCm {
        public final FakeEm em = new FakeEm();
        public String ok = "";
        public boolean disposed;

        public Object getParty() {
            return new Object();
        }

        public boolean isQuestStarted(int q) {
            return false;
        }

        public FakeEm getEventManager(String n) {
            return em;
        }

        public Object getPlayer() {
            return new FakePlayer();
        }

        public Object getJobId() {
            return 110;
        }

        public void sendOk(String t) {
            ok = t;
        }

        public void sendNext(String t) {
            ok = t;
        }

        public void dispose() {
            disposed = true;
        }
    }

    public static final class FakePlayer {
        public int getLevel() {
            return 85;
        }

        public Object getMap() {
            return new Object();
        }
    }

    @Test
    void elnathNpcStartsTheEventForAPartyWithoutQuest6192() throws Exception {
        ScriptEngine engine = new Loader().load("npc/2020008.js");
        assertNotNull(engine);
        ElnathCm cm = new ElnathCm();
        engine.put("cm", cm);
        ((Invocable) engine).invokeFunction("start");
        assertEquals(1, cm.em.started, cm.ok);
    }
}
