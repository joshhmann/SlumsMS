package soloMapling.Economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyConfigTest {

    @AfterEach
    void tearDown() {
        EconomyConfig.useForTest(null);
        System.clearProperty("solomapling.economy.sinks.shopTaxPercent");
    }

    @Test
    void theShippedFileHasEveryOwnerNumberAndMatchesTheCodeDefaults() throws Exception {
        Map<String, Object> v;
        try (FileReader in = new FileReader(EconomyConfig.FILE)) {
            v = EconomyConfig.parse(in);
        }
        EconomyConfig.useForTest(v);
        assertEquals(7, EconomyConfig.getInt("priceBook.windowDays", -1));
        assertEquals(3.0, EconomyConfig.getDouble("sinks.shopTaxPercent", -1), 1e-9);
        assertEquals(40_000_000, EconomyConfig.getLong("botBuying.worldDailyBudget", -1));
        assertEquals(0.95, EconomyConfig.getDouble("bands.botBuysHigh", -1), 1e-9);
        assertEquals(7, EconomyConfig.getInt("gifts.perBotDays", -1));
        assertEquals(20, EconomyConfig.getInt("gifts.worldPerDay", -1));
        assertEquals(10, EconomyConfig.getInt("scrolling.gamblerPercent", -1));
        assertFalse(EconomyConfig.getBool("scrolling.whiteOnLongShots", true));
        assertEquals(2_000L, EconomyConfig.map("sinks.shopPrices").get(2120000));
        assertEquals(3_000_000L, EconomyConfig.map("cosmeticShop.items").get(5220000));
        assertTrue(EconomyConfig.map("saleRules.bossOnly").containsKey(1002357));
        // the file and the code agree, so a missing file changes nothing
        assertEquals(MesoSinks.DEFAULT_COSMETICS, EconomyConfig.map("cosmeticShop.items"));
    }

    @Test
    void aJvmPropertyWinsAndBadValuesFallBack() {
        EconomyConfig.useForTest(Map.of("sinks.shopTaxPercent", "5", "bands.maxRounds", "lots"));
        assertEquals(5.0, EconomyConfig.getDouble("sinks.shopTaxPercent", 3), 1e-9);
        System.setProperty("solomapling.economy.sinks.shopTaxPercent", "2");
        assertEquals(2.0, EconomyConfig.getDouble("sinks.shopTaxPercent", 3), 1e-9);
        assertEquals(4, EconomyConfig.getInt("bands.maxRounds", 4));
        assertEquals(9, EconomyConfig.getInt("missing.key", 9));
    }
}
