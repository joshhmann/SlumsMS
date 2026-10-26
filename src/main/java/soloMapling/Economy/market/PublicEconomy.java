package soloMapling.Economy.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code economy-public.json}: the Market's economy headline in the shape the site was built against
 * ({@code as_of, mesos_total, players_share, trades_24h, shops_open, mesos_removed_7d, bots_online_peak}), plus
 * {@code detail}: the nightly {@code latest-public.json} of {@code EconomySnapshotService} with the plan's
 * human-bucket floor applied. With 5 to 15 friends, a level band holding one or two players would show those
 * players' mesos, so every aggregate over real players that covers fewer than {@link #FLOOR} of them is blanked
 * (its numbers become null and the bucket gets {@code "suppressed": true}); {@code players_share} is null then too.
 * Bot numbers, flows and item prices pass through unchanged. The blanked paths are in {@code privacy.suppressed}.
 */
public final class PublicEconomy {

    public static final int FLOOR = 5;

    private PublicEconomy() {
    }

    /**
     * What the headline needs besides the snapshot: price-board samples in the last 24 h (null when the database could not be read), open FM shops now, the
     * sinks of the last 7 nightly snapshots (null when there are none) and the most bots online in the last 24 h
     * (null before the first count).
     */
    public record Live(Integer trades24h, int shopsOpen, Long mesosRemoved7d, Integer botsOnlinePeak) {
    }

    /** The headline plus the floored snapshot; {@code latestPublic} may be null (no nightly snapshot yet). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> file(Map<String, Object> latestPublic, Live live, long now) {
        Map<String, Object> detail = latestPublic == null ? null : derive(latestPublic, now);
        Long total = null;
        Double share = null;
        if (detail != null && detail.get("mesoSupply") instanceof Map<?, ?> supply) {
            if (supply.get("totalMeso") instanceof Number n) {
                total = n.longValue();
            }
            if (supply.get("humans") instanceof Map<?, ?> h && !Boolean.TRUE.equals(h.get("suppressed"))
                    && h.get("totalMeso") instanceof Number hm && total != null && total > 0) {
                share = Math.round(hm.doubleValue() * 1_000_000.0 / total) / 1_000_000.0;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", PublicMarketExport.SCHEMA);
        out.put("as_of", java.time.Instant.ofEpochMilli(now).toString());
        out.put("snapshot_date", detail == null ? null : detail.get("date"));
        out.put("mesos_total", total);
        out.put("players_share", share);
        out.put("trades_24h", live.trades24h());
        out.put("shops_open", live.shopsOpen());
        out.put("mesos_removed_7d", live.mesosRemoved7d());
        out.put("bots_online_peak", live.botsOnlinePeak());
        out.put("detail", detail);
        return out;
    }

    /** The nightly snapshot with the floor applied (the file's {@code detail}). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> derive(Map<String, Object> latestPublic, long now) {
        Map<String, Object> out = deepCopy(latestPublic);
        List<String> suppressed = new ArrayList<>();
        if (out.get("mesoSupply") instanceof Map<?, ?> supply
                && supply.get("humans") instanceof Map<?, ?> humans) {
            Map<String, Object> h = (Map<String, Object>) humans;
            if (count(h.get("count")) < FLOOR) {
                blank(h, List.of("totalMeso"), "mesoSupply.humans", suppressed);
            }
            if (h.get("levelBands") instanceof Map<?, ?> bands) {
                for (var e : ((Map<String, Object>) bands).entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> band && count(band.get("count")) < FLOOR) {
                        blank((Map<String, Object>) band, List.of("count", "totalMeso"),
                                "mesoSupply.humans.levelBands." + e.getKey(), suppressed);
                    }
                }
            }
        }
        if (out.get("wealthDistribution") instanceof Map<?, ?> wealth
                && wealth.get("humans") instanceof Map<?, ?> humans) {
            Map<String, Object> h = (Map<String, Object>) humans;
            if (count(h.get("count")) < FLOOR) {
                List<String> keys = new ArrayList<>(h.keySet());
                keys.remove("count");
                blank(h, keys, "wealthDistribution.humans", suppressed);
            }
        }
        out.remove("topHolders"); // only ever in the private file; never let it through
        Map<String, Object> privacy = new LinkedHashMap<>();
        privacy.put("human_bucket_floor", FLOOR);
        privacy.put("suppressed", suppressed);
        out.put("privacy", privacy);
        return out;
    }

    private static void blank(Map<String, Object> bucket, List<String> keys, String path, List<String> suppressed) {
        for (String k : keys) {
            if (bucket.containsKey(k)) {
                bucket.put(k, null);
            }
        }
        bucket.put("suppressed", true);
        suppressed.add(path);
    }

    private static long count(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            out.put(e.getKey(), copy(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object copy(Object v) {
        if (v instanceof Map<?, ?> map) {
            return deepCopy((Map<String, Object>) map);
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(x -> out.add(copy(x)));
            return out;
        }
        return v;
    }
}
