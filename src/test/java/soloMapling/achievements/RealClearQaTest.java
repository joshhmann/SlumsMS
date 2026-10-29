package soloMapling.achievements;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;

import javax.script.Invocable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Auto-QA t-ac-2's staging (AchievementsQa.realClear): a named "Kerning9xx" instance of the KerningPQ event, the pretend
 * player registered, cleared through EventInstanceManager.clearPQ (the script's clearPQ calls setEventCleared). With the
 * instance-name bug back in, it reports the goal as not earned.
 */
class RealClearQaTest {

    @AfterEach
    void tearDown() {
        Achievements.setEnabledForTest(null);
        Achievements.useForTest(null);
    }

    @Test
    void aNamedKerningInstanceClearedThroughClearPqEarnsTheGoal() throws Exception {
        Achievements.setEnabledForTest(true);
        MemoryStore store = new MemoryStore();
        FakeHands hands = new FakeHands();
        AchievementService s = new AchievementService(GoalCatalogTest.shipped(), store, hands, () -> 5L, Runnable::run);
        Achievements.useForTest(s);

        EventManager em = mock(EventManager.class);
        when(em.getName()).thenReturn(AchievementsQa.CLEAR_SCRIPT);
        when(em.getWorldServer()).thenReturn(mock(World.class));
        when(em.getChannelServer()).thenReturn(mock(Channel.class));
        Invocable iv = mock(Invocable.class);
        when(em.getIv()).thenReturn(iv);
        List<EventInstanceManager> made = new ArrayList<>();
        when(em.newInstance(anyString())).thenAnswer(inv -> {
            EventInstanceManager eim = spy(new EventInstanceManager(em, inv.getArgument(0)));
            doNothing().when(eim).dispose(); // its map/timer teardown needs the running server
            made.add(eim);
            return eim;
        });
        when(iv.invokeFunction(eq("clearPQ"), any())).thenAnswer(inv -> { // scripts/event/KerningPQ.js clearPQ
            ((EventInstanceManager) inv.getArgument(1)).setEventCleared();
            return null;
        });

        Character me = mock(Character.class);
        when(me.getId()).thenReturn(5_150);
        when(me.isLoggedinWorld()).thenReturn(true);
        when(me.getInventory(InventoryType.EQUIP)).thenReturn(mock(Inventory.class));
        when(me.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        Goal goal = s.catalog().get(AchievementsQa.CLEAR_GOAL);
        when(me.getItemQuantity(eq(goal.medal()), anyBoolean())).thenAnswer(inv -> hands.bag.contains(goal.medal()) ? 1 : 0);

        List<String> ev = new ArrayList<>();
        List<String> bad;
        try (var inv = mockStatic(client.inventory.manipulator.InventoryManipulator.class)) {
            inv.when(() -> client.inventory.manipulator.InventoryManipulator.removeById(any(), eq(InventoryType.EQUIP),
                    eq(goal.medal()), anyInt(), anyBoolean(), anyBoolean())).thenAnswer(a -> {
                hands.bag.remove(goal.medal());
                return null;
            });
            bad = AchievementsQa.realClear(s, me, em, 2_000, ev);
        }
        // the cleanup takes the goal back (reset); the medal is the FakeHands bag's, which removeById does not touch
        assertEquals(1, made.size());
        assertTrue(made.get(0).getName().startsWith("Kerning"), made.get(0).getName());
        assertTrue(made.get(0).isEventCleared());
        assertTrue(ev.stream().anyMatch(l -> l.contains("script name KerningPQ")), ev.toString());
        assertTrue(ev.stream().anyMatch(l -> l.contains("pq-kerning row Row[") && l.contains("state=2")), ev.toString());
        assertEquals(List.of(), bad);
        assertNull(s.row(5_150, AchievementsQa.CLEAR_GOAL), "cleaned up");
    }
}
