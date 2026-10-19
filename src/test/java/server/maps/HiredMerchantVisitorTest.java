package server.maps;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.Array;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HiredMerchantVisitorTest {
    @Test
    void visitorWrappersConvertToCharacterSnapshotWithoutMovingEmptySlots() throws Exception {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getClient()).thenReturn(mock(Client.class));
        HiredMerchant shop = new HiredMerchant(owner, "test", 5030000);
        assertArrayEquals(new Character[3], shop.getVisitorCharacters());

        var visitorsField = HiredMerchant.class.getDeclaredField("visitors");
        visitorsField.setAccessible(true);
        Object visitors = visitorsField.get(shop);
        var constructor = visitors.getClass().getComponentType().getDeclaredConstructor(Character.class, Instant.class);
        constructor.setAccessible(true);
        Character first = mock(Character.class);
        Character third = mock(Character.class);
        Array.set(visitors, 0, constructor.newInstance(first, Instant.now()));
        Array.set(visitors, 2, constructor.newInstance(third, Instant.now()));

        Character[] snapshot = assertDoesNotThrow(shop::getVisitorCharacters);
        assertArrayEquals(new Character[]{first, null, third}, snapshot);
        snapshot[0] = null;
        assertSame(first, shop.getVisitorCharacters()[0]);
    }
}
