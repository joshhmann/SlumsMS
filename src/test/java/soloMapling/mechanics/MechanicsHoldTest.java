package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotSM;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 130: a bot a mechanics run drives is held (no logout, no slow wheel) until the run releases it. */
class MechanicsHoldTest {

    @Test
    void aHeldBotIsCommittedUntilReleased() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1_000_000_913);
        BotSM sm = mock(BotSM.class);
        when(sm.getChr()).thenReturn(chr);
        when(sm.getState()).thenReturn(BotSM.BotState.IDLE);
        assertFalse(BotCommitments.inFlight(sm));
        MechanicsHold.hold(1_000_000_913);
        try {
            assertTrue(MechanicsHold.committed(chr));
            assertTrue(BotCommitments.inFlight(sm));
        } finally {
            MechanicsHold.release(1_000_000_913);
        }
        assertFalse(BotCommitments.inFlight(sm));
        assertFalse(MechanicsHold.committed(null));
    }
}
