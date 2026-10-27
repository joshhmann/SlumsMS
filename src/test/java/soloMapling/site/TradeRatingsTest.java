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
    void aTradeBetweenTwoBotsNeverCounts() throws Exception {
        List<Map<Integer, long[]>> written = new ArrayList<>();
        TradeRatings.resetForTest((batch, now) -> written.add(batch));
        Character bot1 = chr(1_000_000_500), bot2 = chr(1_000_000_501);
        TradeRatings.completed(bot1, bot2);
        TradeRatings.fellThrough(bot1, bot2, true);
        assertTrue(TradeRatings.pendingForTest().isEmpty(), "the trader boards are for players");
        TradeRatings.completed(chr(1), bot1);
        assertArrayEquals(new long[]{1, 0}, TradeRatings.pendingForTest().get(1), "a player and a bot still count");
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

    // ── trade-ratings.json (private, the site's "My trades") ──

    static SiteLeaderboards.PlayerRow player(int id, int account, String name, int gm, String accountName) {
        return new SiteLeaderboards.PlayerRow(id, account, name, 50, 0, 100, 0, 0, 0, gm, accountName, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePrivateFileHasEveryRatedTraderButNoGmOrAutoQa() {
        List<SiteLeaderboards.Person> everyone = new ArrayList<>(SiteLeaderboards.players(List.of(
                player(1, 10, "Josh", 0, "josh"),
                player(2, 11, "Momo", 0, "momo"),
                player(3, 12, "Admin", 1, "staff"),
                player(4, 12, "AdminAlt", 0, "staff"),          // another character of a GM's account
                player(5, 13, "AutoQaOne", 0, "autoqa1"),
                player(6, 14, "Quiet", 0, "quiet")), 0));
        everyone.addAll(SiteLeaderboards.bots(List.of(new SiteLeaderboards.BotRow(1_000_000_007, "BotBob", 40, 0, 100, 0, 0))));
        List<TradeRatings.Row> trades = List.of(new TradeRatings.Row(1, 1, 0), new TradeRatings.Row(2, 0, 2),
                new TradeRatings.Row(3, 9, 0), new TradeRatings.Row(4, 3, 1), new TradeRatings.Row(5, 4, 4),
                new TradeRatings.Row(6, 0, 0), new TradeRatings.Row(1_000_000_007, 12, 3),
                new TradeRatings.Row(999, 5, 0));                     // a deleted character

        Map<String, Object> out = TradeRatings.privateFile(everyone, trades, 1_793_000_000_000L);

        assertEquals(List.of("generated", "ratings"), List.copyOf(out.keySet()));
        assertEquals("2026-10-26T07:33:20Z", out.get("generated"));
        Map<String, Object> ratings = (Map<String, Object>) out.get("ratings");
        assertEquals(List.of("BotBob", "Josh", "Momo"), List.copyOf(ratings.keySet()),
                "1 trade is enough; bots as on the public board; no GM account, no auto-QA, no zero rows");
        assertEquals(Map.of("completed", 1L, "fellThrough", 0L), ratings.get("Josh"));
        assertEquals(List.of("completed", "fellThrough"), List.copyOf(((Map<String, Object>) ratings.get("Momo")).keySet()));
        String json = soloMapling.server.Json.write(out);
        assertTrue(json.startsWith("{\"generated\":\"2026-10-26T07:33:20Z\",\"ratings\":{\"BotBob\":{\"completed\":12,"), json);
        assertFalse(json.contains("Admin") || json.contains("AutoQa"), json);
    }

    @Test
    void thePrivateFileIsNotAPublicSiteFile() {
        assertFalse(PublicSiteExport.FILES.contains(TradeRatings.PRIVATE_FILE));
        assertEquals("trade-ratings.json", TradeRatings.PRIVATE_FILE);
    }
}
