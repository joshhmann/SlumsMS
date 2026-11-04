package scripting.reactor;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos Zakum (round 11, item 4): the Eye of Fire reactor {@code 2111001} stands on both altars, and one script has to
 * serve both. Map {@code 280030000} keeps the normal lines exactly as they were; map {@code 280030001} - Chaos Zakum's
 * Altar - spawns the chaos chain instead, and the room monitor, the message and the summon property follow the same
 * branch. This runs the shipped file through the same Graal engine the server uses
 * ({@code scripting/AbstractScriptManager.java:45}) over a stand-in {@code rm}.
 */
class ZakumAltarReactorTest {

    static final int NORMAL_ALTAR = 280030000;
    static final int CHAOS_ALTAR = 280030001;

    /** What the script calls on {@code rm}, recorded in order. */
    public static class Rm {
        final int mapId;
        final List<String> calls = new ArrayList<>();
        final List<Integer> fakes = new ArrayList<>();
        final List<Integer> spawned = new ArrayList<>();
        final Map<String, Object> monitors = new LinkedHashMap<>();
        final Player player = new Player();

        Rm(int mapId) {
            this.mapId = mapId;
        }

        public Player getPlayer() {
            return player;
        }

        public Reactor getReactor() {
            return new Reactor(mapId);
        }

        public void changeMusic(String name) {
            calls.add("music:" + name);
        }

        public void spawnFakeMonster(int id) {
            fakes.add(id);
        }

        public void spawnMonster(int id) {
            spawned.add(id);
        }

        public void createMapMonitor(int mapId, String portal) {
            monitors.put(portal, mapId);
        }

        public void mapMessage(int type, String message) {
            calls.add("message:" + message);
        }
    }

    public static class Player {
        final Eim eim = new Eim();

        public Eim getEventInstance() {
            return eim;
        }
    }

    public static class Eim {
        final Map<String, String> props = new LinkedHashMap<>();

        public void setProperty(String key, String value) {
            props.put(key, String.valueOf(value));
        }
    }

    public static class Reactor {
        final int mapId;

        Reactor(int mapId) {
            this.mapId = mapId;
        }

        public M getMap() {
            return new M(mapId);
        }
    }

    public static class M {
        final int id;

        M(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    private static Rm click(int mapId) throws Exception {
        Path script = Path.of("scripts/reactor/2111001.js");
        assertTrue(Files.isRegularFile(script), "scripts/reactor/2111001.js is missing");
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        Rm rm = new Rm(mapId);
        engine.put("rm", rm);
        engine.eval(Files.readString(script));
        ((Invocable) engine).invokeFunction("act");
        return rm;
    }

    @Test
    void theNormalAltarSpawnsExactlyWhatItAlwaysDid() throws Exception {
        Rm rm = click(NORMAL_ALTAR);

        assertEquals(List.of(8800000), rm.fakes);
        List<Integer> arms = new ArrayList<>();
        for (int id = 8800003; id <= 8800010; id++) {
            arms.add(id);
        }
        assertEquals(arms, rm.spawned);
        assertEquals(Map.of("ps00", NORMAL_ALTAR), rm.monitors);
        assertTrue(rm.calls.contains("message:Zakum is summoned by the force of Eye of Fire."), rm.calls.toString());
        assertEquals("true", rm.player.eim.props.get("summoned"));
        assertEquals("false", rm.player.eim.props.get("canEnter"));
    }

    @Test
    void theChaosAltarSpawnsTheChaosChain() throws Exception {
        Rm rm = click(CHAOS_ALTAR);

        assertEquals(List.of(8800100), rm.fakes, "the chaos fake body, not 8800000");
        List<Integer> arms = new ArrayList<>();
        for (int id = 8800103; id <= 8800110; id++) {
            arms.add(id);
        }
        assertEquals(arms, rm.spawned, "the eight chaos arms, not the normal ones");
        assertEquals(Map.of("ps00", CHAOS_ALTAR), rm.monitors, "the monitor belongs to the altar that was clicked");
        assertTrue(rm.calls.contains("message:Chaos Zakum is summoned by the force of Eye of Fire."),
                rm.calls.toString());
        assertEquals("true", rm.player.eim.props.get("summoned"), "the row's summon gate is the same gate");
        assertEquals("false", rm.player.eim.props.get("canEnter"));
        assertTrue(rm.spawned.stream().noneMatch(id -> id >= 8800003 && id <= 8800010),
                "a chaos altar must never spawn a normal arm: " + rm.spawned);
    }
}
