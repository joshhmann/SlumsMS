package soloMapling.Economy.market;

import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** economy-public.json's {@code days}: 30 UTC days from the stored counters and the nightly snapshots. */
class PublicEconomyDaysTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Test
    void madeAndSpentFromTheCountersTotalFromTheSnapshots() {
        Map<LocalDate, Map<String, Long>> counters = Map.of(
                TODAY, Map.ofEntries(Map.entry("mob_drops_human", 100L), Map.entry("mob_drops_bot", 1_000L),
                        Map.entry("npc_sell", 50L), Map.entry("quest_reward", 5L), Map.entry("npc_buy", 300L),
                        Map.entry("job_master_fee", 10L), Map.entry("npc_fee", 20L), Map.entry("upkeep", 30L),
                        Map.entry("fm_tax", 40L), Map.entry("trade", 9_999L), Map.entry("fm_sale", 9_999L),
                        Map.entry("nx_earned", 7L)),
                TODAY.minusDays(1), Map.of("npc_buy", 1L),
                TODAY.minusDays(40), Map.of("npc_buy", 1L)); // older than 30 days: left out
        Map<LocalDate, Long> totals = Map.of(TODAY.minusDays(1), 73_509_278_408L, TODAY.minusDays(5), 70_000_000_000L);
        List<Map<String, Object>> days = PublicEconomy.days(TODAY, counters, totals);
        assertEquals(List.of("2026-10-21", "2026-10-25", "2026-10-26"), days.stream().map(d -> d.get("date")).toList());
        Map<String, Object> today = days.get(2);
        assertEquals(1_155L, today.get("mesos_made"), "transfers (trades, FM sales) and NX are neither");
        assertEquals(400L, today.get("mesos_spent"));
        assertNull(today.get("mesos_total"), "no snapshot for today yet");
        assertEquals(73_509_278_408L, days.get(1).get("mesos_total"));
        assertNull(days.get(0).get("mesos_made"), "no counters that day");
        assertEquals(List.of("date", "mesos_total", "mesos_made", "mesos_spent"), List.copyOf(today.keySet()));
    }

    @Test
    void theFileCarriesDaysAlways() {
        Map<?, ?> f = (Map<?, ?>) Json.parse(Json.write(PublicEconomy.file(null,
                new PublicEconomy.Live(null, 0, null, null), 1_793_000_000_000L)));
        assertEquals(List.of(), f.get("days"));
    }
}
