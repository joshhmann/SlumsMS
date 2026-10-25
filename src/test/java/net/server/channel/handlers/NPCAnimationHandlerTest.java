package net.server.channel.handlers;

import client.Client;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 1.5.1 TEST log: an NPC animation packet that arrived after its character had left (Chr ?, account null) threw
 * a NullPointerException on c.getPlayer().isChangingMaps(). Stock Cosmic has the same line.
 */
class NPCAnimationHandlerTest {

    @Test
    void aPacketWithNoCharacterIsDropped() {
        Client c = mock(Client.class); // getPlayer() -> null
        InPacket p = mock(InPacket.class);

        assertDoesNotThrow(() -> new NPCAnimationHandler().handlePacket(p, c));
        verify(c, never()).sendPacket(any());
    }
}
