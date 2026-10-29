package soloMapling.Economy.market;

import soloMapling.Economy.PriceBook;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * {@code prices/<YYYY-MM-DD>.json}: one UTC day of price-board samples rolled up per item. {@code pricesamples} keeps
 * 14 days; these files keep the daily summary for 365 (owner, Track D), with no change to the board's keepDays.
 * Prices are mesos per unit as paid ({@code normMedian} is the clean-equivalent median, which differs for equips).
 */
public final class PriceRollup {

    private PriceRollup() {
    }

    /** Median of a sorted list: the middle value, or the mean of the two middle values (floored). */
    static long median(List<Long> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    static double ratio(int part, int whole) {
        return whole <= 0 ? 0.0 : Math.round(part * 1000.0 / whole) / 1000.0;
    }

    /** One item's day: open (first sale), high, low, close (last sale), median, count and player share. */
    static Map<String, Object> item(int itemId, List<PriceBook.Sample> sales, IntFunction<String> names) {
        List<PriceBook.Sample> byTime = new ArrayList<>(sales);
        byTime.sort(Comparator.comparingLong(PriceBook.Sample::at));
        List<Long> prices = new ArrayList<>(byTime.size());
        List<Long> norm = new ArrayList<>(byTime.size());
        int player = 0;
        long units = 0;
        for (PriceBook.Sample s : byTime) {
            prices.add(s.unitPrice());
            norm.add(s.normPrice());
            if (s.playerSide()) {
                player++;
            }
            units += Math.max(0, s.quantity());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_id", itemId);
        m.put("name", MarketNames.of(names, itemId));
        m.put("open", byTime.get(0).unitPrice());
        m.put("close", byTime.get(byTime.size() - 1).unitPrice());
        prices.sort(Long::compare);
        norm.sort(Long::compare);
        m.put("high", prices.get(prices.size() - 1));
        m.put("low", prices.get(0));
        m.put("median", median(prices));
        m.put("norm_median", median(norm));
        m.put("count", byTime.size());
        m.put("units", units);
        m.put("player_share", ratio(player, byTime.size()));
        m.put("player_count", player); // 1.6.1: sales with a real player on a side (bot-to-bot seeds excluded)
        return m;
    }

    /**
     * The day's file. {@code samples} may hold other days' samples too; only those inside the UTC day count.
     * {@code nxRate} is the day's NX rate entry ({@link NxRate#entry}), stored here so the 365-day history needs no
     * second store.
     */
    public static Map<String, Object> day(LocalDate date, List<PriceBook.Sample> samples, IntFunction<String> names,
                                          Map<String, Object> nxRate, long now) {
        long from = PublicMarketExport.startOf(date), to = PublicMarketExport.startOf(date.plusDays(1));
        Map<Integer, List<PriceBook.Sample>> byItem = new TreeMap<>();
        int count = 0;
        for (PriceBook.Sample s : samples) {
            if (s == null || s.at() < from || s.at() >= to || s.unitPrice() <= 0) {
                continue;
            }
            byItem.computeIfAbsent(s.itemId(), k -> new ArrayList<>()).add(s);
            count++;
        }
        Map<String, Object> items = new LinkedHashMap<>();
        byItem.forEach((id, list) -> items.put(String.valueOf(id), item(id, list, names)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", PublicMarketExport.SCHEMA);
        out.put("date", date.toString());
        out.put("generated_at", Instant.ofEpochMilli(now).toString());
        out.put("samples", count);
        out.put("item_count", items.size());
        out.put("items", items);
        out.put("nx_rate", nxRate);
        return out;
    }
}
