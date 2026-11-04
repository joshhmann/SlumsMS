package net.server.channel.handlers;

import client.Character;
import client.Ring;
import org.junit.jupiter.api.Test;
import tools.Pair;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Ticket 188 (F13): a failed ring insert must fail the wedding cleanly and set no marriage state. */
class RingCreateFailureTest {
    @Test
    void aFailedRingInsertSetsNoMarriageState() {
        Character a = mock(Character.class);
        Character b = mock(Character.class);
        try (var rings = mockStatic(Ring.class)) {
            rings.when(() -> Ring.createRing(anyInt(), any(), any())).thenReturn(new Pair<>(-1, -1));
            assertFalse(RingActionHandler.giveMarriageRings(a, b, 1112803));
        }
        verify(a, never()).addMarriageRing(any());
        verify(b, never()).addMarriageRing(any());
    }
}
