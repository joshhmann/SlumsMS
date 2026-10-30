package soloMapling.Economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 162: a scroll's price learned from real sales (ScrollMarketPrice), behind -Dslums.scroll.marketPrice. */
class ScrollMarketPriceTest {

    static final int S60 = 2043001;     // a plain 60% scroll, yaml 100k here
    static final long YAML = 100_000;

    EconomyTestSupport t;

    @BeforeEach
    void setUp() {
        System.setProperty("slums.scroll.marketPrice", "true");
        t = new EconomyTestSupport();
        t.statics.put(S60, YAML);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("slums.scroll.marketPrice");
        EconomyTestSupport.reset();
    }

    private void sell(long price, int qty, boolean player) {
        assertTrue(PriceBook.record(new PriceBook.Sample(S60, price, price, qty, player ? PriceBook.Source.SHOP
                : PriceBook.Source.BOT_SEED, player, t.now.get())));
    }

    @Test
    void noSalesMeansTheYamlPrice() {
        assertEquals(YAML, ScrollMarketPrice.price(S60, YAML));
        assertEquals(Integer.valueOf(100_000), ScrollMarketPrice.price(S60, Integer.valueOf(100_000)));
        assertEquals(null, ScrollMarketPrice.price(S60, (Integer) null));
        assertEquals(YAML, PriceBook.fairValue(EconomyTestSupport.item(S60, 1)));
        // not a scroll: never touched
        assertEquals(5_000, ScrollMarketPrice.price(4004000, 5_000));
    }

    @Test
    void learningMovesTowardRealSalesButAtMostADayStepADay() {
        for (int i = 0; i < 30; i++) {
            sell(300_000, 1, true);
        }
        ScrollMarketPrice.View day1 = ScrollMarketPrice.view(S60, YAML);
        assertEquals(30, day1.sales());
        // 30 sales at 3x in one day: the estimate moves 15% that day, no further
        assertEquals(115_000, day1.learned());
        assertTrue(day1.price() > YAML && day1.price() <= 115_000, day1.line("s60"));
        long previous = day1.learned();
        for (int d = 1; d <= 12; d++) {
            t.now.addAndGet(EconomyTestSupport.DAY);
            for (int i = 0; i < 8; i++) {
                sell(300_000, 1, true);
            }
            long learned = ScrollMarketPrice.view(S60, YAML).learned();
            assertTrue(learned > previous, "day " + d + ": " + learned);
            assertTrue(learned <= Math.round(previous * 1.15) + 1, "day " + d + ": " + learned + " vs " + previous);
            previous = learned;
        }
        // nearly two weeks of steady sales at 300k: it got there
        long settled = ScrollMarketPrice.price(S60, YAML);
        assertTrue(settled > 270_000 && settled <= 300_000, "settled at " + settled);
        // and what a bot asks for one (the board's fair value) is that learned price, not the yaml's 100k
        assertEquals(settled, PriceBook.fairValue(EconomyTestSupport.item(S60, 1)));
    }

    @Test
    void oneSaleOnlyNudgesThePriceAndBotFillsCountLess() {
        sell(110_000, 1, true);
        ScrollMarketPrice.View v = ScrollMarketPrice.view(S60, YAML);
        assertEquals(102_000, v.learned());   // 20% of the way
        // one sale: 1 / (1 + 4) learned, the rest the yaml price
        assertEquals(100_400, v.price());
        PriceBook.useForTest(t.store, Runnable::run, t.now::get, item -> item.getItemId() == S60 ? YAML : 0L);
        t.store.samples.clear();
        sell(110_000, 1, false);   // a bot bought it: half the weight
        assertTrue(ScrollMarketPrice.view(S60, YAML).learned() < 102_000);
        // a bigger fill weighs more (sqrt of the quantity, up to 3x)
        PriceBook.useForTest(t.store, Runnable::run, t.now::get, item -> item.getItemId() == S60 ? YAML : 0L);
        t.store.samples.clear();
        sell(110_000, 9, true);
        assertTrue(ScrollMarketPrice.view(S60, YAML).learned() > 102_000);
    }

    @Test
    void outliersAreIgnored() {
        sell(10_000, 1, true);       // 0.1x the yaml price
        sell(1_000_000, 1, true);    // 10x
        ScrollMarketPrice.View v = ScrollMarketPrice.view(S60, YAML);
        assertEquals(0, v.sales());
        assertEquals(2, v.outliers());
        assertEquals(YAML, ScrollMarketPrice.price(S60, YAML));
        sell(120_000, 1, true);
        assertEquals(1, ScrollMarketPrice.view(S60, YAML).sales());
    }

    @Test
    void neverLeavesTheBoardsBandAroundTheYamlPrice() {
        for (int d = 0; d < 40; d++) {
            for (int i = 0; i < 8; i++) {
                sell(Math.round(Math.max(YAML, ScrollMarketPrice.view(S60, YAML).learned()) * 4.9), 1, true);
            }
            t.now.addAndGet(EconomyTestSupport.DAY);
        }
        assertEquals(4 * YAML, ScrollMarketPrice.view(S60, YAML).learned());
    }

    @Test
    void theLearnedPriceSurvivesARestartAndFadesWithoutSales() {
        for (int d = 0; d < 8; d++) {
            for (int i = 0; i < 6; i++) {
                sell(200_000, 1, true);
            }
            t.now.addAndGet(EconomyTestSupport.DAY / 2);
        }
        long before = ScrollMarketPrice.price(S60, YAML);
        assertTrue(before > 140_000, "learned " + before);
        // a restart: memory empty, the price board's table replays its window
        PriceBook.useForTest(t.store, Runnable::run, t.now::get, item -> item.getItemId() == S60 ? YAML : 0L);
        assertEquals(before, ScrollMarketPrice.price(S60, YAML));
        // a month with no sales: the weight decays and the price drifts back toward the yaml price
        t.now.addAndGet(30 * EconomyTestSupport.DAY);
        long faded = ScrollMarketPrice.price(S60, YAML);
        assertTrue(faded < before && faded >= YAML, "faded to " + faded);
    }

    @Test
    void offMeansTheYamlPriceAndNothingLearned() {
        System.setProperty("slums.scroll.marketPrice", "false");
        sell(150_000, 1, true);
        assertEquals(YAML, ScrollMarketPrice.price(S60, YAML));
        assertEquals(0, ScrollMarketPrice.view(S60, YAML).sales());
        // unset, it follows -Dslums.scroll.ev
        System.clearProperty("slums.scroll.marketPrice");
        System.setProperty("slums.scroll.ev", "true");
        try {
            assertTrue(ScrollMarketPrice.enabled());
        } finally {
            System.clearProperty("slums.scroll.ev");
        }
        assertTrue(!ScrollMarketPrice.enabled());
    }
}
