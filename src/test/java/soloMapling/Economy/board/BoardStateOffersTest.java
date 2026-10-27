package soloMapling.Economy.board;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The offer log behind offers.json: 7 days, 20 per post, 500 in all, kept across a restart. */
class BoardStateOffersTest {

    static final long NOW = 1_793_000_000_000L;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Test
    void sevenDaysAndTwentyPerPostNewestKept() {
        BoardState s = new BoardState();
        s.recordMade("old", "A", 1, NOW - 8 * DAY);
        for (int i = 0; i < 25; i++) {
            s.recordMade("p1", "Bot" + i, i, NOW - 25 + i);
        }
        List<BoardState.Made> m = s.madeOffers(NOW);
        assertTrue(m.stream().noneMatch(x -> x.postId().equals("old")));
        assertEquals(20, m.size());
        assertTrue(m.stream().anyMatch(x -> x.bot().equals("Bot24")));
        assertTrue(m.stream().noneMatch(x -> x.bot().equals("Bot4")));
    }

    @Test
    void fiveHundredInAllAndItSurvivesTheStateFile() {
        BoardState s = new BoardState();
        for (int i = 0; i < 600; i++) {
            s.recordMade("p" + i, "Bot", 100, NOW - 1000 + i);
        }
        assertEquals(BoardState.MADE_TOTAL, s.madeOffers(NOW).size());
        BoardState back = BoardState.fromJson(s.toJson());
        assertEquals(s.madeOffers(NOW), back.madeOffers(NOW));
    }

    @Test
    void theFileIsNewestFirst() {
        BoardState s = new BoardState();
        s.recordMade("p1", "First", 5, NOW - 2000);
        s.recordMade("p2", "Second", 6, NOW - 1000);
        var f = BoardOutbox.offers(s.madeOffers(NOW), NOW);
        var rows = (List<?>) f.get("offers");
        assertEquals("Second", ((java.util.Map<?, ?>) rows.get(0)).get("bot"));
    }
}
