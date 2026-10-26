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
    public static Map<String, Object> file(Map<String, Object> latestPublic, Live live, long now) {
        return file(latestPublic, live, List.of(), now);
    }

    /** Days kept in {@code days} (owner/site 2026-10-26). */
    public static final int DAYS = 30;

    /**
     * {@code days}: up to {@link #DAYS} UTC days, oldest first, ending today: {@code date}, {@code mesos_total} (all
     * mesos held, from that day's nightly snapshot; null when there is none), {@code mesos_made} (sources: mob drops,
     * NPC sales, quest rewards) and {@code mesos_spent} (sinks: NPC shops, job advance and NPC fees, upkeep, FM tax),
     * from the stored economy counters (migration 032); null when the day has no counters. A day with neither is left
     * out. Pure.
     */
    public static List<Map<String, Object>> days(java.time.LocalDate today,
                                                 Map<java.time.LocalDate, Map<String, Long>> counters,
                                                 Map<java.time.LocalDate, Long> totals) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int back = DAYS - 1; back >= 0; back--) {
            java.time.LocalDate d = today.minusDays(back);
            Map<String, Long> c = counters.get(d);
            Long total = totals.get(d);
            if ((c == null || c.isEmpty()) && total == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("mesos_total", total);
            row.put("mesos_made", c == null || c.isEmpty() ? null : sum(c, MADE));
            row.put("mesos_spent", c == null || c.isEmpty() ? null : sum(c, SPENT));
            out.add(row);
        }
        return out;
    }

    static final List<String> MADE = List.of("mob_drops_human", "mob_drops_bot", "npc_sell", "quest_reward");
    static final List<String> SPENT = List.of("npc_buy", "job_master_fee", "npc_fee", "upkeep", "fm_tax");

    private static long sum(Map<String, Long> c, List<String> keys) {
        long s = 0;
        for (String k : keys) {
            s += Math.max(0, c.getOrDefault(k, 0L));
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> file(Map<String, Object> latestPublic, Live live, List<Map<String, Object>> days,
                                           long now) {
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
        out.put("generated_at", java.time.Instant.ofEpochMilli(now).toString()); // every site file has it (2026-10-26)
        out.put("snapshot_date", detail == null ? null : detail.get("date"));
        out.put("mesos_total", total);
        out.put("players_share", share);
        out.put("trades_24h", live.trades24h());
        out.put("shops_open", live.shopsOpen());
        out.put("mesos_removed_7d", live.mesosRemoved7d());
        out.put("bots_online_peak", live.botsOnlinePeak());
        out.put("days", days);
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
