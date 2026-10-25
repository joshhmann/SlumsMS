package soloMapling.qa;

import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import scripting.event.EventInstanceManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * vb-3 (1.5.2 auto-QA): KerningPQ.js's setup needs the level Cloto starts it with. The QA started it through the
 * overload that passes none, and setup threw on eim.setProperty("level", null), so the instance never started.
 */
class KpqStartLevelTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The event manager's one call setup makes. */
    public static final class FakeEm {
        final EventInstanceManager eim = mock(EventInstanceManager.class, RETURNS_DEEP_STUBS);

        public EventInstanceManager newInstance(String name) {
            return eim;
        }
    }

    private static Invocable kpq(FakeEm em) {
        ScriptEngine engine = new Loader().load("event/KerningPQ.js");
        assertNotNull(engine, "KerningPQ.js evaluates");
        engine.put("em", em);
        return (Invocable) engine;
    }

    @Test
    void setupWithTheQaLevelMakesAnInstance() throws Exception {
        FakeEm em = new FakeEm();
        assertSame(em.eim, kpq(em).invokeFunction("setup", GameQaWorld.KPQ_LEVEL, 0));
        verify(em.eim).setProperty("level", GameQaWorld.KPQ_LEVEL);
    }

    @Test
    void setupWithoutALevelThrowsAsOnTest() {
        FakeEm em = new FakeEm();
        assertThrows(ScriptException.class, () -> kpq(em).invokeFunction("setup", (Object) null));
    }
}
