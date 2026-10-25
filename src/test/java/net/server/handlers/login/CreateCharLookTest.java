package net.server.handlers.login;

import client.Client;
import constants.id.ItemId;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 handler sweep: the creation packet's hair colour is added to the hair id unchecked, so hair 30000 with colour
 * 3000 made a character wearing hair 33000; skin was stored as sent.
 */
class CreateCharLookTest {

    private static InPacket creation(int hairColor, int skin) {
        InPacket p = mock(InPacket.class);
        when(p.readString()).thenReturn("Lookie");
        when(p.readInt()).thenReturn(1 /* adventurer */, ItemId.MOTIVATED_LOOK_M, ItemId.BLACK_TOBEN, hairColor, skin,
                ItemId.WHITE_UNDERSHIRT, ItemId.BLUE_JEAN_SHORTS, ItemId.RED_RUBBER_BOOTS, ItemId.SWORD);
        when(p.readByte()).thenReturn((byte) 0);
        return p;
    }

    @Test
    void aHairColourOffsetIntoAnotherHairIsRefused() {
        Client c = mock(Client.class);
        new CreateCharHandler().handlePacket(creation(3000, 0), c);
        verify(c).disconnect(true, false);
    }

    @Test
    void aSkinWithNoDataIsRefused() {
        Client c = mock(Client.class);
        new CreateCharHandler().handlePacket(creation(0, 50), c);
        verify(c).disconnect(true, false);
    }

    @Test
    void theCreationScreensChoicesAreLegal() {
        for (int hairColor : new int[]{0, 7, 3, 2}) {
            for (int skin = 0; skin <= 3; skin++) {
                assertTrue(CreateCharHandler.isLegalLook(hairColor, skin));
            }
        }
        assertFalse(CreateCharHandler.isLegalLook(-1, 0));
        assertFalse(CreateCharHandler.isLegalLook(8, 0));
        assertFalse(CreateCharHandler.isLegalLook(0, 4));
    }
}
