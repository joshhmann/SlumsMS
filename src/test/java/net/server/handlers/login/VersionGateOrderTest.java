package net.server.handlers.login;

import client.Client;
import config.ServerConfig;
import config.YamlConfig;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.server.coordinator.session.Hwid;
import net.server.coordinator.session.SessionCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.0 review: the version gate's GM bypass read Client.getGMLevel(), which Client.login has just reset to 0; and the
 * gate ran after the loginok 7 block had already kicked the account's live session (and after AUTOMATIC_REGISTER had
 * created an account for an outdated client).
 */
class VersionGateOrderTest {

    private static final int MINIMUM = 10500;
    private int savedMinimum;
    private boolean savedBypass;
    private boolean savedRegister;

    @BeforeEach
    void saveConfig() {
        ServerConfig server = YamlConfig.config.server;
        savedMinimum = server.MIN_CLIENT_BUILD;
        savedBypass = server.MIN_CLIENT_BUILD_GM_BYPASS;
        savedRegister = server.AUTOMATIC_REGISTER;
        server.MIN_CLIENT_BUILD = MINIMUM;
        server.MIN_CLIENT_BUILD_GM_BYPASS = false;
    }

    @AfterEach
    void restoreConfig() {
        ServerConfig server = YamlConfig.config.server;
        server.MIN_CLIENT_BUILD = savedMinimum;
        server.MIN_CLIENT_BUILD_GM_BYPASS = savedBypass;
        server.AUTOMATIC_REGISTER = savedRegister;
    }

    private static ServerConfig config(boolean bypass) {
        ServerConfig config = new ServerConfig();
        config.MIN_CLIENT_BUILD = MINIMUM;
        config.MIN_CLIENT_BUILD_GM_BYPASS = bypass;
        return config;
    }

    private static Client oldClient(int loginok) {
        Client c = mock(Client.class);
        when(c.getRemoteAddress()).thenReturn("73.110.5.2");
        when(c.getClientBuild()).thenReturn(10402);
        when(c.getClientRelease()).thenReturn("1.4.2");
        when(c.getAccID()).thenReturn(77);
        when(c.getGMLevel()).thenReturn(0);   // what Client.login leaves it at
        when(c.login(anyString(), anyString(), any(Hwid.class), anyBoolean())).thenReturn(loginok);
        return c;
    }

    private static InPacket loginPacket() {
        OutPacket out = new ByteBufOutPacket();
        out.writeString("someone");
        out.writeString("secret");
        out.skip(6);
        out.writeBytes(new byte[]{1, 2, 3, 4});
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    /** Runs the handler with the database and session coordinator stubbed; the database must never be touched. */
    private static SessionCoordinator handle(Client c) {
        SessionCoordinator coordinator = mock(SessionCoordinator.class);
        try (var db = mockStatic(DatabaseConnection.class);
             var sc = mockStatic(SessionCoordinator.class)) {
            db.when(DatabaseConnection::getConnection).thenThrow(new AssertionError("the database was used"));
            sc.when(SessionCoordinator::getInstance).thenReturn(coordinator);
            new LoginPasswordHandler().handlePacket(loginPacket(), c);
        }
        return coordinator;
    }

    @Test
    void anOutdatedClientIsRefusedBeforeTheLiveSessionIsKicked() {
        Client c = oldClient(7);
        SessionCoordinator coordinator = handle(c);   // the 7 block would hit the database to kick the session

        verify(c, times(2)).sendPacket(any());          // notice + silent login failure
        verify(coordinator).closeLoginSession(c);
        verify(c, never()).finishLogin();
    }

    @Test
    void autoRegisterDoesNotCreateAnAccountForAnOutdatedClient() {
        YamlConfig.config.server.AUTOMATIC_REGISTER = true;
        Client c = oldClient(5);
        SessionCoordinator coordinator = handle(c);   // registering would INSERT into accounts

        verify(c, times(2)).sendPacket(any());
        verify(coordinator).closeLoginSession(c);
        verify(c, never()).setAccID(77);
    }

    @Test
    void onlyAVerifiedPasswordReachesTheGate() {
        assertTrue(ClientVersionGate.passwordVerified(0));
        assertTrue(ClientVersionGate.passwordVerified(7));
        assertTrue(ClientVersionGate.passwordVerified(23));
        assertTrue(ClientVersionGate.passwordVerified(-23));
        assertTrue(ClientVersionGate.passwordVerified(-10));
        assertFalse(ClientVersionGate.passwordVerified(4), "wrong password");
        assertFalse(ClientVersionGate.passwordVerified(5), "no such account");
        assertFalse(ClientVersionGate.passwordVerified(3), "banned");
    }

    @Test
    void gmBypassUsesTheAccountsRealGmLevel() {
        Client c = oldClient(0);
        assertFalse(ClientVersionGate.refuseIfOutdated(c, "gm", config(true), acc -> acc == 77 ? 6 : 0));
        verify(c, never()).sendPacket(any());

        assertTrue(ClientVersionGate.refuseIfOutdated(c, "player", config(true), acc -> 0));
        assertTrue(ClientVersionGate.refuseIfOutdated(oldClient(0), "gm", config(false), acc -> 6), "bypass off");
    }

    @Test
    void gmLevelIsReadOnlyWhenNeeded() {
        Client current = oldClient(0);
        when(current.getClientBuild()).thenReturn(MINIMUM);
        assertFalse(ClientVersionGate.refuseIfOutdated(current, "x", config(true), acc -> {
            throw new AssertionError("an up-to-date client needs no GM lookup");
        }));
    }

    @Test
    void accountGmLevelIsTheHighestCharacterLevel() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(6);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        try (var db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            assertEquals(6, ClientVersionGate.accountGmLevel(77));
        }
        verify(con).prepareStatement("SELECT MAX(gm) FROM characters WHERE accountid = ?");
        verify(ps).setInt(1, 77);
        assertEquals(0, ClientVersionGate.accountGmLevel(-1));
    }
}
