package soloMapling.achievements;

import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.1 promise audit: the clear goals key on the event script's name ("ZakumBattle", "KerningPQ"), but the instance
 * a script makes is named "Zakum1", "Kerning3"... The clear must reach the goals through the real hook
 * (EventInstanceManager.setEventCleared), for a named instance, not a hand-fed event.
 */
class EventClearHookTest {

    @AfterEach
    void tearDown() {
        Achievements.setEnabledForTest(null);
        Achievements.useForTest(null);
    }

    private static EventInstanceManager instance(String script, String instanceName, List<Character> players) {
        EventManager em = mock(EventManager.class);
        when(em.getName()).thenReturn(script);
        World world = mock(World.class);
        Channel ch = mock(Channel.class);
        when(em.getWorldServer()).thenReturn(world);
        when(em.getChannelServer()).thenReturn(ch);
        EventInstanceManager eim = spy(new EventInstanceManager(em, instanceName));
        doReturn(players).when(eim).getPlayers();
        return eim;
    }

    @Test
    void aNamedInstanceClearEarnsItsScriptsGoal() throws Exception {
        Achievements.setEnabledForTest(true);
        MemoryStore store = new MemoryStore();
        FakeHands hands = new FakeHands();
        AchievementService s = new AchievementService(GoalCatalogTest.shipped(), store, hands, () -> 5L, Runnable::run);
        Achievements.useForTest(s);
        String[][] cases = {
                {"KerningPQ", "Kerning3", "pq-kerning"},
                {"ZakumBattle", "Zakum1", "clear-zakum"},
                {"LudiPQ", "Ludi2", "pq-ludibrium"},
                {"PiratePQ", "Pirate1", "pq-pirate"},
        };
        int id = 4_200;
        for (String[] c : cases) {
            Character chr = mock(Character.class);
            when(chr.getId()).thenReturn(++id);
            EventInstanceManager eim = instance(c[0], c[1], List.of(chr));
            assertEquals(c[0], eim.scriptName());
            eim.setEventCleared();
            assertTrue(eim.isEventCleared());
            var row = store.load(id).get(c[2]);
            assertNotNull(row, c[1] + " (" + c[0] + ") must reach goal " + c[2] + "; rows " + store.load(id));
            assertTrue(row.earnedAt() > 0, c[2] + " earned");
        }
    }
}
