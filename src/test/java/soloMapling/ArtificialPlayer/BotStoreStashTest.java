package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real bots ticket 09: the one-time starter stash is recorded in the bot's row (botstore.StockedAt, migration 007) by
 * the same write that stores the items, and is never given without the column to record it.
 */
class BotStoreStashTest {

    private static final int BOT_ID = 5_000;

    @AfterEach
    void forget() {
        BotStore.setStashForTest(null, null);
    }

    private static Character bot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + BOT_ID);
        chr.setJob(Job.WARRIOR);
        chr.setLevel(30);
        return chr;
    }

    @Test
    void aStockedBotsNextRowWriteRecordsIt() throws SQLException {
        BotStore.setStashForTest(true, Set.of(BOT_ID));
        BotStore.stock(bot());
        assertTrue(BotStore.isStockPendingForTest(BOT_ID));

        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(BotStore.UPDATE_STOCKED)).thenReturn(ps);
        BotStore.writeStocked(BOT_ID, con);

        verify(ps).setLong(org.mockito.ArgumentMatchers.eq(1), anyLong());
        verify(ps).setInt(2, BOT_ID);
        verify(ps).executeUpdate();
        assertTrue(BotStore.UPDATE_STOCKED.contains("StockedAt IS NULL"), "never moves an earlier stamp");
    }

    @Test
    void withoutMigration007NoStashIsGivenOrWritten() throws SQLException {
        BotStore.setStashForTest(false, Set.of(BOT_ID));
        BotStore.stock(bot());
        assertFalse(BotStore.isStockPendingForTest(BOT_ID));

        Connection con = mock(Connection.class);
        BotStore.writeStocked(BOT_ID, con);
        verify(con, never()).prepareStatement(anyString());
    }
}
