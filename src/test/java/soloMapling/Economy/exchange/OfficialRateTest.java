package soloMapling.Economy.exchange;

import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.ExchangeStore.FillRow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Ticket 113: the official rate is a VWAP that the regulars cannot move alone. */
class OfficialRateTest {

    static FillRow f(long at, long nx, long price, boolean buyerRegular, boolean sellerRegular) {
        return new FillRow(at, at, 1, 2, nx, price, OrderBook.cost(nx, price), buyerRegular, sellerRegular);
    }

    @Test
    void volumeWeighted() {
        List<FillRow> fills = List.of(f(10, 1000, 1_000_000, false, false), f(20, 3000, 2_000_000, false, false));
        assertEquals(1_750_000L, OfficialRate.vwap(fills, 100, 1000, 2));
    }

    @Test
    void underTheMinimumThereIsNoRate() {
        assertNull(OfficialRate.vwap(List.of(f(10, 1000, 1_000_000, false, false)), 100, 1000, 2));
        assertNull(OfficialRate.vwap(List.of(), 100, 1000, 1));
    }

    @Test
    void onlyTheWindowCounts() {
        List<FillRow> fills = List.of(f(10, 1000, 9_000_000, false, false), f(950, 1000, 1_000_000, false, false),
                f(2000, 1000, 5_000_000, false, false));
        assertEquals(1_000_000L, OfficialRate.vwap(fills, 1000, 100, 1), "older than the window and future fills are out");
    }

    @Test
    void fillsWithARegularNeverCount() {
        // owner 2026-10-28: regulars are excluded from the official rate, also when they trade with a player
        List<FillRow> fills = List.of(f(10, 1000, 1_000_000, false, false), f(11, 100_000, 50_000_000, true, true),
                f(12, 1000, 1_200_000, true, false), f(13, 1000, 1_400_000, false, true),
                f(14, 1000, 1_100_000, false, false), f(15, 2000, 1_250_000, false, false));
        assertEquals(1_150_000L, OfficialRate.vwap(fills, 100, 1000, 3));
        assertNull(OfficialRate.vwap(fills.subList(0, 4), 100, 1000, 2), "one player fill is under the minimum");
        assertNull(OfficialRate.vwap(List.of(f(11, 1000, 5, true, true)), 100, 1000, 1), "regulars alone: no rate");
    }
}
