package soloMapling.Economy.stats;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates item price statistics (median, p25, p75, min, max, count) from trade and listing samples.
 */
public final class ItemPriceStats {

    public record ItemStat(
            int itemId,
            int count,
            long median,
            long p25,
            long p75,
            long min,
            long max
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", itemId);
            m.put("count", count);
            m.put("median", median);
            m.put("p25", p25);
            m.put("p75", p75);
            m.put("min", min);
            m.put("max", max);
            return m;
        }
    }

    private ItemPriceStats() {
    }

    public static Map<Integer, ItemStat> calculate(Map<Integer, List<Long>> pricesPerItem) {
        if (pricesPerItem == null || pricesPerItem.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Integer, ItemStat> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Long>> entry : pricesPerItem.entrySet()) {
            int itemId = entry.getKey();
            List<Long> prices = entry.getValue();
            if (prices == null || prices.isEmpty()) {
                continue;
            }

            long[] arr = new long[prices.size()];
            for (int i = 0; i < prices.size(); i++) {
                arr[i] = Math.max(0, prices.get(i));
            }
            Arrays.sort(arr);

            int count = arr.length;
            long min = arr[0];
            long max = arr[count - 1];
            long median = EconomyMath.percentile(arr, 0.5);
            long p25 = EconomyMath.percentile(arr, 0.25);
            long p75 = EconomyMath.percentile(arr, 0.75);

            result.put(itemId, new ItemStat(itemId, count, median, p25, p75, min, max));
        }

        return result;
    }
}
