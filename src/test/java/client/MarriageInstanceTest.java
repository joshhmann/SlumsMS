package client;

import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;

/** Ticket 188 (F6): getMarriageInstance threw ClassCastException inside any non-marriage event instance. */
class MarriageInstanceTest {
    @Test
    void aNonMarriageEventInstanceIsNotAMarriage() {
        Character chr = mock(Character.class, CALLS_REAL_METHODS);
        doReturn(mock(EventInstanceManager.class)).when(chr).getEventInstance();
        assertDoesNotThrow(chr::getMarriageInstance);
        assertNull(chr.getMarriageInstance());
    }

    @Test
    void noEventInstanceIsNoMarriage() {
        Character chr = mock(Character.class, CALLS_REAL_METHODS);
        doReturn(null).when(chr).getEventInstance();
        assertNull(chr.getMarriageInstance());
    }
}
