package scripting.event;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos Zakum (round 11, item 5): {@code scripts/event/ChaosZakumBattle.js} is the chaos expedition - altar
 * {@code 280030001}, level 120 and up, six to thirty members, and the clear is the death of {@code 8800102}, the chaos
 * body's last phase. The normal altar's script is untouched, so its own clear (8800002) is what it was.
 *
 * <p>Runs the shipped file through the server's Graal engine and calls its functions directly; nothing here needs a
 * channel server, because the parts that touch one ({@code clearPQ}, {@code updateGateState}) are stubbed on the
 * {@code eim} the test hands in.
 */
class ChaosZakumBattleTest {

    static final int CHAOS_FAKE = 8800100;
    static final int CHAOS_BODY_LAST = 8800102;
    static final int NORMAL_BODY_LAST = 8800002;

    /** The event instance manager as far as these functions are concerned. */
    public static class Eim {
        final Map<String, String> props = new LinkedHashMap<>();
        final Map<String, Integer> ints = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Object> cleared = new ArrayList<>();

        public void dropMessage(int type, String message) {
        }

        public void setProperty(String key, String value) {
            props.put(key, String.valueOf(value));
        }

        public void setIntProperty(String key, int value) {
            ints.put(key, value);
        }

        public void showClearEffect(int mapId) {
            calls.add("effect:" + mapId);
        }

        public void clearPQ() {
            calls.add("clearPQ");
        }

        public List<Object> getPlayers() {
            return List.of();
        }

        public void setEventCleared(Object type) {
            cleared.add(type);
            calls.add("cleared");
        }

        public void stopEventTimer() {
        }
    }

    public static class Mob {
        final int id;

        Mob(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        public M getMap() {
            return new M();
        }
    }

    public static class M {
        public int getId() {
            return 280030001;
        }
    }

    /** {@code em}: only {@code getChannelServer()} is reached, and only from {@code updateGateState}. */
    public static class Em {
        public Channel getChannelServer() {
            return new Channel();
        }
    }

    public static class Channel {
        public Factory getMapFactory() {
            return new Factory();
        }
    }

    public static class Factory {
        public MapStub getMap(int id) {
            return new MapStub();
        }
    }

    public static class MapStub {
        public Gate getReactorById(int id) {
            return new Gate();
        }
    }

    public static class Gate {
        public void forceHitReactor(int state) {
        }
    }

    private static ScriptEngine engine() throws Exception {
        Path script = Path.of("scripts/event/ChaosZakumBattle.js");
        assertTrue(Files.isRegularFile(script), "scripts/event/ChaosZakumBattle.js is missing");
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostClassLookup", true);
        engine.put("em", new Em());
        engine.eval(Files.readString(script));
        return engine;
    }

    @Test
    void theChaosExpeditionIsAltar280030001AtLevel120() throws Exception {
        ScriptEngine engine = engine();

        assertEquals(280030001, ((Number) engine.get("entryMap")).intValue());
        assertEquals(280030001, ((Number) engine.get("minMapId")).intValue());
        assertEquals(280030001, ((Number) engine.get("maxMapId")).intValue());
        assertEquals(120, ((Number) engine.get("minLevel")).intValue());
        assertEquals(255, ((Number) engine.get("maxLevel")).intValue());
        assertEquals(6, ((Number) engine.get("minPlayers")).intValue());
        assertEquals(30, ((Number) engine.get("maxPlayers")).intValue());
    }

    @Test
    void theChaosBodyLastPhaseIsTheClearAndTheNormalIdIsNot() throws Exception {
        ScriptEngine engine = engine();
        Eim onChaos = new Eim();

        ((Invocable) engine).invokeFunction("monsterKilled", new Mob(CHAOS_BODY_LAST), onChaos);

        assertEquals(1, onChaos.ints.get("defeatedBoss"), "8800102's death is the chaos clear");
        assertTrue(onChaos.calls.contains("clearPQ"), onChaos.calls.toString());
        assertTrue(onChaos.calls.contains("effect:280030001"), "the clear effect is shown on the chaos altar");

        Eim onFake = new Eim();
        ((Invocable) engine).invokeFunction("monsterKilled", new Mob(CHAOS_FAKE), onFake);
        assertFalse(onFake.calls.contains("clearPQ"), "the fake body is never the clear");

        Eim onNormalBody = new Eim();
        ((Invocable) engine).invokeFunction("monsterKilled", new Mob(NORMAL_BODY_LAST), onNormalBody);
        assertFalse(onNormalBody.calls.contains("clearPQ"), "a normal body id does not clear the chaos expedition");
    }

    @Test
    void theClearIsLoggedAsTheChaosExpedition() throws Exception {
        ScriptEngine engine = engine();
        Eim eim = new Eim();

        ((Invocable) engine).invokeFunction("clearPQ", eim);

        assertTrue(eim.calls.contains("cleared"), eim.calls.toString());
        assertEquals("CHAOS_ZAKUM", String.valueOf(eim.cleared.get(0)).substring(
                String.valueOf(eim.cleared.get(0)).lastIndexOf('.') + 1),
                "the boss log entry is looked up from this expedition type");
    }
}
