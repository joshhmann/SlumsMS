package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 1.6.2 b096: every expedition-started boss event got {@code setup(channel)} from
 * {@code EventManager.startInstance(Expedition)} but read a second {@code lobbyid} argument, so the instance was named
 * "VonLeonundefined" (in the clear and leaderboard logs) and its map was scaled by the channel number.
 */
class ExpeditionSetupNameTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMap {
        public Integer resetLevel;

        public void resetPQ(int level) {
            resetLevel = level;
        }
    }

    public static final class FakeEim {
        public final String name;
        public final Map<String, String> props = new HashMap<>();
        public final FakeMap map = new FakeMap();

        public FakeEim(String name) {
            this.name = name;
        }

        public void setProperty(String k, Object v) {
            props.put(k, String.valueOf(v));
        }

        public void setIntProperty(String k, Object v) {
            props.put(k, String.valueOf(v));
        }

        public String getProperty(String k) {
            return props.get(k);
        }

        public FakeMap getInstanceMap(int id) {
            return map;
        }

        public void startEventTimer(long ms) {
        }

        public void schedule(String fn, long ms) {
        }

        public void setEventRewards(Object lvl, Object items, Object qty) {
        }

        public void setEventRewards(Object items, Object qty) {
        }

        public void setEventClearStageExp(Object exp) {
        }

        public void setExclusiveItems(Object items) {
        }
    }

    public static final class FakeEm {
        public FakeEim made;

        public FakeEim newInstance(String name) {
            made = new FakeEim(name);
            return made;
        }

        public void setProperty(String k, Object v) {
        }
    }

    private static FakeEim setup(String script, Object... args) throws Exception {
        ScriptEngine engine = new Loader().load("event/" + script + ".js");
        FakeEm em = new FakeEm();
        engine.put("em", em);
        ((Invocable) engine).invokeFunction("setup", args);
        return em.made;
    }

    @ParameterizedTest
    @CsvSource({
            "VonLeonBattle, VonLeon",
            "CygnusBattle, Cygnus",
            "MagnusBattle, Magnus",
            "LucidBattle, Hard Lucid",
            "NLucidBattle, Normal Lucid",
            "WillBattle, Hard Will",
            "NWillBattle, Normal Will",
            "VerusBattle, Hard Verus Hilla",
            "NVerusBattle, Normal Verus Hilla",
            "DarknellBattle, Hard Darknell",
            "NDarknellBattle, Normal Darknell",
            "KrexelBattle, Krexel",
            "HardLotusBattle, Hard Lotus",
    })
    void anExpeditionRunIsNamedForItsChannel(String script, String prefix) throws Exception {
        FakeEim eim = setup(script, 3);   // EventManager.startInstance(Expedition): the channel only
        assertFalse(eim.name.contains("undefined"), eim.name);
        assertEquals(prefix + "3", eim.name);
        if (eim.map.resetLevel != null) {
            assertEquals(1, eim.map.resetLevel, "not scaled by the channel number");
        }
    }

    @ParameterizedTest
    @CsvSource({"VonLeonBattle, VonLeon", "MagnusBattle, Magnus"})
    void aLobbyRunStillUsesItsLobby(String script, String prefix) throws Exception {
        FakeEim eim = setup(script, 2, 4242);
        assertEquals(prefix + "4242", eim.name);
        assertEquals("2", eim.props.get("level"));
    }
}
