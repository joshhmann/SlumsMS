package net.server.channel.handlers;

import client.Client;
import config.YamlConfig;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OpenFamilyHandlerTest {

    private final boolean familyWas = YamlConfig.config.server.USE_FAMILY_SYSTEM;

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = familyWas;
    }

    @Test
    void theFamilyButtonSaysComingSoonWhileTheSystemIsOff() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false;
        Client c = mock(Client.class);
        InPacket p = mock(InPacket.class);

        new OpenFamilyHandler().handlePacket(p, c);
        new OpenFamilyPedigreeHandler().handlePacket(p, c);
        new FamilyAddHandler().handlePacket(p, c);

        verify(c, times(6)).sendPacket(any(Packet.class)); // the notice + enableActions each
        verify(c, never()).getPlayer();
        verifyNoInteractions(p);
    }

    @Test
    void nothingIsShownWhenTheSystemIsOn() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = true;
        Client c = mock(Client.class);
        assertTrue(OpenFamilyHandler.enabledOrExplain(c));
        verifyNoInteractions(c);
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false;
        assertFalse(OpenFamilyHandler.enabledOrExplain(c));
    }
}
