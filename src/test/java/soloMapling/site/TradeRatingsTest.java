package soloMapling.site;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Trade ratings: counting, the batched write, and keeping the counts when the write fails. */
class TradeRatingsTest {

    @AfterEach
    void tearDown() {
        TradeRatings.resetForTest(null);
    }

    static Character chr(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    @Test
    void countsAndWritesInOneBatch() throws Exception {
        List<Map<Integer, long[]>> written = new ArrayList<>();
        TradeRatings.resetForTest((batch, now) -> written.add(batch));
        Character a = chr(1), bot = chr(1_000_000_500);
        TradeRatings.completed(a, bot);
        TradeRatings.completed(a, bot);
        TradeRatings.fellThrough(a, bot, true);
        TradeRatings.fellThrough(a, bot, false); // empty window: nothing
        assertEquals(2, TradeRatings.flush(1L));
        assertEquals(1, written.size());
        assertArrayEquals(new long[]{2, 1}, written.getFirst().get(1));
        assertArrayEquals(new long[]{2, 1}, written.getFirst().get(1_000_000_500));
        assertEquals(0, TradeRatings.flush(2L), "nothing pending after a write");
    }

    @Test
    void aFailedWriteKeepsTheCounts() {
        TradeRatings.resetForTest((batch, now) -> {
            throw new SQLException("Table 'traderatings' doesn't exist");
        });
        TradeRatings.completed(chr(1), chr(2));
        assertThrows(SQLException.class, () -> TradeRatings.flush(1L));
        TradeRatings.completed(chr(1), chr(2));
        assertArrayEquals(new long[]{2, 0}, TradeRatings.pendingForTest().get(1));
        assertDoesNotThrow(TradeRatings::flushSafely);
        assertArrayEquals(new long[]{2, 0}, TradeRatings.pendingForTest().get(1));
    }

    @Test
    void nullsAndBrokenCharactersNeverThrowIntoTheTrade() {
        Character broken = mock(Character.class);
        when(broken.getId()).thenThrow(new IllegalStateException("gone"));
        assertDoesNotThrow(() -> TradeRatings.completed(null, broken));
        assertTrue(TradeRatings.pendingForTest().isEmpty());
    }
}
