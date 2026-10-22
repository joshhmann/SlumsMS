package soloMapling.Economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.Trade;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MesoSinksTest {

    EconomyTestSupport t;

    @BeforeEach
    void setUp() {
        t = new EconomyTestSupport();
    }

    @AfterEach
    void tearDown() {
        EconomyTestSupport.reset();
    }

    @Test
    void theShopTaxIsThreePercentOrTheV83FeeWhicheverIsMore() {
        assertEquals(3_000, MesoSinks.shopFee(100_000), "v83: 0.8%");
        assertEquals(30_000, MesoSinks.shopFee(1_000_000), "v83: 1.8%");
        assertEquals(150_000, MesoSinks.shopFee(5_000_000), "v83: 3%, the same");
        assertEquals(Trade.getFee(25_000_000), MesoSinks.shopFee(25_000_000), "v83: 5% stays");
        assertEquals(Trade.getFee(200_000_000), MesoSinks.shopFee(200_000_000), "v83: 6% stays");
        assertEquals(30, MesoSinks.shopFee(1_000));
        EconomyConfig.useForTest(Map.of("sinks.shopTaxPercent", "0"));
        assertEquals(Trade.getFee(1_000_000), MesoSinks.shopFee(1_000_000), "tax off: v83 as before");
    }

    @Test
    void aShopSaleBooksTheTaxAndASample() {
        MesoSinks.onShopSale(EconomyTestSupport.item(2040804, 1), 1_000_000, 30_000, true, true, false);
        assertEquals(30_000, EconomyMeters.get(EconomyMeters.TAX));
        assertEquals(1, t.store.samples.size());
        assertEquals(PriceBook.Source.SHOP, t.store.samples.get(0).source());
        MesoSinks.onShopSale(EconomyTestSupport.item(2040804, 1), 1_000_000, 30_000, false, false, true);
        assertEquals(PriceBook.Source.BOT_SEED, t.store.samples.get(1).source());
        assertFalse(t.store.samples.get(1).playerSide());
    }

    @Test
    void storageCarriesNoUpkeep() {
        // owner, 2026-10-21: no storage upkeep (hidden fees feel bad); the keeper's v83 fee is the only charge
        Map<String, Object> shipped;
        try (java.io.FileReader in = new java.io.FileReader(EconomyConfig.FILE)) {
            shipped = EconomyConfig.parse(in);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(shipped.keySet().stream().noneMatch(k -> k.toLowerCase().contains("storage")), "no storage fee key");
        assertFalse(java.util.Arrays.stream(MesoSinks.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().toLowerCase().contains("storage")), "no storage hook left");
    }

    @Test
    void npcPriceBumpsFromTheYaml() {
        assertEquals(30, MesoSinks.npcPrice(2120000, 30), "no yaml in this test: unchanged");
        EconomyConfig.useForTest(Map.of("sinks.shopPrices", Map.of("2120000", "2000")));
        assertEquals(2_000, MesoSinks.npcPrice(2120000, 30));
        assertEquals(400, MesoSinks.npcPrice(2030000, 400));
        assertEquals(0, MesoSinks.npcPrice(2120000, 0), "a pitch/token item keeps its currency");
    }

    @Test
    void theCosmeticsMerchantSellsCouponsAndTicketsForBigMesos() {
        List<int[]> stock = MesoSinks.cosmeticStock();
        assertTrue(stock.stream().anyMatch(s -> s[0] == 5220000 && s[1] >= 1_000_000), "gachapon tickets, pricey");
        assertTrue(stock.stream().anyMatch(s -> s[0] / 1000 == 5150), "hair coupons");
        assertTrue(stock.stream().allMatch(s -> s[1] >= 1_000_000), "every line is a real sink");
        assertTrue(MesoSinks.isCosmeticNpc(9209006));
        assertFalse(MesoSinks.isCosmeticNpc(9100100));
    }
}
