package soloMapling.Economy.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EconomySnapshotServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EconomyCounters.resetForTest();
    }

    @Test
    void snapshotCreatesValidPrivateAndPublicFiles() throws IOException {
        EconomySnapshotService service = new EconomySnapshotService(tempDir);

        // Supply mock populations
        List<EconomySnapshotService.CharacterRecord> humans = List.of(
                new EconomySnapshotService.CharacterRecord(101, 15, 50_000, false),
                new EconomySnapshotService.CharacterRecord(102, 55, 500_000, false),
                new EconomySnapshotService.CharacterRecord(103, 100, 5_000_000, false),
                new EconomySnapshotService.CharacterRecord(104, 150, 50_000_000, false)
        );

        List<EconomySnapshotService.CharacterRecord> bots = List.of(
                new EconomySnapshotService.CharacterRecord(1000000001, 20, 20_000, true),
                new EconomySnapshotService.CharacterRecord(1000000002, 60, 200_000, true),
                new EconomySnapshotService.CharacterRecord(1000000003, 110, 2_000_000, true),
                new EconomySnapshotService.CharacterRecord(1000000004, 160, 10_000_000, true)
        );

        // Record some counters
        EconomyCounters.recordMobMesoDrop(false, 100_000);
        EconomyCounters.recordMobMesoDrop(true, 500_000);
        EconomyCounters.recordNpcSell(50_000);
        EconomyCounters.recordQuestReward(25_000);
        EconomyCounters.recordNpcBuy(40_000);
        EconomyCounters.recordJobMasterFee(800_000);
        EconomyCounters.recordUpkeep(30_000);
        EconomyCounters.recordFmTax(15_000);
        EconomyCounters.recordTrade(200_000);
        EconomyCounters.recordFmSale(600_000);
        EconomyCounters.recordMapleLeafGained(100);
        EconomyCounters.recordMapleLeafSpent(40);
        EconomyCounters.recordNxEarned(1000);
        EconomyCounters.recordNxSpent(500);

        // Item prices
        Map<Integer, List<Long>> itemPrices = Map.of(
                4001126, List.of(100L, 120L, 110L),
                2000005, List.of(5000L, 5500L, 5200L, 6000L)
        );

        LocalDate date = LocalDate.of(2026, 10, 25);
        var result = service.executeSnapshot(date, () -> humans, () -> bots, () -> itemPrices);

        assertNotNull(result);
        assertEquals(55_550_000L, result.totalHumanMeso());
        assertEquals(12_220_000L, result.totalBotMeso());

        // Verify files exist
        Path privateFile = tempDir.resolve("2026-10-25-private.json");
        Path publicFile = tempDir.resolve("2026-10-25-public.json");
        Path latestPrivate = tempDir.resolve("latest-private.json");
        Path latestPublic = tempDir.resolve("latest-public.json");

        assertTrue(Files.exists(privateFile), "Private file must exist");
        assertTrue(Files.exists(publicFile), "Public file must exist");
        assertTrue(Files.exists(latestPrivate), "Latest private file must exist");
        assertTrue(Files.exists(latestPublic), "Latest public file must exist");

        String privateJson = Files.readString(privateFile);
        String publicJson = Files.readString(publicFile);

        // Public JSON must NOT contain character IDs or any player names
        assertFalse(publicJson.contains("101"), "Public JSON must not contain character ID 101");
        assertFalse(publicJson.contains("102"), "Public JSON must not contain character ID 102");
        assertFalse(publicJson.contains("1000000001"), "Public JSON must not contain bot ID 1000000001");
        assertFalse(publicJson.contains("topHolders"), "Public JSON must not contain topHolders");

        // Private JSON CAN contain character IDs in topHolders
        assertTrue(privateJson.contains("topHolders"), "Private JSON must contain topHolders");
        assertTrue(privateJson.contains("104"), "Private JSON must contain top holder ID 104");

        // Parse JSON documents to ensure they are valid JSON
        Object parsedPublic = Json.parse(publicJson);
        Object parsedPrivate = Json.parse(privateJson);
        assertTrue(parsedPublic instanceof Map<?, ?>);
        assertTrue(parsedPrivate instanceof Map<?, ?>);

        @SuppressWarnings("unchecked")
        Map<String, Object> pubMap = (Map<String, Object>) parsedPublic;
        assertEquals("2026-10-25", pubMap.get("date"));

        @SuppressWarnings("unchecked")
        Map<String, Object> mesoSupply = (Map<String, Object>) pubMap.get("mesoSupply");
        assertNotNull(mesoSupply);
        assertEquals(67_770_000L, ((Number) mesoSupply.get("totalMeso")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> humansSupply = (Map<String, Object>) mesoSupply.get("humans");
        assertEquals(55_550_000L, ((Number) humansSupply.get("totalMeso")).longValue());
        assertEquals(4, ((Number) humansSupply.get("count")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> bands = (Map<String, Object>) humansSupply.get("levelBands");
        assertNotNull(bands);
        assertTrue(bands.containsKey("1-30"));
        assertTrue(bands.containsKey("31-70"));
        assertTrue(bands.containsKey("71-120"));
        assertTrue(bands.containsKey("121-200"));

        @SuppressWarnings("unchecked")
        Map<String, Object> flows = (Map<String, Object>) pubMap.get("flows");
        assertNotNull(flows);
        assertEquals(675_000L, ((Number) flows.get("totalSources")).longValue());
        assertEquals(885_000L, ((Number) flows.get("totalSinks")).longValue());
        assertEquals(-210_000L, ((Number) flows.get("netMesoDelta")).longValue());

        // Check currencies
        @SuppressWarnings("unchecked")
        Map<String, Object> leaves = (Map<String, Object>) flows.get("mapleLeaf");
        assertEquals(100L, ((Number) leaves.get("gained")).longValue());
        assertEquals(40L, ((Number) leaves.get("spent")).longValue());
        assertEquals(60L, ((Number) leaves.get("net")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> nx = (Map<String, Object>) flows.get("nx");
        assertEquals(1000L, ((Number) nx.get("earned")).longValue());
        assertEquals(500L, ((Number) nx.get("spent")).longValue());
        assertEquals(500L, ((Number) nx.get("net")).longValue());

        // Check item prices
        @SuppressWarnings("unchecked")
        Map<String, Object> prices = (Map<String, Object>) pubMap.get("itemPrices");
        assertNotNull(prices);
        assertTrue(prices.containsKey("4001126"));
        assertTrue(prices.containsKey("2000005"));
    }

    @Test
    void onDemandSnapshotKeepsTheNightlyWindowAndFiles() throws IOException {
        EconomySnapshotService service = new EconomySnapshotService(tempDir);
        EconomyCounters.recordNpcSell(1_000);

        service.executeSnapshot(LocalDate.of(2026, 10, 25), List::of, List::of, Map::of, false);

        // the GM's peek must not reset the day's flows or write the dated / latest files the dashboard reads
        assertEquals(1_000L, EconomyCounters.peek().npcSellMesos());
        assertFalse(Files.exists(tempDir.resolve("2026-10-25-public.json")));
        assertFalse(Files.exists(tempDir.resolve("latest-public.json")));
        assertTrue(Files.exists(tempDir.resolve("ondemand-public.json")));

        service.executeSnapshot(LocalDate.of(2026, 10, 25), List::of, List::of, Map::of, true);
        assertEquals(0L, EconomyCounters.peek().npcSellMesos());
        assertTrue(Files.exists(tempDir.resolve("latest-public.json")));
    }

    @Test
    void nightlyFileIsLabelledWithTheDayItCloses() {
        // fires at 00:00 UTC on the 26th: the flows belong to the 25th, even with a little drift either side
        assertEquals(LocalDate.of(2026, 10, 25),
                EconomySnapshotService.nightlyLabel(java.time.LocalDateTime.of(2026, 10, 26, 0, 0, 1)));
        assertEquals(LocalDate.of(2026, 10, 25),
                EconomySnapshotService.nightlyLabel(java.time.LocalDateTime.of(2026, 10, 25, 23, 59, 59)));
    }
}
