package client;

import client.Character.SaveResult;
import client.inventory.Pet;
import net.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.6.5 P0-5: a pet or cooldown write that fails fails the whole character save, which rolls back. */
class SaveRollsBackTest {

    private static Character loggedInChar() throws Exception {
        Character chr = Character.getDefault(mock(Client.class));   // a human's client, so not a roster bot
        chr.setID(7);
        chr.setName("Saver");
        Field f = Character.class.getDeclaredField("loggedIn");
        f.setAccessible(true);
        f.set(chr, true);
        Field mb = Character.class.getDeclaredField("monsterbook");
        mb.setAccessible(true);
        mb.set(chr, mock(MonsterBook.class));
        return chr;
    }

    private static Connection connection() throws SQLException {
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeUpdate()).thenReturn(1);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(con.prepareStatement(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(ps);
        return con;
    }

    private static SaveResult save(Character chr, Connection con, Character.SaveExtra extra) {
        Server server = mock(Server.class);
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<DatabaseConnection> dbc = mockStatic(DatabaseConnection.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            dbc.when(DatabaseConnection::getConnection).thenReturn(con);
            return chr.saveCharToDBResult(true, extra);
        }
    }

    @Test
    void aFailingPetWriteFailsAndRollsBackTheCharacterSave() throws Exception {
        Character chr = loggedInChar();
        Pet pet = mock(Pet.class);
        doThrow(new SQLException("injected pet failure")).when(pet).saveToDb(any(Connection.class));
        Field pets = Character.class.getDeclaredField("pets");
        pets.setAccessible(true);
        ((Pet[]) pets.get(chr))[0] = pet;
        Connection con = connection();

        assertEquals(SaveResult.FAILED, save(chr, con, null));
        verify(pet).saveToDb(con);   // it failed at the pet, not somewhere unrelated
        verify(con).rollback();
        verify(con, never()).commit();
    }

    @Test
    void aFailingCooldownWriteFailsAndRollsBackTheCharacterSave() throws Exception {
        Character chr = loggedInChar();
        Connection con = connection();
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        Character.SaveExtra cooldowns = c -> { ran.set(true); throw new SQLException("injected cooldown failure"); };

        assertEquals(SaveResult.FAILED, save(chr, con, cooldowns));
        org.junit.jupiter.api.Assertions.assertTrue(ran.get(), "the cooldown write ran inside the save");
        verify(con).rollback();
        verify(con, never()).commit();
    }

    @Test
    void whenEverythingWorksTheSaveCommits() throws Exception {
        Character chr = loggedInChar();
        Connection con = connection();

        SaveResult result = save(chr, con, c -> { });
        assertEquals(SaveResult.SAVED, result);
        verify(con).commit();
    }
}
