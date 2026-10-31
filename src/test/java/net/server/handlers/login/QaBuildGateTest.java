package net.server.handlers.login;

import client.Client;
import config.ServerConfig;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ticket 170: the QA client build (caps bit 31, kCapQaBuild, release "x.y.z-qa") is refused at login unless the server
 * runs with -Dslums.client.allowQa=true (TEST and RC only). Live sets neither, so it refuses; the gate is on even when
 * MIN_CLIENT_BUILD is off, and a GM account gets no bypass.
 */
class QaBuildGateTest {

    private static Client client(int caps, int build, String release) {
        Client c = mock(Client.class);
        when(c.getRemoteAddress()).thenReturn("192.168.0.186");
        when(c.getClientBuild()).thenReturn(build);
        when(c.getClientRelease()).thenReturn(release);
        when(c.getClientCaps()).thenReturn(caps);
        when(c.getAccID()).thenReturn(5);
        return c;
    }

    private static ServerConfig config(int minimum, boolean bypass) {
        ServerConfig config = new ServerConfig();
        config.MIN_CLIENT_BUILD = minimum;
        config.MIN_CLIENT_BUILD_GM_BYPASS = bypass;
        return config;
    }

    @Test
    void theBitIsThirtyOneAndTheSwitchName() {
        assertEquals(0x80000000, ClientVersionGate.CAP_QA_BUILD);
        assertEquals("slums.client.allowQa", ClientVersionGate.ALLOW_QA);
        assertFalse(Boolean.getBoolean(ClientVersionGate.ALLOW_QA), "off by default: live refuses");
    }

    @Test
    void aQaBuildIsRefusedByDefaultEvenWithTheBuildGateOffAndForAGm() {
        Client qa = client(ClientVersionGate.CAP_QA_BUILD | 1, 10700, "1.6.2-qa");
        assertTrue(ClientVersionGate.refuseIfOutdated(qa, "aqclient", config(0, false), acc -> 6));
        verify(qa, times(2)).sendPacket(any(Packet.class));   // the popup, then the silent login failure
        Client gm = client(ClientVersionGate.CAP_QA_BUILD, 10700, "1.6.2-qa");
        assertTrue(ClientVersionGate.refuseIfOutdated(gm, "gm", config(10600, true), acc -> 6, false));
    }

    @Test
    void testAndRcAcceptItWithTheSwitch() {
        Client qa = client(ClientVersionGate.CAP_QA_BUILD | 1, 10700, "1.6.2-qa");
        assertFalse(ClientVersionGate.refuseIfOutdated(qa, "aqclient", config(10600, false), acc -> 0, true));
        verify(qa, never()).sendPacket(any(Packet.class));
    }

    @Test
    void anOrdinaryClientIsUnaffected() {
        Client normal = client(1, 10700, "1.6.2");
        assertFalse(ClientVersionGate.refuseIfOutdated(normal, "player", config(10600, false), acc -> 0, false));
        assertFalse(ClientVersionGate.qaBuildRefused(0x7FFFFFFF, false));
        assertTrue(ClientVersionGate.qaBuildRefused(0x80000000, false));
        assertFalse(ClientVersionGate.qaBuildRefused(0x80000000, true));
    }
}
