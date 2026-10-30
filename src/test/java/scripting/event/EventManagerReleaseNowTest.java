package scripting.event;

import net.server.channel.Channel;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 (owner 2026-10-30, bots yield): a bot lobby run that yields frees its KPQ lobby at once, so the player's party
 * starts on the same talk; the lobby delay's own release, running later, must then leave the new "Kerning0" alone.
 */
class EventManagerReleaseNowTest {

    static EventManager kpq() {
        Channel ch = mock(Channel.class);
        when(ch.getWorld()).thenReturn(1);
        when(ch.getId()).thenReturn(1);
        return new EventManager(ch, mock(Invocable.class), "KerningPQ");
    }

    @SuppressWarnings("unchecked")
    static <T> T field(EventManager em, String name) throws Exception {
        Field f = EventManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(em);
    }

    static EventInstanceManager eim(String name, boolean disposed) {
        EventInstanceManager e = mock(EventInstanceManager.class);
        when(e.getName()).thenReturn(name);
        when(e.isEventDisposed()).thenReturn(disposed);
        return e;
    }

    static void hold(EventManager em, EventInstanceManager e, int lobby) throws Exception {
        Map<String, EventInstanceManager> instances = field(em, "instances");
        Map<String, Integer> locks = field(em, "instanceLocks");
        List<Boolean> opened = field(em, "openedLobbys");
        instances.put(e.getName(), e);
        locks.put(e.getName(), lobby);
        opened.set(lobby, true);
    }

    @Test
    void aDisposedInstanceIsFreedAtOnceAndTheLateReleaseSparesTheNextHolder() throws Exception {
        EventManager em = kpq();
        EventInstanceManager old = eim("Kerning0", true);
        hold(em, old, 0);
        assertTrue(em.releaseInstanceNow(old));
        List<Boolean> opened = field(em, "openedLobbys");
        assertFalse(opened.get(0), "the lobby is free for the next party now");
        assertTrue(em.getInstances().isEmpty());

        EventInstanceManager next = eim("Kerning0", false);
        hold(em, next, 0);
        em.releaseInstance("Kerning0", old); // the old instance's lobby-delay release, 10 s later
        assertSame(next, em.getInstances().iterator().next(), "the new Kerning0 stays");
        assertTrue(opened.get(0), "and keeps its lobby");
    }

    @Test
    void aLiveOrForeignInstanceIsNotFreed() throws Exception {
        EventManager em = kpq();
        EventInstanceManager live = eim("Kerning0", false);
        hold(em, live, 0);
        assertFalse(em.releaseInstanceNow(live), "still running");
        assertFalse(em.releaseInstanceNow(eim("Kerning0", true)), "another instance holds the name");
        List<Boolean> opened = field(em, "openedLobbys");
        assertTrue(opened.get(0));
    }

    @Test
    void theLateReleaseStillFreesAnInstanceNobodyReplaced() throws Exception {
        EventManager em = kpq();
        EventInstanceManager old = eim("Kerning0", true);
        hold(em, old, 0);
        em.releaseInstance("Kerning0", old);
        List<Boolean> opened = field(em, "openedLobbys");
        assertFalse(opened.get(0));
        assertTrue(em.getInstances().isEmpty());
    }
}
