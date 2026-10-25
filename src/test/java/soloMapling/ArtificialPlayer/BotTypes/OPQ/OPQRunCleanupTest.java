package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import client.Character;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 review of the OPQ bot rework: what one run leaves behind must not outlive it.
 */
class OPQRunCleanupTest {

    /**
     * The stage 2 box index was a strong map keyed by the instance's MapleMap and only cleared on a reset, so every
     * OPQ stage 2 map (and everything on it) stayed in memory after its instance was disposed.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void boxIndexDoesNotKeepADisposedStageMapAlive() throws Exception {
        Field f = OPQOrchestrator.class.getDeclaredField("boxIndexes");
        f.setAccessible(true);
        Map raw = (Map) f.get(OPQOrchestrator.getInstance());

        Object key = new Object();      // stands in for a disposed instance map: only the index refers to it
        WeakReference<Object> ref = new WeakReference<>(key);
        raw.put(key, Map.of(1, 0));
        key = null;

        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(ref.get(), "the box index must not hold the stage 2 map");
    }

    /** handleLoopCheck cleared every per-run field but the announced box, so a second run never said its box line. */
    @Test
    void loopCheckForgetsTheAnnouncedBox() throws Exception {
        OPQBot bot = mock(OPQBot.class, CALLS_REAL_METHODS);
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1_000_000_001);
        when(chr.getName()).thenReturn("OpqBot");
        doReturn(chr).when(bot).getChr();

        Field ctx = OPQBot.class.getDeclaredField("sharedContext");
        ctx.setAccessible(true);
        ctx.set(bot, new OPQSharedContext());
        Field announced = OPQBot.class.getDeclaredField("announcedBoxReactorOid");
        announced.setAccessible(true);
        announced.setInt(bot, 4242);

        Method loopCheck = OPQBot.class.getDeclaredMethod("handleLoopCheck");
        loopCheck.setAccessible(true);
        loopCheck.invoke(bot);

        assertEquals(-1, announced.getInt(bot), "the next run announces its box again");
    }
}
