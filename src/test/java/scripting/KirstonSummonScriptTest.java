package scripting;

import client.Client;
import client.Character;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import server.maps.MapleMap;
import server.maps.Reactor;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: the Temple of Time summon (scripts/npc/2141000.js) makes Kirston vanish and start the boss reactor on
 * 270050100, which it did with cm.removeNpc and cm.forceStartReactor - neither defined anywhere in the scripting
 * package, so accepting the dialog died on the first call and the summon never happened.
 *
 * <p>The script runs against the real {@link NPCConversationManager}; only the player and the map are mocks, so the
 * two calls have to be real map operations, not stubs that would pass either way.
 */
class KirstonSummonScriptTest {
    private static final int TEMPLE_OF_TIME = 270050100;
    private static final int KIRSTON = 2141000;
    private static final int BOSS_REACTOR = 2709000;

    private static final class KirstonCm extends NPCConversationManager {
        final Client client;
        final Character player = mock(Character.class);
        boolean disposed;

        KirstonCm() {
            this(mock(Client.class));
        }

        KirstonCm(Client client) {
            super(client, KIRSTON, "2141000");
            this.client = client;
        }

        @Override
        public Character getPlayer() {
            return player;
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable load(KirstonCm cm) {
        ScriptEngine engine = new Loader().load("npc/2141000.js");
        assertNotNull(engine, "scripts/npc/2141000.js did not evaluate");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    private static MapleMap templeWith(KirstonCm cm, Reactor reactor) {
        MapleMap map = mock(MapleMap.class);
        when(cm.player.getWarpMap(TEMPLE_OF_TIME)).thenReturn(map);
        when(map.containsNPC(KIRSTON)).thenReturn(true);
        when(map.getReactorById(BOSS_REACTOR)).thenReturn(reactor);
        return map;
    }

    @Test
    void acceptingTheSummonRemovesKirstonAndStartsTheBossReactor() throws Exception {
        KirstonCm cm = new KirstonCm();
        Reactor boss = mock(Reactor.class);
        MapleMap map = templeWith(cm, boss);
        Invocable script = load(cm);

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);

        verify(map).destroyNPC(KIRSTON);
        verify(boss).hitReactor(cm.client);
        assertTrue(cm.disposed);
    }

    @Test
    void refusingTheSummonTouchesNothing() throws Exception {
        KirstonCm cm = new KirstonCm();
        Reactor boss = mock(Reactor.class);
        MapleMap map = templeWith(cm, boss);
        Invocable script = load(cm);

        script.invokeFunction("start");
        script.invokeFunction("action", 0, 0, 0);

        verify(map, never()).destroyNPC(anyInt());
        verify(boss, never()).hitReactor(any());
        assertTrue(cm.disposed);
    }

    @Test
    void aReactorThatIsNotOnTheMapStillLetsKirstonGo() throws Exception {
        KirstonCm cm = new KirstonCm();
        MapleMap map = templeWith(cm, null);
        Invocable script = load(cm);

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);

        verify(map).destroyNPC(KIRSTON);
        assertTrue(cm.disposed, "the dialog still has to close");
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
