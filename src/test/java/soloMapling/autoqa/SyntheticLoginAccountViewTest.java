package soloMapling.autoqa;

import client.Character;
import client.Client;
import client.SyntheticClient;
import net.opcodes.RecvOpcode;
import net.server.Server;
import net.server.coordinator.session.SessionCoordinator;
import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyntheticLoginAccountViewTest {
    @Test
    void gmFixtureLoadsItsAccountViewsBeforeChannelLogin() throws Exception {
        checkLogin(1);
    }

    @Test
    void ordinaryFixtureLoadsItsAccountViewsBeforeChannelLogin() throws Exception {
        checkLogin(2);
    }

    private void checkLogin(int slot) throws Exception {
        Server server = mock(Server.class);
        when(server.getHomeWorldId()).thenReturn(1);
        Character chr = mock(Character.class);
        when(chr.getWorld()).thenReturn(1);
        when(chr.getId()).thenReturn(42);
        when(chr.getName()).thenReturn(SyntheticPlayers.NAMES[slot - 1]);
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true);
            when(rows.getInt(1)).thenReturn(42);
            when(statement.executeQuery()).thenReturn(rows);
            return statement;
        });
        AtomicBoolean viewsLoaded = new AtomicBoolean();
        doAnswer(call -> { viewsLoaded.set(true); return null; }).when(server).loadAccountCharacters(any());

        try (var servers = mockStatic(Server.class);
             var database = mockStatic(DatabaseConnection.class);
             var sessions = mockStatic(SessionCoordinator.class);
             var clients = mockConstruction(SyntheticClient.class, (client, context) -> {
                 when(client.getAccID()).thenReturn(42);
                 when(client.getPlayer()).thenReturn(chr);
                 when(client.isLoggedIn()).thenReturn(true);
             });
             var synthetic = mockStatic(SyntheticPlayers.class, CALLS_REAL_METHODS)) {
            servers.when(Server::getInstance).thenReturn(server);
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            sessions.when(SessionCoordinator::getInstance).thenReturn(mock(SessionCoordinator.class));
            synthetic.when(() -> SyntheticPlayers.handle(any(), eq(RecvOpcode.PLAYER_LOGGEDIN), any(byte[].class)))
                    .thenAnswer(call -> {
                        assertTrue(viewsLoaded.get(), "channel login must not precede the account-view load needed by saves");
                        return null;
                    });
            try {
                assertNotNull(SyntheticPlayers.login(slot));
                SyntheticClient client = clients.constructed().getFirst();
                var order = inOrder(server, client);
                order.verify(server).loadAccountCharacters(client);
                order.verify(client).updateLoginState(Client.LOGIN_SERVER_TRANSITION);
                verify(server, times(1)).loadAccountCharacters(client);
            } finally {
                SyntheticPlayers.logoutAll();
            }
        }
    }
}
