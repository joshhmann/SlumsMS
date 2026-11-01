package soloMapling.ArtificialPlayer.BotDramaSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDbGate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The player-facing stores write through {@link BotDbGate#write}: the writer that carries them
 * ({@link BotRomance}'s) used to run on a virtual thread, where the gate refuses bot work instead of letting it
 * wait, so a choice the player had already been told about was dropped with a warning.
 */
class RomanceStoreRetryTest {

    @Test
    void aChoiceTheShareRefusedIsWrittenOnceTheShareFrees() throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeUpdate()).thenReturn(1);
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        RomanceStore store = new RomanceStore.Jdbc(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new BotDbGate.RefusedException("The bots' share of the database pool is busy");
            }
            return con;
        });

        store.setOptedIn(1234, true, 1_000L);

        assertAll(
                () -> assertEquals(2, attempts.get(), "the refused attempt was asked for again"),
                () -> verify(ps).executeUpdate());
    }
}
