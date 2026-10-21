package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.2 Cosmic parity: PapulatusBattle drives the three gate reactors at the Papulatus entrance (220080000)
 * again, shut while a fight is on and open once it is cleared or the instance ends. The repack's rewrite
 * (expedition leaderboard, coin reward) had dropped updateGateState.
 */
class PapulatusGateTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeReactor {
        public int state = -1;

        public void forceHitReactor(byte newState) {
            state = newState;
        }
    }

    public static final class FakeMap {
        public final Map<Integer, FakeReactor> reactors = new HashMap<>();

        public FakeReactor getReactorById(int id) {
            return reactors.get(id);
        }
    }

    public static final class FakeMapFactory {
        public final Map<Integer, FakeMap> maps = new HashMap<>();

        public FakeMap getMap(int id) {
            return maps.computeIfAbsent(id, k -> new FakeMap());
        }
    }

    public static final class FakeChannel {
        public final FakeMapFactory factory = new FakeMapFactory();

        public FakeMapFactory getMapFactory() {
            return factory;
        }
    }

    public static final class FakeEm {
        public final FakeChannel channel = new FakeChannel();

        public FakeChannel getChannelServer() {
            return channel;
        }
    }

    public static final class FakeEim {
        public boolean cleared;
        public Object clearedAs;

        public boolean isEventCleared() {
            return cleared;
        }

        public void stopEventTimer() {
        }

        public void setEventCleared(Object expedition) {
            cleared = true;
            clearedAs = expedition;
        }
    }

    private static Invocable load(FakeEm em) {
        ScriptEngine engine = new Loader().load("event/PapulatusBattle.js");
        assertNotNull(engine);
        engine.put("em", em);
        return (Invocable) engine;
    }

    private static FakeEm emWithGates() {
        FakeEm em = new FakeEm();
        FakeMap entrance = em.channel.factory.getMap(220080000);
        for (int id = 2208001; id <= 2208003; id++) {
            entrance.reactors.put(id, new FakeReactor());
        }
        return em;
    }

    private static void assertGates(FakeEm em, int state) {
        FakeMap entrance = em.channel.factory.getMap(220080000);
        for (int id = 2208001; id <= 2208003; id++) {
            assertEquals(state, entrance.reactors.get(id).state, "gate " + id);
        }
    }

    @Test
    void theGatesShutWhenAFightStarts() throws Exception {
        FakeEm em = emWithGates();
        load(em).invokeFunction("afterSetup", new FakeEim());

        assertGates(em, 1);
    }

    @Test
    void theGatesOpenWhenPapulatusIsCleared() throws Exception {
        FakeEm em = emWithGates();
        Invocable battle = load(em);
        FakeEim eim = new FakeEim();
        battle.invokeFunction("afterSetup", eim);
        battle.invokeFunction("clearPQ", eim);

        assertTrue(eim.cleared, "still records the expedition clear (leaderboard)");
        assertEquals("PAPULATUS", String.valueOf(eim.clearedAs));
        assertGates(em, 0);
    }

    @Test
    void theGatesOpenWhenAnUnclearedInstanceIsDisposed() throws Exception {
        FakeEm em = emWithGates();
        Invocable battle = load(em);
        FakeEim eim = new FakeEim();
        battle.invokeFunction("afterSetup", eim);
        battle.invokeFunction("dispose", eim);

        assertGates(em, 0);
    }

    @Test
    void aMapWithoutTheGatesDoesNotBreakSetup() throws Exception {
        FakeEm em = new FakeEm();
        load(em).invokeFunction("afterSetup", new FakeEim());
    }
}
