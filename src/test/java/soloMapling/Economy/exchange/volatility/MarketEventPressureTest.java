package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.ExchangeCatalog;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.Ticker;
import soloMapling.worldevents.EventTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 270 (owner 2026-11-03: more events that can weigh in price driving in items): world events move the item
 * books too. The mapping is pure arithmetic, so these pin the sign and the size rather than any scheduler.
 */
class MarketEventPressureTest {

    static final String CATALOG = "slums.exchange.catalog";
    static final long NOW = 1_800_000_000_000L;

    List<Ticker> tickers;

    @BeforeEach
    void setUp() {
        System.setProperty(CATALOG, "scrolls,use,etc");
        ExchangeCatalog.reset();
        tickers = Ticker.fromSystem(ExchangeConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(CATALOG);
        ExchangeCatalog.reset();
    }

    Ticker find(String id) {
        return tickers.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    static MarketEventPressure.Live invasion() {
        return new MarketEventPressure.Live("invasion-zakum", EventTemplate.Kind.INVASION, 1.0, List.of(), 0);
    }

    @Test
    void aBossFightIsPotionDemandAndNothingElse() {
        MarketEventPressure.Live e = invasion();
        assertEquals(0.4, MarketEventPressure.pressure(List.of(e), find("ELIXIR"), NOW), 1e-9, "potions burn");
        assertEquals(0, MarketEventPressure.pressure(List.of(e), find("E4006001"), NOW), 1e-9);
        assertEquals(0, MarketEventPressure.pressure(List.of(e), find("S2040805"), NOW), 1e-9);
    }

    @Test
    void aTreasureHuntFloodsTheEtcBooks() {
        MarketEventPressure.Live e = new MarketEventPressure.Live("treasure-henesys", EventTemplate.Kind.TREASURE_HUNT,
                1.0, List.of(), 0);
        double rock = MarketEventPressure.pressure(List.of(e), find("E4006001"), NOW);
        assertEquals(-0.3, rock, 1e-9, "more loot in circulation is a supply push");
        assertEquals(0, MarketEventPressure.pressure(List.of(e), find("ELIXIR"), NOW), 1e-9);
        assertEquals(0, MarketEventPressure.pressure(List.of(e), find("S2040805"), NOW), 1e-9);
    }

    @Test
    void anEventThatHandsOutAnItemFloodsThatItemAndStopsWhenItEnds() {
        MarketEventPressure.Live e = new MarketEventPressure.Live("cake", EventTemplate.Kind.GIANT_CAKE, 1.0,
                List.of(4006001), NOW + 60_000);
        assertEquals(-0.5, MarketEventPressure.pressure(List.of(e), find("E4006001"), NOW), 1e-9);
        assertEquals(0, MarketEventPressure.pressure(List.of(e), find("E4006001"), NOW + 60_000), 1e-9,
                "a finished event presses on nothing");
    }

    @Test
    void theWholeBoardIsBounded() {
        MarketEventPressure.Live hot = new MarketEventPressure.Live("a", EventTemplate.Kind.INVASION, 2.0,
                List.of(), 0);
        MarketEventPressure.Live hot2 = new MarketEventPressure.Live("b", EventTemplate.Kind.INVASION, 2.0,
                List.of(), 0);
        assertEquals(MarketEventPressure.MAX,
                MarketEventPressure.pressure(List.of(hot, hot2), find("ELIXIR"), NOW), 1e-9);
        assertTrue(MarketEventPressure.pressure(List.of(), find("ELIXIR"), NOW) == 0, "no events, no pressure");
    }

    @Test
    void aQuestNpcConsumesTheItemItAsksForAndMovesNothingElse() {
        MarketEventPressure.Live e = new MarketEventPressure.Live("npc", EventTemplate.Kind.WANDERING_NPC, 1.0,
                List.of(4006001), 0);
        assertEquals(0.3, MarketEventPressure.pressure(List.of(e), find("E4006001"), NOW), 1e-9,
                "handing the item over is demand");
        assertEquals(0, MarketEventPressure.pressure(List.of(e), find("ELIXIR"), NOW), 1e-9);
    }
}
