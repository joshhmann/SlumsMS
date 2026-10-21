package net.server.channel.handlers;

import client.Client;
import constants.id.ItemId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapleLifeSlotTest {
    final Client client = mock(Client.class);

    @Test void failedCreationTakesTheSlotBack() {
        when(client.gainCharacterSlot()).thenReturn(true);
        assertEquals(-1, UseCashItemHandler.createWithMapleLife(client, ItemId.MAPLE_LIFE_B, () -> -1));
        verify(client).gainCharacterSlot();
        verify(client).loseCharacterSlot();
    }

    @Test void retriesWithBadNamesCannotFarmSlots() {
        AtomicInteger slots = new AtomicInteger(3);
        when(client.gainCharacterSlot()).thenAnswer(x -> { slots.incrementAndGet(); return true; });
        when(client.loseCharacterSlot()).thenAnswer(x -> { slots.decrementAndGet(); return true; });
        for (int i = 0; i < 5; i++) {
            UseCashItemHandler.createWithMapleLife(client, ItemId.MAPLE_LIFE_B, () -> -1);
        }
        assertEquals(3, slots.get());
        assertEquals(0, UseCashItemHandler.createWithMapleLife(client, ItemId.MAPLE_LIFE_B, () -> 0));
        assertEquals(4, slots.get());
    }

    @Test void anExceptionDuringCreationAlsoTakesTheSlotBack() {
        when(client.gainCharacterSlot()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> UseCashItemHandler.createWithMapleLife(
                client, ItemId.MAPLE_LIFE_B, () -> { throw new IllegalStateException("db down"); }));
        verify(client).loseCharacterSlot();
    }

    @Test void noSlotLeftMeansNoCreation() {
        when(client.gainCharacterSlot()).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(UseCashItemHandler.MAPLE_LIFE_NO_SLOT,
                UseCashItemHandler.createWithMapleLife(client, ItemId.MAPLE_LIFE_B, calls::incrementAndGet));
        assertEquals(0, calls.get());
        verify(client, never()).loseCharacterSlot();
    }

    @Test void cardADoesNotTouchSlots() {
        assertEquals(-1, UseCashItemHandler.createWithMapleLife(client, 5431000, () -> -1));
        verify(client, never()).gainCharacterSlot();
        verify(client, never()).loseCharacterSlot();
    }
}
