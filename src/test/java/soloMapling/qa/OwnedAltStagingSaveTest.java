package soloMapling.qa;

import client.BotClient;
import client.Character;
import net.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.2 TEST 10-30: {@code !qa do alts} said "made AqClientAlt1 ... level 30, 100 x Red Potion ..." but the rows stayed
 * level 1 with no items. The alt is loaded onto a BotClient, which made it a roster bot, and saveCharToDB skips roster
 * bots silently. The staging must really write the character, or refuse.
 */
class OwnedAltStagingSaveTest {
    static Character gm() {
        Character gm = mock(Character.class);
        when(gm.getId()).thenReturn(1); when(gm.getAccountID()).thenReturn(7); when(gm.getWorld()).thenReturn(1);
        return gm;
    }

    /** What loadCharFromDB(id, botClient, true) hands back: a logged-in character on a BotClient, not yet an alt. */
    static Character loaded() throws Exception {
        BotClient c = new BotClient(1, 1); c.setAccID(7);
        Character alt = Character.getDefault(c); alt.setID(105); alt.setWorld(1); alt.setName("AqClientAlt1");
        c.setPlayer(alt);
        alt.setLevel(OwnedAltStaging.LEVEL);
        Field f = Character.class.getDeclaredField("loggedIn"); f.setAccessible(true); f.set(alt, true);
        return alt;
    }

    @Test
    void stagedAltIsReallyWrittenToTheDatabase() throws Exception {
        Character alt = loaded();
        Connection con = mock(Connection.class); PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1); when(ps.executeQuery()).thenReturn(rs);
        when(ps.getGeneratedKeys()).thenReturn(rs);
        List<String> added = new ArrayList<>();
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            String what = OwnedAltStaging.supplyAndSave(alt, gm(), OwnedAltStaging.Role.ALT2,
                    (id, qty) -> added.add(id + "x" + qty), id -> "Red Potion");
            assertEquals("level 30, 5 x Red Potion", what);
        }
        assertEquals(List.of("2000000x5"), added);
        verify(con).prepareStatement(startsWith("UPDATE characters SET"), anyInt());
        verify(ps).setInt(1, OwnedAltStaging.LEVEL);
        verify(con).commit();
    }

    @Test
    void aSaveThatDoesNotCommitIsARefusalNotAFalseMade() throws Exception {
        Character alt = loaded();
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("unavailable"));
            QaRefusal r = assertThrows(QaRefusal.class, () -> OwnedAltStaging.supplyAndSave(alt, gm(),
                    OwnedAltStaging.Role.ALT2, (id, qty) -> { }, id -> "Red Potion"));
            assertTrue(r.getMessage().contains("could not save AqClientAlt1"), r.getMessage());
        }
    }
}
