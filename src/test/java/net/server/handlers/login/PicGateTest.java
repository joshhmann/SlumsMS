package net.server.handlers.login;

import client.Client;
import config.YamlConfig;
import net.packet.InPacket;
import net.server.Server;
import net.server.coordinator.session.Hwid;
import net.server.coordinator.session.SessionCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * pic-bypass-char-select (S1, 9 Nov 2026): on TEST-B a client sent CHAR_SELECT (0x13, no PIC) for an account with a PIC
 * and the server answered SERVER_IP. The PIC-less selects are now refused exactly where the character list asked for a
 * PIC; where it said "2" (PIC off, or a bypass entry) they select as before; the WITH_PIC path is unchanged; the
 * view-all register no longer overwrites an existing PIC.
 */
class PicGateTest {

    private static final String HOST = "001122334455_1A2B3C4D";   // a well-formed host string (12 hex mac, 8 hex hwid)

    private boolean enablePic;
    private MockedStatic<SessionCoordinator> coordinatorStatic;
    private MockedStatic<Server> serverStatic;
    private SessionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        enablePic = YamlConfig.config.server.ENABLE_PIC;
        coordinator = mock(SessionCoordinator.class);
        // a refused game session ends each allowed handler right after the gate: enough to see it got past it
        when(coordinator.attemptGameSession(any(), anyInt(), any())).thenReturn(
                SessionCoordinator.AntiMulticlientResult.REMOTE_PROCESSING);
        coordinatorStatic = mockStatic(SessionCoordinator.class);
        coordinatorStatic.when(SessionCoordinator::getInstance).thenReturn(coordinator);
        Server server = mock(Server.class);
        when(server.haveCharacterEntry(anyInt(), anyInt())).thenReturn(true);
        serverStatic = mockStatic(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(server);
    }

    @AfterEach
    void tearDown() {
        YamlConfig.config.server.ENABLE_PIC = enablePic;
        coordinatorStatic.close();
        serverStatic.close();
    }

    private static Client client(boolean bypass, String pic) {
        Client c = mock(Client.class);
        when(c.canBypassPic()).thenReturn(bypass);
        when(c.getPic()).thenReturn(pic);
        when(c.getAccID()).thenReturn(150);
        return c;
    }

    private static InPacket selectPacket() {
        InPacket p = mock(InPacket.class);
        when(p.readInt()).thenReturn(208, 0);
        when(p.readString()).thenReturn("00-11-22-33-44-55", HOST, "123456");
        when(p.readByte()).thenReturn((byte) 1);
        return p;
    }

    // --- the plain select (CHAR_SELECT 0x13) ---

    @Test
    void aPlainSelectIsRefusedWhenTheListAskedForAPic() {
        YamlConfig.config.server.ENABLE_PIC = true;
        Client c = client(false, "123456");

        new CharSelectedHandler().handlePacket(selectPacket(), c);

        verify(coordinator).closeSession(c, true);
        verify(coordinator, never()).attemptGameSession(any(), anyInt(), any());
        verify(c, never()).setCharacterOnSessionTransitionState(anyInt());
    }

    @Test
    void aPlainSelectIsRefusedForAnAccountThatMustStillRegisterAPic() {
        YamlConfig.config.server.ENABLE_PIC = true;
        Client c = client(false, "");   // the list said 0: register one

        new CharSelectedHandler().handlePacket(selectPacket(), c);

        verify(coordinator).closeSession(c, true);
        verify(coordinator, never()).attemptGameSession(any(), anyInt(), any());
    }

    @Test
    void aPlainSelectStillWorksWhereTheListSaidNoPic() {
        Client offClient = client(false, "123456");
        YamlConfig.config.server.ENABLE_PIC = false;   // the list said 2: PIC off
        new CharSelectedHandler().handlePacket(selectPacket(), offClient);
        verify(coordinator).attemptGameSession(any(), anyInt(), any());
        verify(coordinator, never()).closeSession(any(), anyBoolean());

        YamlConfig.config.server.ENABLE_PIC = true;
        Client bypassClient = client(true, "123456");  // the list said 2: a live bypass entry for this machine
        new CharSelectedHandler().handlePacket(selectPacket(), bypassClient);
        verify(coordinator, org.mockito.Mockito.times(2)).attemptGameSession(any(), anyInt(), any(Hwid.class));   // both got past the gate
        verify(coordinator, never()).closeSession(bypassClient, true);
    }

    // --- the view-all select (PICK_ALL_CHAR) ---

    @Test
    void aViewAllSelectIsRefusedWhenTheListAskedForAPic() {
        YamlConfig.config.server.ENABLE_PIC = true;
        Client c = client(false, "123456");

        new ViewAllCharSelectedHandler().handlePacket(selectPacket(), c);

        verify(coordinator).closeSession(c, true);
        verify(coordinator, never()).attemptGameSession(any(), anyInt(), any());
    }

    @Test
    void aViewAllSelectStillWorksWhereTheListSaidNoPic() {
        YamlConfig.config.server.ENABLE_PIC = false;
        Client c = client(false, "123456");

        new ViewAllCharSelectedHandler().handlePacket(selectPacket(), c);

        verify(coordinator).attemptGameSession(any(), anyInt(), any());
        verify(coordinator, never()).closeSession(c, true);
    }

    // --- the view-all register (VIEW_ALL_PIC_REGISTER) ---

    @Test
    void theViewAllRegisterNeverOverwritesAnExistingPic() {
        YamlConfig.config.server.ENABLE_PIC = true;
        Client c = client(false, "123456");

        new ViewAllCharRegisterPicHandler().handlePacket(selectPacket(), c);

        verify(c, never()).setPic(anyString());
        verify(coordinator).closeSession(c, true);
        verify(coordinator, never()).attemptGameSession(any(), anyInt(), any());
    }

    @Test
    void theViewAllRegisterStillRegistersAFirstPic() {
        YamlConfig.config.server.ENABLE_PIC = true;
        Client c = client(false, "");

        new ViewAllCharRegisterPicHandler().handlePacket(selectPacket(), c);

        verify(coordinator, never()).closeSession(c, true);
        verify(coordinator).attemptGameSession(any(), anyInt(), any());
    }

    // --- the gate itself, and the WITH_PIC path ---

    @Test
    void theGateMatchesWhatTheCharacterListSends() {
        YamlConfig.config.server.ENABLE_PIC = true;
        assertTrue(PicGate.required(client(false, "123456")), "list sent 1");
        assertTrue(PicGate.required(client(false, "")), "list sent 0");
        assertFalse(PicGate.required(client(true, "123456")), "list sent 2 (bypass)");
        YamlConfig.config.server.ENABLE_PIC = false;
        assertFalse(PicGate.required(client(false, "123456")), "list sent 2 (PIC off)");
    }

    @Test
    void theWithPicSelectIsUnchanged() {
        YamlConfig.config.server.ENABLE_PIC = true;
        Client c = client(false, "123456");
        when(c.checkPic("123456")).thenReturn(true);
        InPacket p = mock(InPacket.class);
        when(p.readString()).thenReturn("123456", "00-11-22-33-44-55", HOST);
        when(p.readInt()).thenReturn(208);

        new CharSelectedWithPicHandler().handlePacket(p, c);

        verify(c).checkPic("123456");
        verify(coordinator, never()).closeSession(c, true);
    }
}
