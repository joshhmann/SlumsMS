package soloMapling.autoqa;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.AbstractPlayerInteraction;
import soloMapling.crafting.WeaponCoreExchange;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProbesGearTest {
    String previous;
    @BeforeEach void enable() {
        previous = System.getProperty(WeaponCoreExchange.PROPERTY);
        System.setProperty(WeaponCoreExchange.PROPERTY, "true");
    }
    @AfterEach void restore() {
        if (previous == null) System.clearProperty(WeaponCoreExchange.PROPERTY);
        else System.setProperty(WeaponCoreExchange.PROPERTY, previous);
    }
    static class Run {
        final Map<Integer, Integer> bag = new HashMap<>();
        final AbstractPlayerInteraction cm = mock(AbstractPlayerInteraction.class);
        final Character player = mock(Character.class);
        final Inventory inventory = mock(Inventory.class);
        Run() {
            when(cm.getPlayer()).thenReturn(player);
            when(player.getInventory(InventoryType.ETC)).thenReturn(inventory);
            when(cm.getItemQuantity(anyInt())).thenAnswer(i -> bag.getOrDefault(i.getArgument(0), 0));
            when(inventory.countById(anyInt())).thenAnswer(i -> bag.getOrDefault(i.getArgument(0), 0));
            when(cm.canHold(anyInt(), eq(1))).thenReturn(true);
            doAnswer(i -> { bag.merge(i.getArgument(0), ((Number) i.getArgument(1)).intValue(), Integer::sum); return null; })
                    .when(cm).gainItem(anyInt(), anyShort());
        }
    }
    @Test void roundTripRestoresPreexistingCoresAndLocksTheInventory() {
        Run r = new Run(); r.bag.put(4036544, 4); r.bag.put(4036545, 3);
        assertTrue(ProbesGear.exchange(r.cm).getFirst().startsWith("PASS"));
        assertEquals(4, r.bag.get(4036544)); assertEquals(3, r.bag.get(4036545));
        verify(r.inventory, atLeastOnce()).lockInventory();
        verify(r.inventory, atLeastOnce()).unlockInventory();
    }
    @Test void failedOutputIsDetectedAndTheOriginalInputRestored() {
        Run r = new Run(); r.bag.put(4036544, 4);
        doNothing().when(r.cm).gainItem(eq(4036545), eq((short) 1));
        assertTrue(ProbesGear.exchange(r.cm).getFirst().startsWith("FAIL"));
        assertEquals(4, r.bag.get(4036544)); assertEquals(0, r.bag.getOrDefault(4036545, 0));
    }
    @Test void noRoomDoesNotStageAnything() {
        Run r = new Run(); when(r.cm.canHold(anyInt(), eq(1))).thenReturn(false);
        assertTrue(ProbesGear.exchange(r.cm).getFirst().startsWith("SKIP"));
        verify(r.cm, never()).gainItem(anyInt(), anyShort());
    }
    @Test void checkIsHalfAndMayNotSkipInAReleaseRun() {
        Check c = AutoQaCatalog.find("t-ng-8");
        assertEquals(Check.Kind.HALF, c.kind()); assertFalse(AutoQaRelease.allowList().containsKey(c.id()));
        assertTrue(c.expects().getFirst().describe().contains("forgeCoreExchange"));
    }
    @Test void disabledProbeNamesItsSwitchAndUnknownProbesFallThrough() {
        System.clearProperty(WeaponCoreExchange.PROPERTY);
        assertTrue(ProbesGear.probe("forgeCoreExchange", null).getFirst().contains(WeaponCoreExchange.PROPERTY));
        assertNull(ProbesGear.probe("unknown", null));
    }
}
