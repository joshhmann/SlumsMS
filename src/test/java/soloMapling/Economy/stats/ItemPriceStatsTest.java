package soloMapling.Economy.stats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemPriceStatsTest {

    @Test
    void computesPriceStatsPerItem() {
        // Item 1000: 5 samples [100, 200, 300, 400, 500]
        // Item 2000: 1 sample [1000]
        Map<Integer, List<Long>> input = Map.of(
                1000, List.of(500L, 100L, 300L, 200L, 400L),
                2000, List.of(1000L)
        );

        Map<Integer, ItemPriceStats.ItemStat> stats = ItemPriceStats.calculate(input);

        assertEquals(2, stats.size());

        var stat1000 = stats.get(1000);
        assertNotNull(stat1000);
        assertEquals(1000, stat1000.itemId());
        assertEquals(5, stat1000.count());
        assertEquals(100L, stat1000.min());
        assertEquals(500L, stat1000.max());
        assertEquals(300L, stat1000.median());
        assertEquals(200L, stat1000.p25());
        assertEquals(400L, stat1000.p75());

        var stat2000 = stats.get(2000);
        assertNotNull(stat2000);
        assertEquals(2000, stat2000.itemId());
        assertEquals(1, stat2000.count());
        assertEquals(1000L, stat2000.min());
        assertEquals(1000L, stat2000.max());
        assertEquals(1000L, stat2000.median());
        assertEquals(1000L, stat2000.p25());
        assertEquals(1000L, stat2000.p75());
    }

    @Test
    void ignoresEmptyOrNullEntries() {
        Map<Integer, List<Long>> input = Map.of(
                1000, List.of()
        );
        Map<Integer, ItemPriceStats.ItemStat> stats = ItemPriceStats.calculate(input);
        assertTrue(stats.isEmpty());

        assertTrue(ItemPriceStats.calculate(null).isEmpty());
    }
}
