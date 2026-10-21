package server.setitem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * One equipment set from {@code set-items.yaml} (ticket 18, SetEquipV3 addon): its pieces and the stats each tier adds.
 *
 * <p>Tiers stack like GMS sets: wearing N pieces grants every tier at or below N, see {@link #cumulativeEffectFor}.
 *
 * @param id            GMS SetItemInfo id
 * @param name          shown in the tooltip
 * @param completeCount pieces for the whole set (only one weapon can be worn, so this is not {@code itemIds.size()})
 * @param itemIds       every piece, in file order
 * @param tiers         piece count -> stat key -> value at exactly that count, ascending
 */
public record ItemSetInfo(int id, String name, int completeCount, List<Integer> itemIds,
                          NavigableMap<Integer, Map<String, Integer>> tiers) {

    public ItemSetInfo {
        itemIds = List.copyOf(itemIds);
        NavigableMap<Integer, Map<String, Integer>> copy = new TreeMap<>();
        tiers.forEach((pieces, stats) -> copy.put(pieces, Collections.unmodifiableMap(new LinkedHashMap<>(stats))));
        tiers = Collections.unmodifiableNavigableMap(copy);
    }

    /** Sum of every tier whose piece count is at most {@code equippedPieces}; empty below the first tier. */
    public Map<String, Integer> cumulativeEffectFor(int equippedPieces) {
        Map<String, Integer> total = new LinkedHashMap<>();
        if (equippedPieces <= 0) {
            return total;
        }
        for (Map<String, Integer> stats : tiers.headMap(equippedPieces, true).values()) {
            stats.forEach((key, value) -> total.merge(key, value, Integer::sum));
        }
        return total;
    }
}
