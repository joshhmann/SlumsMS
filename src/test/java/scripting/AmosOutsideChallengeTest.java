package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.1 TEST log: "TypeError: Cannot read property 'getProperty' of null" from Amos (9201044) when a player without
 * an Amorian Challenge instance (a GM who warped to 670010200) talked to him. Both Amos scripts read the event
 * instance without a null check (stock Cosmic too); they now say who they are and close.
 */
class AmosOutsideChallengeTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakePlayer {
        public Object getEventInstance() {
            return null;
        }
    }

    public static final class FakeCm {
        public final FakePlayer player = new FakePlayer();
        public final int mapId;
        public String said;
        public boolean disposed;

        FakeCm(int mapId) {
            this.mapId = mapId;
        }

        public FakePlayer getPlayer() {
            return player;
        }

        public int getMapId() {
            return mapId;
        }

        public void sendOk(String text) {
            said = text;
        }

        public void sendNext(String text) {
            said = text;
        }

        public void dispose() {
            disposed = true;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc/9201044.js", "npc/9201045.js"})
    void talkingToAmosOutsideTheChallengeClosesTheChat(String script) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine, script);
        FakeCm cm = new FakeCm(670010200);
        engine.put("cm", cm);

        ((Invocable) engine).invokeFunction("start");

        assertNotNull(cm.said, "Amos says something instead of throwing");
        assertTrue(cm.disposed, "and the conversation ends");
    }
}
