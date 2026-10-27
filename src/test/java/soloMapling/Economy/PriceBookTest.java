package soloMapling.Economy;

import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceBookTest {

    static final int GLOVE_60 = 2040804;
    EconomyTestSupport t;

    @BeforeEach
    void setUp() {
        t = new EconomyTestSupport();
        t.statics.put(GLOVE_60, 900_000L);
    }

    @AfterEach
    void tearDown() {
        EconomyTestSupport.reset();
    }

    private void sale(long price, PriceBook.Source src, boolean player) {
        PriceBook.record(EconomyTestSupport.item(GLOVE_60, 1), price, 1, src, player);
    }

    @Test
    void priceSamplesAreKeptForeverByDefaultAndPrunedOnlyWhenAskedTo() {
        sale(1_000_000, PriceBook.Source.SHOP, true);
        t.now.addAndGet(400 * EconomyTestSupport.DAY);
        sale(1_100_000, PriceBook.Source.SHOP, true); // far past the old 14 days: the prune check runs here
        assertEquals(2, t.store.samples.size(), "history is kept");

        EconomyConfig.useForTest(java.util.Map.of("priceBook.keepDays", "30"));
        try {
            t.now.addAndGet(7 * EconomyTestSupport.DAY);
            sale(1_200_000, PriceBook.Source.SHOP, true);
            assertEquals(2, t.store.samples.size(), "a keepDays above 0 prunes again: the 400-day-old row went");
        } finally {
            EconomyConfig.useForTest(null);
        }
    }

    @Test
    void tooFewSalesShowTheStaticValue() {
        sale(2_000_000, PriceBook.Source.SHOP, true);
        sale(2_000_000, PriceBook.Source.SHOP, true);
        PriceBook.Quote q = PriceBook.quote(EconomyTestSupport.item(GLOVE_60, 1));
        assertFalse(q.fromSamples());
        assertEquals(900_000, q.median());
        assertEquals(2, q.samples());
        assertEquals(900_000, PriceBook.fairValue(EconomyTestSupport.item(GLOVE_60, 1)));
    }

    @Test
    void theMedianOfTheLastSevenDays() {
        for (long p : new long[]{1_000_000, 1_200_000, 5_000_000, 1_100_000}) {
            sale(p, PriceBook.Source.SHOP, true);
        }
        PriceBook.Quote q = PriceBook.quote(GLOVE_60, 0);
        assertTrue(q.fromSamples());
        assertEquals(1_150_000, q.median(), "one silly sale does not move a median");
        assertEquals(1_000_000, q.low());
        assertEquals(5_000_000, q.high());
        t.now.addAndGet(8 * EconomyTestSupport.DAY);
        sale(2_000_000, PriceBook.Source.SHOP, true);
        assertFalse(PriceBook.quote(GLOVE_60, 0).fromSamples(), "week-old sales left the window");
    }

    @Test
    void botsSeedTheBoardUntilPlayersTradeEnough() {
        for (int i = 0; i < 4; i++) {
            sale(500_000, PriceBook.Source.BOT_SEED, false);
        }
        assertEquals(500_000, PriceBook.quote(GLOVE_60, 0).median(), "bots alone set it while players are few");
        for (int i = 0; i < 5; i++) {
            sale(2_000_000, PriceBook.Source.SHOP, true);
        }
        assertFalse(PriceBook.quote(GLOVE_60, 0).playerOnly(),
                "player sales whose sellers and buyers are unknown cannot replace the seeds (1.5.0 rc3 review)");
        for (int i = 0; i < 3; i++) {
            PriceBook.recordBetween(EconomyTestSupport.item(GLOVE_60, 1), 2_000_000, 1, PriceBook.Source.SHOP,
                    new PriceBook.Party(10 + i, "10.0.0." + i, null), new PriceBook.Party(20 + i, "10.0.1." + i, null));
        }
        PriceBook.Quote q = PriceBook.quote(GLOVE_60, 0);
        assertTrue(q.playerOnly());
        assertEquals(2_000_000, q.median(), "players from three sellers and buyers: bot seeds stop counting");
    }

    @Test
    void botSeedsAreCappedPerItemPerDay() {
        int kept = 0;
        for (int i = 0; i < 20; i++) {
            kept += PriceBook.record(new PriceBook.Sample(GLOVE_60, 1, 1, 1, PriceBook.Source.BOT_SEED, false,
                    t.now.get())) ? 1 : 0;
        }
        assertEquals(6, kept);
        assertEquals(6, t.store.samples.size());
    }

    @Test
    void stacksArePricedPerUnitAndReadBackAfterARestart() {
        PriceBook.record(EconomyTestSupport.item(4004000, 10), 1_000_000, 10, PriceBook.Source.SHOP, true);
        PriceBook.record(EconomyTestSupport.item(4004000, 5), 600_000, 5, PriceBook.Source.SHOP, true);
        PriceBook.record(EconomyTestSupport.item(4004000, 1), 100_000, 1, PriceBook.Source.TRADE, true);
        assertEquals(100_000, PriceBook.quote(4004000, 0).median());
        // a restart: memory empty, the store has the rows
        PriceBook.useForTest(t.store, Runnable::run, t.now::get, item -> 0L);
        assertEquals(100_000, PriceBook.quote(4004000, 0).median());
        assertEquals(3, PriceBook.quote(4004000, 0).samples());
    }

    @Test
    void aPlayerToPlayerTradeOfOneItemForMesosIsASample() {
        client.Character a = org.mockito.Mockito.mock(client.Character.class);
        client.Character b = org.mockito.Mockito.mock(client.Character.class);
        Item gloves = EconomyTestSupport.item(GLOVE_60, 1);
        // a put in the scroll and receives 1.5m; b put in nothing and receives the scroll
        PriceBook.onWindowTrade(a, List.of(gloves), 1_500_000, b, List.of(), 0);
        assertEquals(1, t.store.samples.size());
        assertEquals(1_500_000, t.store.samples.get(0).unitPrice());
        assertEquals(PriceBook.Source.TRADE, t.store.samples.get(0).source());
        // a swap of items, or mesos both ways, is no price
        PriceBook.onWindowTrade(a, List.of(gloves), 0, b, List.of(EconomyTestSupport.item(4004000, 1)), 0);
        PriceBook.onWindowTrade(a, List.of(), 100, b, List.of(), 100);
        assertEquals(1, t.store.samples.size());
    }

    @Test
    void theBoardLineSaysMedianRangeAndCount() {
        for (long p : new long[]{1_000_000, 1_250_000, 2_000_000}) {
            sale(p, PriceBook.Source.SHOP, true);
        }
        String line = PriceBook.quote(GLOVE_60, 0).describe("Gloves for ATT 60%");
        assertTrue(line.contains("median 1.25m") && line.contains("1m - 2m") && line.contains("3 sales"), line);
        assertEquals("1.25m", PriceBook.shorthand(1_250_000));
        assertEquals("300k", PriceBook.shorthand(300_000));
    }
}
