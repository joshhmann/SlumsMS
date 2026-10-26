package soloMapling.Economy.market;

import soloMapling.Economy.PriceBook;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * {@code sales.json}: the newest price-board samples (table {@code pricesamples}), newest first. The table keeps no
 * parties, so neither does this file. Times are cut to the hour (UTC) so a sale can't be pinned to the minute.
 */
public final class MarketSales {

    public static final int LIMIT = 500;

    private MarketSales() {
    }

    /** The start of the UTC hour the time falls in, as ISO text ("2026-10-26T07:00:00Z"). */
    static String hour(long at) {
        return Instant.ofEpochMilli(at).truncatedTo(ChronoUnit.HOURS).toString();
    }

    public static Map<String, Object> build(List<PriceBook.Sample> samples, IntFunction<String> names, long now) {
        List<PriceBook.Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingLong(PriceBook.Sample::at).reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PriceBook.Sample s : sorted) {
            if (rows.size() >= LIMIT) {
                break;
            }
            if (s == null || s.source() == null || s.unitPrice() <= 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_id", s.itemId());
            m.put("name", MarketNames.of(names, s.itemId()));
            m.put("price", s.unitPrice());
            m.put("qty", s.quantity());
            m.put("source", s.source().key());
            m.put("player_side", s.playerSide());
            m.put("hour", hour(s.at()));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", PublicMarketExport.SCHEMA);
        out.put("generated_at", Instant.ofEpochMilli(now).toString());
        out.put("count", rows.size());
        out.put("sales", rows);
        return out;
    }
}
