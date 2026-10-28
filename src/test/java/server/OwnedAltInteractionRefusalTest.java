package server;

import client.Character;
import net.server.coordinator.world.InviteCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.companions.OwnedAltService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 1.6.0.4 (L2): an owned alt is headless; a trade invite to it is refused instead of sitting pending on it. */
class OwnedAltInteractionRefusalTest {
    @Test void aTradeInviteToAnOwnedAltIsRefusedWithALine() {
        Character owner = mock(Character.class), stranger = mock(Character.class), alt = mock(Character.class);
        when(owner.getId()).thenReturn(1); when(stranger.getId()).thenReturn(5);
        when(alt.isOwnedAlt()).thenReturn(true); when(alt.getOwnedAltOwner()).thenReturn(owner); when(alt.getName()).thenReturn("Second");
        try (MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class)) {
            Trade.inviteTrade(stranger, alt);
            verify(stranger).message("Second is an alt traveling with its owner and can't trade.");
            Trade.inviteTrade(owner, alt);
            verify(owner).message("Second is your alt and can't trade while it travels with you. Use @dismiss Second and log in on it.");
            invites.verifyNoInteractions();
        }
        verify(alt, never()).setTrade(any());
    }

    @Test void onlyOwnedAltsAreRefused() {
        Character someone = mock(Character.class);
        assertNull(OwnedAltService.interactionRefusal(mock(Character.class), someone, "trade"));
        assertNull(OwnedAltService.interactionRefusal(mock(Character.class), null, "trade"));
    }
}
