package soloMapling.Economy.market;

import constants.id.ItemId;
import soloMapling.Economy.PriceBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NX rate (owner addendum, 2026-10-26): mesos per 1,000 NX, like meowdb's NX:Meso indicator. Every price-board
 * sale of an item with a fixed NX price gives one reading, mesos per NX = unit price / the item's NX price per unit:
 * <ul>
 *   <li>a Cash Shop item with a price in {@code cashshop/catalog.tsv} (price / count per unit): a <b>cash resale</b>;</li>
 *   <li>an NX code item, the Nexon Game Cards 4031865 (100 NX) and 4031866 (250 NX) ({@link ItemId#nxCardValue}):
 *   a <b>code trade</b>. They are redeemed on pickup today, so they rarely reach a sale.</li>
 * </ul>
 * The day's rate is the median reading x 1,000, published only with at least {@link #MIN_TRADES} readings.
 */
public final class NxRate {

    public static final int MIN_TRADES = 5;

    private NxRate() {
    }

    /** NX per unit for the items that have a fixed NX price, and which of them are code items. */
    public record Prices(Map<Integer, Double> nxPerUnit, java.util.Set<Integer> codeItems) {
        public static Prices of(Map<Integer, Double> cash) {
            Map<Integer, Double> all = new HashMap<>(cash);
            all.put(ItemId.NX_CARD_100, (double) ItemId.nxCardValue(ItemId.NX_CARD_100, 1));
            all.put(ItemId.NX_CARD_250, (double) ItemId.nxCardValue(ItemId.NX_CARD_250, 1));
            return new Prices(Map.copyOf(all), java.util.Set.of(ItemId.NX_CARD_100, ItemId.NX_CARD_250));
        }

        Double nx(int itemId) {
            return nxPerUnit.get(itemId);
        }
    }

    /**
     * The catalogue's NX price per unit: the server's loaded catalogue when it has rows, otherwise the file
     * (itemId, price, count, ...; tab separated; '#' comments). Rows priced 0 have no fixed price and are left out.
     */
    public static Map<Integer, Double> cashPrices(Path catalogFile) {
        Map<Integer, Double> out = new HashMap<>();
        try {
            List<server.cashshop.CashShopCatalog.Row> rows = server.cashshop.CashShopCatalog.all();
            if (!rows.isEmpty()) {
                for (var r : rows) {
                    if (r.price() > 0 && r.count() > 0) {
                        out.put(r.itemId(), r.price() / (double) r.count());
                    }
                }
                return out;
            }
        } catch (RuntimeException | LinkageError e) {
            // not loaded here: read the file
        }
        return readCatalog(catalogFile);
    }

    /** The catalogue file's NX price per unit, for rows with a price. */
    static Map<Integer, Double> readCatalog(Path catalogFile) {
        Map<Integer, Double> out = new HashMap<>();
        if (catalogFile == null || !Files.isRegularFile(catalogFile)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(catalogFile, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.charAt(0) == '#') {
                    continue;
                }
                String[] f = s.split("\t");
                if (f.length < 3) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(f[0].strip());
                    int price = Integer.parseInt(f[1].strip());
                    int count = Integer.parseInt(f[2].strip());
                    if (price > 0 && count > 0) {
                        out.put(id, price / (double) count);
                    }
                } catch (NumberFormatException e) {
                    // a malformed row: the shop skips it too
                }
            }
        } catch (IOException e) {
            // no catalogue: no cash readings
        }
        return out;
    }

    /** One day's entry: date, mesos_per_1000nx (null under {@link #MIN_TRADES}), the counts and the bot share. */
    public static Map<String, Object> entry(LocalDate date, List<PriceBook.Sample> samples, Prices prices) {
        long from = PublicMarketExport.startOf(date), to = PublicMarketExport.startOf(date.plusDays(1));
        List<Double> readings = new ArrayList<>();
        int resales = 0, codes = 0, bots = 0;
        for (PriceBook.Sample s : samples) {
            if (s == null || s.at() < from || s.at() >= to || s.unitPrice() <= 0) {
                continue;
            }
            Double nx = prices.nx(s.itemId());
            if (nx == null || nx <= 0) {
                continue;
            }
            readings.add(s.unitPrice() / nx);
            if (prices.codeItems().contains(s.itemId())) {
                codes++;
            } else {
                resales++;
            }
            if (!s.playerSide()) {
                bots++;
            }
        }
        int n = readings.size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("mesos_per_1000nx", n >= MIN_TRADES ? (Object) Math.round(medianOf(readings) * 1000.0) : null);
        m.put("n_trades", n);
        m.put("n_cash_resales", resales);
        m.put("n_code_trades", codes);
        m.put("bot_share", PriceRollup.ratio(bots, n));
        return m;
    }

    static double medianOf(List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        v.sort(Double::compare);
        int n = v.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    /** {@code nx-rate.json}: the daily history (oldest first, at most 365 days) and today so far. */
    public static Map<String, Object> file(List<Map<String, Object>> days, Map<String, Object> today, long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", PublicMarketExport.SCHEMA);
        out.put("generated_at", java.time.Instant.ofEpochMilli(now).toString());
        out.put("min_trades", MIN_TRADES);
        List<Map<String, Object>> kept = days.size() > PublicMarketExport.KEEP_DAYS
                ? days.subList(days.size() - PublicMarketExport.KEEP_DAYS, days.size()) : days;
        out.put("days", new ArrayList<>(kept));
        Map<String, Object> t = new LinkedHashMap<>(today);
        t.put("as_of", java.time.Instant.ofEpochMilli(now).toString());
        out.put("today", t);
        return out;
    }
}
