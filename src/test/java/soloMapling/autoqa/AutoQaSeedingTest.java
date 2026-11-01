package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 163: ten lanes seed twenty accounts ({@code autoqa1}..{@code autoqa24}) and twenty characters, and a lane that is
 * started, stopped and started again must find what it already made - a second {@code INSERT} would fail on the
 * unique account name, and a second character on one account would leave the first one behind.
 */
class AutoQaSeedingTest {

    @Test
    void seedingAnAccountTwiceCreatesItOnce() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        ResultSet empty = mock(ResultSet.class);
        ResultSet found = mock(ResultSet.class);
        ResultSet key = mock(ResultSet.class);
        when(con.prepareStatement("SELECT id FROM accounts WHERE name = ?")).thenReturn(select);
        when(con.prepareStatement(startsWith("INSERT INTO accounts"), anyInt())).thenReturn(insert);
        when(select.executeQuery()).thenReturn(empty, found); // the first look finds nothing, the second finds the row
        when(empty.next()).thenReturn(false);
        when(found.next()).thenReturn(true);
        when(found.getInt(1)).thenReturn(44);
        when(insert.executeUpdate()).thenReturn(1);
        when(insert.getGeneratedKeys()).thenReturn(key);
        when(key.next()).thenReturn(true);
        when(key.getInt(1)).thenReturn(44);

        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            assertEquals(44, SyntheticPlayers.ensureAccount("autoqa13"), "the first seeding makes the account");
            assertEquals(44, SyntheticPlayers.ensureAccount("autoqa13"), "the second finds it");
        }
        verify(con, times(1)).prepareStatement(startsWith("INSERT INTO accounts"), anyInt());
        verify(select, times(2)).setString(1, "autoqa13");
    }

    @Test
    void seedingAnExistingCharacterKeepsItAndItsGmLevel() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        ResultSet found = mock(ResultSet.class);
        when(con.prepareStatement("SELECT id FROM characters WHERE accountid = ? AND world = ? ORDER BY id LIMIT 1"))
                .thenReturn(select);
        when(con.prepareStatement("UPDATE characters SET gm = ? WHERE id = ?")).thenReturn(update);
        when(select.executeQuery()).thenReturn(found);
        when(found.next()).thenReturn(true);
        when(found.getInt(1)).thenReturn(9001);

        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            // lane 7's GM (slot 13, gm 3) and lane 7's second player (slot 14, no gm) on the same account read back
            assertEquals(9001, SyntheticPlayers.ensureCharacter(44, 1, 13), "the character the account already has");
            assertEquals(9001, SyntheticPlayers.ensureCharacter(44, 1, 13), "and again, unchanged");
        }
        verify(select, times(2)).setInt(1, 44);
        verify(select, times(2)).setInt(2, 1);
        verify(update, times(2)).setInt(1, SyntheticPlayers.GM_LEVEL);
        verify(update, times(2)).setInt(2, 9001);
        verify(con, never()).prepareStatement(startsWith("UPDATE characters SET gm = ?, level = ?"), anyInt());
    }
}
