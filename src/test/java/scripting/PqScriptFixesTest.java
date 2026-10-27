package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 PQ research pass: script errors that broke party quests for everyone. HPQ's "Moon Bunny is feeling sick"
 * notice called a bare broadcastMessage (and its hit counter never counted), the HPQ full-moon reactor called
 * MapleMap.showAllMonsters and PacketCreator.showHPQMoon, neither of which exists, OPQ's Minerva reactor and the CWKPQ stirge reactor used an undefined eim
 * (OPQ's 5-minute bonus timer never started), and the stirge reactor called killAllMonsters with an argument.
 */
class PqScriptFixesTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMap {
        public final List<Object> broadcasts = new ArrayList<>();
        public int killAll;
        public String effect;

        public void broadcastMessage(Object packet) {
            broadcasts.add(packet);
        }

        public void startMapEffect(String msg, int itemId, long time) {
            effect = msg;
        }

        public void killAllMonsters() {
            killAll++;
        }
    }

    public static final class FakeMob {
        final FakeMap map = new FakeMap();

        public int getId() {
            return 9300061;
        }

        public FakeMap getMap() {
            return map;
        }
    }

    public static final class FakeEim {
        final Map<String, String> props = new HashMap<>();
        public boolean cleared;
        public int exp;
        public boolean clearEffect;
        public long timer;

        public int getIntProperty(String key) {
            String v = props.get(key);
            return v == null ? 0 : Integer.parseInt(v);
        }

        public void setIntProperty(String key, int value) {
            props.put(key, Integer.toString(value));
        }

        public void setProperty(String key, String value) {
            props.put(key, value);
        }

        public String getProperty(String key) {
            return props.get(key);
        }

        public void clearPQ() {
            cleared = true;
        }

        public void giveEventPlayersExp(int gain) {
            exp += gain;
        }

        public void showClearEffect(boolean b) {
            clearEffect = b;
        }

        public void startEventTimer(long time) {
            timer = time;
        }
    }

    public static final class FakeClient {
        final FakeMap map;

        FakeClient(FakeMap map) {
            this.map = map;
        }

        public FakeMap getMap() {
            return map;
        }
    }

    /** The parts of ReactorActionManager the fixed reactors use. */
    public static final class FakeRm {
        final FakeEim eim = new FakeEim();
        final FakeMap map = new FakeMap();
        public int npc;
        public int mob;
        public final List<String> messages = new ArrayList<>();

        public FakeEim getEventInstance() {
            return eim;
        }

        public FakeMap getMap() {
            return map;
        }

        public FakeClient getClient() {
            return new FakeClient(map);
        }

        public void spawnNpc(int id) {
            npc = id;
        }

        public void spawnMonster(int id, int qty, int x, int y) {
            mob = id;
        }

        public void mapMessage(int type, String msg) {
            messages.add(msg);
        }
    }

    private static Invocable load(String path, String var, Object value) {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " evaluates");
        if (var != null) {
            engine.put(var, value);
        }
        return (Invocable) engine;
    }

    @Test
    void hpqWarnsTheMapWhenTheMoonBunnyKeepsGettingHit() throws Exception {
        Invocable hpq = load("event/HenesysPQ.js", null, null);
        FakeEim eim = new FakeEim();
        FakeMob bunny = new FakeMob();
        for (int i = 0; i < 6; i++) {
            hpq.invokeFunction("friendlyDamaged", eim, bunny);
        }
        assertEquals(1, bunny.map.broadcasts.size(), "the sixth hit warns the map");
        assertEquals(0, eim.getIntProperty("bunnyDamaged"), "the counter resets after the warning");
    }

    @Test
    void hpqFullMoonReactorRunsWithoutError() throws Exception {
        FakeRm rm = new FakeRm();
        load("reactor/9101000.js", "rm", rm).invokeFunction("act");
        assertEquals(9300061, rm.mob);
        assertEquals(1, rm.map.broadcasts.size());
        assertNotNull(rm.map.effect);
    }

    @Test
    void opqMinervaStartsTheBonusTimer() throws Exception {
        FakeRm rm = new FakeRm();
        load("reactor/2006001.js", "rm", rm).invokeFunction("act");
        assertEquals(2013002, rm.npc);
        assertTrue(rm.eim.cleared);
        assertEquals("1", rm.eim.getProperty("statusStg8"));
        assertEquals(3500, rm.eim.exp);
        assertTrue(rm.eim.clearEffect);
        assertEquals(5 * 60000L, rm.eim.timer);
    }

    @Test
    void cwkpqStirgeSwitchClearsTheStirgesOnTheSixthPress() throws Exception {
        FakeRm rm = new FakeRm();
        rm.eim.setIntProperty("glpq_s", 5);
        load("reactor/6109013.js", "rm", rm).invokeFunction("touch");
        assertEquals(1, rm.map.killAll);
        assertEquals(778, rm.eim.getIntProperty("glpq_s"));
        assertEquals(1, rm.messages.size());
    }
}
