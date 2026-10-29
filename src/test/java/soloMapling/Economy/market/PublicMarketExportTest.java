package soloMapling.Economy.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.Economy.PriceBook;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** The exporter's runs over a fake server: which files, back-fill, pruning, the index and the NX history. */
class PublicMarketExportTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    static final long HOUR = 3_600_000L;

    @TempDir Path dir;

    static final class Fake implements PublicMarketExport.Sources {
        final List<PriceBook.Sample> samples = new ArrayList<>();
        final List<MarketListings.Shop> shops = new ArrayList<>();
        Map<String, Object> economy;
        int betweenCalls;
        boolean dbDown;

        public List<MarketListings.Shop> shops() { return shops; }

        public List<PriceBook.Sample> latestSamples(int limit) {
            if (dbDown) throw new IllegalStateException("db down");
            List<PriceBook.Sample> out = new ArrayList<>(samples);
            out.sort((a, b) -> Long.compare(b.at(), a.at()));
            return out.subList(0, Math.min(limit, out.size()));
        }

        public List<PriceBook.Sample> samplesBetween(long from, long to) {
            betweenCalls++;
            if (dbDown) throw new IllegalStateException("db down");
            return samples.stream().filter(s -> s.at() >= from && s.at() < to).toList();
        }

        public NxRate.Prices nxPrices() { return NxRate.Prices.of(Map.of(5072000, 1500.0)); }
        public Map<String, Object> latestPublicEconomy() { return economy; }
        final Map<LocalDate, Long> sinks = new java.util.HashMap<>();
        int bots = 42;
        public Map<LocalDate, Long> dailySinks() { return sinks; }
        public int botsOnline() { return bots; }
        public IntFunction<String> names() { return id -> "item" + id; }
    }

    static PriceBook.Sample at(LocalDate d, int hour, int item, long price) {
        return new PriceBook.Sample(item, price, price, 1, PriceBook.Source.BOT_SEED, false,
                PublicMarketExport.startOf(d) + hour * HOUR);
    }

    PublicMarketExport.Runner runner(Fake f, AtomicLong clock) {
        return new PublicMarketExport.Runner(dir, f, clock::get);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path p) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(p));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQuarterRunWritesEveryFileAndBackFillsRecentDaysWithSales() throws Exception {
        Fake f = new Fake();
        f.samples.add(at(TODAY.minusDays(3), 4, 2000000, 50));
        f.samples.add(at(TODAY.minusDays(1), 4, 2000000, 60));
        f.samples.add(at(TODAY.minusDays(30), 4, 2000000, 70)); // older than the back-fill window
        f.samples.add(at(TODAY, 1, 2000000, 55));
        AtomicLong clock = new AtomicLong(PublicMarketExport.startOf(TODAY) + 2 * HOUR);
        var r = runner(f, clock).quarter();

        assertTrue(r.failed().isEmpty(), r.failed().toString());
        for (String name : PublicMarketExport.FILES) {
            assertTrue(Files.isRegularFile(dir.resolve(name)), name);
            assertNotNull(read(dir.resolve(name)));
        }
        assertTrue(Files.exists(dir.resolve("prices/" + TODAY.minusDays(3) + ".json")));
        assertTrue(Files.exists(dir.resolve("prices/" + TODAY.minusDays(1) + ".json")));
        assertFalse(Files.exists(dir.resolve("prices/" + TODAY.minusDays(2) + ".json")), "no sales: no file");
        assertFalse(Files.exists(dir.resolve("prices/" + TODAY + ".json")), "today is not finished");
        assertFalse(Files.exists(dir.resolve("prices/" + TODAY.minusDays(30) + ".json")));
        assertEquals(List.of(TODAY.minusDays(3).toString(), TODAY.minusDays(1).toString()),
                read(dir.resolve("prices/index.json")).get("dates"));
        assertEquals(4L, read(dir.resolve("sales.json")).get("count"));
        assertEquals(2L, ((List<?>) read(dir.resolve("nx-rate.json")).get("days")).size());
        Map<String, Object> econ = read(dir.resolve("economy-public.json"));
        assertNull(econ.get("detail"), "no nightly snapshot yet");
        assertNull(econ.get("mesos_total"));
        assertEquals(2L, econ.get("trades_24h"), "the samples of the last 24 h");
        assertNotNull(econ.get("player_trades_24h"), "player-side count sits next to trades_24h");
        assertTrue(((Number) econ.get("player_trades_24h")).longValue() <= 2L);
        assertEquals(0L, econ.get("shops_open"));
        assertEquals(42L, econ.get("bots_online_peak"));
        assertNull(econ.get("mesos_removed_7d"));
        try {
            assertEquals("rw-r--r--", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(dir.resolve("listings.json"))));
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system
        }

        // a second run does not ask the database again for the days it already has or knows are empty
        int calls = f.betweenCalls;
        var same = new PublicMarketExport.Runner(dir, f, clock::get);
        same.quarter();
        int first = f.betweenCalls - calls;
        same.quarter();
        int second = f.betweenCalls - calls - first;
        assertEquals(2, second, "only today's NX rate and the 24 h trade count are read again");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withMarketGzipDaysOldDayFilesAreCompressedAndStillFeedTheIndexAndNxRate() throws Exception {
        Files.createDirectories(dir.resolve("prices"));
        LocalDate old = TODAY.minusDays(100);
        Files.writeString(dir.resolve("prices/" + old + ".json"), "{\"nx_rate\":{\"date\":\"" + old + "\"}}");
        System.setProperty("slums.history.marketGzipDays", "30");
        try {
            var r = runner(new Fake(), new AtomicLong(PublicMarketExport.startOf(TODAY) + 5 * 60_000L)).nightly();
            assertTrue(r.failed().isEmpty(), r.failed().toString());
        } finally {
            System.clearProperty("slums.history.marketGzipDays");
        }
        assertFalse(Files.exists(dir.resolve("prices/" + old + ".json")));
        assertTrue(Files.exists(dir.resolve("prices/" + old + ".json.gz")), "compressed, not deleted");
        assertEquals(List.of(old.toString(), TODAY.minusDays(1).toString()), read(dir.resolve("prices/index.json")).get("dates"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) read(dir.resolve("nx-rate.json")).get("days");
        assertEquals(old.toString(), days.get(0).get("date"), "the NX history is read from the compressed day");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nightlyWritesYesterdayEvenWithoutSalesAndKeepsEveryDayFileButWindowsNxRateTo365() throws Exception {
        Files.createDirectories(dir.resolve("prices"));
        LocalDate old = TODAY.minusDays(366), kept = TODAY.minusDays(365);
        Files.writeString(dir.resolve("prices/" + old + ".json"), "{\"nx_rate\":{\"date\":\"" + old + "\"}}");
        Files.writeString(dir.resolve("prices/" + kept + ".json"), "{\"nx_rate\":{\"date\":\"" + kept + "\"}}");
        Files.writeString(dir.resolve("prices/notes.json"), "{}");
        Fake f = new Fake();
        AtomicLong clock = new AtomicLong(PublicMarketExport.startOf(TODAY) + 5 * 60_000L);
        var r = runner(f, clock).nightly();

        assertTrue(r.failed().isEmpty(), r.failed().toString());
        Path y = dir.resolve("prices/" + TODAY.minusDays(1) + ".json");
        assertTrue(Files.exists(y), "yesterday is written even with no sales");
        assertEquals(0L, read(y).get("samples"));
        assertTrue(Files.exists(dir.resolve("prices/" + old + ".json")), "history is kept: nothing is deleted by default");
        assertTrue(Files.exists(dir.resolve("prices/" + kept + ".json")));
        assertTrue(Files.exists(dir.resolve("prices/notes.json")));
        assertEquals(List.of(old.toString(), kept.toString(), TODAY.minusDays(1).toString()),
                read(dir.resolve("prices/index.json")).get("dates"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) read(dir.resolve("nx-rate.json")).get("days");
        assertEquals(List.of(kept.toString(), TODAY.minusDays(1).toString()),
                days.stream().map(d -> d.get("date")).toList(), "the history comes from the day files");
        assertNull(days.get(1).get("mesos_per_1000nx"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nxHistoryAndTodayComeFromTheSales() throws Exception {
        Fake f = new Fake();
        for (int i = 0; i < 5; i++) {
            f.samples.add(at(TODAY.minusDays(1), i, 5072000, 3_000_000));   // 2,000 mesos/NX
            f.samples.add(at(TODAY, i, 5072000, 1_500_000 + i));            // ~1,000 mesos/NX
        }
        AtomicLong clock = new AtomicLong(PublicMarketExport.startOf(TODAY) + 10 * HOUR);
        runner(f, clock).all();
        Map<String, Object> nx = read(dir.resolve("nx-rate.json"));
        Map<String, Object> y = ((List<Map<String, Object>>) nx.get("days")).getLast();
        assertEquals(TODAY.minusDays(1).toString(), y.get("date"));
        assertEquals(2_000_000L, y.get("mesos_per_1000nx"));
        assertEquals(1.0, y.get("bot_share"));
        Map<String, Object> today = (Map<String, Object>) nx.get("today");
        assertEquals(TODAY.toString(), today.get("date"));
        assertEquals(1_000_001L, today.get("mesos_per_1000nx"));
        assertEquals(5L, today.get("n_trades"));
        assertEquals("2026-10-26T10:00:00Z", today.get("as_of"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void economyPublicHasTheSiteHeadlineAndTheFlooredSnapshot() throws Exception {
        Fake f = new Fake();
        Map<String, Object> humans = new java.util.LinkedHashMap<>(Map.of("count", 19L, "totalMeso", 1_000L));
        Map<String, Object> supply = new java.util.LinkedHashMap<>(Map.of("totalMeso", 4_000L, "humans", humans));
        f.economy = new java.util.LinkedHashMap<>(Map.of("date", "2026-10-25", "topHolders", List.of(),
                "mesoSupply", supply));
        for (int back = 1; back <= 9; back++) {
            f.sinks.put(TODAY.minusDays(back), 100L * back);   // only the 7 days before today count
        }
        f.sinks.put(TODAY, 99_999L);
        f.shops.add(new MarketListings.Shop(1, 910000001, "t", "Bot", true, true,
                List.of(new MarketListings.Row(2000000, 1, 1, 10, false))));
        AtomicLong clock = new AtomicLong(PublicMarketExport.startOf(TODAY) + HOUR);
        var runner = runner(f, clock);
        runner.quarter();
        f.bots = 10;
        clock.addAndGet(15 * 60_000L);
        runner.quarter();
        Map<String, Object> e = read(dir.resolve("economy-public.json"));
        assertEquals(List.of("schema", "as_of", "generated_at", "snapshot_date", "mesos_total", "players_share",
                "trades_24h", "player_trades_24h", "shops_open", "mesos_removed_7d", "bots_online_peak", "days", "detail"),
                new ArrayList<>(e.keySet()));
        assertEquals(1L, e.get("schema"));
        assertEquals("2026-10-26T01:15:00Z", e.get("as_of"));
        assertEquals("2026-10-25", e.get("snapshot_date"));
        assertEquals(4_000L, e.get("mesos_total"));
        assertEquals(0.25, e.get("players_share"));
        assertEquals(1L, e.get("shops_open"));
        assertEquals(2_800L, e.get("mesos_removed_7d"), "100+200+...+700");
        assertEquals(42L, e.get("bots_online_peak"), "the peak over the last 24 h, not the latest count");
        Map<String, Object> detail = (Map<String, Object>) e.get("detail");
        assertFalse(detail.containsKey("topHolders"));
        assertEquals(5L, ((Map<String, Object>) detail.get("privacy")).get("human_bucket_floor"));

        // fewer than 5 players: their share is not published
        humans.put("count", 3L);
        runner.quarter();
        assertNull(read(dir.resolve("economy-public.json")).get("players_share"));
        // a day later the old counts are gone from the peak
        clock.addAndGet(java.util.concurrent.TimeUnit.DAYS.toMillis(1));
        runner.quarter();
        assertEquals(10L, read(dir.resolve("economy-public.json")).get("bots_online_peak"));
    }

    @Test
    void aFailingSourceFailsOnlyItsOwnFilesAndNeverThrows() throws Exception {
        Fake f = new Fake();
        f.dbDown = true;
        f.shops.add(new MarketListings.Shop(1, 910000001, "t", "Bot", true, true,
                List.of(new MarketListings.Row(2000000, 1, 1, 10, false))));
        var runner = runner(f, new AtomicLong(PublicMarketExport.startOf(TODAY)));
        var r = runner.quarter();
        assertTrue(Files.exists(dir.resolve("listings.json")), "listings need no database");
        assertTrue(Files.exists(dir.resolve("economy-public.json")));
        assertNull(read(dir.resolve("economy-public.json")).get("trades_24h"), "unknown, not 0");
        assertNull(read(dir.resolve("economy-public.json")).get("player_trades_24h"), "unknown, not 0");
        assertFalse(Files.exists(dir.resolve("sales.json")));
        assertTrue(r.failed().stream().anyMatch(s -> s.startsWith("sales.json")), r.failed().toString());
        assertDoesNotThrow(runner::quarterSafely);
        assertDoesNotThrow(runner::nightlySafely);
        // the database comes back: the days it could not read are back-filled, not remembered as empty
        f.dbDown = false;
        f.samples.add(at(TODAY.minusDays(2), 1, 2000000, 5));
        runner.quarter();
        assertTrue(Files.exists(dir.resolve("prices/" + TODAY.minusDays(2) + ".json")));
    }

    @Test
    void theNightlyRunIsAt0005Utc() {
        assertEquals(5 * 60, PublicMarketExport.secondsUntilNightly(LocalDateTime.of(2026, 10, 26, 0, 0)));
        assertEquals(24 * 3600 - 60, PublicMarketExport.secondsUntilNightly(LocalDateTime.of(2026, 10, 26, 0, 6)));
        assertEquals(24 * 3600, PublicMarketExport.secondsUntilNightly(LocalDateTime.of(2026, 10, 26, 0, 5)));
    }

    @Test
    void offByDefault() {
        assertFalse(Boolean.getBoolean(PublicMarketExport.ENABLED));
        assertFalse(PublicMarketExport.enabled());
    }

    @Test
    void aListingsRunWritesOnlyListings() throws Exception {
        Fake f = new Fake();
        AtomicLong clock = new AtomicLong(PublicMarketExport.startOf(TODAY) + 2 * HOUR);
        var r = runner(f, clock).listings();

        assertTrue(r.failed().isEmpty(), r.failed().toString());
        assertTrue(Files.isRegularFile(dir.resolve("listings.json")));
        assertFalse(Files.exists(dir.resolve("sales.json")), "sales stay on the 15-minute run");
        assertFalse(Files.exists(dir.resolve("nx-rate.json")));
    }
}
