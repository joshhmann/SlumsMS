package soloMapling.itemPool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyStore;
import soloMapling.Economy.PriceBook;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 162: a scrolled equip's reproduction cost follows what its scrolls really sell for (ScrollMarketPrice). */
class ScrollMarketValuationTest {

    static final int TEN = 2043002, SIXTY = 2043001;   // 10% +5 at 1.2m, 60% +2 at 300k (yaml)
    static final long DAY = 86_400_000L;

    final AtomicLong now = new AtomicLong(100 * DAY);

    @BeforeEach
    void setUp() {
        System.setProperty("slums.scroll.ev", "true");
        EconomyConfig.useForTest(Map.of());
        PriceBook.useForTest(new EconomyStore.Memory(), Runnable::run, now::get,
                item -> item.getItemId() == TEN ? 1_200_000L : item.getItemId() == SIXTY ? 300_000L : 0L);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("slums.scroll.ev");
        System.clearProperty("slums.scroll.marketPrice");
        PriceBook.useForTest(null, null, null, null);
        EconomyConfig.useForTest(null);
    }

    private static List<UpgradeSimulator.Scroll> weaponSet() {
        return List.of(UpgradeSimulator.Scroll.market(TEN, 1_200_000, 10, 5),
                UpgradeSimulator.Scroll.market(SIXTY, 300_000, 60, 2));
    }

    @Test
    void scrolledGearIsRepricedWhenItsScrollsSellForMore() {
        long atYaml = UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, 10);
        assertEquals(2_000_000, UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, 0), "clean stays the base");
        // two weeks of 60% scrolls selling at 600k (2x the yaml)
        for (int d = 0; d < 14; d++) {
            for (int i = 0; i < 8; i++) {
                PriceBook.record(new PriceBook.Sample(SIXTY, 600_000, 600_000, 1, PriceBook.Source.SHOP, true,
                        now.get()));
            }
            now.addAndGet(DAY);
        }
        assertTrue(weaponSet().get(1).price > 500_000, "the 60% is now priced " + weaponSet().get(1).price);
        long atMarket = UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, 10);
        assertTrue(atMarket > atYaml * 1.2, "+10 at the yaml " + atYaml + ", at the market " + atMarket);
        // the switch off: the yaml prices again
        System.setProperty("slums.scroll.marketPrice", "false");
        assertEquals(atYaml, UpgradeSimulator.evPrice(weaponSet(), 2_000_000, 7, 10));
    }
}
