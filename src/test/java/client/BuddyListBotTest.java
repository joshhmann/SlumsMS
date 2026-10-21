package client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBuddiesTestAccess;
import soloMapling.ArtificialPlayer.BotStore;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ticket 49: a saved bot buddy survives the player's relog. The stock load joined {@code characters}, which has no row
 * for a bot, so the entry vanished at the next login.
 */
class BuddyListBotTest {

    private static final int BOT = BotStore.BOT_ID_BASE + 9001;
    private static final int RETIRED_BOT = BotStore.BOT_ID_BASE + 9003;

    @AfterEach
    void real() {
        BotBuddiesTestAccess.names(null);
    }

    @Test
    void theLoadKeepsBotBuddiesAndNamesThemFromTheBotStore() throws Exception {
        BotBuddiesTestAccess.names(id -> id == BOT ? "Ressurectme" : null);
        // buddyid, pending, group, name from characters (null when there is no characters row)
        Object[][] rows = {
                {8, 0, "Default Group", "Mira"},
                {BOT, 0, "Friends", null},
                {RETIRED_BOT, 0, "Default Group", null},
                {12, 0, "Default Group", null}, // a deleted real character
        };
        AtomicInteger at = new AtomicInteger(-1);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenAnswer(i -> at.incrementAndGet() < rows.length);
        when(rs.getInt("buddyid")).thenAnswer(i -> rows[at.get()][0]);
        when(rs.getInt("pending")).thenAnswer(i -> rows[at.get()][1]);
        when(rs.getString("group")).thenAnswer(i -> rows[at.get()][2]);
        when(rs.getString("buddyname")).thenAnswer(i -> rows[at.get()][3]);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);

        BuddyList list = new BuddyList(20);
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(con);
            list.loadFromDb(7);
        }

        assertEquals(List.of(8, BOT), list.getBuddies().stream().map(BuddylistEntry::getCharacterId).toList());
        assertEquals("Ressurectme", list.get(BOT).getName());
        assertEquals("Friends", list.get(BOT).getGroup());
        assertNull(list.get(RETIRED_BOT), "a bot gone from the store is dropped, like a deleted character");
        assertTrue(BuddyList.SELECT_BUDDIES.contains("LEFT JOIN characters"));
    }

    @Test
    void onlyBotIdsFallBackToTheBotStore() {
        BotBuddiesTestAccess.names(id -> "Anyone");
        assertEquals("Mira", BuddyList.buddyName(8, "Mira"));
        assertNull(BuddyList.buddyName(12, null), "a real id with no character is not a bot");
        assertEquals("Anyone", BuddyList.buddyName(BOT, null));
    }
}
