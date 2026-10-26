package soloMapling.Economy.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.Economy.PriceBook;
import soloMapling.Economy.PriceBook.Source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The pure writers behind the website Market's files: shapes, the rollup maths, the NX rate and the privacy floor. */
class MarketWritersTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 25);
    static final long T0 = PublicMarketExport.startOf(DAY);
    static final long HOUR = 3_600_000L;

    static PriceBook.Sample sample(int item, long price, long at, boolean player) {
        return new PriceBook.Sample(item, price, price, 1, player ? Source.MERCHANT : Source.BOT_SEED, player, at);
    }

    static String name(int id) {
        return id == 2000000 ? "Red Potion" : null;
    }

    // ── listings.json ──

    @Test
    @SuppressWarnings("unchecked")
    void listingsShowEveryLiveItemWithSellerNamesForBotsAndPlayers() {
        var botShop = new MarketListings.Shop(3, 910000007, "cheap pots", "BotBob", true, true, List.of(
                new MarketListings.Row(2000000, 100, 2, 5000, false),
                new MarketListings.Row(2070000, 800, 1, 30000, true),     // a star set is one unit
                new MarketListings.Row(2000001, 10, 0, 1000, false)));    // sold out: left out
        var playerShop = new MarketListings.Shop(1, 100000000, "selling stuff", "Josh", false, false, List.of(
                new MarketListings.Row(2000000, 50, 1, 2000, false)));
        Map<String, Object> out = MarketListings.build(List.of(botShop, playerShop), MarketWritersTest::name, T0);

        assertEquals("2026-10-25T00:00:00Z", out.get("generated_at"));
        assertEquals(1, out.get("schema"));
        assertFalse(out.containsKey("sample"), "the site shows a sample-data banner when this is present");
        assertEquals(2, out.get("shops"));
        assertEquals(1, out.get("bot_shops"));
        assertEquals(1, out.get("player_shops"));
        assertEquals(3, out.get("count"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("listings");
        // sorted by item, then by unit price
        Map<String, Object> cheapest = rows.get(0);
        assertEquals(2000000, cheapest.get("item_id"));
        assertEquals("Red Potion", cheapest.get("name"));
        assertEquals(40L, cheapest.get("price"));
        assertEquals(50, cheapest.get("qty"));
        assertEquals(1, cheapest.get("bundles"));
        assertEquals(2000L, cheapest.get("bundle_price"));
        assertEquals("Josh", cheapest.get("seller_name"));
        assertEquals("player", cheapest.get("seller_kind"));
        assertEquals("player_shop", cheapest.get("shop_type"));
        assertNull(cheapest.get("room"));
        assertEquals(1, cheapest.get("channel"));

        Map<String, Object> bot = rows.get(1);
        assertEquals(50L, bot.get("price"));
        assertEquals("BotBob", bot.get("seller_name"));
        assertEquals("bot", bot.get("seller_kind"));
        assertEquals("merchant", bot.get("shop_type"));
        assertEquals(7, bot.get("room"));
        assertEquals(910000007, bot.get("map_id"));
        assertEquals("cheap pots", bot.get("shop_title"));
        assertEquals("", rows.get(2).get("name"), "an item with no WZ name gets an empty name, never null");
        assertEquals(30000L, rows.get(2).get("price"), "a star set is priced per set");
        assertEquals(List.of("item_id", "name", "qty", "bundles", "price", "bundle_price", "channel", "map_id", "room",
                "shop_title", "seller_name", "seller_kind", "shop_type"), new ArrayList<>(cheapest.keySet()));
    }

    @Test
    void fmRoomsAreOneToTwentyTwo() {
        assertEquals(1, MarketListings.room(910000001));
        assertEquals(22, MarketListings.room(910000022));
        assertNull(MarketListings.room(910000000));
        assertNull(MarketListings.room(910000023));
    }

    // ── sales.json ──

    @Test
    @SuppressWarnings("unchecked")
    void salesAreNewestFirstCappedAt500WithTimesCutToTheHour() {
        List<PriceBook.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            samples.add(sample(2000000, 50 + i, T0 + i * 60_000L + 59_999, i % 2 == 0));
        }
        Map<String, Object> out = MarketSales.build(samples, MarketWritersTest::name, T0);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("sales");
        assertEquals(500, rows.size());
        assertEquals(500, out.get("count"));
        assertEquals(649L, rows.get(0).get("price"), "newest first");
        assertEquals(1, out.get("schema"));
        assertEquals("2026-10-25T09:00:00Z", rows.get(0).get("hour"), "sample at 09:59:59 shows as 09:00");
        assertEquals(List.of("item_id", "name", "price", "qty", "source", "player_side", "hour"),
                new ArrayList<>(rows.get(0).keySet()), "no parties, no exact times");
        assertEquals("bot_seed", rows.get(0).get("source"));
        assertEquals(false, rows.get(0).get("player_side"));
    }

    // ── prices/<day>.json ──

    @Test
    @SuppressWarnings("unchecked")
    void dailyRollupHasOpenHighLowCloseMedianCountAndPlayerShare() {
        List<PriceBook.Sample> samples = List.of(
                sample(2000000, 60, T0 + 5 * HOUR, false),
                sample(2000000, 40, T0 + 1 * HOUR, true),    // first of the day: open
                sample(2000000, 90, T0 + 3 * HOUR, false),
                sample(2000000, 50, T0 + 9 * HOUR, true),    // last of the day: close
                sample(2000000, 999, T0 - 1, true),          // yesterday: not counted
                sample(2000000, 999, T0 + 24 * HOUR, true),  // tomorrow: not counted
                sample(4000000, 7, T0 + 2 * HOUR, false));
        Map<String, Object> day = PriceRollup.day(DAY, samples, MarketWritersTest::name, Map.of("x", 1), T0);
        assertEquals("2026-10-25", day.get("date"));
        assertEquals(5, day.get("samples"));
        assertEquals(2, day.get("item_count"));
        assertEquals(1, day.get("schema"));
        Map<String, Object> red = (Map<String, Object>) ((Map<String, Object>) day.get("items")).get("2000000");
        assertEquals(40L, red.get("open"));
        assertEquals(50L, red.get("close"));
        assertEquals(90L, red.get("high"));
        assertEquals(40L, red.get("low"));
        assertEquals(55L, red.get("median"), "median of 40 50 60 90 = (50+60)/2");
        assertEquals(4, red.get("count"));
        assertEquals(0.5, red.get("player_share"));
        assertEquals(Map.of("x", 1), day.get("nx_rate"));
        Map<String, Object> one = (Map<String, Object>) ((Map<String, Object>) day.get("items")).get("4000000");
        assertEquals(7L, one.get("median"));
        assertEquals(0.0, one.get("player_share"));
    }

    @Test
    void medianOfOddAndEvenLists() {
        assertEquals(0, PriceRollup.median(List.of()));
        assertEquals(5, PriceRollup.median(List.of(5L)));
        assertEquals(2, PriceRollup.median(List.of(1L, 2L, 9L)));
        assertEquals(3, PriceRollup.median(List.of(1L, 2L, 5L, 9L)), "(2+5)/2 floored");
    }

    // ── NX rate ──

    static NxRate.Prices nx() {
        // 5390000 Diablo Messenger: 3,000 NX for 10 = 300 NX each; 5072000 Super Megaphone: 1,500 NX for 1
        return NxRate.Prices.of(Map.of(5390000, 300.0, 5072000, 1500.0));
    }

    @Test
    void nxRateIsTheDailyMedianOfMesosPerNxTimesAThousand() {
        List<PriceBook.Sample> s = List.of(
                sample(5072000, 3_000_000, T0 + HOUR, true),      // 2,000 mesos/NX
                sample(5072000, 2_400_000, T0 + 2 * HOUR, false), // 1,600
                sample(5390000, 600_000, T0 + 3 * HOUR, true),    // 2,000
                sample(5390000, 540_000, T0 + 4 * HOUR, false),   // 1,800
                sample(4031865, 190_000, T0 + 5 * HOUR, true),    // NX card 100: 1,900
                sample(2000000, 50, T0 + 6 * HOUR, true),         // not a cash item
                sample(5072000, 1, T0 - 1, true));                // another day
        Map<String, Object> e = NxRate.entry(DAY, s, nx());
        assertEquals("2026-10-25", e.get("date"));
        assertEquals(1_900_000L, e.get("mesos_per_1000nx"), "median of 1600 1800 1900 2000 2000 = 1900 x 1000");
        assertEquals(5, e.get("n_trades"));
        assertEquals(4, e.get("n_cash_resales"));
        assertEquals(1, e.get("n_code_trades"));
        assertEquals(0.4, e.get("bot_share"));
        assertEquals(List.of("date", "mesos_per_1000nx", "n_trades", "n_cash_resales", "n_code_trades", "bot_share"),
                new ArrayList<>(e.keySet()));
    }

    @Test
    void nxRateIsNullUnderFiveTrades() {
        List<PriceBook.Sample> s = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            s.add(sample(5072000, 3_000_000, T0 + i * HOUR, true));
        }
        Map<String, Object> e = NxRate.entry(DAY, s, nx());
        assertTrue(e.containsKey("mesos_per_1000nx"));
        assertNull(e.get("mesos_per_1000nx"));
        assertEquals(4, e.get("n_trades"));
        s.add(sample(5072000, 3_000_000, T0 + 5 * HOUR, true));
        assertEquals(2_000_000L, NxRate.entry(DAY, s, nx()).get("mesos_per_1000nx"));
        Map<String, Object> none = NxRate.entry(DAY, List.of(), nx());
        assertNull(none.get("mesos_per_1000nx"));
        assertEquals(0, none.get("n_trades"));
        assertEquals(0.0, none.get("bot_share"));
    }

    @Test
    void nxCardsAreCodeItemsAtTheirFaceValue() {
        NxRate.Prices p = NxRate.Prices.of(Map.of());
        assertEquals(100.0, p.nx(4031865));
        assertEquals(250.0, p.nx(4031866));
        assertTrue(p.codeItems().contains(4031866));
    }

    @Test
    void catalogPricesArePerUnitAndZeroPricedRowsAreLeftOut(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("catalog.tsv");
        Files.writeString(f, "# comment\n# itemId\tprice\tcount\n5390000\t3000\t10\t5\t1\t0\t2\tDiablo\n"
                + "5072000\t1500\t1\t5\t1\t0\t2\n5000000\t0\t1\t5\t1\t0\t2\nbad\trow\n");
        Map<Integer, Double> m = NxRate.readCatalog(f);
        assertEquals(300.0, m.get(5390000));
        assertEquals(1500.0, m.get(5072000));
        assertFalse(m.containsKey(5000000));
        assertEquals(2, m.size());
        assertTrue(NxRate.readCatalog(dir.resolve("missing.tsv")).isEmpty());
    }

    @Test
    void theRealCatalogueGivesFixedNxPrices() {
        Path real = Path.of("cashshop/catalog.tsv");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(real));
        Map<Integer, Double> m = NxRate.readCatalog(real);
        assertTrue(m.size() > 1000, "catalogue rows with a price: " + m.size());
        assertTrue(m.values().stream().allMatch(v -> v > 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nxRateFileKeepsAtMost365DaysPlusToday() {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            days.add(Map.of("date", DAY.minusDays(400 - i).toString()));
        }
        Map<String, Object> f = NxRate.file(days, NxRate.entry(DAY, List.of(), nx()), T0 + HOUR);
        List<Map<String, Object>> kept = (List<Map<String, Object>>) f.get("days");
        assertEquals(365, kept.size());
        assertEquals(DAY.minusDays(1).toString(), kept.get(364).get("date"));
        Map<String, Object> today = (Map<String, Object>) f.get("today");
        assertEquals("2026-10-25T01:00:00Z", today.get("as_of"));
        assertEquals("2026-10-25", today.get("date"));
        assertTrue(today.containsKey("mesos_per_1000nx"));
        assertEquals(5, f.get("min_trades"));
        assertEquals(1, f.get("schema"));
        assertEquals("as_of", new ArrayList<>(today.keySet()).getLast());
    }

    // ── economy-public.json ──

    static Map<String, Object> band(long count, long meso) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("totalMeso", meso);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicEconomyBlanksHumanBucketsUnderFive() {
        Map<String, Object> bands = new LinkedHashMap<>();
        bands.put("1-30", band(18, 3320));
        bands.put("31-70", band(1, 1_005_000));
        Map<String, Object> humans = new LinkedHashMap<>(band(19, 1_008_320));
        humans.put("levelBands", bands);
        Map<String, Object> bots = new LinkedHashMap<>(band(3, 900));
        Map<String, Object> supply = new LinkedHashMap<>();
        supply.put("humans", humans);
        supply.put("bots", bots);
        Map<String, Object> wealthHumans = new LinkedHashMap<>();
        wealthHumans.put("count", 4L);
        wealthHumans.put("median", 10L);
        wealthHumans.put("gini", 0.9);
        Map<String, Object> wealth = new LinkedHashMap<>();
        wealth.put("humans", wealthHumans);
        wealth.put("bots", new LinkedHashMap<>(Map.of("count", 3L, "gini", 0.4)));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("date", "2026-10-25");
        doc.put("mesoSupply", supply);
        doc.put("wealthDistribution", wealth);
        doc.put("topHolders", List.of(Map.of("id", 1)));

        Map<String, Object> out = PublicEconomy.derive(doc, T0);
        Map<String, Object> h = (Map<String, Object>) ((Map<String, Object>) out.get("mesoSupply")).get("humans");
        assertEquals(1_008_320L, h.get("totalMeso"), "19 people: shown");
        Map<String, Object> b = (Map<String, Object>) h.get("levelBands");
        assertEquals(18L, ((Map<String, Object>) b.get("1-30")).get("count"));
        Map<String, Object> lone = (Map<String, Object>) b.get("31-70");
        assertNull(lone.get("count"));
        assertNull(lone.get("totalMeso"), "one player's mesos never show");
        assertEquals(true, lone.get("suppressed"));
        Map<String, Object> wh = (Map<String, Object>) ((Map<String, Object>) out.get("wealthDistribution")).get("humans");
        assertEquals(4L, wh.get("count"));
        assertNull(wh.get("median"));
        assertNull(wh.get("gini"));
        assertEquals(0.4, ((Map<String, Object>) ((Map<String, Object>) out.get("wealthDistribution")).get("bots"))
                .get("gini"), "bots are never floored");
        assertFalse(out.containsKey("topHolders"));
        Map<String, Object> privacy = (Map<String, Object>) out.get("privacy");
        assertEquals(5, privacy.get("human_bucket_floor"));
        assertEquals(List.of("mesoSupply.humans.levelBands.31-70", "wealthDistribution.humans"), privacy.get("suppressed"));
        assertEquals(1_005_000L, band(1, 1_005_000).get("totalMeso"), "the input is not changed");
        assertEquals(1L, ((Map<String, Object>) bands.get("31-70")).get("count"), "the input is not changed");
    }
}
