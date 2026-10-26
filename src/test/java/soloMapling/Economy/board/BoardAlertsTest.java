package soloMapling.Economy.board;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoardAlertsTest {
    static final int GLOVE = 2040804, ORE = 4004000;
    long now = 1_793_000_000_000L;

    Board.Alert alert(String id, int item, Board.Dir dir, long price, boolean armed, long armedAt) {
        return new Board.Alert(id, 7, item, dir, price, armed, armedAt);
    }

    List<Board.AccountAlerts> one(Board.Alert... alerts) {
        return List.of(new Board.AccountAlerts(7, List.of(), List.of(alerts)));
    }

    @Test
    void belowAndAboveFireOnTheCheapestListingAndOnlyThen() {
        BoardState st = new BoardState();
        Map<Integer, Long> low = Map.of(GLOVE, 2_400_000L, ORE, 6_000L);
        List<BoardAlerts.Fire> f = BoardAlerts.due(one(
                alert("below-hit", GLOVE, Board.Dir.BELOW, 2_500_000, true, 0),
                alert("below-miss", GLOVE, Board.Dir.BELOW, 2_000_000, true, 0),
                alert("above-hit", ORE, Board.Dir.ABOVE, 5_000, true, 0),
                alert("above-miss", ORE, Board.Dir.ABOVE, 7_000, true, 0),
                alert("unlisted", 2_000_000, Board.Dir.BELOW, 9_999_999, true, 0),
                alert("disarmed", GLOVE, Board.Dir.BELOW, 2_500_000, false, 0)), low, st, now);
        assertEquals(List.of("below-hit", "above-hit"), f.stream().map(x -> x.alert().id()).toList());
        assertEquals(2_400_000L, f.get(0).listingPrice());
        assertEquals(now, st.fired("below-hit").firedAt());
    }

    @Test
    void anAlertFiresOnceUntilTheSiteDisarmsAndRearmsIt() {
        BoardState st = new BoardState();
        Map<Integer, Long> low = Map.of(GLOVE, 2_400_000L);
        Board.Alert a = alert("a1", GLOVE, Board.Dir.BELOW, 2_500_000, true, 0);
        assertEquals(1, BoardAlerts.due(one(a), low, st, now).size());
        assertEquals(0, BoardAlerts.due(one(a), low, st, now + 900_000).size(), "once only");
        // the site shows it disarmed, then the player re-arms it
        assertEquals(0, BoardAlerts.due(one(alert("a1", GLOVE, Board.Dir.BELOW, 2_500_000, false, 0)), low, st,
                now + 1_800_000).size());
        assertTrue(st.fired("a1").acked());
        assertEquals(1, BoardAlerts.due(one(a), low, st, now + 2_700_000).size(), "re-armed");
        assertEquals(0, BoardAlerts.due(one(a), low, st, now + 3_600_000).size());
    }

    @Test
    void aLaterArmedAtAlsoRearmsWithoutTheServerSeeingItDisarmed() {
        BoardState st = new BoardState();
        Map<Integer, Long> low = Map.of(GLOVE, 2_400_000L);
        assertEquals(1, BoardAlerts.due(one(alert("a1", GLOVE, Board.Dir.BELOW, 2_500_000, true, now - 5)), low, st,
                now).size());
        assertEquals(0, BoardAlerts.due(one(alert("a1", GLOVE, Board.Dir.BELOW, 2_500_000, true, now - 5)), low, st,
                now + 60_000).size());
        assertEquals(1, BoardAlerts.due(one(alert("a1", GLOVE, Board.Dir.BELOW, 2_500_000, true, now + 30_000)), low,
                st, now + 120_000).size());
    }

    @Test
    void onlyTheFirstTenAlertsOfAnAccountCount() {
        List<Board.Alert> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(alert("a" + i, GLOVE, Board.Dir.BELOW, 3_000_000, true, 0));
        }
        List<BoardAlerts.Fire> f = BoardAlerts.due(List.of(new Board.AccountAlerts(7, List.of(), many)),
                Map.of(GLOVE, 1L), new BoardState(), now);
        assertEquals(BoardAlerts.MAX_ALERTS, f.size());
    }

    @Test
    void stateRoundTripsThroughItsFile() {
        BoardState st = new BoardState();
        st.firstSight("p1", now);
        assertTrue(st.takeOffer("p1", 1_000_000_001, now));
        st.fire(new BoardState.Fired("a1", 7, GLOVE, Board.Dir.BELOW, 5, now, 4, false));
        st.remove("p9", now, "Gm", "spam");
        BoardState back = BoardState.fromJson(st.toJson());
        assertFalse(back.firstSight("p1", now + 1));
        assertTrue(back.offered("p1", 1_000_000_001));
        assertEquals(st.fired("a1"), back.fired("a1"));
        assertEquals(java.util.Set.of("p9"), back.removedIds());
    }
}
